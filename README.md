# Logwood

A small stdout backend for SLF4J 2 and `System.Logger`, inspired by Rainbow Gum
and slf4j-simple. Java 21+, Zero-Clause BSD. System.Logger needs no runtime dependency;
`slf4j-api` is optional and needed only for SLF4J logging.

Named after [logwood (*Haematoxylum campechianum*)](https://powo.science.kew.org/taxon/urn:lsid:ipni.org:names:316401-2/general-information),
a shrub or small tree native from southern Mexico to Honduras.

One output, LTSV or JSON Lines, one application SPI. No file or network outputs,
configuration files, format patterns, output plugins, asynchronous queues,
shutdown hooks, caller lookup, or packaging-data lookup.

## Build and use

This is a Maven reactor: `logwood/` builds the runtime artifact, `logwood-slf4j/`
adds SLF4J module-path registration, `example/` builds a runnable application,
and `bench/` builds its isolated JMH harness.
The root `logwood-parent` POM shares dependency versions,
Java 21 compiler settings, Spring formatting validation, and build plugins.
The runtime coordinates are `io.jstach.logwood:logwood`.

```sh
./mvnw spring-javaformat:apply
./mvnw clean verify
./mvnw install
```

```xml
<dependency>
  <groupId>io.jstach.logwood</groupId>
  <artifactId>logwood</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The [example](example/README.md) is built and formatted with the reactor but
excluded from Maven deployment. Run it with dependencies from your local Maven
repository:

```sh
./mvnw -pl example -am install
java -jar example/target/logwood-example.jar
```

For just the runtime, use `./mvnw -pl logwood -am clean verify`.

The build validates the same Spring Java Format 0.0.48 style used by Rainbow Gum.
Run `spring-javaformat:apply` after editing Java sources; CI rejects formatting drift.

This snapshot must first be installed locally; it is not a published release.
System.Logger applications need only the dependency above and, on JPMS,
`requires io.jstach.logwood`. SLF4J can be absent from both compilation and runtime.
The core descriptor has `requires static org.slf4j`; its Maven dependency is
optional. Placing SLF4J on the module path does not force it to resolve.

For SLF4J applications, use this dependency instead. It adds the core and SLF4J API
and enables automatic discovery on both the classpath and module path:

```xml
<dependency>
  <groupId>io.jstach.logwood</groupId>
  <artifactId>logwood-slf4j</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

A modular SLF4J application can `requires org.slf4j`; service binding discovers
the provider. It may also explicitly `requires io.jstach.logwood.slf4j`.
The registration module contains one subclass and its module descriptor.
JPMS validates service types during resolution, so the SLF4J `provides` directive
belongs here. The core JAR retains its classpath service descriptor; a classpath
application can also use `logwood` with an explicit `slf4j-api` dependency.
Use Logwood as the only SLF4J provider.

```java
LoggerFactory.getLogger("app").info("Hello {}", "world");
System.getLogger("app").log(System.Logger.Level.INFO, "Hello {0}", "world");
```

Output defaults to UTF-8 LTSV, with a single LF after each record:

```text
time:2026-11-10T03:00:00.123Z	level:INFO	thread:main	threadId:1	logger:app	message:Hello world
```

Select JSON Lines with `-Dlogging.format=jsonl`:

```json
{"time":"2026-11-10T03:00:00.123Z","level":"INFO","thread":"main","threadId":1,"logger":"app","message":"Hello world"}
```

Each record is one compact JSON object followed by LF. `threadId` is a number
and the optional `truncated` flag is a boolean; other values, including context
and rendered objects, are strings. No object serialization or JSON runtime
dependency is added. Both formats share the same bounds and defensive escaping.

The following shows literal text written inside values:

| Input | LTSV | JSONL |
| --- | --- | --- |
| Double quote | `"` | `\"` |
| Backslash | `\\` | `\\` |
| Newline | `\n` | `\n` |
| ESC | `\u001b` | `\u001b` |
| C1 CSI | `\u009b` | `\u009b` |
| Bidi override | `\u202e` | `\u202e` |

The [LTSV encoding reference](doc/ltsv.md) specifies value escapes, percent-encoded
labels, malformed UTF-16 handling, decoding order, and truncation. All input ESC
characters are escaped, including SGR sequences that could conceal log text.

The separators above are actual tabs. `time` is always UTC ISO-8601 with exactly
three fractional digits (milliseconds). `LogwoodConfig.now()` supplies epoch
milliseconds and defaults to `System.currentTimeMillis()`. Enabled events read
it once. Override it to supply another clock; overrides must be thread-safe.
Failure diagnostics use the system clock, so a failing clock callback can still
be reported without invoking it again.

With `logging.journald=true`, records omit `time` and skip the clock callback and
timestamp formatting. Journald supplies the timestamp.
The syslog priority prefix remains outside the JSON object in this transport mode;
the journal's `MESSAGE` contains the JSON object after journald strips the prefix.

`threadId` is the current Java thread's numeric ID, including for virtual threads;
it identifies the virtual thread itself. `thread` contains its name, which can be
empty or shared by multiple threads. Failure diagnostics also include `threadId`.

SLF4J uses `{}` substitution, including its backslash rules, arrays, trailing
throwables, and fluent key/value pairs. Marker arguments are ignored. `System.Logger` uses localized
MessageFormat substitution when an argument index is present. A throwable
supplied as a System.Logger formatting argument stays an argument; the explicit
throwable overload writes the `error` field.

## Configuration

Defaults: INFO, stdout, LTSV, no journald prefix, and no stored MDC (all MDC operations default to no-ops).
Logger level keys match Rainbow Gum, so those settings carry over when upgrading.
The only built-in properties are:

| Property | Meaning |
| --- | --- |
| `logging.level` | Root threshold: ALL, TRACE, DEBUG, INFO, WARN/WARNING, ERROR, OFF. |
| `logging.level.com.example` | Threshold inherited by dotted descendants. |
| `logging.journald` | `true` adds a syslog priority prefix and omits time; default `false`. |
| `logging.format` | `ltsv` (default) or `jsonl`, case-insensitive. |

```sh
java -Dlogging.level=WARN -Dlogging.level.com.example=DEBUG -Dlogging.journald=true ...
```

Levels resolve at first use of each logger and then stay fixed. Journal prefixes
are `<3>` for ERROR, `<4>` for WARN, `<6>` for INFO, and `<7>` for DEBUG/TRACE/ALL.
OFF suppresses application events, including `System.Logger.log(OFF, ...)`.
Failure diagnostics are independent of application thresholds.

### The application SPI

`io.jstach.logwood.spi` is the core module's only exported package. Its sealed
`LogwoodService` interface has two non-sealed nested base classes:

* `LogwoodService.LogwoodConfig` supplies the clock, levels, properties, and optional MDC storage.
  Register zero or one configuration; the default applies when none is present.
* `LogwoodService.KeyValuesProvider` pushes fields through
  `keyValues(BiConsumer<String, String>)`. Register any number of providers.
  `order()` defaults to zero; providers run in ascending order and later values
  override earlier values for the same key. Equal orders retain discovery order,
  so choose distinct orders when override precedence matters.

Logwood makes one ServiceLoader pass over `LogwoodService`, constructs services once,
selects their roles with instanceof, and sorts key-value providers once during
initialization. The loader uses Logwood's class loader, not a per-request context
class loader. Constructors and order methods must not depend on logging.
Bootstrap calls are suppressed until initialization completes; SLF4J itself may
separately buffer bootstrap calls.

For example, application fields can be supplied independently of configuration:

```java
import io.jstach.logwood.spi.LogwoodService.KeyValuesProvider;
import java.util.function.BiConsumer;

public final class MyValues extends KeyValuesProvider {
  @Override public void keyValues(BiConsumer<String, String> consumer) {
    consumer.accept("service", "example");
  }
}
```

Custom settings use the other base class:

```java
import io.jstach.logwood.spi.LogwoodService.LogwoodConfig;

public final class MyConfig extends LogwoodConfig {
  @Override public System.Logger.Level levelOrNull(String name) {
    return name.isEmpty() ? System.Logger.Level.WARNING : super.levelOrNull(name);
  }
}
```

On the classpath, list all services in
`META-INF/services/io.jstach.logwood.spi.LogwoodService`:

```text
my.app.MyConfig
my.app.MyValues
```

On the module path:

```java
module my.app {
  requires io.jstach.logwood;
  provides io.jstach.logwood.spi.LogwoodService with my.app.MyConfig, my.app.MyValues;
}
```

`levelOrNull(name)` returns null to inherit, including the empty name for the
root. Override it to replace property-based level selection. Key-value callbacks
run synchronously and must not retain their consumer. All providers share the
32-key context budget. Context and fluent fields share `kv.` labels; fluent
fields have final precedence, the last event value wins, and each label is emitted
once. A bounded temporary map is created only when fields exist. Logwood retains
no context map between records. Built-in metadata remains separate. LTSV labels
use percent-encoded UTF-8, a `%` sentinel for the empty key, and a `%uXXXX`
extension for unpaired UTF-16 surrogates. Literal percent signs become `%25`,
keeping these forms distinct. JSON keeps the original keys after `kv.` and
escapes JSON member names.

Logwood's System.Logger path loads no SLF4J classes and never calls
`LogwoodConfig.createMDCAdapter()`. The adapter is a JDK-only storage contract
under `LogwoodService.LogwoodConfig.MDCAdapter`; null selects the no-op bridge.
A custom adapter supplies map operations, with copy accessors and
`setContextMap(null)` clearing context. SLF4J deque operations are no-ops.
To include MDC in records, a separate KeyValuesProvider can read the same
application-owned adapter. Context providers can instead read scoped storage
without MDC or any logging facade.

The SLF4J and JDK provider registrations are integration requirements, not extra
application service discovery passes. Markers are ignored.

## Security and size

See [SECURITY.md](SECURITY.md) for the threat model, limits, and failure behavior.
Controls, terminal escapes, Unicode format characters (including bidi controls),
line separators, backslashes, and malformed surrogates are escaped. Values and
records have fixed limits; clipping is explicit as `truncated:true` in LTSV or
`"truncated":true` in JSONL. JSON clipping preserves complete escapes, member names,
and closing delimiters.

Logwood retains no logger-name registry or marker state.
The per-thread recursion guard holds only a Boolean, not a record buffer or context.

The core has eleven types plus package and module descriptors; optional JPMS SLF4J
registration adds one class and its descriptor. Runtime jars omit
compiler debugging tables, Maven descriptors, and build-environment manifest fields. To build with source/line
information for debugging:

```sh
./mvnw clean package -Dmaven.compiler.debuglevel=lines,source
```

Both encoders share a lazy 8 KiB bitmap for Unicode escape classification.
Printable ASCII text bypasses the table; surrogate pairs retain their separate
code point checks. The table is generated at runtime rather than embedded in the JAR.

## Nullability

Every production package, including internal packages, has package-info.java
with JSpecify `@NullMarked`. Nullable settings, messages, context data, arrays,
array elements, and return values use explicit `@Nullable` type annotations.
JSpecify is a normal transitive Maven dependency, following its
[dependency guidance](https://jspecify.dev/docs/using/), so consumers can read the
published contracts. Its JPMS requirement is static; logging still runs when the
annotation JAR is omitted from the runtime.

Run the library nullness check with JDK 25+:

```sh
bin/analyze.sh
```

This runs NullAway in JSpecify mode with JDK nullness models and checks explicit
null marking. It checks production library sources; test-only driver packages
are explicitly NullUnmarked because they exercise invalid inputs. Two MDC map
forwarders have narrow analyzer suppressions for SLF4J's unannotated generic map
boundary, retaining nullable values allowed by that API. Integration tests cover
null values and copies in both directions. Normal builds still target Java 21.

## Measurements

See the [current JAR comparison with Avaje Simple Logger](bench/SIZE.md),
including required runtime dependencies and exact artifact hashes.

The [JMH benchmark](bench/README.md) compares Logwood with slf4j-simple using the same
PrintStream sink. It is separate from the runtime artifact. Size and speed are
measured goals, not a blanket claim that an escaped structured logger wins every
workload. The checked-in report is the initial implementation baseline at commit `994a006`;
it predates the Java 21 and context-callback changes and does not measure the current code.

## License

[Zero-Clause BSD](LICENSE). The generated Maven wrapper scripts retain their
Apache-2.0 notices; they are build tooling, not runtime code.
