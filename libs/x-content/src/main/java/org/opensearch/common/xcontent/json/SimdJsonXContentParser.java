/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.common.xcontent.json;

import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.xcontent.AbstractXContentParser;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentLocation;
import org.opensearch.core.xcontent.XContentParser;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;

import tools.jackson.core.io.NumberInput;

/**
 * A JSON parser that reads the tokens the native tokenizer (simdjson) produced for a whole document.
 * <p>
 * The native side validates the document completely before this parser exists: the grammar, UTF-8,
 * the numbers, the escapes, the duplicate keys and the limits that {@link JsonXContent} configures in
 * Jackson. It returns no tokens for any document that Jackson might treat differently (comments, a
 * scalar at the root, a number longer than 1000 characters, an escaped lone surrogate, any error), and
 * {@link JsonXContent} then parses that document with Jackson. A document this parser accepts is thus
 * one Jackson accepts, and this parser returns the same tokens, names, text and values.
 * <p>
 * Strings and numbers are materialized when they are read. The common cases are handled here; the
 * rest (numbers out of range of the requested type, base64, a value requested from a token of another
 * type) are delegated to a Jackson parser over the bytes of the current token, so that the values and
 * the exceptions are Jackson's.
 */
final class SimdJsonXContentParser extends AbstractXContentParser {

    // Token kinds, as written by the native tokenizer.
    private static final int START_OBJECT = 1;
    private static final int END_OBJECT = 2;
    private static final int START_ARRAY = 3;
    private static final int END_ARRAY = 4;
    private static final int FIELD_NAME = 5;
    private static final int STRING = 6;
    private static final int INT = 7;
    private static final int FLOAT = 8;
    private static final int TRUE = 9;
    private static final int FALSE = 10;
    private static final int NULL = 11;
    /** Set on a string or a field name whose content had escapes: it is decoded in the scratch buffer. */
    private static final int ESCAPED = 0x40;
    private static final int KIND_MASK = 0x3F;

    private static final Token[] TOKENS = {
        null,
        Token.START_OBJECT,
        Token.END_OBJECT,
        Token.START_ARRAY,
        Token.END_ARRAY,
        Token.FIELD_NAME,
        Token.VALUE_STRING,
        Token.VALUE_NUMBER,
        Token.VALUE_NUMBER,
        Token.VALUE_BOOLEAN,
        Token.VALUE_BOOLEAN,
        Token.VALUE_NULL };

    private static final float[] FLOAT_POWERS = { 1e0f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f };
    private static final double[] DOUBLE_POWERS = {
        1e0,
        1e1,
        1e2,
        1e3,
        1e4,
        1e5,
        1e6,
        1e7,
        1e8,
        1e9,
        1e10,
        1e11,
        1e12,
        1e13,
        1e14,
        1e15,
        1e16,
        1e17,
        1e18,
        1e19,
        1e20,
        1e21,
        1e22 };

    private SimdJsonParserState state;
    private final byte[] buf;
    private final int documentOffset;
    private final int documentLength;
    private final int[] tokens;
    private final byte[] scratch;
    private final int tokenCount;
    private final SimdJsonParserState.NameCache names;
    private String[] contextNames;
    private boolean[] contextArrays;

    /** Index of the current token, -1 before the first one and tokenCount after the last one. */
    private int pos = -1;
    /** Kind of the current token, with the ESCAPED flag, or 0 when there is none. */
    private int kind;
    private int start;
    private int end;
    private int depth;
    /** textCharacters() of the current token, built on demand. */
    private char[] chars;
    /**
     * Whether the float or the BigDecimal value of the current FLOAT token was read: Jackson then reports the
     * number as a FLOAT or a BIG_DECIMAL, rather than a DOUBLE, in numberType() and numberValue().
     */
    private boolean floatRead;
    private boolean decimalRead;

