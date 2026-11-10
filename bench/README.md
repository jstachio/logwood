# Benchmark

This Maven module is separate from the runtime artifact and is built by the root reactor. It uses JMH 1.37 and
slf4j-simple 2.0.20. Both providers are instantiated directly to avoid measuring
facade provider selection. Each receives the same PrintStream backed by
OutputStream.nullOutputStream(), so results measure formatting, escaping,
allocation, encoding, and synchronization rather than a physical terminal.
Balsa writes fixed UTF-8 bytes; slf4j-simple uses its UTF-8 PrintStream.
Balsa emits a UTC timestamp and labeled LTSV fields and performs defensive escaping; slf4j-simple's
standard output is unescaped. The escaping case deliberately measures that
security difference, not equivalent output bytes.

```sh
./mvnw -pl bench -am clean package
mkdir -p /tmp/balsa-jmh
java -Djava.io.tmpdir=/tmp/balsa-jmh -jar bench/target/benchmarks.jar \
  -jvmArgs '-Xms128m -Xmx128m -Djava.io.tmpdir=/tmp/balsa-jmh' \
  -prof gc -rf json -rff bench/results.json
```

The annotations use two forks, three one-second warmups and five one-second
measurements per benchmark. Disabled calls are expected to allocate nothing.
Use the same JDK, machine, sink, and run settings for comparisons; inspect
confidence intervals and allocation rates. File/terminal throughput and
application performance need separate measurements.

See [RESULTS.md](RESULTS.md) for the measured artifact and run details.
The [escape lookup comparison](ESCAPING.md) measures the current encoder's lazy
classification table with ASCII and Japanese messages in both LTSV and JSONL.
