/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.common.time;

import org.opensearch.test.OpenSearchTestCase;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

/**
 * Whenever {@link DateFormatter#parseInstantFast} returns an instant, java.time must parse the input to the same
 * instant.
 */
public class FastDateParserTests extends OpenSearchTestCase {

    private static final String[] FORMATS = {
        "strict_date_optional_time",
        "strict_date_optional_time_nanos",
        "strict_date_time_no_millis||strict_date_optional_time||epoch_millis",
        "strict_date_optional_time||epoch_second",
        "strict_date_optional_time||epoch_millis",
        "epoch_millis||strict_date_optional_time",
        "epoch_second||strict_date_time",
        "epoch_millis",
        "epoch_second",
        "strict_date",
        "strict_date_time",
        "strict_date_time_no_millis",
        "strict_year_month_day||epoch_millis",
        "strict_date||strict_date_optional_time",
        "8strict_date_optional_time",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "dd/MM/yyyy HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss,SSS",
        "HH:mm:ss dd.MM.yyyy",
        "yyyy-MM-dd",
        "uuuu-MM-dd HH:mm:ss",
        "date_optional_time||epoch_millis",
        "strict_date_optional_time||yyyy-MM-dd HH:mm:ss" };

    public void testSameInstantAsJavaTime() {
        int fast = 0;
        for (String format : FORMATS) {
            DateFormatter formatter = DateFormatter.forPattern(format);
            for (int i = 0; i < 3000; i++) {
                String input = randomInput();
                if (check(formatter, input)) {
                    fast++;
                }
            }
        }
        assertTrue("fast path used " + fast + " times", fast > 5_000);
    }

    public void testCommonInputsUseTheFastPath() {
        DateFormatter defaultFormat = DateFormatter.forPattern("strict_date_time_no_millis||strict_date_optional_time||epoch_millis");
        for (String input : new String[] {
            "2023-01-01T07:58:37.000Z",
            "2023-01-01T07:58:37Z",
            "2023-01-01",
            "2023-01-01T07:58:37.123456789+05:30",
            "2023-01-01T07:58:37-08:00",
            "1420070400000" }) {
            assertTrue(input, check(defaultFormat, input));
        }
        assertTrue(check(DateFormatter.forPattern("strict_date_optional_time||epoch_second"), "893964617"));
        assertTrue(check(DateFormatter.forPattern("yyyy-MM-dd HH:mm:ss"), "2015-01-01 00:34:42"));
        // the ISO year comes first
        assertFalse(check(DateFormatter.forPattern("strict_date_optional_time||epoch_millis"), "2015"));
        assertFalse(check(DateFormatter.forPattern("strict_date_optional_time"), "2015-02-29"));
        assertFalse(check(DateFormatter.forPattern("strict_date_optional_time"), "2015-01-01T23:59:60Z"));
    }

    public void testOtherZonesAndLocales() {
        DateFormatter formatter = DateFormatter.forPattern("strict_date_optional_time");
        assertNull(formatter.withZone(ZoneId.of("Europe/Paris")).parseInstantFast("2023-01-01T07:58:37"));
        assertNotNull(formatter.withLocale(Locale.GERMANY).parseInstantFast("2023-01-01T07:58:37"));
        assertTrue(check(formatter.withLocale(Locale.GERMANY), "2023-01-01T07:58:37.5Z"));
    }

    /** Returns whether the fast path parsed the input, after checking it agrees with java.time. */
    private static boolean check(DateFormatter formatter, String input) {
        Instant fast = formatter.parseInstantFast(input);
        if (fast == null) {
            return false;
        }
        Instant expected;
        try {
            expected = DateFormatters.from(formatter.parse(input), formatter.locale()).toInstant();
        } catch (RuntimeException e) {
            throw new AssertionError("[" + formatter.pattern() + "] [" + input + "]: fast path gave " + fast + ", java.time " + e, e);
        }
        assertEquals("[" + formatter.pattern() + "] [" + input + "]", expected, fast);
        return true;
    }

