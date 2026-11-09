package io.jstach.balsa;

import java.lang.System.Logger.Level;
import java.util.Locale;
import java.util.Deque;
import java.util.Map;
import java.util.function.BiConsumer;

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
	 * retains no context: null selects the no-op bridge.
	 */
	public MDCAdapter createMDCAdapter() {
		return null;
	}

	/** Reads balsa.level, balsa.level.name, and balsa.journald; null means absent. */
	public String propertyOrNull(String key) {
		return System.getProperty(key);
	}

	/**
	 * Optional MDC storage, independent of SLF4J. The SLF4J provider bridges this
	 * contract only when that API is used. Defaults do nothing and store no data. Map and
	 * deque accessors must return copies; setContextMap(null) clears context.
	 * Implementations own their storage, concurrency and inheritance policies.
	 */
	public interface MDCAdapter {

		default void put(String key, String value) {
		}

		default String get(String key) {
			return null;
		}

		default void remove(String key) {
		}

		default void clear() {
		}

		default Map<String, String> getCopyOfContextMap() {
			return null;
		}

		default void setContextMap(Map<String, String> contextMap) {
		}

		default void pushByKey(String key, String value) {
		}

		default String popByKey(String key) {
			return null;
		}

		default Deque<String> getCopyOfDequeByKey(String key) {
			return null;
		}

		default void clearDequeByKey(String key) {
		}

	}

}
