# Goals

Smallest slf4j and system logger combined implementation.

# Project crap

gav: io.jstach.balsa:balsa

package: io.jstach.balsa

license: zero bsd

# Inspiration

rainbow gum and slf4j-simple

# Details

* Only has one output: console stdout
* Has only one format: ltsv ( see rainbow gum )
  * LTSV will include MDC key values. No key values is NOOPMDCAdapter.
  * LTSV will properly escape nasty whitespace injection shit etc
* Implements both SLF4J and System.Logger
* Has one and only one SPI using service loader
* Has module-info
* An option to prefix for journald like `<3>`

## SPI

```java

// or interface
public abstract class BalsamConfig {

// maybe an int instead.
public System.Logger.Level levelOrNull(String loggerName) {
  return INFO;
}

public MDCAdapter createMDCAdapter() { 
   return NOOPMDCAdapter 
}

// might not even need this.
public String propertyOrNull(String key) {
  return System.getProperty(key);
}

}
```

# Style

Almost no javadoc except in module-info, package-info and SPI.

Use minimal spacing and as few class files as possible for possible copy and paste for zero dep.


