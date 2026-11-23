/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Turns a JSON document into a flat list of tokens for the Java pull parser.
 *
 * simdjson's stage 1 finds the structural characters, validates UTF-8 and
 * rejects unescaped control characters inside strings. A second pass then
 * walks the structural indexes and, in one pass, checks the grammar, parses
 * and validates the numbers, checks the atoms, decodes the strings that have
 * escapes, interns the field names and rejects duplicate keys. It writes one
 * token per value, field name and container boundary, so that the Java side
 * only reads results.
 *
 * Any document this file does not accept (invalid JSON, comments, a scalar
 * root, a duplicate key, a lone surrogate escape, a limit exceeded) returns
 * FALLBACK and the caller parses it with Jackson, so the outcome and the
 * error messages for those documents are Jackson's.
 *
 * The input buffer is not padded, so stage 1 runs in its unpadded mode.
 */

#include "simdjson.h"

#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <vector>

#if defined(_WIN32)
#define OPENSEARCH_SIMDJSON_EXPORT extern "C" __declspec(dllexport)
#else
#define OPENSEARCH_SIMDJSON_EXPORT extern "C" __attribute__((visibility("default")))
#endif

using namespace simdjson;

namespace {

/*
 * A token is 4 ints: kind, then
 *   START_OBJECT, START_ARRAY: position, position + 1, index of the close token
 *   END_OBJECT, END_ARRAY:     position, position + 1, 0
 *   FIELD_NAME:                content start, content end, name id (-1 when not interned)
 *   STRING:                    content start, content end, position of the opening quote
 *   INT, FLOAT:                position, low and high 32 bits of the value (with NUMBER_VALUE)
 *   TRUE, FALSE, NULL_VALUE:   position, end, position
 * With ESCAPED, a string or name start and end are offsets in the scratch
 * buffer, which holds the decoded UTF-8; for a name, the 4 bytes before its
 * start hold the position of its opening quote.
 */
enum : int32_t {
  START_OBJECT = 1,
  END_OBJECT = 2,
  START_ARRAY = 3,
  END_ARRAY = 4,
  FIELD_NAME = 5,
  STRING = 6,
  INT = 7,
  FLOAT = 8,
  TRUE_VALUE = 9,
  FALSE_VALUE = 10,
  NULL_VALUE = 11,
  ESCAPED = 0x40,
  // The value of an INT (an int64) or of a FLOAT (the bits of the double) is in the token.
  NUMBER_VALUE = 0x80
};

constexpr int32_t OUTPUT_TOO_SMALL = -1000;
constexpr int32_t FALLBACK = -2000;
// Minimum capacity of a context, in bytes.
constexpr uint32_t MIN_CAPACITY = 128;
constexpr int INTS_PER_TOKEN = 4;
// Jackson's default StreamReadConstraints maximum number length.
constexpr uint32_t MAX_NUMBER_LENGTH = 1000;
// Bounds of the name table: names longer than this, or past this count, are not interned.
constexpr uint32_t MAX_INTERNED_NAME = 256;
constexpr uint32_t MAX_INTERNED_NAMES = 4096;
constexpr uint32_t NAME_TABLE_SIZE = 2 * MAX_INTERNED_NAMES;

struct key_ref {
  const uint8_t *bytes;
  uint32_t length;
  int32_t id;
};

struct frame {
  uint32_t open_token;
  uint32_t first_key;
  uint32_t serial;
  bool is_array;
  // Bit h % 256 is set for the hash (or id) h of every key of the object: a key whose bit is clear is new.
  uint64_t filter[4];
};

inline bool same_bytes(const uint8_t *a, const uint8_t *b, uint32_t len);

/* Field names seen by this context, with ids that the Java side maps to Strings. */
struct name_table {
  struct slot {
    uint32_t hash{0};
    int32_t id{-1};
    uint32_t offset{0};
    uint32_t length{0};
  };
  std::vector<slot> slots;
  std::string bytes;
  uint32_t count{0};
  // seen[id] is the serial number of the last object that had the name: equal to the current one for a duplicate
  std::vector<uint32_t> seen;
  uint32_t serial{0};