    private SimdJsonXContentParser(
        NamedXContentRegistry registry,
        DeprecationHandler deprecationHandler,
        SimdJsonParserState state,
        byte[] buf,
        int offset,
        int length,
        int tokenCount
    ) {
        super(registry, deprecationHandler);
        this.state = state;
        this.buf = buf;
        this.documentOffset = offset;
        this.documentLength = length;
        this.tokens = state.tokens;
        this.scratch = state.scratch;
        this.tokenCount = tokenCount;
        this.names = state.names;
        this.contextNames = state.contextNames;
        this.contextArrays = state.contextArrays;
        contextNames[0] = null;
        contextArrays[0] = false;
    }

    /** Returns a parser for the document, or null when Jackson must parse it. */
    static XContentParser create(
        NamedXContentRegistry registry,
        DeprecationHandler deprecationHandler,
        byte[] buf,
        int offset,
        int length
    ) {
        SimdJsonParserState state = SimdJsonParserState.acquire();
        if (state == null) {
            return null;
        }
        int count;
        try {
            count = state.tokenize(buf, offset, length);
        } catch (RuntimeException | Error e) {
            state.release();
            throw e;
        }
        if (count <= 0) {
            state.release();
            return null;
        }
        return new SimdJsonXContentParser(registry, deprecationHandler, state, buf, offset, length, count);
    }

    @Override
    public XContentType contentType() {
        return XContentType.JSON;
    }

    @Override
    public Token nextToken() {
        chars = null;
        floatRead = false;
        decimalRead = false;
        final int p = ++pos;
        if (p >= tokenCount) {
            pos = tokenCount;
            kind = 0;
            return null;
        }
        final int[] tokens = this.tokens;
        final int base = p << 2;
        final int k = tokens[base];
        kind = k;
        start = tokens[base + 1];
        end = tokens[base + 2];
        switch (k & KIND_MASK) {
            case START_OBJECT:
                push(false);
                break;
            case START_ARRAY:
                push(true);
                break;
            case END_OBJECT:
            case END_ARRAY:
                depth--;
                break;
            case FIELD_NAME:
                contextNames[depth] = (k & ESCAPED) == 0
                    ? names.lookup(buf, start, end - start, tokens[base + 3])
                    : names.lookup(scratch, start, end - start);
                break;
            default:
                break;
        }
        return TOKENS[k & KIND_MASK];
    }

    private void push(boolean array) {
        if (++depth == contextNames.length) {
            contextNames = java.util.Arrays.copyOf(contextNames, depth * 2);
            contextArrays = java.util.Arrays.copyOf(contextArrays, depth * 2);
            state.contextNames = contextNames;
            state.contextArrays = contextArrays;
        }
        contextNames[depth] = null;
        contextArrays[depth] = array;
    }

    @Override
    public void skipChildren() {
        if (kind == START_OBJECT || kind == START_ARRAY) {
            chars = null;
            floatRead = false;
            decimalRead = false;
            pos = tokens[(pos << 2) + 3];
            kind = kind + 1;
            start = tokens[(pos << 2) + 1];
            end = tokens[(pos << 2) + 2];
            depth--;
        }
    }

    @Override
    public Token currentToken() {
        return TOKENS[kind & KIND_MASK];
    }

    /** Jackson's rule: the name of the field the current value, or the current object or array, belongs to. */
    @Override
    public String currentName() {
        int d = (kind == START_OBJECT || kind == START_ARRAY) ? depth - 1 : depth;
        return contextArrays[d] ? null : contextNames[d];
    }

    @Override
    protected boolean doBooleanValue() throws IOException {
        if (kind == TRUE) {
            return true;
        }
        if (kind == FALSE) {
            return false;
        }
        try (JsonXContentParser jackson = jacksonAtCurrentToken()) {
            return jackson.doBooleanValue();
        }
    }

    @Override
    public String text() throws IOException {
        if (currentToken().isValue()) {
            return currentText();
        }
        throw new IllegalStateException("Can't get text on a " + currentToken() + " at " + getTokenLocation());
    }

