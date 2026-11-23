/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.common.xcontent.json;

import org.opensearch.common.xcontent.XContentConstraints;

import java.lang.ref.Cleaner;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The buffers a {@link SimdJsonXContentParser} reads its tokens from: the native context, the token
 * array, the scratch buffer with the decoded strings, and a cache of field names. Each thread has one,
 * lent to one parser at a time: a parser created while another one on the same thread is open (for
 * example a parser over a sub-document) uses Jackson.
 */
final class SimdJsonParserState {

    private static final Cleaner CLEANER = Cleaner.create();

    /** Documents up to this size do not grow the buffers beyond what is kept between documents. */
    private static final int RETAINED_DOCUMENT_BYTES = 1 << 20;
    private static final int INITIAL_CAPACITY = 1 << 16;

    private static final ThreadLocal<SimdJsonParserState> STATES = new ThreadLocal<>();

    private final Object context;
    private int contextCapacity;

    int[] tokens = new int[4 * 1024];
    byte[] scratch = new byte[INITIAL_CAPACITY];
    final NameCache names = new NameCache();
    String[] contextNames = new String[32];
    boolean[] contextArrays = new boolean[32];

    private volatile boolean inUse;

    private SimdJsonParserState(Object context) {
        this.context = context;
        this.contextCapacity = INITIAL_CAPACITY;
    }

    /** Returns this thread's state if it is free, after marking it in use, or null. */
    static SimdJsonParserState acquire() {
        SimdJsonParserState state = STATES.get();
        if (state == null) {
            Object context = SimdJsonNative.create(INITIAL_CAPACITY);
            if (context == null) {
                return null;
            }
            state = new SimdJsonParserState(context);
            CLEANER.register(state, () -> SimdJsonNative.destroy(context));
            STATES.set(state);
        }
        if (state.inUse) {
            return null;
        }
        state.inUse = true;
        return state;
    }

    /**
     * Tokenizes the document. Returns the number of tokens, or a value of at most zero when Jackson must
     * parse the document.
     */
    int tokenize(byte[] buf, int offset, int length) {
        if (scratch.length < length) {
            scratch = new byte[Math.max(length, scratch.length * 2)];
        }
        if (tokens.length < length) {
            // Room for one token every 4 bytes; denser documents retry below.
            tokens = new int[Math.max(length, tokens.length * 2)];
        }
        if (length > contextCapacity) {
            contextCapacity = length;
        }
        while (true) {
            int n = SimdJsonNative.tokenize(
                context,
                buf,
                offset,
                length,
                tokens,
                scratch,
                XContentConstraints.DEFAULT_MAX_DEPTH,
                XContentConstraints.DEFAULT_MAX_NAME_LEN,
                XContentConstraints.DEFAULT_MAX_STRING_LEN
            );
            if (n != SimdJsonNative.OUTPUT_TOO_SMALL) {
                return n;
            }
            tokens = new int[Math.max(tokens.length * 2, 4 * (length + 1))];
        }
    }

    void release() {
        if (contextCapacity > RETAINED_DOCUMENT_BYTES) {
            if (SimdJsonNative.setCapacity(context, INITIAL_CAPACITY)) {
                contextCapacity = INITIAL_CAPACITY;
            }
        }
        if (tokens.length > RETAINED_DOCUMENT_BYTES) {
            tokens = new int[4 * 1024];
        }
        if (scratch.length > RETAINED_DOCUMENT_BYTES) {
            scratch = new byte[INITIAL_CAPACITY];
        }
        inUse = false;
    }

    /** Field names by their UTF-8 bytes, so that a document mentioning a field reuses its String. */
    static final class NameCache {
        private static final int SIZE = 4096;
        private static final int MASK = SIZE - 1;
        private static final int MAX_LENGTH = 256;
        private final byte[][] keys = new byte[SIZE][];
        private final String[] values = new String[SIZE];
        private final int[] hashes = new int[SIZE];
        private int count;

        /** The name buf[start, start + length), whose hash is hash. */
        String lookup(byte[] buf, int start, int length, int hash) {
            if (length > MAX_LENGTH) {
                return new String(buf, start, length, StandardCharsets.UTF_8);
            }
            int slot = hash & MASK;
            for (int probe = 0; probe < 8; probe++) {
                byte[] key = keys[slot];
                if (key == null) {
                    String value = new String(buf, start, length, StandardCharsets.UTF_8);
                    if (count < SIZE / 2) {
                        keys[slot] = Arrays.copyOfRange(buf, start, start + length);
                        values[slot] = value;
                        hashes[slot] = hash;
                        count++;
                    }
                    return value;
                }
                if (hashes[slot] == hash && key.length == length && Arrays.equals(key, 0, length, buf, start, start + length)) {
                    return values[slot];
                }
                slot = (slot + 1) & MASK;
            }
            return new String(buf, start, length, StandardCharsets.UTF_8);
        }

        /** Lookup for a name decoded from escapes, which the native side does not hash. */
        String lookup(byte[] buf, int start, int length) {
            int hash = 1;
            for (int i = start, end = start + length; i < end; i++) {
                hash = 31 * hash + buf[i];
            }
            return lookup(buf, start, length, hash ^ (hash >>> 16));
        }
    }
}
