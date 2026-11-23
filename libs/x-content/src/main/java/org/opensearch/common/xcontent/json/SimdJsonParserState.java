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

    int[] tokens;
    byte[] scratch;
    // MemorySegments over tokens and scratch for the native calls
    private Object tokensSegment;
    private Object scratchSegment;
    /** The field names the native side interned, by id. */
    private String[] names = new String[256];
    String[] contextNames = new String[32];
    boolean[] contextArrays = new boolean[32];

    /**
     * Whether a parser holds this state. Only the owning thread sets it; a parser closed on another thread may leave
     * it set as far as the owning thread sees, which then uses Jackson, so it needs no memory barrier.
     */
    private boolean inUse;

    private SimdJsonParserState(Object context) {
        this.context = context;
        this.contextCapacity = INITIAL_CAPACITY;
        setTokens(new int[4 * 1024]);
        setScratch(new byte[INITIAL_CAPACITY]);
    }

    private void setTokens(int[] tokens) {
        this.tokens = tokens;
        this.tokensSegment = SimdJsonNative.segment(tokens);
    }

    private void setScratch(byte[] scratch) {
        this.scratch = scratch;
        this.scratchSegment = SimdJsonNative.segment(scratch);
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
        // decoded strings are never longer than the source, plus 4 bytes per decoded field name
        int scratchLength = 2 * length + 8;
        if (scratch.length < scratchLength) {
            setScratch(new byte[Math.max(scratchLength, scratch.length * 2)]);
        }
        if (tokens.length < length) {
            // Room for one token every 4 bytes; denser documents retry below.
            setTokens(new int[Math.max(length, tokens.length * 2)]);
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
                tokensSegment,
                tokens.length,
                scratchSegment,
                XContentConstraints.DEFAULT_MAX_DEPTH,
                XContentConstraints.DEFAULT_MAX_NAME_LEN,
                XContentConstraints.DEFAULT_MAX_STRING_LEN
            );
            if (n != SimdJsonNative.OUTPUT_TOO_SMALL) {
                return n;
            }
            setTokens(new int[Math.max(tokens.length * 2, 4 * (length + 1))]);
        }
    }

    void release() {
        if (contextCapacity > RETAINED_DOCUMENT_BYTES) {
            if (SimdJsonNative.setCapacity(context, INITIAL_CAPACITY)) {
                contextCapacity = INITIAL_CAPACITY;
            }
        }
        if (tokens.length > RETAINED_DOCUMENT_BYTES) {
            setTokens(new int[4 * 1024]);
        }
        if (scratch.length > RETAINED_DOCUMENT_BYTES) {
            setScratch(new byte[INITIAL_CAPACITY]);
        }
        inUse = false;
    }

    /** The field name bytes[start, start + length), whose id the native side assigned (-1 when it has none). */
    String name(int id, byte[] bytes, int start, int length) {
        if (id < 0) {
            return new String(bytes, start, length, StandardCharsets.UTF_8);
        }
        if (id >= names.length) {
            names = Arrays.copyOf(names, Math.max(id + 1, names.length * 2));
        }
        String name = names[id];
        if (name == null) {
            name = new String(bytes, start, length, StandardCharsets.UTF_8);
            names[id] = name;
        }
        return name;
    }
}