  void init() {
    slots.resize(NAME_TABLE_SIZE);
    seen.resize(MAX_INTERNED_NAMES);
  }

  int32_t intern(const uint8_t *p, uint32_t length, uint32_t hash) {
    if (length > MAX_INTERNED_NAME) { return -1; }
    uint32_t mask = NAME_TABLE_SIZE - 1;
    for (uint32_t i = hash & mask;; i = (i + 1) & mask) {
      slot &s = slots[i];
      if (s.id < 0) {
        if (count >= MAX_INTERNED_NAMES) { return -1; }
        s.hash = hash;
        s.id = int32_t(count++);
        s.offset = uint32_t(bytes.size());
        s.length = length;
        bytes.append(reinterpret_cast<const char *>(p), length);
        return s.id;
      }
      if (s.hash == hash && s.length == length &&
          same_bytes(reinterpret_cast<const uint8_t *>(bytes.data()) + s.offset, p, length)) {
        return s.id;
      }
    }
  }
};

struct context {
  std::unique_ptr<internal::dom_parser_implementation> impl;
  std::vector<frame> frames;
  std::vector<key_ref> keys;
  name_table names;
};

inline bool is_ws(uint8_t c) { return c == ' ' || c == '\n' || c == '\r' || c == '\t'; }
inline bool is_digit(uint8_t c) { return uint8_t(c - '0') < 10; }

inline int hex_value(uint8_t c) {
  if (is_digit(c)) { return c - '0'; }
  uint8_t lower = uint8_t(c | 0x20);
  if (lower >= 'a' && lower <= 'f') { return lower - 'a' + 10; }
  return -1;
}

inline uint64_t load64(const uint8_t *p) {
  uint64_t v;
  std::memcpy(&v, p, 8);
  return v;
}

inline uint32_t load32(const uint8_t *p) {
  uint32_t v;
  std::memcpy(&v, p, 4);
  return v;
}

// Whether a[0, len) equals b[0, len). It never reads outside these ranges.
inline bool same_bytes(const uint8_t *a, const uint8_t *b, uint32_t len) {
  if (len >= 8) {
    for (uint32_t i = 0; i + 8 < len; i += 8) {
      if (load64(a + i) != load64(b + i)) { return false; }
    }
    return load64(a + len - 8) == load64(b + len - 8);
  }
  if (len >= 4) { return load32(a) == load32(b) && load32(a + len - 4) == load32(b + len - 4); }
  for (uint32_t i = 0; i < len; i++) {
    if (a[i] != b[i]) { return false; }
  }
  return true;
}

inline uint64_t mix(uint64_t h, uint64_t w) {
  h = (h ^ w) * 0x9E3779B97F4A7C15ULL;
  return h ^ (h >> 29);
}

// Hash of a field name. It never reads outside [p, p + len).
inline uint32_t name_hash(const uint8_t *p, uint32_t len) {
  uint64_t h = 0x243F6A8885A308D3ULL ^ len;
  if (len >= 8) {
    const uint8_t *end = p + len;
    while (end - p > 8) {
      h = mix(h, load64(p));
      p += 8;
    }
    h = mix(h, load64(end - 8));
  } else if (len >= 4) {
    h = mix(h, (uint64_t(load32(p)) << 32) | load32(p + len - 4));
  } else if (len > 0) {
    h = mix(h, uint64_t(p[0]) | (uint64_t(p[len >> 1]) << 8) | (uint64_t(p[len - 1]) << 16));
  }
  h *= 0xD6E8FEB86659FD93ULL;
  return uint32_t(h >> 32);
}

// Whether the 8 bytes (little endian) are all ASCII digits.
inline bool is_eight_digits(uint64_t v) {
  return (((v & 0xF0F0F0F0F0F0F0F0ULL) | (((v + 0x0606060606060606ULL) & 0xF0F0F0F0F0F0F0F0ULL) >> 4)) ==
          0x3333333333333333ULL);
}

// Value of 8 ASCII digits (little endian): the SWAR method of simdjson and fast_float.
inline uint32_t parse_eight_digits(uint64_t v) {
  const uint64_t mask = 0x000000FF000000FFULL;
  const uint64_t mul1 = 0x000F424000000064ULL; // 100 + (1000000ULL << 32)
  const uint64_t mul2 = 0x0000271000000001ULL; // 1 + (10000ULL << 32)
  v -= 0x3030303030303030ULL;
  v = (v * 10) + (v >> 8);
  v = (((v & mask) * mul1) + (((v >> 16) & mask) * mul2)) >> 32;
  return uint32_t(v);
}

// Bytes of v (little endian) that are not ASCII digits are nonzero in the result.
inline uint64_t non_digits(uint64_t v) {
  return ((v & 0xF0F0F0F0F0F0F0F0ULL) | (((v + 0x0606060606060606ULL) & 0xF0F0F0F0F0F0F0F0ULL) >> 4)) ^
         0x3333333333333333ULL;
}

/*
 * Reads the digits that start at b[i] into mantissa (the first 19 significant
 * ones) and returns the index after the last digit. digits counts all of
 * them. Reads 8 bytes at a time while they are before end, the end of the
 * document (the digits themselves end earlier, at a non-digit).
 */
inline uint32_t read_digits(const uint8_t *b, uint32_t i, uint32_t end, uint64_t &mantissa, uint32_t &digits) {
  while (end - i >= 8 && digits + 8 <= 19) {
    uint64_t v = load64(b + i);
    if (non_digits(v) != 0) { break; }
    mantissa = mantissa * 100000000 + parse_eight_digits(v);
    digits += 8;
    i += 8;
  }
  while (i < end && is_digit(b[i])) {
    if (digits < 19) { mantissa = mantissa * 10 + (b[i] - '0'); }
    digits++;
    i++;
  }
  return i;
}

#if defined(__SIZEOF_INT128__)
inline double bits_to_double(uint64_t mantissa, uint64_t real_exponent, bool negative) {
  double d;
  mantissa &= ~(1ULL << 52);
  mantissa |= real_exponent << 52;
  mantissa |= uint64_t(negative) << 63;
  std::memcpy(&d, &mantissa, sizeof(d));
  return d;
}

/*
 * The Eisel-Lemire algorithm: the correctly rounded double nearest to
 * i * 10^power, for a nonzero i of at most 19 digits. Returns false when it
 * cannot decide or the value overflows. This is compute_float_64 of simdjson
 * (Apache License 2.0), without its Clinger fast path, which the caller
 * tries first.
 */
inline bool eisel_lemire(int64_t power, uint64_t i, bool negative, double &d) {
  if (power < simdjson::internal::smallest_power || power > simdjson::internal::largest_power) { return false; }
  int64_t exponent = (((152170 + 65536) * power) >> 16) + 1024 + 63;
  int lz = __builtin_clzll(i);
  i <<= lz;
  const uint32_t index = 2 * uint32_t(power - simdjson::internal::smallest_power);
  unsigned __int128 first = (unsigned __int128)i * simdjson::internal::power_of_five_128[index];
  uint64_t upper = uint64_t(first >> 64);
  uint64_t lower = uint64_t(first);
  if ((upper & 0x1FF) == 0x1FF) {
    unsigned __int128 second = (unsigned __int128)i * simdjson::internal::power_of_five_128[index + 1];
    uint64_t second_high = uint64_t(second >> 64);
    lower += second_high;
    if (second_high > lower) { upper++; }
  }
  uint64_t upperbit = upper >> 63;
  uint64_t mantissa = upper >> (upperbit + 9);
  lz += int(1 ^ upperbit);
  int64_t real_exponent = exponent - lz;
  if (real_exponent <= 0) {
    // a subnormal
    if (-real_exponent + 1 >= 64) {
      d = negative ? -0.0 : 0.0;
      return true;
    }
    mantissa >>= -real_exponent + 1;
    mantissa += (mantissa & 1);
    mantissa >>= 1;
    real_exponent = (mantissa < (uint64_t(1) << 52)) ? 0 : 1;
    d = bits_to_double(mantissa, uint64_t(real_exponent), negative);
    return true;
  }
  if ((lower <= 1) && (power >= -4) && (power <= 23) && ((mantissa & 3) == 1)) {
    // exactly halfway between two doubles: round to even
    if ((mantissa << (upperbit + 64 - 53 - 2)) == upper) { mantissa &= ~uint64_t(1); }
  }
  mantissa += mantissa & 1;
  mantissa >>= 1;
  if (mantissa >= (uint64_t(1) << 53)) {
    mantissa = uint64_t(1) << 52;
    real_exponent++;
  }
  mantissa &= ~(uint64_t(1) << 52);
  if (real_exponent > 2046) { return false; }
  d = bits_to_double(mantissa, uint64_t(real_exponent), negative);
  return true;
}
#endif

const double POWERS_OF_TEN[] = {1e0,  1e1,  1e2,  1e3,  1e4,  1e5,  1e6,  1e7,  1e8,  1e9,  1e10, 1e11,
                                1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22};

/*
 * Validates the JSON number b[p, e) and computes its value: an integer that
 * fits an int64, or a decimal with at most 19 significant digits, whose
 * correctly rounded double (Jackson's parser also rounds correctly) comes from
 * Clinger's fast path or from the Eisel-Lemire algorithm. Returns the kind
 * (with NUMBER_VALUE when value is set), or 0 when it is not a JSON number.
 */
inline int32_t parse_number(const uint8_t *b, uint32_t p, uint32_t e, uint32_t end, uint64_t &value) {
  uint32_t i = p;
  bool negative = b[i] == '-';
  if (negative) { i++; }
  if (i >= e) { return 0; }
  uint64_t mantissa = 0;
  uint32_t digits = 0;
  uint32_t integer_digits;
  if (b[i] == '0') {
    i++;
    integer_digits = 1;
    digits = 0; // a leading zero is not a significant digit
  } else if (b[i] >= '1' && b[i] <= '9') {
    uint32_t start = i;
    i = read_digits(b, i, end, mantissa, digits);
    integer_digits = i - start;
  } else {
    return 0;
  }
  if (i == e) {
    // an integer: fits an int64 with at most 18 digits, or 19 digits up to 2^63 - 1 (2^63 when negative)
    if (integer_digits <= 18 || (integer_digits == 19 && mantissa <= uint64_t(INT64_MAX) + (negative ? 1 : 0))) {
      value = negative ? uint64_t(0) - mantissa : mantissa;
      return INT | NUMBER_VALUE;
    }
    return INT;
  }
  int64_t exponent = 0;
  bool exact = digits <= 19;
  if (b[i] == '.') {
    uint32_t start = ++i;
    uint32_t before = digits;
    i = read_digits(b, i, end, mantissa, digits);
    if (i == start) { return 0; }
    if (digits > 19) { exact = false; }
    exponent -= int64_t(digits - before);
    // digits after the 19th were not added to the mantissa: they do not scale it
    if (!exact) { exponent += int64_t(digits) - 19; }
  }
  if (i < e && (b[i] | 0x20) == 'e') {
    i++;
    bool negative_exponent = false;
    if (i < e && (b[i] == '+' || b[i] == '-')) {
      negative_exponent = b[i] == '-';
      i++;
    }
    uint32_t start = i;
    int64_t x = 0;
    while (i < e && is_digit(b[i])) {
      if (x < 100000) { x = x * 10 + (b[i] - '0'); }
      i++;
    }
    if (i == start) { return 0; }
    exponent += negative_exponent ? -x : x;
  }
  if (i != e) { return 0; }
  if (!exact) { return FLOAT; }
  double d;
  if (mantissa <= (uint64_t(1) << 53) && exponent >= -22 && exponent <= 22) {
    d = double(mantissa);
    d = exponent < 0 ? d / POWERS_OF_TEN[-exponent] : d * POWERS_OF_TEN[exponent];
    if (negative) { d = -d; }
  } else if (mantissa == 0) {
    d = negative ? -0.0 : 0.0;
  } else {
#if defined(__SIZEOF_INT128__)
    if (!eisel_lemire(exponent, mantissa, negative, d)) { return FLOAT; }
#else
    return FLOAT;
#endif
  }
  std::memcpy(&value, &d, 8);
  return FLOAT | NUMBER_VALUE;
}

inline uint8_t *write_utf8(uint8_t *o, uint32_t cp) {
  if (cp < 0x80) {
    *o++ = uint8_t(cp);
  } else if (cp < 0x800) {
    *o++ = uint8_t(0xC0 | (cp >> 6));
    *o++ = uint8_t(0x80 | (cp & 0x3F));
  } else if (cp < 0x10000) {
    *o++ = uint8_t(0xE0 | (cp >> 12));
    *o++ = uint8_t(0x80 | ((cp >> 6) & 0x3F));
    *o++ = uint8_t(0x80 | (cp & 0x3F));
  } else {
    *o++ = uint8_t(0xF0 | (cp >> 18));
    *o++ = uint8_t(0x80 | ((cp >> 12) & 0x3F));
    *o++ = uint8_t(0x80 | ((cp >> 6) & 0x3F));
    *o++ = uint8_t(0x80 | (cp & 0x3F));
  }
  return o;
}

inline int32_t read_hex4(const uint8_t *p) {
  int a = hex_value(p[0]), b = hex_value(p[1]), c = hex_value(p[2]), d = hex_value(p[3]);
  if ((a | b | c | d) < 0) { return -1; }
  return (a << 12) | (b << 8) | (c << 4) | d;
}

/*
 * Decodes the string content b[s, e), whose first backslash is at first, to
 * o. Returns the end of the output, or nullptr for an invalid escape or a
 * \u escape that is a lone surrogate (not representable in UTF-8).
 */
uint8_t *unescape(const uint8_t *b, uint32_t s, uint32_t e, const uint8_t *first, uint8_t *o) {
  const uint8_t *p = b + s;
  const uint8_t *end = b + e;
  while (true) {
    size_t run = size_t(first - p);
    std::memcpy(o, p, run);
    o += run;
    p = first;
    if (end - p < 2) { return nullptr; }
    uint8_t c = p[1];
    p += 2;
    switch (c) {
      case '"': *o++ = '"'; break;
      case '\\': *o++ = '\\'; break;
      case '/': *o++ = '/'; break;
      case 'b': *o++ = '\b'; break;
      case 'f': *o++ = '\f'; break;
      case 'n': *o++ = '\n'; break;
      case 'r': *o++ = '\r'; break;
      case 't': *o++ = '\t'; break;
      case 'u': {
        if (end - p < 4) { return nullptr; }
        int32_t cp = read_hex4(p);
        if (cp < 0) { return nullptr; }
        p += 4;
        if (cp >= 0xD800 && cp < 0xE000) {
          if (cp >= 0xDC00 || end - p < 6 || p[0] != '\\' || p[1] != 'u') { return nullptr; }
          int32_t low = read_hex4(p + 2);
          if (low < 0xDC00 || low >= 0xE000) { return nullptr; }
          p += 6;
          cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
        }
        o = write_utf8(o, uint32_t(cp));
        break;
      }
      default:
        return nullptr;
    }
    first = static_cast<const uint8_t *>(std::memchr(p, '\\', size_t(end - p)));
    if (first == nullptr) {
      size_t rest = size_t(end - p);
      std::memcpy(o, p, rest);
      return o + rest;
    }
  }
}

error_code run_stage1(internal::dom_parser_implementation &impl, const uint8_t *doc, uint32_t len) {
  if (len > impl.capacity()) {
    error_code err = impl.set_capacity(len);
    if (err != SUCCESS) { return err; }
  }
  // The Java arrays have no padding. The unpadded stage 1 copies the last block, which costs less than copying
  // small documents to take the padded small-document path.
  impl._unpadded = true;
  return impl.stage1(doc, len, stage1_mode::regular);
}

class tokenizer {
public:
  tokenizer(context &ctx, const uint8_t *buf, uint32_t offset, uint32_t length, int32_t *out, uint8_t *scratch,
            uint32_t max_depth, uint32_t max_name_length, uint32_t max_string_length, bool has_backslash)
      : ctx(ctx), b(buf), off(offset), doc_end(offset + length), idx(ctx.impl->structural_indexes.get()), n(ctx.impl->n_structural_indexes),
        out(out), scratch(scratch), scratch_end(scratch), max_depth(max_depth), max_name_length(max_name_length),
        max_string_length(max_string_length), has_backslash(has_backslash) {}