    /** What Jackson's getString() returns for the current token. */
    private String currentText() {
        switch (kind) {
            case STRING:
                return new String(buf, start, end - start, StandardCharsets.UTF_8);
            case STRING | ESCAPED:
                return new String(scratch, start, end - start, StandardCharsets.UTF_8);
            case INT:
            case FLOAT:
                return new String(buf, start, end - start, StandardCharsets.ISO_8859_1);
            case TRUE:
                return "true";
            case FALSE:
                return "false";
            case NULL:
                return "null";
            case FIELD_NAME:
            case FIELD_NAME | ESCAPED:
                return contextNames[depth];
            case START_OBJECT:
                return "{";
            case END_OBJECT:
                return "}";
            case START_ARRAY:
                return "[";
            case END_ARRAY:
                return "]";
            default:
                return null;
        }
    }

    private char[] currentChars() {
        if (chars == null) {
            String text = currentText();
            chars = text == null ? new char[0] : text.toCharArray();
        }
        return chars;
    }

    @Override
    public CharBuffer charBuffer() {
        char[] c = currentChars();
        return CharBuffer.wrap(c, 0, c.length);
    }

    @Override
    public Object objectText() throws IOException {
        switch (kind & KIND_MASK) {
            case STRING:
                return text();
            case INT:
            case FLOAT:
                return numberValue();
            case TRUE:
                return Boolean.TRUE;
            case FALSE:
                return Boolean.FALSE;
            case NULL:
                return null;
            default:
                return text();
        }
    }

    @Override
    public Object objectBytes() throws IOException {
        switch (kind & KIND_MASK) {
            case STRING:
                return charBuffer();
            case INT:
            case FLOAT:
                return numberValue();
            case TRUE:
                return Boolean.TRUE;
            case FALSE:
                return Boolean.FALSE;
            case NULL:
                return null;
            default:
                return charBuffer();
        }
    }

    @Override
    public boolean hasTextCharacters() {
        return false;
    }

    @Override
    public char[] textCharacters() {
        return currentChars();
    }

    @Override
    public int textLength() {
        return currentChars().length;
    }

    @Override
    public int textOffset() {
        return 0;
    }

    /** Digits of the current INT token, without the sign. */
    private int digits() {
        return end - start - (buf[start] == '-' ? 1 : 0);
    }

    /** The current INT token, which has at most 18 digits. */
    private long smallLong() {
        final byte[] b = buf;
        int i = start;
        final boolean negative = b[i] == '-';
        if (negative) {
            i++;
        }
        long value = 0;
        for (final int e = end; i < e; i++) {
            value = value * 10 + (b[i] - '0');
        }
        return negative ? -value : value;
    }

    @Override
    public Number numberValue() throws IOException {
        if (kind == INT) {
            int digits = digits();
            if (digits <= 9) {
                return (int) smallLong();
            }
            if (digits <= 18) {
                long value = smallLong();
                if (value == (int) value) {
                    return (int) value;
                }
                return value;
            }
        } else if (kind == FLOAT) {
            if (decimalRead) {
                return new BigDecimal(currentText());
            }
            if (floatRead) {
                return parseFloat();
            }
            return parseDouble();
        }
        try (JsonXContentParser jackson = jacksonAtCurrentToken()) {
            return jackson.numberValue();
        }
    }

    @Override
    public NumberType numberType() throws IOException {
        if (kind == INT) {
            int digits = digits();
            if (digits <= 9) {
                return NumberType.INT;
            }
            if (digits <= 18) {
                long value = smallLong();
                return value == (int) value ? NumberType.INT : NumberType.LONG;
            }
        } else if (kind == FLOAT) {
            return decimalRead ? NumberType.BIG_DECIMAL : (floatRead ? NumberType.FLOAT : NumberType.DOUBLE);
        }
        try (JsonXContentParser jackson = jacksonAtCurrentToken()) {
            return jackson.numberType();
        }
    }

    @Override
    protected short doShortValue() throws IOException {
        if (kind == INT && digits() <= 4) {
            return (short) smallLong();
        }
        try (JsonXContentParser jackson = jacksonAtCurrentToken()) {
            return jackson.doShortValue();
        }
    }

