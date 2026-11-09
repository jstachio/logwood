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

`io.jstach.balsa.spi` is the core module's only exported package. Its sealed
`BalsaService` interface has two non-sealed nested base classes:

* `BalsaService.BalsaConfig` supplies levels, properties, and optional MDC storage.
  Register zero or one configuration; the default applies when none is present.
* `BalsaService.KeyValuesProvider` pushes fields through
  `keyValues(BiConsumer<String, String>)`. Register any number of providers.
  `order()` defaults to zero; providers run in ascending order and later values
  override earlier values for the same key. Equal orders retain discovery order,
  so choose distinct orders when override precedence matters.

Balsa makes one ServiceLoader pass over `BalsaService`, constructs services once,
selects their roles with instanceof, and sorts key-value providers once during
initialization. The loader uses Balsa's class loader, not a per-request context
class loader. Constructors and order methods must not depend on logging.
Bootstrap calls are suppressed until initialization completes; SLF4J itself may
separately buffer bootstrap calls.

For example, application fields can be supplied independently of configuration:

```java
import io.jstach.balsa.spi.BalsaService.KeyValuesProvider;
import java.util.function.BiConsumer;

public final class MyValues extends KeyValuesProvider {
  @Override public void keyValues(BiConsumer<String, String> consumer) {
    consumer.accept("service", "example");
  }
}
```

Custom settings use the other base class:

```java
import io.jstach.balsa.spi.BalsaService.BalsaConfig;

public final class MyConfig extends BalsaConfig {
  @Override public System.Logger.Level levelOrNull(String name) {
    return name.isEmpty() ? System.Logger.Level.WARNING : super.levelOrNull(name);
  }
}
```

On the classpath, list all services in
`META-INF/services/io.jstach.balsa.spi.BalsaService`:

```text
my.app.MyConfig
my.app.MyValues
```

On the module path:

```java
module my.app {
  requires io.jstach.balsa;
  provides io.jstach.balsa.spi.BalsaService with my.app.MyConfig, my.app.MyValues;
}
```

`levelOrNull(name)` returns null to inherit, including the empty name for the
root. Override it to replace property-based level selection. Key-value callbacks
run synchronously and must not retain their consumer. All providers share the
32-key context budget. Context and fluent fields share `kv.` labels; fluent
fields have final precedence, the last event value wins, and each label is emitted
once. A bounded temporary map is created only when fields exist. Balsa retains
no context map between records. Built-in metadata remains separate. Labels use
injective percent-encoded UTF-8, including literal percent signs and malformed UTF-16.

Balsa's System.Logger path loads no SLF4J classes and never calls
`BalsaConfig.createMDCAdapter()`. The adapter is a JDK-only storage contract
under `BalsaService.BalsaConfig.MDCAdapter`; null selects the no-op bridge.
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
records have fixed limits; clipping is explicit as `truncated:true`.

Balsa retains no logger-name registry or marker state.
The per-thread recursion guard holds only a Boolean, not a record buffer or context.

The core has ten class files plus `module-info.class`; optional JPMS SLF4J
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