  int32_t run();

private:
  context &ctx;
  const uint8_t *const b;
  const uint32_t off;
  const uint32_t doc_end;
  const uint32_t *const idx;
  const uint32_t n;
  int32_t *const out;
  uint8_t *const scratch;
  uint8_t *scratch_end;
  const uint32_t max_depth;
  const uint32_t max_name_length;
  const uint32_t max_string_length;
  const bool has_backslash;
  uint32_t t{0};
  uint32_t i{0};
  uint32_t depth{0};

  uint32_t at(uint32_t k) const { return off + idx[k]; }

  void emit(int32_t kind, uint32_t a, uint32_t b2, uint32_t c) {
    int32_t *o = out + size_t(t) * INTS_PER_TOKEN;
    o[0] = kind;
    o[1] = int32_t(a);
    o[2] = int32_t(b2);
    o[3] = int32_t(c);
    t++;
  }

  // End (exclusive) of the scalar that starts at p and is followed by the structural at next.
  uint32_t scalar_end(uint32_t p, uint32_t next) const {
    while (next > p && is_ws(b[next - 1])) { next--; }
    return next;
  }

  bool open(bool is_array) {
    if (++depth > max_depth) { return false; }
    if (depth >= ctx.frames.size()) { ctx.frames.resize(ctx.frames.size() * 2); }
    frame &f = ctx.frames[depth];
    f.open_token = t;
    f.is_array = is_array;
    if (!is_array) {
      f.first_key = uint32_t(ctx.keys.size());
      f.filter[0] = f.filter[1] = f.filter[2] = f.filter[3] = 0;
      if (++ctx.names.serial == 0) {
        // the serial numbers wrapped around: forget them all
        std::fill(ctx.names.seen.begin(), ctx.names.seen.end(), 0);
        ctx.names.serial = 1;
      }
      f.serial = ctx.names.serial;
    }
    uint32_t p = at(i);
    emit(is_array ? START_ARRAY : START_OBJECT, p, p + 1, 0);
    i++;
    return true;
  }