    @Override
    protected int doIntValue() throws IOException {
        if (kind == INT && digits() <= 9) {
            return (int) smallLong();
        }
        try (JsonXContentParser jackson = jacksonAtCurrentToken()) {
            return jackson.doIntValue();
        }
    }

    @Override
    protected long doLongValue() throws IOException {
        if (kind == INT && digits() <= 18) {
            return smallLong();
        }
        try (JsonXContentParser jackson = jacksonAtCurrentToken()) {
            return jackson.doLongValue();
        }
    }

    @Override
    protected float doFloatValue() throws IOException {
        if (kind == INT && digits() <= 18) {
            return (float) smallLong();
        }
        if (kind == FLOAT) {
            floatRead = true;
            return parseFloat();
        }
        try (JsonXContentParser jackson = jacksonAtCurrentToken()) {
            return jackson.doFloatValue();
        }
    }

    @Override
    protected double doDoubleValue() throws IOException {
        if (kind == INT && digits() <= 18) {
            return (double) smallLong();
        }
        if (kind == FLOAT) {
            return parseDouble();
        }
        try (JsonXContentParser jackson = jacksonAtCurrentToken()) {
            return jackson.doDoubleValue();
        }
    }

    @Override
    protected BigInteger doBigIntegerValue() throws IOException {
        if (kind == INT && digits() <= 18) {
            return BigInteger.valueOf(smallLong());
        }
        if (kind == FLOAT) {
            // Jackson reads a float token as a BigDecimal here
            decimalRead = true;
        }
        try (JsonXContentParser jackson = jacksonAtCurrentToken()) {
            return jackson.doBigIntegerValue();
        }
    }

    /**
     * Decimal significand and power of ten of the current FLOAT token, in decimal[0] and decimal[1], when
     * the significand has at most 19 digits. Returns false otherwise.
     */
    private boolean decimal(long[] decimal) {
        final byte[] b = buf;
        int i = start;
        final int e = end;
        if (b[i] == '-') {
            i++;
        }
        long significand = 0;
        int digits = 0;
        int exponent = 0;
        for (; i < e && b[i] >= '0' && b[i] <= '9'; i++) {
            significand = significand * 10 + (b[i] - '0');
            digits++;
        }
        if (i < e && b[i] == '.') {
            for (i++; i < e && b[i] >= '0' && b[i] <= '9'; i++) {
                significand = significand * 10 + (b[i] - '0');
                digits++;
                exponent--;
            }
        }
        if (i < e) {
            // an exponent: 'e' or 'E', an optional sign, digits
            i++;
            boolean negative = b[i] == '-';
            if (b[i] == '+' || b[i] == '-') {
                i++;
            }
            int value = 0;
            for (; i < e; i++) {
                if (value < 100_000) {
                    value = value * 10 + (b[i] - '0');
                }
            }
            exponent += negative ? -value : value;
        }
        // digits counts the leading zeros, so 19 digits never overflow
        if (digits > 19) {
            return false;
        }
        decimal[0] = significand;
        decimal[1] = exponent;
        return true;
    }

    private final long[] decimal = new long[2];

    /**
     * Clinger's fast path: when the significand and the power of ten are both exact doubles (floats),
     * a single correctly rounded multiplication or division gives the correctly rounded result, the value
     * Jackson's parser returns. Other numbers go to Jackson's parser.
     */
    private double parseDouble() {
        if (decimal(decimal)) {
            long significand = decimal[0];
            int exponent = (int) decimal[1];
            if (significand <= (1L << 53) && exponent >= -22 && exponent <= 22) {
                double d = (double) significand;
                d = exponent < 0 ? d / DOUBLE_POWERS[-exponent] : d * DOUBLE_POWERS[exponent];
                return buf[start] == '-' ? -d : d;
            }
        }
        return NumberInput.parseDouble(currentText(), true);
    }

    private float parseFloat() {
        if (decimal(decimal)) {
            long significand = decimal[0];
            int exponent = (int) decimal[1];
            if (significand <= (1L << 24) && exponent >= -10 && exponent <= 10) {
                float f = (float) significand;
                f = exponent < 0 ? f / FLOAT_POWERS[-exponent] : f * FLOAT_POWERS[exponent];
                return buf[start] == '-' ? -f : f;
            }
        }
        return NumberInput.parseFloat(currentText(), true);
    }

