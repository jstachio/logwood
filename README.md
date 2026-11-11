# Logwood

[![Build](https://github.com/jstachio/logwood/actions/workflows/ci.yml/badge.svg)](https://github.com/jstachio/logwood/actions/workflows/ci.yml)
[![Docs + Javadoc](https://jstach.io/logwood/badge.svg)](https://jstach.io/logwood/)
[![Maven Central](https://img.shields.io/maven-central/v/io.jstach.logwood/logwood)](https://central.sonatype.com/artifact/io.jstach.logwood/logwood)

A modern **slf4j-simple replacement** for SLF4J 2 and `System.Logger`.
Logwood writes secure, structured logs to stdout with a small runtime and one
application SPI. Start here, then move to [Rainbow Gum](https://jstach.io/rainbowgum/)
when you need more outputs, asynchronous publishing, or framework integration.
The `logging.level` and `logging.level.<logger>` properties carry over. Ordinary
application keys are bare by default; metadata collisions use `keyValues.`.

The core runs on **Java 21+**. Optional scoped key values use **Java 25+** and
Rainbow Gum 0.17.2's ScopedKeyValues API. System.Logger works without SLF4J.
The library is [Zero-Clause BSD](LICENSE).

Named after [logwood (*Haematoxylum campechianum*)](https://powo.science.kew.org/taxon/urn:lsid:ipni.org:names:316401-2/general-information),
a shrub or small tree native from southern Mexico to Honduras.

## Why LTSV?

LTSV is the default because it is deterministically parseable with minimal Unix
tooling and remains readable to humans. Records contain tab-separated
`label:value` fields; split each field at its first colon. Data cannot introduce
raw tabs or newlines. No `jq` is required. Use `tabs -2` for compact terminal display:

```sh
tabs -2
java -jar example/target/logwood-example.jar
```

```text
time:2026-11-11T12:00:00.000Z	level:INFO	thread:main	threadId:1	logger:app	message:Hello world
```

Select JSON Lines with `-Dlogging.format=jsonl`. Both formats escape controls,
terminal sequences, bidi/format characters, and malformed UTF-16, with fixed
work and output limits. See [the security model](SECURITY.md).

Select application key-value output with `logging.keyvalues.mode`:

| Value | Output |
| --- | --- |
| `none` / `false` | Omit context and fluent fields; skip backend collection and rendering. |
| `flat` / `true` | Default: bare keys, with `keyValues.` protecting collisions. |
| `prefixed` | Every application key receives `keyValues.`. |

Names are case-insensitive. For example, flat mode writes `requestId:42` and
`keyValues.level:custom`; prefixed mode writes `keyValues.requestId:42`.
Both formats use dotted names and preserve collision protection. This setting
does not redact data already present in messages, arguments, or exceptions.

The following shows literal text written inside values:

| Input | LTSV | JSONL |
| --- | --- | --- |
| Double quote | `"` | `\"` |
| Backslash | `\\` | `\\` |
| Newline | `\n` | `\n` |
| ESC | `\u001b` | `\u001b` |
| C1 CSI | `\u009b` | `\u009b` |
| Bidi override | `\u202e` | `\u202e` |

## Install and use

The first planned release is **1.0.0**. Until it is published, build and install
this checkout using **JDK 27 or newer**:

```sh
bin/reformat.sh
./mvnw clean install
```

For SLF4J and System.Logger together:

```xml
<dependency>
  <groupId>io.jstach.logwood</groupId>
  <artifactId>logwood-slf4j</artifactId>
  <version>1.1.0-SNAPSHOT</version>
</dependency>
```

For System.Logger alone, use `io.jstach.logwood:logwood` at the same version;
SLF4J can be absent entirely. Use only one SLF4J provider.

```java
LoggerFactory.getLogger("app").info("Hello {}", "world");
System.getLogger("app").log(System.Logger.Level.INFO, "Hello {0}", "world");
```

The [runnable example](example/README.md) uses a normal manifest `Class-Path`
into the local Maven repository. It and the benchmark module are excluded from
releases. The optional [scoped-values module](logwood-scopedkeyvalues/README.md)
adds scoped context without a separate LogwoodConfig.

## Documentation and development

The [user guide and aggregate Javadoc](https://jstach.io/logwood/) cover
configuration, the SPI, MDC, scoped values, exact escaping rules, and migration
to Rainbow Gum. Its source is [doc/overview.md](doc/overview.md).

```sh
bin/doc.sh       # target/reports/apidocs/index.html, with version badge
bin/analyze.sh   # JSpecify / NullAway checks
bin/test-tools.sh
```

Maven, javac, and javadoc use JDK 27+ even though the core targets Java 21.
CI also runs the core on Java 21 and all modules on Java 25 and 27.
Build-tool sources and documentation assets stay outside the runtime JARs.

See [bin/README.md](bin/README.md) for the scripts and [release.md](release.md)
for the version helper, reproducible builds, and the 1.0.0 release process.
[Artifact sizes](bench/SIZE.md) and [benchmark reports](bench/README.md) include
measurement details and limits.