  void close() {
    frame &f = ctx.frames[depth];
    out[size_t(f.open_token) * INTS_PER_TOKEN + 3] = int32_t(t);
    if (!f.is_array) { ctx.keys.resize(f.first_key); }
    uint32_t p = at(i);
    emit(f.is_array ? END_ARRAY : END_OBJECT, p, p + 1, 0);
    depth--;
    i++;
  }

  /*
   * Finds the content [s, e) of the string whose quote is at structural i and
   * which is followed by the structural at i + 1. When the content has an
   * escape, decodes it to the scratch buffer, after reserve bytes, and sets
   * decoded.
   */
  bool string_content(uint32_t &s, uint32_t &e, const uint8_t *&decoded_start, uint32_t &decoded_length,
                      uint32_t limit, uint32_t reserve) {
    uint32_t q = at(i);
    uint32_t close = scalar_end(q, at(i + 1)) - 1;
    if (close <= q || b[close] != '"') { return false; }
    s = q + 1;
    e = close;
    if (e - s > limit) { return false; }
    decoded_start = nullptr;
    if (has_backslash) {
      const uint8_t *first = static_cast<const uint8_t *>(std::memchr(b + s, '\\', e - s));
      if (first != nullptr) {
        uint8_t *start = scratch_end + reserve;
        uint8_t *end = unescape(b, s, e, first, start);
        if (end == nullptr) { return false; }
        decoded_start = start;
        decoded_length = uint32_t(end - start);
        scratch_end = end;
      }
    }
    return true;
  }

