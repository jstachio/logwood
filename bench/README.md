# Benchmark

This Maven module is separate from the runtime artifact and is built by the root reactor. It uses JMH 1.37 and
slf4j-simple 2.0.20. Both providers are instantiated directly to avoid measuring
facade provider selection. Each receives the same PrintStream backed by
OutputStream.nullOutputStream(), so results measure formatting, escaping,
allocation, encoding, and synchronization rather than a physical terminal.
Logwood writes fixed UTF-8 bytes; slf4j-simple uses its UTF-8 PrintStream.
Logwood emits a UTC timestamp and labeled LTSV fields and performs defensive escaping; slf4j-simple's
standard output is unescaped. The escaping case deliberately measures that
security difference, not equivalent output bytes.

```sh
./mvnw -pl bench -am clean package
mkdir -p /tmp/logwood-jmh
java -Djava.io.tmpdir=/tmp/logwood-jmh -jar bench/target/benchmarks.jar \
  -jvmArgs '-Xms128m -Xmx128m -Djava.io.tmpdir=/tmp/logwood-jmh' \
  -prof gc -rf json -rff bench/results.json
```

The annotations use two forks, three one-second warmups and five one-second
measurements per benchmark. Disabled calls are expected to allocate nothing.
Use the same JDK, machine, sink, and run settings for comparisons; inspect
confidence intervals and allocation rates. File/terminal throughput and
application performance need separate measurements.

The [historical measurements](history/RESULTS.md) and
[escape lookup comparison](history/ESCAPING.md) were recorded before the project
was renamed from Balsa to Logwood. Their raw records retain the original artifact,
class, and method names. Current artifact sizes are in [SIZE.md](SIZE.md).
