/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.common.xcontent.json;

import org.opensearch.common.SuppressForbidden;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

/**
 * Binding to the native tokenizer ({@code libopensearch_simdjson}), which runs simdjson's stage 1
 * and a validating pass over a JSON document.
 * <p>
 * The binding uses the foreign function and memory API (final in Java 22) with critical downcalls,
 * which read the Java arrays in place. This library is compiled for Java 21, so the API is reached
 * through method handles that are looked up reflectively once: they are constants, so the JIT
 * compiles the calls as if the API were referenced directly. On Java 21 the binding is unavailable.
 * <p>
 * The library is looked up in {@code java.library.path}, then extracted from the class path to
 * {@code java.io.tmpdir}, then read from the path in the {@code opensearch.xcontent.json.simdjson.library}
 * system property.
 */
final class SimdJsonNative {

    static final int OUTPUT_TOO_SMALL = -1000;
    static final int FALLBACK = -2000;

    private static final String LIBRARY_NAME = "opensearch_simdjson";
    static final String LIBRARY_PATH_PROPERTY = "opensearch.xcontent.json.simdjson.library";

    private static final MethodHandle CREATE;
    private static final MethodHandle DESTROY;
    private static final MethodHandle TOKENIZE;
    private static final MethodHandle SET_CAPACITY;
    private static final MethodHandle SEGMENT_ADDRESS;
    private static final MethodHandle OF_INTS;
    private static final MethodHandle OF_BYTES;
    private static final String IMPLEMENTATION;
    private static final String UNAVAILABLE_REASON;

    static {
        MethodHandle create = null, destroy = null, tokenize = null, setCapacity = null, segmentAddress = null;
        MethodHandle ofInts = null, ofBytes = null;
        String implementation = null;
        String reason = null;
        try {
            if (Runtime.version().feature() < 22) {
                throw new UnsupportedOperationException("requires Java 22 or later, running " + Runtime.version());
            }
            Ffm ffm = new Ffm(findLibrary());
            create = ffm.downcall("opensearch_simdjson_create", false, ffm.address, ffm.javaInt);
            destroy = ffm.downcall("opensearch_simdjson_destroy", false, null, ffm.address);
            setCapacity = ffm.downcall("opensearch_simdjson_set_capacity", false, ffm.javaInt, ffm.address, ffm.javaInt);
            MethodHandle name = ffm.downcall("opensearch_simdjson_implementation", false, ffm.address);
            MethodHandle raw = ffm.downcall(
                "opensearch_simdjson_tokenize",
                true,
                ffm.javaInt,
                ffm.address,
                ffm.address,
                ffm.javaInt,
                ffm.javaInt,
                ffm.address,
                ffm.javaInt,
                ffm.address,
                ffm.javaInt,
                ffm.javaInt,
                ffm.javaInt
            );
            // (Object ctx, byte[] buf, int offset, int length, int[] out, int outLength, byte[] scratch, int, int, int)int
            raw = raw.asType(
                MethodType.methodType(
                    int.class,
                    Object.class,
                    Object.class,
                    int.class,
                    int.class,
                    Object.class,
                    int.class,
                    Object.class,
                    int.class,
                    int.class,
                    int.class
                )
            );
            // the document is wrapped on each call; the token and scratch arrays are wrapped once by segment()
            tokenize = MethodHandles.filterArguments(raw, 1, ffm.ofBytes);
            ofInts = ffm.ofInts;
            ofBytes = ffm.ofBytes;
            segmentAddress = ffm.segmentAddress;
            implementation = ffm.cString(name.invoke());
        } catch (Throwable t) {
            create = destroy = tokenize = setCapacity = segmentAddress = null;
            reason = t.toString();
        }
        CREATE = create;
        DESTROY = destroy;
        TOKENIZE = tokenize;
        SET_CAPACITY = setCapacity;
        SEGMENT_ADDRESS = segmentAddress;
        OF_INTS = ofInts;
        OF_BYTES = ofBytes;
        IMPLEMENTATION = implementation;
        UNAVAILABLE_REASON = reason;
    }

    private SimdJsonNative() {}

    static boolean isAvailable() {
        return TOKENIZE != null;
    }

    /** The SIMD kernel simdjson selected for this CPU, or null when the library is unavailable. */
    static String implementation() {
        return IMPLEMENTATION;
    }

    /** Why the library is unavailable, or null. */
    static String unavailableReason() {
        return UNAVAILABLE_REASON;
    }

    /** Returns a native context (a MemorySegment) for documents of up to capacity bytes, or null. */
    static Object create(int capacity) {
        try {
            Object ctx = CREATE.invoke(capacity);
            return isNull(ctx) ? null : ctx;
        } catch (Throwable t) {
            return null;
        }
    }