  // Parses the key at structural i, the colon after it, and checks that it is new in its object.
  bool key() {
    if (i + 2 >= n || b[at(i)] != '"' || b[at(i + 1)] != ':') { return false; }
    uint32_t s, e, decoded_length;
    const uint8_t *decoded;
    // 4 bytes before a decoded name hold the position of its quote
    if (!string_content(s, e, decoded, decoded_length, max_name_length, 4)) { return false; }
    const uint8_t *bytes = decoded != nullptr ? decoded : b + s;
    uint32_t length = decoded != nullptr ? decoded_length : e - s;
    frame &f = ctx.frames[depth];
    uint32_t h = name_hash(bytes, length);
    int32_t id = ctx.names.intern(bytes, length, h);
    if (id >= 0) {
      uint32_t &last = ctx.names.seen[uint32_t(id)];
      if (last == f.serial) { return false; }
      last = f.serial;
    } else {
      // a name that is not interned: compare with the other names of the object that are not interned
      uint64_t bit = uint64_t(1) << (h & 63);
      uint64_t &word = f.filter[(h >> 6) & 3];
      if (word & bit) {
        for (size_t k = f.first_key; k < ctx.keys.size(); k++) {
          const key_ref &other = ctx.keys[k];
          if (other.length == length && same_bytes(other.bytes, bytes, length)) { return false; }
        }
      }
      word |= bit;
      ctx.keys.push_back(key_ref{bytes, length, id});
    }
    if (decoded != nullptr) {
      uint32_t quote = s - 1;
      std::memcpy(const_cast<uint8_t *>(decoded) - 4, &quote, 4);
      uint32_t ds = uint32_t(decoded - scratch);
      emit(FIELD_NAME | ESCAPED, ds, ds + decoded_length, uint32_t(id));
    } else {
      emit(FIELD_NAME, s, e, uint32_t(id));
    }
    i += 2;
    return true;
  }

