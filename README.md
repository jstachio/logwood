# Balsa

A small stdout backend for SLF4J 2 and `System.Logger`, inspired by Rainbow Gum
and slf4j-simple. Java 17+, Zero-Clause BSD, one runtime dependency: `slf4j-api`.

One output, one format, one application SPI. No file or network outputs,
configuration files, format patterns, output plugins, asynchronous queues,
shutdown hooks, caller lookup, or packaging-data lookup.

## Build and use

```sh
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

This snapshot must first be installed locally; it is not a published release.
Use Balsa as the only SLF4J provider. The SLF4J API is required even when only
`System.Logger` is used. Both classpath and module-path discovery are supported;
a modular application can `requires io.jstach.balsa`.

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
throwables, markers, and fluent key/value pairs. `System.Logger` uses localized
MessageFormat substitution when an argument index is present. A throwable
supplied as a System.Logger formatting argument stays an argument; the explicit
throwable overload writes the `error` field.

## Configuration

Defaults: INFO, stdout, no journald prefix, and `NOPMDCAdapter` (no stored MDC).
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

Register zero or one `io.jstach.balsa.BalsamConfig` provider. It can supply levels,
property values, and the shared SLF4J MDC adapter. For example:

```java
import io.jstach.balsa.BalsamConfig;
import org.slf4j.spi.MDCAdapter;

public final class MyConfig extends BalsamConfig {
  @Override public MDCAdapter createMDCAdapter() {
    return new org.slf4j.helpers.BasicMDCAdapter();
  }
}
```

On the classpath, put its fully qualified name in
`META-INF/services/io.jstach.balsa.BalsamConfig`. On the module path:

```java
module my.config {
  requires io.jstach.balsa;
  provides io.jstach.balsa.BalsamConfig with my.config.MyConfig;
}
```

`levelOrNull(name)` returns null to inherit, including the empty name for the
root. Override it to replace property-based level selection. The provider is
loaded once through Balsa's class loader, not a per-request context class loader.
Avoid logging or depending on logging during provider construction. Bootstrap
calls into Balsa are suppressed and become usable after initialization;
SLF4J itself may separately buffer bootstrap calls.

MDC fields use `mdc.` and fluent key/value fields use `kv.`. A user field named
`level` therefore cannot replace the built-in `level`. Labels are percent-encoded
UTF-8; literal percent signs and malformed UTF-16 are encoded distinctly.

The SLF4J and JDK provider registrations are integration requirements, not extra
application extension points. Both APIs share the same configuration and MDC.

## Security and size

See [SECURITY.md](SECURITY.md) for the threat model, limits, and failure behavior.
Controls, terminal escapes, Unicode format characters (including bidi controls),
line separators, backslashes, and malformed surrogates are escaped. Values and
records have fixed limits; clipping is explicit as `truncated:true`.

Balsa retains no logger-name registry. The marker cache holds at most 256 short names
and weak marker references, so it does not retain application marker graphs.
The per-thread recursion guard holds only a Boolean, not a record buffer or MDC.

Production has six class files plus `module-info.class`. Runtime jars omit
compiler debugging tables, Maven descriptors, and build-environment manifest fields. To build with source/line
information for debugging:

```sh
./mvnw clean package -Dmaven.compiler.debuglevel=lines,source
```

## Measurements

The [JMH benchmark](bench/README.md) compares Balsa with slf4j-simple using the same
PrintStream sink. It is separate from the runtime artifact. Size and speed are
measured goals, not a blanket claim that an escaped structured logger wins every
workload. The checked-in benchmark report records the actual tradeoffs.

## License

[Zero-Clause BSD](LICENSE). The generated Maven wrapper scripts retain their
Apache-2.0 notices; they are build tooling, not runtime code.
