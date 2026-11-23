/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.common.xcontent.json;

import org.opensearch.common.xcontent.XContentConstraints;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentLocation;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.test.OpenSearchTestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.instanceOf;

/**
 * Compares the simdjson parser with Jackson on random documents: every accessor must return the same
 * value or throw the same exception type.
 */
public class SimdJsonXContentParserTests extends OpenSearchTestCase {

    private static final NamedXContentRegistry REGISTRY = NamedXContentRegistry.EMPTY;
    private static final DeprecationHandler HANDLER = DeprecationHandler.THROW_UNSUPPORTED_OPERATION;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        assumeTrue("simdjson parser: " + JsonXContent.simdJsonStatus(), JsonXContent.isSimdJsonEnabled());
    }

    private static XContentParser simd(byte[] bytes, int offset, int length) {
        return SimdJsonXContentParser.create(REGISTRY, HANDLER, bytes, offset, length);
    }

    private static XContentParser jackson(byte[] bytes, int offset, int length) {
        return JsonXContent.jsonXContent.createJacksonParser(REGISTRY, HANDLER, Arrays.copyOfRange(bytes, offset, offset + length));
    }

    public void testRandomDocuments() throws IOException {
        for (int i = 0; i < 2000; i++) {
            String json = randomDocument();
            byte[] doc = json.getBytes(StandardCharsets.UTF_8);
            // a document in the middle of a larger array, as in a bulk request
            int before = randomIntBetween(0, 16);
            byte[] padded = new byte[before + doc.length + randomIntBetween(0, 16)];
            Arrays.fill(padded, (byte) (char) randomFrom('x', '"', '\\', '{', ' '));
            System.arraycopy(doc, 0, padded, before, doc.length);
            try (XContentParser parser = simd(padded, before, doc.length)) {
                assertNotNull(json, parser);
                try (XContentParser expected = jackson(padded, before, doc.length)) {
                    assertSameTokens(json, expected, parser);
                }
            }
        }
    }

    public void testSkipChildren() throws IOException {
        for (int i = 0; i < 500; i++) {
            byte[] doc = randomDocument().getBytes(StandardCharsets.UTF_8);
            long seed = randomLong();
            try (XContentParser parser = simd(doc, 0, doc.length); XContentParser expected = jackson(doc, 0, doc.length)) {
                java.util.Random random = new java.util.Random(seed);
                XContentParser.Token token;
                while ((token = expected.nextToken()) != null) {
                    assertEquals(token, parser.nextToken());
                    if ((token == XContentParser.Token.START_OBJECT || token == XContentParser.Token.START_ARRAY)
                        && random.nextInt(3) == 0) {
                        expected.skipChildren();
                        parser.skipChildren();
                        assertEquals(expected.currentToken(), parser.currentToken());
                    }
                    assertEquals(expected.currentName(), parser.currentName());
                }
                assertNull(parser.nextToken());
            }
        }
    }

    public void testMap() throws IOException {
        for (int i = 0; i < 500; i++) {
            String json = randomObject(0);
            byte[] doc = json.getBytes(StandardCharsets.UTF_8);
            try (XContentParser parser = simd(doc, 0, doc.length); XContentParser expected = jackson(doc, 0, doc.length)) {
                assertEquals(json, expected.map(), parser.map());
            }
        }
    }

    public void testFallbacks() throws IOException {
        String[] documents = {
            "{\"a\":1,\"a\":2}",
            "{\"a\":1,\"\\u0061\":2}",
            "{\"a\":01}",
            "{\"a\":1.}",
            "{\"a\":.5}",
            "{\"a\":+1}",
            "{\"a\":tru}",
            "{\"a\":truex}",
            "{\"a\":NaN}",
            "{\"a\":1 /* comment */}",
            "// comment\n{\"a\":1}",
            "{\"a\":1}{}",
            "{\"a\":1,}",
            "[1,]",
            "{\"a\" 1}",
            "{\"a\":\"\\x\"}",
            "{\"a\":\"\\u12\"}",
            "{\"a\":\"\\ud800\"}",
            "{\"a\":\"\\udc00\"}",
            "{\"a\":\"\t\"}",
            "{\"a\":\"\u00e9",
            "1",
            "\"x\"",
            "true",
            "",
            "  ",
            "{\"a\":1",
            "{\"a\":[1}",
            "{\"a\":\"b\" \"c\"}",
            "{\"a\":\"x\"y}",
            "{\"a\":1e}",
            "{\"a\":-}",
            "{\"a\":" + "1".repeat(1000) + "}",
            "[".repeat(XContentConstraints.DEFAULT_MAX_DEPTH + 1) + "]".repeat(XContentConstraints.DEFAULT_MAX_DEPTH + 1) };
        for (String json : documents) {
            byte[] doc = json.getBytes(StandardCharsets.UTF_8);
            assertNull(json, simd(doc, 0, doc.length));
            // JsonXContent falls back on Jackson
            try (XContentParser parser = JsonXContent.jsonXContent.createParser(REGISTRY, HANDLER, doc)) {
                assertThat(parser, instanceOf(JsonXContentParser.class));
            }
        }
        byte[] invalidUtf8 = { '{', '"', 'a', '"', ':', '"', (byte) 0xC3, '"', '}' };
        assertNull(simd(invalidUtf8, 0, invalidUtf8.length));
    }

    public void testEdgeCases() throws IOException {
        String[] documents = {
            "{}",
            "[]",
            " \n\t{ } \r\n",
            "{\"\":\"\"}",
            "{\"a\":{},\"b\":[],\"c\":[{}],\"d\":[[]]}",
            "{\"a\":2147483647,\"b\":2147483648,\"c\":-2147483648,\"d\":-2147483649,\"e\":32767,\"f\":32768,\"g\":-32769}",
            "{\"a\":9223372036854775807,\"b\":9223372036854775808,\"c\":-9223372036854775808,\"d\":-9223372036854775809}",
            "{\"a\":123456789012345678901234567890,\"b\":-0,\"c\":0,\"d\":1E400,\"e\":-1e-400,\"f\":4.9e-324,\"g\":1.7976931348623157e308}",
            "{\"a\":0.1,\"b\":0.2,\"c\":0.30000000000000004,\"d\":3.4028235e38,\"e\":3.4028236e38,\"f\":1.4e-45,\"g\":16777217}",
            "{\"a\":1.5,\"b\":-1.5,\"c\":1e3,\"d\":2.5e-3,\"e\":123456789.123456789,\"f\":9007199254740993,\"g\":9007199254740993.0}",
            "{\"a\":true,\"b\":false,\"c\":null,\"d\":[true,false,null]}",
            "{\"a\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0000\\u001f\\u00e9\\u20ac\\ud83d\\ude00\"}",
            "{\"k\\n\":1,\"k\\t\":2,\"\\u00e9\":3,\"\u00e9t\u00e9\":\"caf\u00e9 \u20ac \ud83d\ude00\"}",
            "{\"a\":\"dGVzdA==\",\"b\":\"not base64!\"}",
            "{\"a\":\"true\",\"b\":\"1.5\",\"c\":\"12\",\"d\":\"\"}",
            "{\"a\":\r\n[\r1,\n2\r\n],\n\"b\":\n{\"c\":\"d\"}}" };
        for (String json : documents) {
            byte[] doc = json.getBytes(StandardCharsets.UTF_8);
            try (XContentParser parser = simd(doc, 0, doc.length); XContentParser expected = jackson(doc, 0, doc.length)) {
                assertNotNull(json, parser);
                assertSameTokens(json, expected, parser);
            }
        }
    }

    public void testParsersOnTheSameThread() throws IOException {
        byte[] doc = "{\"a\":[1,2,3],\"b\":\"c\"}".getBytes(StandardCharsets.UTF_8);
        try (XContentParser outer = JsonXContent.jsonXContent.createParser(REGISTRY, HANDLER, doc)) {
            assertThat(outer, instanceOf(SimdJsonXContentParser.class));
            assertEquals(XContentParser.Token.START_OBJECT, outer.nextToken());
            // the thread's buffers are lent to the outer parser, so the inner one uses Jackson
            try (XContentParser inner = JsonXContent.jsonXContent.createParser(REGISTRY, HANDLER, doc)) {
                assertThat(inner, instanceOf(JsonXContentParser.class));
                assertEquals(XContentParser.Token.START_OBJECT, inner.nextToken());
            }
            assertEquals(XContentParser.Token.FIELD_NAME, outer.nextToken());
            assertEquals("a", outer.currentName());
        }
        try (XContentParser again = JsonXContent.jsonXContent.createParser(REGISTRY, HANDLER, doc)) {
            assertThat(again, instanceOf(SimdJsonXContentParser.class));
        }
    }

    public void testLargeDocument() throws IOException {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 50_000; i++) {
            sb.append(i == 0 ? "" : ",").append(randomBoolean() ? "1" : "{\"a\":[0,\"x\\n\"]}");
        }
        byte[] doc = sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
        for (int round = 0; round < 2; round++) {
            try (XContentParser parser = simd(doc, 0, doc.length); XContentParser expected = jackson(doc, 0, doc.length)) {
                assertSameTokens("large document", expected, parser);
            }
        }
    }

    private static void assertSameTokens(String json, XContentParser expected, XContentParser actual) throws IOException {
        assertNull(json, actual.currentToken());
        while (true) {
            XContentParser.Token token = expected.nextToken();
            assertEquals(json, token, actual.nextToken());
            assertEquals(json, expected.currentToken(), actual.currentToken());
            assertEquals(json, expected.currentName(), actual.currentName());
            if (token == null) {
                break;
            }
            assertLocation(json, expected.getTokenLocation(), actual.getTokenLocation());
            assertEquals(
                json,
                new String(expected.textCharacters(), expected.textOffset(), expected.textLength()),
                new String(actual.textCharacters(), actual.textOffset(), actual.textLength())
            );
            assertSame(json, expected, actual, XContentParser::text);
            assertSame(json, expected, actual, XContentParser::textOrNull);
            assertSame(json, expected, actual, p -> p.charBuffer().toString());
            assertSame(json, expected, actual, XContentParser::objectText);
            assertSame(json, expected, actual, p -> String.valueOf(p.objectBytes()));
            assertSame(json, expected, actual, XContentParser::isBooleanValue);
            assertSame(json, expected, actual, XContentParser::booleanValue);
            assertSame(json, expected, actual, XContentParser::numberType);
            assertSame(json, expected, actual, XContentParser::numberValue);
            for (boolean coerce : new boolean[] { true, false }) {
                assertSame(json, expected, actual, p -> p.shortValue(coerce));
                assertSame(json, expected, actual, p -> p.intValue(coerce));
                assertSame(json, expected, actual, p -> p.longValue(coerce));
                assertSame(json, expected, actual, p -> p.floatValue(coerce));
                assertSame(json, expected, actual, p -> p.doubleValue(coerce));
                assertSame(json, expected, actual, p -> p.bigIntegerValue(coerce));
            }
            assertSame(json, expected, actual, p -> {
                byte[] bytes = p.binaryValue();
                return bytes == null ? null : Arrays.toString(bytes);
            });
        }
        assertNull(json, actual.nextToken());
    }

    private static void assertLocation(String json, XContentLocation expected, XContentLocation actual) {
        assertEquals(json, expected.lineNumber, actual.lineNumber);
        assertEquals(json, expected.columnNumber, actual.columnNumber);
    }

    private interface Accessor {
        Object get(XContentParser parser) throws Exception;
    }

    /** Both parsers return equal values, or throw exceptions of the same class. */
    private static void assertSame(String json, XContentParser expected, XContentParser actual, Accessor accessor) {
        Object expectedValue;
        try {
            expectedValue = accessor.get(expected);
        } catch (Exception e) {
            try {
                Object value = accessor.get(actual);
                fail(json + " at " + expected.currentToken() + ": expected " + e + " but got " + value);
            } catch (Exception f) {
                assertEquals(json + ": " + e + " / " + f, e.getClass(), f.getClass());
            }
            return;
        }
        Object actualValue;
        try {
            actualValue = accessor.get(actual);
        } catch (Exception e) {
            throw new AssertionError(json + " at " + expected.currentToken() + ": expected " + expectedValue + " but got " + e, e);
        }
        if (expectedValue instanceof Float f && actualValue instanceof Float g) {
            assertEquals(json, Float.floatToRawIntBits(f), Float.floatToRawIntBits(g));
        } else if (expectedValue instanceof Double d && actualValue instanceof Double e) {
            assertEquals(json, Double.doubleToRawLongBits(d), Double.doubleToRawLongBits(e));
        } else {
            assertEquals(json + " at " + expected.currentToken(), expectedValue, actualValue);
            if (expectedValue != null) {
                assertEquals(json, expectedValue.getClass(), actualValue.getClass());
            }
        }
    }

    private static String randomDocument() {
        return randomBoolean() ? randomObject(0) : randomArray(0);
    }

    private static String ws() {
        return randomInt(4) == 0 ? randomFrom(" ", "\n", "\t", "\r\n", "  \n ") : "";
    }

    private static String randomObject(int depth) {
        StringBuilder sb = new StringBuilder(ws()).append('{');
        int n = randomIntBetween(0, depth < 3 ? 8 : 2);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String key;
            do {
                key = randomString(true);
            } while (keys.contains(key));
            keys.add(key);
            sb.append(i == 0 ? "" : ",").append(ws()).append(quote(key, false)).append(ws()).append(':').append(randomValue(depth + 1));
        }
        return sb.append(ws()).append('}').append(ws()).toString();
    }

    private static String randomArray(int depth) {
        StringBuilder sb = new StringBuilder(ws()).append('[');
        int n = randomIntBetween(0, depth < 3 ? 6 : 2);
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append(randomValue(depth + 1));
        }
        return sb.append(ws()).append(']').append(ws()).toString();
    }

    private static String randomValue(int depth) {
        String value = switch (randomInt(depth < 5 ? 9 : 7)) {
            case 0, 1 -> quote(randomString(false));
            case 2 -> randomInteger();
            case 3 -> randomDecimal();
            case 4 -> randomFrom("true", "false", "null");
            case 5 -> quote(randomFrom("true", "false", "1", "-2.5", "1e3", "dGVzdA==", "", "x"));
            case 6, 7 -> quote(randomString(true));
            case 8 -> randomObject(depth);
            default -> randomArray(depth);
        };
        return ws() + value + ws();
    }

    private static String randomInteger() {
        return switch (randomInt(5)) {
            case 0 -> Integer.toString(randomInt());
            case 1 -> Long.toString(randomLong());
            case 2 -> Integer.toString(randomIntBetween(-40000, 40000));
            case 3 -> (randomBoolean() ? "-" : "") + randomFrom("1", "9") + randomNumericString(randomIntBetween(0, 30));
            case 4 -> randomFrom("2147483647", "2147483648", "-2147483649", "9223372036854775807", "9223372036854775808", "-0", "0");
            default -> Short.toString(randomShort());
        };
    }

    private static String randomNumericString(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append((char) ('0' + randomInt(9)));
        }
        return sb.toString();
    }

    private static String randomDecimal() {
        return switch (randomInt(4)) {
            case 0 -> Double.toString(randomDouble() * randomFrom(1.0, 1e5, 1e-5, 1e300));
            case 1 -> Float.toString(randomFloat() * 1000);
            case 2 -> (randomBoolean() ? "-" : "")
                + randomFrom("0", "1", "12", "123456789")
                + "."
                + randomNumericString(randomIntBetween(1, 25))
                + (randomBoolean() ? "" : randomFrom("e", "E") + randomFrom("", "+", "-") + randomIntBetween(0, 400));
            case 3 -> randomFrom("1e5", "1E-5", "0.0", "-0.0", "1.0", "100.5", "40.677711486816406", "-73.92259216308594");
            default -> Double.toString(randomDouble());
        };
    }

    private static String randomString(boolean shortString) {
        int length = shortString ? randomIntBetween(0, 12) : randomIntBetween(0, randomBoolean() ? 20 : 300);
        return switch (randomInt(3)) {
            case 0 -> randomAlphaOfLength(length);
            case 1 -> randomUnicodeOfLength(length);
            case 2 -> randomRealisticUnicodeOfCodepointLength(length);
            default -> randomAlphaOfLength(length) + "/" + randomAlphaOfLength(2);
        };
    }

    private static String quote(String s) {
        return quote(s, true);
    }

    /** A JSON string literal for s, with random escapes, and with a random escape appended when suffix is true. */
    private static String quote(String s, boolean suffix) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean escape = c < 0x20 || c == '"' || c == '\\' || Character.isSurrogate(c) == false && randomInt(20) == 0;
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)) && randomInt(5) == 0) {
                sb.append(String.format(java.util.Locale.ROOT, "\\u%04x\\u%04X", (int) c, (int) s.charAt(i + 1)));
                i++;
            } else if (Character.isSurrogate(c)) {
                sb.append(c);
            } else if (escape) {
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append(randomBoolean() ? "\\\\" : "\\u005c");
                    case '\n' -> sb.append("\\n");
                    case '\t' -> sb.append("\\t");
                    case '/' -> sb.append("\\/");
                    default -> sb.append(String.format(java.util.Locale.ROOT, randomBoolean() ? "\\u%04x" : "\\u%04X", (int) c));
                }
            } else {
                sb.append(c);
            }
        }
        if (suffix && randomInt(30) == 0) {
            sb.append(randomFrom("\\b", "\\f", "\\r", "\\/"));
        }
        return sb.append('"').toString();
    }
}
