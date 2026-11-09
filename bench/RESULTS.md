# Measurements

Historical baseline from commit `994a00661fbdf860b8663a8f4143674bc93d6234`.
This predates the Java 21 baseline, context callback, and marker removal.
These numbers do not measure the current implementation.

Measured on a shared Linux aarch64 host with 20 logical CPUs. Runtime: OpenJDK
27+35-2325; library and benchmark compiled with Amazon Corretto 17.0.21.0.11.
JMH 1.37, one worker, 128 MiB heap, two forks, three one-second warmups, and
five one-second measurements per fork. The GC profiler measured allocation.
Raw data: [results.json](results.json). Artifact hashes and class counts:
[metadata.json](metadata.json).

## Artifact size

All rows exclude the common slf4j-api dependency. Published SLF4J artifacts are
version 2.0.20. Balsa is the normal Java 17 Maven build, including its license,
module descriptor and both service registrations.

| Backend | JAR bytes | Implementation classes | Uncompressed class bytes |
| --- | ---: | ---: | ---: |
| Balsa (both APIs) | 15,701 | 6 | 24,558 |
| slf4j-simple (SLF4J only) | 15,705 | 6 | 21,690 |
| SLF4J System.Logger bridge | 9,649 | 4 | 9,184 |
| simple + bridge (both APIs) | 25,354 | 10 | 30,874 |

Balsa is 38.1% smaller than simple plus the System.Logger bridge. It is
essentially the same compressed JAR size as slf4j-simple alone. Balsa has more
uncompressed implementation bytecode than simple alone; it includes the JDK
backend and defensive structured encoding. Compiler debug tables and build
environment manifest entries are omitted, and two clean Java 17 builds were
byte-for-byte identical.

## SLF4J call cost

Time is mean ns/op with the JMH error estimate. Lower is better. Allocation is
bytes/op. These are microbenchmarks with a discard sink, not terminal or file
I/O throughput, and results do not imply a universal speedup.

| Case | Balsa ns/op | simple ns/op | Balsa B/op | simple B/op |
| --- | ---: | ---: | ---: | ---: |
| Disabled DEBUG | 0.561 ± 0.016 | 0.467 ± 0.030 | 0.0 | 0.0 |
| Plain INFO | 89.676 ± 3.218 | 144.774 ± 8.962 | 456.0 | 408.0 |
| One argument INFO | 107.523 ± 4.251 | 189.526 ± 9.624 | 512.0 | 560.0 |
| Controls + argument INFO | 132.076 ± 4.878 | 169.266 ± 2.301 | 528.0 | 568.0 |

The control-character case is not equivalent output: Balsa escapes controls
and produces one record, while simple emits the raw control characters. The
providers have different output layouts. Both include the same ordinary
metadata (level, thread, logger, message), and Balsa adds LTSV labels. This run
does not measure System.Logger call speed, startup, physical stdout I/O,
MDC-enabled workloads, exceptions, or heavily contended logging. Both disabled
paths allocate nothing after initialization; sub-nanosecond timing differences
are too small to treat as an application performance guarantee.

## Validation

* 27 tests pass on Java 17 and Java 27 with compiler warnings treated as errors.
* Fresh-JVM classpath, module-path, and named configuration-module discovery pass.
* Javadoc generation and the pinned CI workflow syntax checks pass.
* The benchmark contains the exact verified runtime class bytes.
* Runtime module dependencies are only java.base and org.slf4j.
