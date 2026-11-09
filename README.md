# Balsa

A small stdout backend for SLF4J 2 and `System.Logger`, inspired by Rainbow Gum
and slf4j-simple. Java 21+, Zero-Clause BSD. System.Logger needs no runtime dependency;
`slf4j-api` is optional and needed only for SLF4J logging.

One output, one format, one application SPI. No file or network outputs,
configuration files, format patterns, output plugins, asynchronous queues,
shutdown hooks, caller lookup, or packaging-data lookup.

## Build and use

This is a Maven reactor: `balsa/` builds the runtime artifact, `balsa-slf4j/`
adds SLF4J module-path registration, and `bench/` builds its isolated JMH harness.
The root `balsa-parent` POM shares dependency versions,
Java 21 compiler settings, Spring formatting validation, and build plugins.
The runtime coordinates remain `io.jstach.balsa:balsa`.

```sh
./mvnw spring-javaformat:apply
./mvnw clean verify
./mvnw install
```

```xml
<dependency>
  <groupId>io.jstach.balsa</groupId>
  <artifactId>balsa</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

For just the runtime, use `./mvnw -pl balsa -am clean verify`.

The build validates the same Spring Java Format 0.0.48 style used by Rainbow Gum.
Run `spring-javaformat:apply` after editing Java sources; CI rejects formatting drift.

This snapshot must first be installed locally; it is not a published release.
System.Logger applications need only the dependency above and, on JPMS,
`requires io.jstach.balsa`. SLF4J can be absent from both compilation and runtime.
The core descriptor has `requires static org.slf4j`; its Maven dependency is
optional. Placing SLF4J on the module path does not force it to resolve.

For SLF4J applications, use this dependency instead. It adds the core and SLF4J API
and enables automatic discovery on both the classpath and module path:

```xml
<dependency>
  <groupId>io.jstach.balsa</groupId>
  <artifactId>balsa-slf4j</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

A modular SLF4J application can `requires org.slf4j`; service binding discovers
the provider. It may also explicitly `requires io.jstach.balsa.slf4j`.
The registration module contains one subclass and its module descriptor.
JPMS validates service types during resolution, so the SLF4J `provides` directive
belongs here. The core JAR retains its classpath service descriptor; a classpath
application can also use `balsa` with an explicit `slf4j-api` dependency.
Use Balsa as the only SLF4J provider.

```java
LoggerFactory.getLogger("app").info("Hello {}", "world");
System.getLogger("app").log(System.Logger.Level.INFO, "Hello {0}", "world");
```

Output is UTF-8 LTSV, with a single LF after each record:

```text
level:INFO	thread:main	logger:app	message:Hello world
```

The separators above are actual tabs. There is no clock read or timestamp formatting;
journald or the stdout capture system can timestamp records.
SLF4J uses `{}` substitution, including its backslash rules, arrays, trailing
throwables, and fluent key/value pairs. Marker arguments are ignored. `System.Logger` uses localized
MessageFormat substitution when an argument index is present. A throwable
supplied as a System.Logger formatting argument stays an argument; the explicit
throwable overload writes the `error` field.

## Configuration

Defaults: INFO, stdout, no journald prefix, and no stored MDC (all MDC operations default to no-ops).
The only built-in properties are:

| Property | Meaning |
| --- | --- |
| `balsa.level` | Root threshold: ALL, TRACE, DEBUG, INFO, WARN/WARNING, ERROR, OFF. |
| `balsa.level.com.example` | Threshold inherited by dotted descendants. |
| `balsa.journald` | `true` adds a syslog priority prefix; default `false`. |

```sh
java -Dbalsa.level=WARN -Dbalsa.level.com.example=DEBUG -Dbalsa.journald=true ...
```

Levels resolve at first use of each logger and then stay fixed. Journal prefixes
are `<3>` for ERROR, `<4>` for WARN, `<6>` for INFO, and `<7>` for DEBUG/TRACE/ALL.
OFF suppresses application events, including `System.Logger.log(OFF, ...)`.
Failure diagnostics are independent of application thresholds.