  bool scalar() {
    uint32_t p = at(i);
    uint8_t c = b[p];
    if (c == '"') {
      uint32_t s, e, decoded_length;
      const uint8_t *decoded;
      if (!string_content(s, e, decoded, decoded_length, max_string_length, 0)) { return false; }
      if (decoded != nullptr) {
        uint32_t ds = uint32_t(decoded - scratch);
        emit(STRING | ESCAPED, ds, ds + decoded_length, p);
      } else {
        emit(STRING, s, e, p);
      }
      i++;
      return true;
    }
    uint32_t e = scalar_end(p, at(i + 1));
    switch (c) {
      case 't':
        if (e - p != 4 || std::memcmp(b + p, "true", 4) != 0) { return false; }
        emit(TRUE_VALUE, p, e, p);
        break;
      case 'f':
        if (e - p != 5 || std::memcmp(b + p, "false", 5) != 0) { return false; }
        emit(FALSE_VALUE, p, e, p);
        break;
      case 'n':
        if (e - p != 4 || std::memcmp(b + p, "null", 4) != 0) { return false; }
        emit(NULL_VALUE, p, e, p);
        break;
      default: {
        if (e - p >= MAX_NUMBER_LENGTH) { return false; }
        uint64_t value = 0;
        int32_t kind = parse_number(b, p, e, doc_end, value);
        if (kind == 0) { return false; }
        emit(kind, p, uint32_t(value), uint32_t(value >> 32));
      }
    }
    i++;
    return true;
  }
};

int32_t tokenizer::run() {
  if (n == 0) { return FALLBACK; }
  ctx.keys.clear();
  enum { VALUE, AFTER_VALUE } state = VALUE;
  uint8_t c = b[at(0)];
  if (c != '{' && c != '[') { return FALLBACK; }
  while (true) {
    if (state == VALUE) {
      // A value inside a container is always followed by another structural.
      if (i + 1 >= n) { return FALLBACK; }
      c = b[at(i)];
      if (c == '{') {
        if (!open(false)) { return FALLBACK; }
        c = b[at(i)];
        if (c == '}') {
          close();
          state = AFTER_VALUE;
        } else if (!key()) {
          return FALLBACK;
        }
      } else if (c == '[') {
        if (!open(true)) { return FALLBACK; }
        if (b[at(i)] == ']') {
          close();
          state = AFTER_VALUE;
        }
      } else {
        if (!scalar()) { return FALLBACK; }
        state = AFTER_VALUE;
      }
    } else {
      if (depth == 0) { return i == n ? int32_t(t) : FALLBACK; }
      if (i >= n) { return FALLBACK; }
      c = b[at(i)];
      const frame &f = ctx.frames[depth];
      if (c == ',') {
        i++;
        if (!f.is_array && !key()) { return FALLBACK; }
        state = VALUE;
      } else if (c == (f.is_array ? ']' : '}')) {
        close();
      } else {
        return FALLBACK;
      }
    }
  }
}

} // namespace

