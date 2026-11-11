# JAR size comparison

Logwood measured 2026-11-11. Logwood uses the normal Maven build with OpenJDK
27+35, Java 21 bytecode (`--release 21`), and `debug:none`. Avaje uses the published Maven
Central 2.0 artifacts. These are compressed on-disk JAR sizes, including their
normal descriptors and resources. This comparison measures size, not speed.

| Implementation | Provider JARs | Required runtime JARs, excluding SLF4J API |
| --- | ---: | ---: |
| Logwood core + SLF4J module | 26,698 bytes (26.07 KiB) | 30,517 bytes (29.80 KiB) |
| Avaje Simple JSON Logger 2.0 | 49,832 bytes (48.66 KiB) | 180,520 bytes (176.29 KiB) |
| Avaje Simple Logger 2.0 | 55,869 bytes (54.56 KiB) | 285,416 bytes (278.73 KiB) |

Logwood includes both LTSV and JSONL. Its lazy 8 KiB escape-classification bitmap
is generated at runtime and is not stored in the JAR. Its provider total is the
core plus the SLF4J module, even though classpath applications can use the core directly.
Its runtime total includes JSpecify 1.0.0 (3,819 bytes), which Maven brings in
by default; the static JPMS requirement allows it to be omitted at runtime.

Avaje Simple JSON Logger is the smaller provider option. Its required
`avaje-json-core` 3.13 dependency contributes 130,688 bytes. The full Avaje
Simple Logger package wraps that provider and adds configuration and application
logging support. Its provider column includes the wrapper and JSON provider;
its runtime column also includes avaje-config, avaje-applog, avaje-applog-slf4j,
avaje-json-core, and JSpecify.

Both runtime totals exclude each implementation's SLF4J API (Logwood 2.0.20,
Avaje 2.0.18), test/provided dependencies, and optional OpenTelemetry integration.
Logwood's example, benchmark, and optional scoped module are excluded.
Jackson core is used only by Logwood tests and is absent from its runtime total.

Published dependency declarations:
[Avaje JSON provider POM](https://repo.maven.apache.org/maven2/io/avaje/avaje-simple-json-logger/2.0/avaje-simple-json-logger-2.0.pom),
[Avaje full package POM](https://repo.maven.apache.org/maven2/io/avaje/avaje-simple-logger/2.0/avaje-simple-logger-2.0.pom).

Exact per-artifact sizes, coordinates, class counts, and SHA-256 hashes are in
[size-comparison.json](size-comparison.json). To reproduce Logwood's artifacts:

```sh
./mvnw -pl logwood,logwood-slf4j -am clean package
```

Avaje dependency sets were resolved with Maven Dependency Plugin 3.8.1
`copy-dependencies -DincludeScope=runtime` against isolated consumer POMs
containing only the named Avaje 2.0 dependency.
