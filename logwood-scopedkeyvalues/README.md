# ScopedKeyValues provider

This module implements Rainbow Gum **1.0.0** ScopedKeyValues with Java 25
ScopedValue and automatically contributes context to Logwood through
`LogwoodService.KeyValuesProvider`. It supplies no LogwoodConfig. Application settings
and other key-value providers remain independent and compose with scoped context.
The module exports no implementation package; Logwood's only exported package is
`io.jstach.logwood.spi`.

It depends on `rainbowgum-scopedkeyvalues-api`, not Rainbow Gum's backend.
The core targets Java 21. Scoped storage needs JDK 25+, without preview flags or
ThreadLocal context storage. System.Logger works with the SLF4J API absent.

## Build and use

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
  <version>1.1.0-SNAPSHOT</version>
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

## Scope and merge rules

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
usual defensive encoding apply; built-in metadata remains separate.
`logging.keyvalues.mode=none` skips reading scoped context into log records;
scoped storage itself remains available to the application.

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
