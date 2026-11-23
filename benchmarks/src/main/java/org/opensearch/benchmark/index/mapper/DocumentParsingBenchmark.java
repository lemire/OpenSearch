/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.benchmark.index.mapper;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.opensearch.Version;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.common.compress.CompressedXContent;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.LoggingDeprecationHandler;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.json.JsonXContent;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.XContentParser;
import org.opensearch.index.IndexSettings;
import org.opensearch.index.analysis.AnalyzerScope;
import org.opensearch.index.analysis.IndexAnalyzers;
import org.opensearch.index.analysis.NamedAnalyzer;
import org.opensearch.index.mapper.DocumentMapper;
import org.opensearch.index.mapper.MapperExtrasModulePlugin;
import org.opensearch.index.mapper.MapperService;
import org.opensearch.index.mapper.SourceToParse;
import org.opensearch.index.similarity.SimilarityService;
import org.opensearch.indices.IndicesModule;
import org.opensearch.script.ScriptModule;
import org.opensearch.script.ScriptService;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Parses the documents of an OpenSearch Benchmark workload with the workload's mapping, as a shard does
 * when it indexes them. The directory given by {@code dataDir} holds {@code <workload>.json}, one
 * document per line (for example the first lines of the workload corpus), and
 * {@code <workload>.mapping.json}, the {@code mappings} of the workload index.
 * <p>
 * Compare the parsers by running the benchmark twice:
 * <pre>
 * java -jar benchmarks.jar DocumentParsingBenchmark -p dataDir=... -jvmArgsAppend -Dopensearch.xcontent.json.simdjson=false
 * java -jar benchmarks.jar DocumentParsingBenchmark -p dataDir=... -jvmArgsAppend -Dopensearch.xcontent.json.simdjson=true
 * </pre>
 */
@Fork(value = 1, jvmArgsAppend = { "--enable-native-access=ALL-UNNAMED", "-Xms4g", "-Xmx4g" })
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Thread)
@SuppressWarnings("unused") // invoked by benchmarking framework
public class DocumentParsingBenchmark {

    @Param({ "nyc_taxis", "http_logs", "geonames", "noaa", "nested", "so", "pmc", "eventdata", "big5" })
    public String workload;

    @Param({ "" })
    public String dataDir;

    private DocumentMapper mapper;
    private BytesReference[] documents;

    @Setup
    public void setup() throws IOException {
        Path dir = Path.of(dataDir);
        List<BytesReference> docs = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve(workload + ".json"), StandardCharsets.UTF_8)) {
            if (line.isBlank() == false) {
                docs.add(new BytesArray(line.getBytes(StandardCharsets.UTF_8)));
            }
        }
        documents = docs.toArray(new BytesReference[0]);
        String mapping = Files.readString(dir.resolve(workload + ".mapping.json"), StandardCharsets.UTF_8);
        mapper = createMapperService(mapping).documentMapper();
        System.out.println("\n" + workload + ": " + documents.length + " documents, simdjson " + JsonXContent.simdJsonStatus());
    }

    private static MapperService createMapperService(String mapping) throws IOException {
        Settings settings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .build();
        IndexMetadata metadata = IndexMetadata.builder("index").settings(settings).build();
        IndexSettings indexSettings = new IndexSettings(metadata, settings);
        IndexAnalyzers analyzers = new IndexAnalyzers(
            Collections.singletonMap("default", new NamedAnalyzer("default", AnalyzerScope.INDEX, new StandardAnalyzer())),
            Collections.emptyMap(),
            Collections.emptyMap()
        );
        ScriptModule scriptModule = new ScriptModule(Settings.EMPTY, Collections.emptyList());
        ScriptService scriptService = new ScriptService(Settings.EMPTY, scriptModule.engines, scriptModule.contexts);
        MapperService mapperService = new MapperService(
            indexSettings,
            analyzers,
            NamedXContentRegistry.EMPTY,
            new SimilarityService(indexSettings, scriptService, Collections.emptyMap()),
            new IndicesModule(List.of(new MapperExtrasModulePlugin())).getMapperRegistry(),
            () -> { throw new UnsupportedOperationException(); },
            () -> true,
            scriptService
        );
        mapperService.merge(
            MapperService.SINGLE_MAPPING_NAME,
            new CompressedXContent("{\"" + MapperService.SINGLE_MAPPING_NAME + "\":" + mapping + "}"),
            MapperService.MergeReason.MAPPING_UPDATE
        );
        return mapperService;
    }

    /** What a shard does with each document of a bulk request. */
    @Benchmark
    public void parseDocuments(Blackhole bh) {
        for (int i = 0; i < documents.length; i++) {
            bh.consume(mapper.parse(new SourceToParse("index", Integer.toString(i), documents[i], MediaTypeRegistry.JSON)));
        }
    }

    /** What an ingest pipeline does with each document. */
    @Benchmark
    public void convertToMap(Blackhole bh) {
        for (BytesReference document : documents) {
            bh.consume(XContentHelper.convertToMap(document, false, MediaTypeRegistry.JSON));
        }
    }

    /** Only the parser: every token, field name and value. */
    @Benchmark
    public void readTokens(Blackhole bh) throws IOException {
        for (BytesReference document : documents) {
            try (
                XContentParser parser = XContentHelper.createParser(
                    NamedXContentRegistry.EMPTY,
                    LoggingDeprecationHandler.INSTANCE,
                    document,
                    MediaTypeRegistry.JSON
                )
            ) {
                XContentParser.Token token;
                while ((token = parser.nextToken()) != null) {
                    switch (token) {
                        case FIELD_NAME -> bh.consume(parser.currentName());
                        case VALUE_STRING -> bh.consume(parser.text());
                        case VALUE_NUMBER -> bh.consume(parser.numberValue());
                        case VALUE_BOOLEAN -> bh.consume(parser.booleanValue());
                        default -> bh.consume(token);
                    }
                }
            }
        }
    }
}
