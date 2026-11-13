# Logwood user guide

Documentation version: `_VERSION_`. [Markdown source](overview.md).

Logwood is a modern slf4j-simple replacement for SLF4J 2 and `System.Logger`.
Start with a small stdout backend, then move to [Rainbow Gum](https://jstach.io/rainbowgum/)
when you need more configuration, integrations, outputs, or asynchronous publishing. Java 21+, Zero-Clause BSD. System.Logger needs no runtime dependency;
`slf4j-api` is optional and needed only for SLF4J logging.

Named after [logwood (*Haematoxylum campechianum*)](https://powo.science.kew.org/taxon/urn:lsid:ipni.org:names:316401-2/general-information),
a shrub or small tree native from southern Mexico to Honduras.

One output, LTSV or JSON Lines, one application SPI. No file or network outputs,
configuration files, format patterns, output plugins, asynchronous queues,
shutdown hooks, caller lookup, or packaging-data lookup.

## Why LTSV is the default

LTSV is readable tab-separated text with explicit `label:value` fields. Its
structure is deterministic: split at raw tabs, then split each field at its
first colon. Input tabs, newlines, controls, and backslashes are escaped, so
application data cannot introduce record or field separators. Double quotes
need no quoting rules. The exact escape spellings are listed below.

Standard Unix tools can select fields without `jq`. For a compact terminal
layout, set tab stops to two columns:

```sh
tabs -2
java -jar example/target/logwood-example.jar
```

For example, this selects the INFO message from records using `awk`:

```sh
awk -F '\t' '{
  level = message = ""
  for (i = 1; i <= NF; i++) {
    colon = index($i, ":")
    label = substr($i, 1, colon - 1)
    value = substr($i, colon + 1)
    if (label == "level") level = value
    if (label == "message") message = value
  }
  if (level == "INFO") print message
}' application.ltsv
```

This prints encoded message text. Decode escapes once after splitting if you
need original data; keep controls escaped when displaying it in a terminal.
JSON Lines is also available with `-Dlogging.format=jsonl`.

## Moving to Rainbow Gum

Logwood and Rainbow Gum use `logging.level` for the root level and
`logging.level.<logger>` for named loggers. Application calls through SLF4J or
`System.Logger` can stay in place. Remove Logwood's provider dependencies and
add the Rainbow Gum modules you need, with one SLF4J provider at a time.
The optional scoped-values module implements Rainbow Gum 1.0.0's
ScopedKeyValues API, so scoped-context calls use that API already.

Key-value output defaults to bare keys with collision prefixes, matching Rainbow
Gum's LTSV policy. Logwood additionally reserves its `truncated` marker.
Configuration beyond logger levels needs review: the Java configuration SPI,
format selection, field names, key prefixes, limits, and output setup are not
identical. Choose Rainbow Gum when you need files and rolling archives,
network outputs, asynchronous publishers, or framework integration.

## Build and use

This is a Maven reactor: `logwood/` builds the runtime artifact, `logwood-slf4j/`
adds SLF4J module-path registration, `example/` builds a runnable application,
and `bench/` builds its isolated JMH harness.
The root `logwood-parent` POM shares dependency versions,
Java 21 compiler settings, Spring formatting validation, and build plugins.
The runtime coordinates are `io.jstach.logwood:logwood`.
Builds use JDK 27 or newer for javac and javadoc. Core bytecode targets Java 21;
the optional scoped-values module targets Java 25.

```sh
./mvnw spring-javaformat:apply
./mvnw clean verify
./mvnw install
```

```xml
<dependency>
  <groupId>io.jstach.logwood</groupId>
  <artifactId>logwood</artifactId>
  <version>_VERSION_</version>
</dependency>
```

The [example](https://github.com/jstachio/logwood/blob/main/example/README.md) is built and formatted with the reactor but
excluded from Maven deployment. Run it with dependencies from your local Maven
repository:

```sh
./mvnw -pl example -am install
java -jar example/target/logwood-example.jar
```

For just the runtime, use `./mvnw -pl logwood -am clean verify`.

The build validates the same Spring Java Format 0.0.48 style used by Rainbow Gum.
Run `spring-javaformat:apply` after editing Java sources; CI rejects formatting drift.

Before the first release, install the development snapshot locally.
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
  <version>_VERSION_</version>
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

The [LTSV encoding reference](https://github.com/jstachio/logwood/blob/main/doc/ltsv.md) specifies value escapes, percent-encoded
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
| `logging.keyvalues.mode` | `none`/`false`, `flat`/`true` (default), or `prefixed`; case-insensitive. |
| `logging.ltsv.columns` | Fields written first without labels, for example `time,level,logger,thread,message`; LTSV only. |
| `logging.ltsv.stacktrace` | `inline`/`default`/`false` (default) or `lines`/`true`, which puts each stack trace line on its own line; case-insensitive. |

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
32-key context budget. Context and fluent fields share one namespace; fluent
fields have final precedence, the last event value wins, and each label is emitted
once. Flat output is the default; reserved keys receive `keyValues.`. Set
`logging.keyvalues.mode=prefixed` to prefix every key or `none` to omit them.
A bounded temporary map is created only when fields exist. Logwood retains
no context map between records. Built-in metadata remains separate. LTSV labels
use percent-encoded UTF-8, a `%` sentinel for the empty key, and a `%uXXXX`
extension for unpaired UTF-16 surrogates. Literal percent signs become `%25`,
keeping these forms distinct. JSON keeps the original keys after any added
namespace and escapes JSON member names.

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

## Scoped context

This module implements Rainbow Gum **1.0.0** ScopedKeyValues with Java 25
ScopedValue and automatically contributes context to Logwood through
`LogwoodService.KeyValuesProvider`. It supplies no LogwoodConfig. Application settings
and other key-value providers remain independent and compose with scoped context.
The module exports no implementation package; Logwood's only exported package is
`io.jstach.logwood.spi`.

It depends on `rainbowgum-scopedkeyvalues-api`, not Rainbow Gum's backend.
The core targets Java 21. Scoped storage needs JDK 25+, without preview flags or
ThreadLocal context storage. System.Logger works with the SLF4J API absent.

### Build and use

Build with JDK 27 or newer. The root reactor includes this module by default;
its runtime requirement remains Java 25:

```sh
./mvnw spring-javaformat:apply
./mvnw clean verify javadoc:javadoc
./mvnw install
bin/analyze.sh
```

```xml
<dependency>
  <groupId>io.jstach.logwood</groupId>
  <artifactId>logwood-scopedkeyvalues</artifactId>
  <version>_VERSION_</version>
</dependency>
```

Both classpath descriptors and module-info registrations are bundled. Add the
module and start logging; there is no application configuration to register.
A named application can `requires io.jstach.logwood.scopedkeyvalues`.
Use this as the only ScopedKeyValues implementation. The Rainbow Gum API selects
the first factory, so adding its backend/provider alongside Logwood could bind and
read different storage. The implementation package uses JSpecify `@NullMarked`.
JSpecify's JPMS requirement is static, so the annotation JAR can be omitted at
runtime. The root nullness check includes this module on JDK 25+.

```java
import io.jstach.rainbowgum.scopedkeyvalues.ScopedKeyValues;

ScopedKeyValues.builder().add("requestId", "r-123").run(() -> {
  System.getLogger("app").log(System.Logger.Level.INFO, "request");
});
```

The record contains `requestId:r-123`. Logwood discovers this contributor in its
single LogwoodService pass. The Rainbow Gum facade separately discovers its own
required factory; both instances share the same ScopedValue storage.

To use SLF4J too, add `io.jstach.logwood:logwood-slf4j` at the same version.
LoggingEventBuilder fields override scoped and application-provider fields:

```java
ScopedKeyValues.builder().add("requestId", "r-123").run(() -> {
  org.slf4j.LoggerFactory.getLogger("app").atInfo()
      .addKeyValue("requestId", "event-456").log("one event");
});
```

This emits only `requestId:event-456` for that key.

### Scope and merge rules

Scoped context has provider order **-100**. Application KeyValuesProviders use
zero by default, so they can override scoped keys. Providers run in ascending
order and fluent event fields have final precedence. A custom LogwoodConfig is
registered independently under `io.jstach.logwood.spi.LogwoodService` and does not
conflict with this module. MDC is optional and is not involved in scoped collection.

Each binding gets an immutable merged snapshot. Inner scopes override outer
keys. Bindings unwind after normal return or any exception. Builders capture
parent context at run/call time, rather than construction time. Later builder
changes cannot mutate a bound snapshot. Builders are for one thread at a time
and can be reused after an operation. currentMerged returns the existing
immutable snapshot. Structured child tasks inherit JDK scoped bindings;
arbitrary new platform or virtual threads do not inherit them.

Logwood reads the snapshot directly without a per-event scoped-map copy. The core
uses a bounded temporary merge map only when fields exist, overlays fluent
values, and encodes each label once. Overridden objects are not rendered.
No context map is retained between records. The selected key-values mode and
usual defensive encoding apply; built-in metadata remains separate. With mode
`none`, the backend does not read scoped context.

Storage accepts at most **32 merged distinct keys**, **256 UTF-16 units per key**,
and **4096 per value**. Overflow throws IllegalArgumentException without data,
before entering the body or changing an active binding. Null entries are rejected.
The core's shared 32-key context budget and encoded-label/record limits still
apply, with clipping marked `truncated:true`. Push values intended for logging.

Tests cover nested scopes, restoration, immutable snapshots, checked exceptions,
platform/virtual concurrency, bounds, classpath/module-path discovery with no
LogwoodConfig, independent named application services, ordered overrides, control
injection, fluent precedence, and complete absence of SLF4J and JSpecify. Fresh-JVM traces
verify no SLF4J classes load during scoped System.Logger logging.


## Security and size

See [SECURITY.md](https://github.com/jstachio/logwood/blob/main/SECURITY.md) for the threat model, limits, and failure behavior.
Controls, terminal escapes, Unicode format characters (including bidi controls),
line separators, backslashes, and malformed surrogates are escaped. Values and
records have fixed limits; clipping is explicit as `truncated:true` in LTSV or
`"truncated":true` in JSONL. JSON clipping preserves complete escapes, member names,
and closing delimiters.

Logwood retains no logger-name registry or marker state.
The per-thread recursion guard holds only a Boolean, not a record buffer or context.

The core has twelve types plus package and module descriptors; optional JPMS SLF4J
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

## LTSV encoding details

LTSV is Logwood's default (`logging.format=ltsv`). Each record is UTF-8 text with
tab-separated `label:value` fields and exactly one final LF. Split a field at
its **first** colon: colons may occur in values. Values are never quoted.
These rules apply to messages, logger and thread names, context and fluent
values, and throwable text. Logwood does not emit ANSI colors.

### Values

The right column below is literal output text, not an instruction to emit a
control character.

| Input | Encoded value text |
| --- | --- |
| Backslash | `\\` |
| Tab (U+0009) | `\t` |
| LF (U+000A) | `\n` |
| CR (U+000D) | `\r` |
| Other C0 controls (U+0000–U+001F) | `\uXXXX` |
| DEL and C1 controls (U+007F–U+009F) | `\uXXXX` |
| Line/paragraph separators U+2028/U+2029 | `\u2028`, `\u2029` |
| Unicode format characters (category Cf), including bidi controls | `\uXXXX` per UTF-16 code unit |
| Unpaired UTF-16 surrogates | `\uXXXX` |

`XXXX` means exactly four **lowercase** hexadecimal digits. Supplementary
format characters use two escapes: U+1D173 becomes `\ud834\udd73`. The
Unicode 17 format set is covered even on Java 21's older character database.
Every input ESC is escaped, so SGR conceal/color sequences cannot act as
terminal commands: ESC followed by `[8m` becomes `\u001b[8m`. The C1 CSI
U+009B likewise becomes `\u009b`.

Other valid Unicode text, including ordinary supplementary characters, is
preserved without normalization. Double quotes, colons, slashes, and percent
signs in values stay literal. An actual LF becomes `\n`; a literal backslash
followed by `n` becomes `\\n`. A null context/fluent value is empty; a null
message is the text `null`.

### Labels

`logging.keyvalues.mode` selects application key-value output in both formats:

| Mode | Behavior |
| --- | --- |
| `none` / `false` | Omit all context and fluent fields; skip backend collection and rendering. |
| `flat` / `true` | Default: keep ordinary keys bare; prefix collisions with `keyValues.`. |
| `prefixed` | Prefix every application key with `keyValues.`. |

Values are case-insensitive and resolved once at initialization. Invalid values
disable logging with one fixed diagnostic. There is no nested-object option.
`LogwoodConfig.keyValuesMode()` exposes the same choice as the
`LogwoodService.KeyValuesMode` enum for programmatic configuration.

Reserved names are `time`, `level`, `logger`, `message`, `thread`, `threadId`,
`threadName`, `error`, `stacktrace`, `keyValues`, and `truncated`. Names starting
with `keyValues.` also receive another prefix, preserving distinct original keys:
`level` becomes `keyValues.level`, while `keyValues.level` becomes
`keyValues.keyValues.level`. Collision protection applies even when an optional
built-in field is absent. Prefixed JSON members remain dotted names; Logwood does
not expand dots into objects.

After choosing the namespace, encode the original key using these rules:

| Original key text | Encoded key (before an optional namespace prefix) |
| --- | --- |
| ASCII `A-Z a-z 0-9 _ . -` | Unchanged |
| Other valid Unicode | UTF-8 bytes, each written `%hh` with lowercase hex digits |
| Empty key | `%` |
| Unpaired UTF-16 surrogate | `%uXXXX` with four lowercase hex digits |

In the default flat mode, `a b` becomes `a%20b`, `:` becomes `%3a`, `%` becomes
`%25`, `é` becomes `%c3%a9`, and `😀` becomes `%f0%9f%98%80`.
An unpaired U+D800 becomes `%ud800`; the literal text `%ud800` becomes
`%25ud800`. The empty key is `%`, distinct from a literal percent key. Prefixed
mode adds `keyValues.` to each result: for example `keyValues.a%20b`.
The `%uXXXX` form is an extension for malformed UTF-16, not a UTF-8 byte
escape. These encodings keep distinct keys distinct. Null keys are skipped
and set the truncation marker.

### Reading and limits

Separate records and fields **before** decoding escapes. Decode value
backslash escapes in one pass; recursively decoding `\\n` would incorrectly
turn literal escape text into a newline. For application labels, remove one
leading `keyValues.` namespace if present, then handle the empty-key sentinel,
`%uXXXX` code units, and UTF-8 percent bytes.
Do not percent-decode a decoded result again: `%25ud800` is literal `%ud800`.
This escaping is Logwood's convention layered on LTSV; a generic LTSV reader
can split fields but needs these rules to recover original text.

An encoded value is limited to 4,096 UTF-16 code units and a complete record
to 16,384, including the truncation marker and final LF. Escapes and valid
surrogate pairs are never split at a limit. Keys are skipped whole if the
raw key or encoded label (excluding an added `keyValues.` namespace) exceeds
256 code units. Clipping adds `truncated:true`; clipped values cannot recover
the omitted suffix.
See [the security model](https://github.com/jstachio/logwood/blob/main/SECURITY.md) for the remaining work and storage
limits and failure behavior.

With `logging.journald=true`, the transport adds a syslog priority prefix
before the first field and omits `time`. Strip that prefix before using a
generic LTSV parser. JSONL uses JSON string/member-name escaping instead of
the label rules above; select it with `logging.format=jsonl`.

### Stack trace lines

`logging.ltsv.stacktrace=lines` (alias `true`) makes stack traces readable in a
terminal. The record's line is unchanged, except that `error` holds only the
throwable's first line: its class and message. The rest of the stack trace
follows the record, each line on a line of its own after one tab:

```text
time:2026-11-12T03:00:00.123Z	level:ERROR	thread:main	threadId:1	logger:app	message:failed	error:java.lang.IllegalStateException: boom
		at com.example.App.run(App.java:12)
	Caused by: java.io.IOException: disk full
		at com.example.Store.write(Store.java:40)
```

This is no longer strict LTSV, but it is still read by rule rather than by
guessing: a record never starts with a tab, and a line that does continues the
record before it. Remove that one tab, decode the line's escapes, and join the
lines to `error` with LF to get the value the default `inline` (aliases
`default` and `false`) writes. Exception messages stay escaped, so they never
add lines. The value and record limits count the continuation lines, and
`truncated:true` stays on the record's line. Other values disable logging; JSON
Lines ignores the option.
Journald stores each continuation line as its own entry, so keep the default
there.

### Columns

`logging.ltsv.columns` lists fields to write first, in that order and without
labels, so they line up in a terminal: `time`, `level`, `thread`, `logger`, and
`message` (aliases `timestamp` and `msg`), case-insensitive. The fields not
listed follow with their labels, then `error` and the key values. With
`logging.ltsv.columns=time,level,logger,thread,message`:

```text
2026-11-12T03:00:00.123Z	INFO	app	main	1	Hello world
```

`thread` is two columns: the name, then `threadId`. Journald mode omits `time`,
column or not. With `logging.ltsv.columns=thread,message`:

```text
main	1	Hello world	time:2026-11-12T03:00:00.123Z	level:INFO	logger:app
```

Output with columns is no longer LTSV; it is for reading, not parsing. Unknown
or repeated names disable logging. JSON Lines and failure diagnostics ignore
the option. With `logging.ltsv.stacktrace=lines` an empty first column is
written as the reserved escape `\e`, so a record still never starts with a tab.
A literal `\e` remains `\\e`.


## Measurements

See the [current JAR comparison with Avaje Simple Logger](https://github.com/jstachio/logwood/blob/main/bench/SIZE.md),
including required runtime dependencies and exact artifact hashes.

The [JMH benchmark](https://github.com/jstachio/logwood/blob/main/bench/README.md) compares Logwood with slf4j-simple using the same
PrintStream sink. It is separate from the runtime artifact. Size and speed are
measured goals, not a blanket claim that an escaped structured logger wins every
workload. The checked-in report is the initial implementation baseline at commit `994a006`;
it predates the Java 21 and context-callback changes and does not measure the current code.

## License

[Zero-Clause BSD](https://github.com/jstachio/logwood/blob/main/LICENSE). The generated Maven wrapper scripts retain their
Apache-2.0 notices; they are build tooling, not runtime code.
