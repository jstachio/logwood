# Escape classification lookup

Historical measurements from before the Balsa-to-Logwood rename. Names, artifact
hashes, and commands below describe the measured revisions.

Measured 2026-11-10 on a shared Linux aarch64 host with Amazon Corretto 21.0.13,
Java 21 bytecode, and JMH 1.37. Baseline core: `8e43170`. Both versions use the
same benchmark source, including the new Japanese message case. The benchmark
JARs were checked against each measured core JAR's class bytes.

The encoder now caches BMP escape classification in a lazy 8 KiB bitmap. It
keeps the existing ASCII path, surrogate handling, escaping policy, and limits.
The core JAR grows from 22,050 to 22,723 bytes: **673 additional bytes**. The
bitmap is generated at runtime and adds no per-event allocation after initialization.

These calls include timestamp formatting, defensive encoding, UTF-8 conversion,
and synchronization through a PrintStream with a discard sink. They measure
complete logging calls rather than character classification alone. The Japanese
message is the same literal in `LoggingBench.balsaUnicode()` and
`LoggingBench.simpleUnicode()`. This comparison runs only the Balsa cases.

| Format and message | Before, ns/op | With lookup table, ns/op |
| --- | ---: | ---: |
| LTSV, ASCII | 338.485 ± 20.276 | 340.261 ± 7.736 |
| LTSV, Japanese | 774.553 ± 17.658 | 739.283 ± 52.070 |
| JSONL, ASCII | 384.575 ± 14.670 | 361.742 ± 30.606 |
| JSONL, Japanese | 876.108 ± 25.572 | 777.105 ± 49.455 |

The Japanese means are about 4.6% lower for LTSV and 11.3% lower for JSONL in
these runs. LTSV error ranges overlap; ASCII differences are within noise.
These are short local measurements, not an application throughput guarantee.
They do not measure startup or first-use table initialization.

Each case uses one worker, two forks, three 500 ms warmups, five 500 ms
measurements per fork, a 128 MiB heap, and the GC profiler. Before/after scores,
per-iteration samples, allocation measurements, JVM arguments, and artifact
hashes are in [escape-table-results.json](escape-table-results.json).

To reproduce a run on either revision, build the benchmark with the same Unicode
methods, then select the format in the forked JVM:

```sh
./mvnw -pl bench -am clean package -DskipTests
java -jar bench/target/benchmarks.jar 'LoggingBench.balsa(Plain|Unicode)' \
  -wi 3 -i 5 -w 500ms -r 500ms -f 2 -t 1 -prof gc \
  -jvmArgsAppend '-Xms128m -Xmx128m -Dlogging.format=ltsv' \
  -rf json -rff /tmp/balsa-escape-ltsv.json
```

Repeat with `-Dlogging.format=jsonl` and a separate result file for JSONL.
Current artifact totals are in [../SIZE.md](../SIZE.md).
