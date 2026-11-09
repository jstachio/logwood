package io.jstach.balsa;

import java.lang.System.Logger.Level;
import java.util.Locale;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;

/**
 * The one application SPI. Register zero or one provider through ServiceLoader.
 * The provider is loaded using Balsa's class loader. Construction must not rely
 * on logging: bootstrap logging is disabled until configuration is complete.
 * Multiple providers or invalid configuration disable Balsa with one diagnostic.
 * Levels are resolved once per logger, from its exact name through dotted
 * ancestors and then the empty root name. Returning null defers to the parent.
 */
public class BalsamConfig {
  public BalsamConfig() {}

  /** Returns a threshold, or null to inherit. Defaults to balsa.level[.name]. */
  public Level levelOrNull(String loggerName) {
    String value = propertyOrNull(loggerName.isEmpty() ? "balsa.level" : "balsa.level." + loggerName);
    if (value == null) return null;
    if (value.length() > 16) throw new IllegalArgumentException("Invalid Balsa level");
    value = value.trim().toUpperCase(Locale.ROOT);
    return Level.valueOf(value.equals("WARN") ? "WARNING" : value);
  }

  /** Creates the shared MDC adapter once. The default retains no context. */
  public MDCAdapter createMDCAdapter() { return new NOPMDCAdapter(); }

  /** Reads balsa.level, balsa.level.name, and balsa.journald; null means absent. */
  public String propertyOrNull(String key) { return System.getProperty(key); }
}
