/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.common.time;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the common shapes of dates without java.time, for the formats of a {@link JavaDateFormatter}:
 * <ul>
 *     <li>ISO 8601 dates and date-times, {@code yyyy-MM-dd} optionally followed by {@code THH:mm:ss}, a fraction
 *     of 1 to 9 digits, and {@code Z} or an offset {@code +HH:mm}, for the strict ISO formats such as
 *     {@code strict_date_optional_time};</li>
 *     <li>unsigned numbers of up to 15 digits for {@code epoch_millis} and {@code epoch_second};</li>
 *     <li>custom patterns made of the fields {@code yyyy MM dd HH mm ss} (and {@code SSS} after a literal), each
 *     followed by a literal, such as {@code yyyy-MM-dd HH:mm:ss}.</li>
 * </ul>
 * {@link #parse} returns null for any other input, and for any input it cannot prove the formats would parse to
 * the same instant (an invalid date, a leap second, a year without four digits, a format it does not know). The
 * caller then parses with java.time, so the result, and the error, never change. The instant is the one the
 * formats give in UTC, which is what {@link DateFormatters#from(java.time.temporal.TemporalAccessor, Locale)} uses
 * when the input has no offset.
 *
 * @opensearch.internal
 */
final class FastDateParser {

    /** What one format of a combined format ({@code a||b||c}) accepts. */
    private enum Kind {
        /** Strict ISO format that accepts every ISO shape of this class: yyyy-MM-dd[THH:mm:ss[.f][offset]]. */
        ISO_ALL,
        /**
         * Strict ISO format that may accept some ISO shapes, with the same instant, and rejects unsigned digit strings
         * of other than 4 digits (its year has 4 digits, or more with a sign).
         */
        ISO_SOME,
        EPOCH_MILLIS,
        EPOCH_SECOND,
        PATTERN
    }

    private final Kind[] kinds;
    /** For a single custom pattern: the fields, in order (Y, M, D, h, m, s, S) and the literal after each. */
    private final char[] patternFields;
    private final char[] patternLiterals;

    private FastDateParser(Kind[] kinds, char[] patternFields, char[] patternLiterals) {
        this.kinds = kinds;
        this.patternFields = patternFields;
        this.patternLiterals = patternLiterals;
    }

    /** A parser for the format, or null when no input of the format can be parsed here. */
    static FastDateParser forFormat(String format) {
        if (format == null) {
            return null;
        }
        List<String> parts = DateFormatter.splitCombinedPatterns(DateFormatter.strip8Prefix(format));
        Kind[] kinds = new Kind[parts.size()];
        for (int i = 0; i < kinds.length; i++) {
            String part = parts.get(i).trim();
            FormatNames name = FormatNames.forName(part);
            if (name == null) {
                if (kinds.length != 1) {
                    return null;
                }
                return forPattern(part);
            }
            switch (name) {
                case STRICT_DATE_OPTIONAL_TIME:
                case STRICT_DATE_OPTIONAL_TIME_NANOS:
                    kinds[i] = Kind.ISO_ALL;
                    break;
                case STRICT_DATE:
                case STRICT_DATE_TIME:
                case STRICT_DATE_TIME_NO_MILLIS:
                case STRICT_YEAR_MONTH_DAY:
                    kinds[i] = Kind.ISO_SOME;
                    break;
                case EPOCH_MILLIS:
                    kinds[i] = Kind.EPOCH_MILLIS;
                    break;
                case EPOCH_SECOND:
                    kinds[i] = Kind.EPOCH_SECOND;
                    break;
                default:
                    return null;
            }
        }
        return new FastDateParser(kinds, null, null);
    }

    /**
     * A parser for a custom pattern made of the fields yyyy MM dd HH mm ss SSS, each followed by one literal
     * character (none after the last), or null.
     */
    private static FastDateParser forPattern(String pattern) {
        List<Character> fields = new ArrayList<>();
        List<Character> literals = new ArrayList<>();
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            int width;
            char field;
            switch (c) {
                case 'y':
                    width = 4;
                    field = 'Y';
                    break;
                case 'M':
                    width = 2;
                    field = 'M';
                    break;
                case 'd':
                    width = 2;
                    field = 'D';
                    break;
                case 'H':
                    width = 2;
                    field = 'h';
                    break;
                case 'm':
                    width = 2;
                    field = 'm';
                    break;
                case 's':
                    width = 2;
                    field = 's';
                    break;
                case 'S':
                    width = 3;
                    field = 'S';
                    break;
                default:
                    return null;
            }
            if (pattern.regionMatches(i, String.valueOf(c).repeat(width), 0, width) == false) {
                return null;
            }
            if (i + width < pattern.length() && pattern.charAt(i + width) == c) {
                return null;
            }
            if (fields.contains(field)) {
                return null;
            }
            // a fraction must follow a literal, as in ss.SSS
            if (field == 'S' && (fields.isEmpty() || literals.get(literals.size() - 1) == 0)) {
                return null;
            }
            fields.add(field);
            i += width;
            if (i == pattern.length()) {
                literals.add((char) 0);
                break;
            }
            char literal = pattern.charAt(i);
            int literalLength = 1;
            if (literal == '\'') {
                // a quoted single letter, such as 'T'
                if (i + 2 >= pattern.length() || pattern.charAt(i + 2) != '\'' || Character.isLetter(pattern.charAt(i + 1)) == false) {
                    return null;
                }
                literal = pattern.charAt(i + 1);
                literalLength = 3;
            } else if (literal != '-' && literal != ':' && literal != ' ' && literal != '/' && literal != '.' && literal != ',') {
                return null;
            }
            literals.add(literal);
            i += literalLength;
            if (i == pattern.length()) {
                // a pattern ending with a literal
                return null;
            }
        }
        // a full date and time to the second: other combinations default missing fields in ways not handled here
        if (fields.containsAll(List.of('Y', 'M', 'D', 'h', 'm', 's')) == false) {
            return null;
        }
        char[] f = new char[fields.size()];
        char[] l = new char[literals.size()];
        for (int k = 0; k < f.length; k++) {
            f[k] = fields.get(k);
            l[k] = literals.get(k);
        }
        return new FastDateParser(new Kind[] { Kind.PATTERN }, f, l);
    }

    /** The instant of the input in UTC, or null when the caller must parse it with java.time. */
    Instant parse(String input) {
        final int length = input.length();
        if (length == 0) {
            return null;
        }
        if (kinds[0] == Kind.PATTERN) {
            return parsePattern(input);
        }
        if (isDigits(input)) {
            // The first format that accepts the digits decides. A strict ISO format accepts 4 digits (a year).
            for (Kind kind : kinds) {
                switch (kind) {
                    case EPOCH_MILLIS:
                        return length <= 15 ? Instant.ofEpochMilli(Long.parseLong(input)) : null;
                    case EPOCH_SECOND:
                        return length <= 15 ? Instant.ofEpochSecond(Long.parseLong(input)) : null;
                    default:
                        if (length == 4) {
                            return null;
                        }
                }
            }
            return null;
        }
        // An ISO date-time: epoch formats reject it, the ISO formats that accept it agree.
        for (Kind kind : kinds) {
            if (kind == Kind.ISO_ALL) {
                return parseIso(input);
            }
        }
        return null;
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Value of the digits s[start, start + count), or -1 when one is not a digit. */
    private static int digits(String s, int start, int count) {
        int value = 0;
        for (int i = start; i < start + count; i++) {
            int d = s.charAt(i) - '0';
            if (d < 0 || d > 9) {
                return -1;
            }
            value = value * 10 + d;
        }
        return value;
    }

    /** yyyy-MM-dd[THH:mm:ss[.f{1,9}][Z|+HH:mm|-HH:mm]] */
    private static Instant parseIso(String s) {
        final int length = s.length();
        if (length != 10 && length < 19) {
            return null;
        }
        if (s.charAt(4) != '-' || s.charAt(7) != '-') {
            return null;
        }
        int year = digits(s, 0, 4);
        int month = digits(s, 5, 2);
        int day = digits(s, 8, 2);
        if (year < 0 || isValidDate(year, month, day) == false) {
            return null;
        }
        long epochDay = epochDay(year, month, day);
        if (length == 10) {
            return Instant.ofEpochSecond(epochDay * 86400);
        }
        if (s.charAt(10) != 'T' || s.charAt(13) != ':' || s.charAt(16) != ':') {
            return null;
        }
        int hour = digits(s, 11, 2);
        int minute = digits(s, 14, 2);
        int second = digits(s, 17, 2);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) {
            return null;
        }
        int i = 19;
        int nanos = 0;
        if (i < length && s.charAt(i) == '.') {
            int start = ++i;
            while (i < length && i - start < 9 && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
                nanos = nanos * 10 + (s.charAt(i) - '0');
                i++;
            }
            int count = i - start;
            if (count == 0 || (i < length && s.charAt(i) >= '0' && s.charAt(i) <= '9')) {
                return null;
            }
            for (int k = count; k < 9; k++) {
                nanos *= 10;
            }
        }
        int offsetSeconds = 0;
        if (i < length) {
            char c = s.charAt(i);
            if (c == 'Z' && i + 1 == length) {
                offsetSeconds = 0;
            } else if ((c == '+' || c == '-') && i + 6 == length && s.charAt(i + 3) == ':') {
                int offsetHours = digits(s, i + 1, 2);
                int offsetMinutes = digits(s, i + 4, 2);
                if (offsetHours < 0 || offsetMinutes < 0 || offsetMinutes > 59 || offsetHours * 60 + offsetMinutes > 18 * 60) {
                    return null;
                }
                offsetSeconds = (offsetHours * 3600 + offsetMinutes * 60) * (c == '-' ? -1 : 1);
            } else {
                return null;
            }
        }
        long seconds = epochDay * 86400 + hour * 3600L + minute * 60L + second - offsetSeconds;
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private Instant parsePattern(String s) {
        int year = -1, month = -1, day = -1, hour = -1, minute = -1, second = -1, nanos = 0;
        int i = 0;
        final int length = s.length();
        for (int k = 0; k < patternFields.length; k++) {
            char field = patternFields[k];
            int width = field == 'Y' ? 4 : field == 'S' ? 3 : 2;
            if (i + width > length) {
                return null;
            }
            int value = digits(s, i, width);
            if (value < 0) {
                return null;
            }
            i += width;
            switch (field) {
                case 'Y' -> year = value;
                case 'M' -> month = value;
                case 'D' -> day = value;
                case 'h' -> hour = value;
                case 'm' -> minute = value;
                case 's' -> second = value;
                default -> nanos = value * 1_000_000;
            }
            char literal = patternLiterals[k];
            if (literal == 0) {
                if (i != length) {
                    return null;
                }
            } else {
                if (i >= length || s.charAt(i) != literal) {
                    return null;
                }
                i++;
            }
        }
        // yyyy is the year of the era: year 0 does not exist
        if (year < 1 || isValidDate(year, month, day) == false || hour > 23 || minute > 59 || second > 59) {
            return null;
        }
        return Instant.ofEpochSecond(epochDay(year, month, day) * 86400 + hour * 3600L + minute * 60L + second, nanos);
    }

    private static boolean isValidDate(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1) {
            return false;
        }
        int days = switch (month) {
            case 2 -> ((year & 3) == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28;
            case 4, 6, 9, 11 -> 30;
            default -> 31;
        };
        return day <= days;
    }

    /** Days from 1970-01-01 to the date, for years 0 to 9999 (proleptic Gregorian calendar). */
    private static long epochDay(int year, int month, int day) {
        // the algorithm of java.time.LocalDate.toEpochDay
        long y = year;
        long total = 365 * y;
        total += (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400;
        total += (367 * month - 362) / 12;
        total += day - 1;
        if (month > 2) {
            total--;
            if ((year & 3) != 0 || (year % 100 == 0 && year % 400 != 0)) {
                total--;
            }
        }
        return total - 719528;
    }
}