    static void destroy(Object ctx) {
        try {
            DESTROY.invoke(ctx);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    static boolean setCapacity(Object ctx, int capacity) {
        try {
            return (int) SET_CAPACITY.invoke(ctx, capacity) == 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /** A MemorySegment over the array (an int[] or a byte[]), for {@link #tokenize}. */
    static Object segment(Object array) {
        try {
            return array instanceof int[] ? OF_INTS.invokeExact(array) : OF_BYTES.invokeExact(array);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /**
     * Tokenizes buf[offset, offset + length) into out (4 ints per token) and scratch (decoded strings).
     * Returns the number of tokens, {@link #OUTPUT_TOO_SMALL}, {@link #FALLBACK}, or another negative
     * value for a document that is not valid JSON.
     */
    static int tokenize(
        Object ctx,
        byte[] buf,
        int offset,
        int length,
        Object out,
        int outLength,
        Object scratch,
        int maxDepth,
        int maxNameLength,
        int maxStringLength
    ) {
        try {
            return (int) TOKENIZE.invokeExact(
                ctx,
                (Object) buf,
                offset,
                length,
                out,
                outLength,
                scratch,
                maxDepth,
                maxNameLength,
                maxStringLength
            );
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    private static boolean isNull(Object segment) throws Throwable {
        return (long) SEGMENT_ADDRESS.invokeExact(segment) == 0L;
    }

    static String platformDirectory() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            arch = "x86_64";
        } else if (arch.equals("arm64") || arch.equals("aarch64")) {
            arch = "aarch64";
        }
        if (os.startsWith("linux")) {
            os = "linux";
        } else if (os.startsWith("mac")) {
            os = "darwin";
        } else if (os.startsWith("windows")) {
            os = "windows";
        }
        return os + "-" + arch;
    }

    static String libraryFileName() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            return LIBRARY_NAME + ".dll";
        }
        return "lib" + LIBRARY_NAME + (os.startsWith("mac") ? ".dylib" : ".so");
    }

    @SuppressForbidden(reason = "locates the native library in java.library.path and java.io.tmpdir")
    private static Path findLibrary() throws IOException {
        String file = libraryFileName();
        for (String dir : System.getProperty("java.library.path", "").split(java.io.File.pathSeparator)) {
            if (dir.isEmpty() == false) {
                Path candidate = Path.of(dir, file);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        String resource = "native/" + platformDirectory() + "/" + file;
        try (InputStream in = SimdJsonNative.class.getResourceAsStream(resource)) {
            if (in != null) {
                Path dir = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "opensearch-simdjson-");
                Path lib = dir.resolve(file);
                Files.copy(in, lib, StandardCopyOption.REPLACE_EXISTING);
                lib.toFile().deleteOnExit();
                dir.toFile().deleteOnExit();
                return lib;
            }
        }
        String path = System.getProperty(LIBRARY_PATH_PROPERTY);
        if (path != null) {
            return Path.of(path);
        }
        throw new IOException("no " + file + " for " + platformDirectory());
    }

    /** The parts of java.lang.foreign this class uses, looked up reflectively. */
    private static final class Ffm {
        final Object address;
        final Object javaInt;
        final MethodHandle ofBytes;
        final MethodHandle ofInts;
        final MethodHandle segmentAddress;
        private final Class<?> memorySegment;
        private final Class<?> linkerClass;
        private final Class<?> symbolLookup;
        private final Class<?> memoryLayout;
        private final Class<?> functionDescriptor;
        private final Class<?> option;
        private final Object linker;
        private final Object lookup;

        Ffm(Path library) throws ReflectiveOperationException {
            memorySegment = Class.forName("java.lang.foreign.MemorySegment");
            memoryLayout = Class.forName("java.lang.foreign.MemoryLayout");
            functionDescriptor = Class.forName("java.lang.foreign.FunctionDescriptor");
            option = Class.forName("java.lang.foreign.Linker$Option");
            linkerClass = Class.forName("java.lang.foreign.Linker");
            Class<?> arena = Class.forName("java.lang.foreign.Arena");
            symbolLookup = Class.forName("java.lang.foreign.SymbolLookup");
            Class<?> valueLayout = Class.forName("java.lang.foreign.ValueLayout");
            linker = linkerClass.getMethod("nativeLinker").invoke(null);
            Object global = arena.getMethod("global").invoke(null);
            lookup = symbolLookup.getMethod("libraryLookup", Path.class, arena).invoke(null, library, global);
            address = valueLayout.getField("ADDRESS").get(null);
            javaInt = valueLayout.getField("JAVA_INT").get(null);
            MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
            ofBytes = publicLookup.findStatic(memorySegment, "ofArray", MethodType.methodType(memorySegment, byte[].class))
                .asType(MethodType.methodType(Object.class, Object.class));
            ofInts = publicLookup.findStatic(memorySegment, "ofArray", MethodType.methodType(memorySegment, int[].class))
                .asType(MethodType.methodType(Object.class, Object.class));
            segmentAddress = publicLookup.findVirtual(memorySegment, "address", MethodType.methodType(long.class))
                .asType(MethodType.methodType(long.class, Object.class));
        }

        /** A downcall handle; result is null for void, critical allows heap arrays as arguments. */
        MethodHandle downcall(String symbol, boolean critical, Object result, Object... arguments) throws ReflectiveOperationException {
            Optional<?> found = (Optional<?>) symbolLookup.getMethod("find", String.class).invoke(lookup, symbol);
            Object address = found.orElseThrow(() -> new IllegalStateException("missing symbol " + symbol));
            Object layouts = Array.newInstance(memoryLayout, arguments.length);
            for (int i = 0; i < arguments.length; i++) {
                Array.set(layouts, i, arguments[i]);
            }
            Object descriptor = result == null
                ? functionDescriptor.getMethod("ofVoid", layouts.getClass()).invoke(null, layouts)
                : functionDescriptor.getMethod("of", memoryLayout, layouts.getClass()).invoke(null, result, layouts);
            Object options = Array.newInstance(option, critical ? 1 : 0);
            if (critical) {
                Array.set(options, 0, option.getMethod("critical", boolean.class).invoke(null, true));
            }
            Method downcallHandle = linkerClass.getMethod("downcallHandle", memorySegment, functionDescriptor, options.getClass());
            return (MethodHandle) downcallHandle.invoke(linker, address, descriptor, options);
        }

        String cString(Object segment) throws ReflectiveOperationException {
            Object wide = memorySegment.getMethod("reinterpret", long.class).invoke(segment, 256L);
            return (String) memorySegment.getMethod("getString", long.class).invoke(wide, 0L);
        }
    }
}