### The application SPI

Register zero or one `io.jstach.balsa.BalsaConfig` provider. It can supply levels,
property values, context key/value pairs, and the SLF4J MDC adapter.
`keyValues(BiConsumer<String, String>)` pushes context directly during each event.
Its default is empty. The callback can read scoped storage without making an MDC
map or invoking any SLF4J facade. It must run synchronously, must not retain the
consumer, and must emit distinct keys.

Balsa's System.Logger path does not initialize `LoggerFactory`, `MDC`,
`MarkerFactory`, or `BalsaProvider`. It never calls `createMDCAdapter()`.
SLF4J initialization requests that adapter separately. The default null result
selects the no-op bridge. `BalsaConfig.MDCAdapter` is Balsa's own JDK-only contract,
not an SLF4J type. A custom adapter supplies storage for the map and deque operations;
the SLF4J provider delegates to it. Copy accessors must return copies and
`setContextMap(null)` must clear context. To include MDC in either API's output,
read a provider-owned adapter in `keyValues` rather than invoking the static MDC
facade. Context itself does not depend on MDC. For example, a config can push
application fields directly:

```java
import io.jstach.balsa.BalsaConfig;
import java.util.function.BiConsumer;

public final class MyConfig extends BalsaConfig {
  @Override public void keyValues(BiConsumer<String, String> consumer) {
    consumer.accept("service", "example");
  }
}
```

On the classpath, put its fully qualified name in
`META-INF/services/io.jstach.balsa.BalsaConfig`. On the module path:

```java
module my.config {
  requires io.jstach.balsa;
  provides io.jstach.balsa.BalsaConfig with my.config.MyConfig;
}
```

`levelOrNull(name)` returns null to inherit, including the empty name for the
root. Override it to replace property-based level selection. The provider is
loaded once through Balsa's class loader, not a per-request context class loader.
Avoid logging or depending on logging during provider construction. Bootstrap
calls into Balsa are suppressed and become usable after initialization;
SLF4J itself may separately buffer bootstrap calls.

Context fields use `context.` and fluent key/value fields use `kv.`. A user field named
`level` therefore cannot replace the built-in `level`. Labels are percent-encoded
UTF-8; literal percent signs and malformed UTF-16 are encoded distinctly.

The SLF4J and JDK provider registrations are integration requirements, not extra
application extension points. Both APIs share the same configuration and context callback. The SLF4J contract
requires a marker factory; the standard helper supplies it, but Balsa never reads
or emits markers.

## Security and size

See [SECURITY.md](SECURITY.md) for the threat model, limits, and failure behavior.
Controls, terminal escapes, Unicode format characters (including bidi controls),
line separators, backslashes, and malformed surrogates are escaped. Values and
records have fixed limits; clipping is explicit as `truncated:true`.

Balsa retains no logger-name registry or marker state.
The per-thread recursion guard holds only a Boolean, not a record buffer or context.

The core has eight class files plus `module-info.class`; optional JPMS SLF4J
registration adds one class and its descriptor. Runtime jars omit
compiler debugging tables, Maven descriptors, and build-environment manifest fields. To build with source/line
information for debugging:

```sh
./mvnw clean package -Dmaven.compiler.debuglevel=lines,source
```

## Measurements

The [JMH benchmark](bench/README.md) compares Balsa with slf4j-simple using the same
PrintStream sink. It is separate from the runtime artifact. Size and speed are
measured goals, not a blanket claim that an escaped structured logger wins every
workload. The checked-in report is the initial implementation baseline at commit `994a006`;
it predates the Java 21 and context-callback changes and does not measure the current code.

## License

[Zero-Clause BSD](LICENSE). The generated Maven wrapper scripts retain their
Apache-2.0 notices; they are build tooling, not runtime code.