    @Override
    public byte[] binaryValue() throws IOException {
        try (JsonXContentParser jackson = jacksonAtCurrentToken()) {
            return jackson.binaryValue();
        }
    }

    /**
     * A Jackson parser over a copy of the current token, positioned on it, for the accessors this class
     * does not implement itself.
     */
    private JsonXContentParser jacksonAtCurrentToken() throws IOException {
        final byte[] doc;
        final int advance;
        switch (kind & KIND_MASK) {
            case 0:
                doc = new byte[0];
                advance = 0;
                break;
            case START_OBJECT:
                doc = new byte[] { '{' };
                advance = 1;
                break;
            case END_OBJECT:
                doc = new byte[] { '{', '}' };
                advance = 2;
                break;
            case START_ARRAY:
                doc = new byte[] { '[' };
                advance = 1;
                break;
            case END_ARRAY:
                doc = new byte[] { '[', ']' };
                advance = 2;
                break;
            case FIELD_NAME: {
                byte[] name = rawString();
                doc = new byte[name.length + 4];
                doc[0] = '{';
                System.arraycopy(name, 0, doc, 1, name.length);
                doc[name.length + 1] = ':';
                doc[name.length + 2] = '0';
                doc[name.length + 3] = '}';
                advance = 2;
                break;
            }
            case STRING:
                doc = rawString();
                advance = 1;
                break;
            default:
                doc = java.util.Arrays.copyOfRange(buf, start, end);
                advance = 1;
        }
        JsonXContentParser jackson = JsonXContent.jsonXContent.createJacksonParser(getXContentRegistry(), getDeprecationHandler(), doc);
        for (int i = 0; i < advance; i++) {
            jackson.nextToken();
        }
        return jackson;
    }

    /** The source bytes of the current string or field name, with its quotes and escapes. */
    private byte[] rawString() {
        if ((kind & ESCAPED) == 0) {
            return java.util.Arrays.copyOfRange(buf, start - 1, end + 1);
        }
        int quote = tokens[(pos << 2) + 3];
        int i = quote + 1;
        while (buf[i] != '"') {
            i += buf[i] == '\\' ? 2 : 1;
        }
        return java.util.Arrays.copyOfRange(buf, quote, i + 1);
    }

    /** Offset in buf of the first byte of the current token, as Jackson reports it. */
    private int sourcePosition() {
        if (pos < 0) {
            return documentOffset;
        }
        if (pos >= tokenCount) {
            return documentOffset + documentLength;
        }
        switch (kind) {
            case START_OBJECT:
            case END_OBJECT:
            case START_ARRAY:
            case END_ARRAY:
                return start;
            case FIELD_NAME:
                return start - 1;
            default:
                return tokens[(pos << 2) + 3];
        }
    }

    // Line counting state of the last getTokenLocation() call, to continue from there.
    private int locationPosition = -1;
    private int locationLine;
    private int locationLineStart;

    @Override
    public XContentLocation getTokenLocation() {
        final int position = sourcePosition();
        int i = documentOffset;
        int line = 1;
        int lineStart = documentOffset;
        if (locationPosition >= 0 && locationPosition <= position) {
            i = locationPosition;
            line = locationLine;
            lineStart = locationLineStart;
        }
        for (; i < position; i++) {
            byte b = buf[i];
            if (b == '\n' || b == '\r') {
                // a CR LF pair ends one line
                if (b == '\r' && i + 1 < position && buf[i + 1] == '\n') {
                    i++;
                }
                line++;
                lineStart = i + 1;
            }
        }
        locationPosition = position;
        locationLine = line;
        locationLineStart = lineStart;
        return new XContentLocation(line, position - lineStart + 1);
    }

    @Override
    public boolean isClosed() {
        return state == null;
    }

    @Override
    public void close() {
        if (state != null) {
            state.release();
            state = null;
        }
    }
}