    private static String digits(int count, int max) {
        return String.format(Locale.ROOT, "%0" + count + "d", randomIntBetween(0, max));
    }

    private static String randomInput() {
        String s = switch (randomInt(5)) {
            case 0, 1 -> randomIso();
            case 2 -> randomPatternLike();
            case 3 -> randomNumericOfLength(randomIntBetween(1, 20));
            case 4 -> digits(4, 9999);
            default -> Long.toString(randomLongBetween(-100_000_000_000_000L, 100_000_000_000_000L));
        };
        if (randomInt(10) == 0) {
            s = mutate(s);
        }
        return s;
    }

    private static String randomNumericOfLength(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append((char) ('0' + randomInt(9)));
        }
        return sb.toString();
    }

    private static String randomIso() {
        StringBuilder sb = new StringBuilder();
        sb.append(randomBoolean() ? digits(4, 9999) : randomFrom("1970", "2000", "2023", "1900", "2100", "0000", "0001", "9999"));
        sb.append('-').append(String.format(Locale.ROOT, "%02d", randomIntBetween(0, 13)));
        sb.append('-').append(String.format(Locale.ROOT, "%02d", randomIntBetween(0, 32)));
        if (randomInt(4) == 0) {
            return sb.toString();
        }
        sb.append(randomFrom("T", "T", "T", " ", "t"));
        sb.append(String.format(Locale.ROOT, "%02d", randomIntBetween(0, 24)));
        sb.append(':').append(String.format(Locale.ROOT, "%02d", randomIntBetween(0, 60)));
        if (randomInt(10) != 0) {
            sb.append(':').append(String.format(Locale.ROOT, "%02d", randomIntBetween(0, 60)));
        }
        if (randomBoolean()) {
            sb.append(randomFrom(".", ".", ",")).append(randomNumericOfLength(randomIntBetween(0, 10)));
        }
        switch (randomInt(6)) {
            case 0, 1 -> sb.append('Z');
            case 2 -> sb.append(randomFrom("+", "-"))
                .append(String.format(Locale.ROOT, "%02d", randomIntBetween(0, 19)))
                .append(':')
                .append(String.format(Locale.ROOT, "%02d", randomIntBetween(0, 60)));
            case 3 -> sb.append(randomFrom("+", "-")).append(String.format(Locale.ROOT, "%04d", randomIntBetween(0, 1900)));
            case 4 -> sb.append(randomFrom("z", "+01", "UTC", "[UTC]", "+01:00:00"));
            default -> {
            }
        }
        return sb.toString();
    }

    private static String randomPatternLike() {
        String date = randomBoolean()
            ? digits(4, 9999) + "-" + digits(2, 13) + "-" + digits(2, 32)
            : digits(2, 32) + "/" + digits(2, 13) + "/" + digits(4, 9999);
        String time = digits(2, 24) + ":" + digits(2, 60) + ":" + digits(2, 60);
        String s = switch (randomInt(4)) {
            case 0 -> date + " " + time;
            case 1 -> date + "T" + time + "." + digits(3, 999);
            case 2 -> time + " " + digits(2, 31) + "." + digits(2, 12) + "." + digits(4, 9999);
            default -> date.replace('-', '/') + " " + time + "," + digits(3, 999);
        };
        return s;
    }

    private static String mutate(String s) {
        if (s.isEmpty()) {
            return "1";
        }
        int i = randomInt(s.length() - 1);
        return switch (randomInt(2)) {
            case 0 -> s.substring(0, i) + s.substring(i + 1);
            case 1 -> s.substring(0, i) + randomFrom("0", "9", "-", ":", "T", " ", "+", ".") + s.substring(i);
            default -> s.substring(0, i) + randomFrom("0", "9", "x") + s.substring(i + 1);
        };
    }
}
