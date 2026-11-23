# Native JSON tokenizer

`opensearch_simdjson.cpp` turns a JSON document into the token list that
`SimdJsonXContentParser` reads. It uses simdjson
(https://github.com/simdjson/simdjson, Apache License 2.0, see
`SIMDJSON-LICENSE.txt`) for structural indexing and UTF-8 validation.

The build downloads the single-header release of simdjson (version and
SHA-256 checksums in `../../../build.gradle`) and compiles the library for
the host platform with the C++ compiler named by the `CXX` environment
variable, or `c++`. Without a compiler, or offline without a cached download,
the library is left out and JSON is parsed with Jackson.