OPENSEARCH_SIMDJSON_EXPORT void *opensearch_simdjson_create(uint32_t capacity) {
  auto ctx = new (std::nothrow) context();
  if (ctx == nullptr) { return nullptr; }
  if (capacity < MIN_CAPACITY) { capacity = MIN_CAPACITY; }
  try {
    ctx->frames.resize(64);
    ctx->names.init();
  } catch (...) {
    delete ctx;
    return nullptr;
  }
  if (get_active_implementation()->create_dom_parser_implementation(capacity, 1024, ctx->impl) != SUCCESS) {
    delete ctx;
    return nullptr;
  }
  return ctx;
}

OPENSEARCH_SIMDJSON_EXPORT void opensearch_simdjson_destroy(void *ctx) { delete static_cast<context *>(ctx); }

/*
 * Tokenizes buf[offset, offset + len) into out, 4 ints per token as
 * described above. scratch must hold at least 2 * len + 8 bytes (decoded
 * strings never grow, plus 4 bytes per decoded name).
 *
 * Returns the number of tokens, OUTPUT_TOO_SMALL when out holds fewer than
 * 4 * (number of structural characters) ints, FALLBACK when Jackson must
 * parse the document, or minus a simdjson error code.
 */
OPENSEARCH_SIMDJSON_EXPORT int32_t opensearch_simdjson_tokenize(void *opaque, const uint8_t *buf, int32_t offset,
                                                                int32_t len, int32_t *out, int32_t out_capacity,
                                                                uint8_t *scratch, int32_t max_depth,
                                                                int32_t max_name_length, int32_t max_string_length) {
  context &ctx = *static_cast<context *>(opaque);
  if (len <= 0 || offset < 0) { return FALLBACK; }
  const uint8_t *doc = buf + offset;
  error_code err = run_stage1(*ctx.impl, doc, uint32_t(len));
  if (err != SUCCESS) { return -int32_t(err); }
  if (int64_t(ctx.impl->n_structural_indexes) * INTS_PER_TOKEN > int64_t(out_capacity)) { return OUTPUT_TOO_SMALL; }
  bool has_backslash = std::memchr(doc, '\\', size_t(len)) != nullptr;
  tokenizer tk(ctx, buf, uint32_t(offset), uint32_t(len), out, scratch, uint32_t(max_depth), uint32_t(max_name_length),
               uint32_t(max_string_length), has_backslash);
  try {
    return tk.run();
  } catch (...) {
    // std::bad_alloc from the key and frame stacks: let Jackson parse the document.
    return FALLBACK;
  }
}

/* Name of the SIMD kernel in use, such as "icelake", "haswell" or "arm64". */
OPENSEARCH_SIMDJSON_EXPORT const char *opensearch_simdjson_implementation() {
  static const std::string name = get_active_implementation()->name();
  return name.c_str();
}

/* Shrinks (or grows) the buffers of a context to documents of capacity bytes. Returns 0 on success. */
OPENSEARCH_SIMDJSON_EXPORT int32_t opensearch_simdjson_set_capacity(void *opaque, uint32_t capacity) {
  context &ctx = *static_cast<context *>(opaque);
  if (capacity < MIN_CAPACITY) { capacity = MIN_CAPACITY; }
  return -int32_t(ctx.impl->set_capacity(capacity));
}
