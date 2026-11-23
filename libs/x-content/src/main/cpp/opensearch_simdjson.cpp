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
 * walks the structural indexes, checks the grammar, the numbers, the atoms,
 * the escapes and the duplicate keys, and writes one token per value, field
 * name, and container boundary. Strings with escapes are decoded into a
 * scratch buffer so that Java builds every string with a single UTF-8 decode.
 *
 * Any document this file does not accept (invalid JSON, comments, a scalar
 * root, a duplicate key, a lone surrogate escape, a limit exceeded) returns
 * FALLBACK and the caller parses it with Jackson, so the outcome and the
 * error messages for those documents are Jackson's.
 *
 * The input buffer is not padded: documents above SMALL_DOCUMENT bytes use
 * the unpadded stage 1 and smaller ones are first copied to a padded buffer.
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
  // The token's start and end are offsets in the scratch buffer, which holds the decoded string.
  ESCAPED = 0x40
};

constexpr int32_t OUTPUT_TOO_SMALL = -1000;
constexpr int32_t FALLBACK = -2000;
constexpr uint32_t SMALL_DOCUMENT = 128;
constexpr int INTS_PER_TOKEN = 4;
// Jackson's default StreamReadConstraints maximum number length.
constexpr uint32_t MAX_NUMBER_LENGTH = 1000;

struct key_ref {
  const uint8_t *bytes;
  uint32_t length;
  uint32_t hash;
};

struct frame {
  uint32_t open_token;
  uint32_t first_key;
  bool is_array;
  // Bit h % 256 is set for the hash h of every key of the object: a key whose bit is clear is new.
  uint64_t filter[4];
};

struct context {
  std::unique_ptr<internal::dom_parser_implementation> impl;
  std::vector<frame> frames;
  std::vector<key_ref> keys;
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

// Returns INT, FLOAT, or 0 when b[i, e) is not a JSON number.
inline int32_t number_kind(const uint8_t *b, uint32_t i, uint32_t e) {
  if (b[i] == '-') { i++; }
  if (i >= e) { return 0; }
  if (b[i] == '0') {
    i++;
  } else if (b[i] >= '1' && b[i] <= '9') {
    i++;
    while (i < e && is_digit(b[i])) { i++; }
  } else {
    return 0;
  }
  if (i == e) { return INT; }
  if (b[i] == '.') {
    uint32_t first = ++i;
    while (i < e && is_digit(b[i])) { i++; }
    if (i == first) { return 0; }
  }
  if (i < e && (b[i] | 0x20) == 'e') {
    i++;
    if (i < e && (b[i] == '+' || b[i] == '-')) { i++; }
    uint32_t first = i;
    while (i < e && is_digit(b[i])) { i++; }
    if (i == first) { return 0; }
  }
  return i == e ? FLOAT : 0;
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
  if (len <= SMALL_DOCUMENT) {
    alignas(64) uint8_t padded[SMALL_DOCUMENT + SIMDJSON_PADDING];
    std::memcpy(padded, doc, len);
    std::memset(padded + len, ' ', sizeof(padded) - len);
    impl._unpadded = false;
    return impl.stage1(padded, len, stage1_mode::regular);
  }
  impl._unpadded = true;
  return impl.stage1(doc, len, stage1_mode::regular);
}

class tokenizer {
public:
  tokenizer(context &ctx, const uint8_t *buf, uint32_t offset, int32_t *out, uint8_t *scratch, uint32_t max_depth,
            uint32_t max_name_length, uint32_t max_string_length, bool has_backslash)
      : ctx(ctx), b(buf), off(offset), idx(ctx.impl->structural_indexes.get()), n(ctx.impl->n_structural_indexes),
        out(out), scratch(scratch), scratch_end(scratch), max_depth(max_depth), max_name_length(max_name_length),
        max_string_length(max_string_length), has_backslash(has_backslash) {}

  int32_t run();

private:
  context &ctx;
  const uint8_t *const b;
  const uint32_t off;
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

  void emit(int32_t kind, uint32_t start, uint32_t end, uint32_t aux) {
    int32_t *o = out + size_t(t) * INTS_PER_TOKEN;
    o[0] = kind;
    o[1] = int32_t(start);
    o[2] = int32_t(end);
    o[3] = int32_t(aux);
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
   * escape, decodes it to the scratch buffer and sets decoded.
   */
  bool string_content(uint32_t &s, uint32_t &e, const uint8_t *&decoded_start, uint32_t &decoded_length,
                      uint32_t limit) {
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
        uint8_t *end = unescape(b, s, e, first, scratch_end);
        if (end == nullptr) { return false; }
        decoded_start = scratch_end;
        decoded_length = uint32_t(end - scratch_end);
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
    if (!string_content(s, e, decoded, decoded_length, max_name_length)) { return false; }
    const uint8_t *bytes = decoded != nullptr ? decoded : b + s;
    uint32_t length = decoded != nullptr ? decoded_length : e - s;
    uint32_t h = name_hash(bytes, length);
    frame &f = ctx.frames[depth];
    uint64_t bit = uint64_t(1) << (h & 63);
    uint64_t &word = f.filter[(h >> 6) & 3];
    if (word & bit) {
      for (size_t k = f.first_key; k < ctx.keys.size(); k++) {
        const key_ref &other = ctx.keys[k];
        if (other.hash == h && other.length == length && std::memcmp(other.bytes, bytes, length) == 0) { return false; }
      }
    }
    word |= bit;
    ctx.keys.push_back(key_ref{bytes, length, h});
    if (decoded != nullptr) {
      uint32_t ds = uint32_t(decoded - scratch);
      emit(FIELD_NAME | ESCAPED, ds, ds + decoded_length, s - 1);
    } else {
      emit(FIELD_NAME, s, e, h);
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
      if (!string_content(s, e, decoded, decoded_length, max_string_length)) { return false; }
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
    int32_t kind;
    switch (c) {
      case 't': kind = (e - p == 4 && std::memcmp(b + p, "true", 4) == 0) ? TRUE_VALUE : 0; break;
      case 'f': kind = (e - p == 5 && std::memcmp(b + p, "false", 5) == 0) ? FALSE_VALUE : 0; break;
      case 'n': kind = (e - p == 4 && std::memcmp(b + p, "null", 4) == 0) ? NULL_VALUE : 0; break;
      default: kind = e - p < MAX_NUMBER_LENGTH ? number_kind(b, p, e) : 0;
    }
    if (kind == 0) { return false; }
    emit(kind, p, e, p);
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
  if (capacity < SMALL_DOCUMENT) { capacity = SMALL_DOCUMENT; }
  try {
    ctx->frames.resize(64);
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
 * Tokenizes buf[offset, offset + len). Writes 4 ints per token to out:
 * kind, start, end, aux. start and end delimit the token in buf (a string's
 * content, without the quotes), or in scratch when kind has ESCAPED. aux is
 * the index of the close token for an open token, the hash of the name for a
 * field name without escapes, and the offset in buf of the opening quote of
 * an escaped field name or of a value. scratch must hold at least
 * len bytes (decoding never grows a string).
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
  tokenizer tk(ctx, buf, uint32_t(offset), out, scratch, uint32_t(max_depth), uint32_t(max_name_length),
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
  if (capacity < SMALL_DOCUMENT) { capacity = SMALL_DOCUMENT; }
  return -int32_t(ctx.impl->set_capacity(capacity));
}
