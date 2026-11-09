package io.jstach.balsa;

import java.lang.System.Logger.Level;
import java.util.Locale;
import java.util.function.BiConsumer;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;

/**
 * The one application SPI. Register zero or one provider through ServiceLoader. The
 * provider is loaded using Balsa's class loader. Construction must not rely on logging:
 * bootstrap logging is disabled until configuration is complete. Multiple providers or
 * invalid configuration disable Balsa with one diagnostic. Levels are resolved once per
 * logger, from its exact name through dotted ancestors and then the empty root name.
 * Returning null defers to the parent.
 */
public class BalsaConfig {

	public BalsaConfig() {
	}

	/** Returns a threshold, or null to inherit. Defaults to balsa.level[.name]. */
	public Level levelOrNull(String loggerName) {
		String value = propertyOrNull(loggerName.isEmpty() ? "balsa.level" : "balsa.level." + loggerName);
		if (value == null)
			return null;
		if (value.length() > 16)
			throw new IllegalArgumentException("Invalid Balsa level");
		value = value.trim().toUpperCase(Locale.ROOT);
		return Level.valueOf(value.equals("WARN") ? "WARNING" : value);
	}

	/**
	 * Pushes context fields synchronously to the consumer. The default is empty. This
	 * method can read scoped values or a provider-owned MDC adapter without invoking
	 * SLF4J facades. Do not retain the consumer or emit duplicate keys.
	 */
	public void keyValues(BiConsumer<String, String> consumer) {
	}

	/**
	 * Creates the adapter only when SLF4J initializes, never for System.Logger. To
	 * include MDC in records, also push its entries from {@link #keyValues}. The default
	 * retains no context.
	 */
	public MDCAdapter createMDCAdapter() {
		return new NOPMDCAdapter();
	}

	/** Reads balsa.level, balsa.level.name, and balsa.journald; null means absent. */
	public String propertyOrNull(String key) {
		return System.getProperty(key);
	}

}
