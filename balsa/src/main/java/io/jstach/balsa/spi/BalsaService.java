package io.jstach.balsa.spi;

import org.jspecify.annotations.Nullable;

import java.lang.System.Logger.Level;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The one application service type, discovered once using Balsa's class loader. Register
 * zero or one BalsaConfig and any number of KeyValuesProvider services. Constructors and
 * order methods must not depend on logging: bootstrap calls are suppressed until
 * initialization completes. Invalid services or multiple configs disable logging with one
 * sanitized diagnostic.
 */
public sealed interface BalsaService permits BalsaService.BalsaConfig, BalsaService.KeyValuesProvider {

	/**
	 * Configuration independent of context collection. Levels are resolved once per
	 * logger through dotted ancestors and the empty root name; null inherits.
	 */
	non-sealed class BalsaConfig implements BalsaService {

		public BalsaConfig() {
		}

		/**
		 * Current epoch milliseconds, defaulting to the system wall clock. Called once
		 * for each enabled event outside journald mode. Overrides must be thread-safe.
		 * Failure diagnostics use the system clock independently of this callback.
		 * @return milliseconds since 1970-01-01T00:00:00Z.
		 */
		public long now() {
			return System.currentTimeMillis();
		}

		/** Returns a threshold, or null to inherit. Defaults to logging.level[.name]. */
		public @Nullable Level levelOrNull(String loggerName) {
			String value = propertyOrNull(loggerName.isEmpty() ? "logging.level" : "logging.level." + loggerName);
			if (value == null)
				return null;
			if (value.length() > 16)
				throw new IllegalArgumentException("Invalid Balsa level");
			value = value.trim().toUpperCase(Locale.ROOT);
			return Level.valueOf(value.equals("WARN") ? "WARNING" : value);
		}

		/**
		 * Creates the adapter only when SLF4J initializes, never for System.Logger. To
		 * include MDC in records, push its entries from a {@link KeyValuesProvider}. The
		 * default retains no context: null selects the no-op bridge.
		 */
		public @Nullable MDCAdapter createMDCAdapter() {
			return null;
		}

		/**
		 * Reads logging.level, logging.level.name, and logging.journald; null means
		 * absent.
		 */
		public @Nullable String propertyOrNull(String key) {
			return System.getProperty(key);
		}

		/**
		 * Optional MDC storage, independent of SLF4J. The SLF4J provider bridges this
		 * contract only when that API is used. Defaults do nothing and store no data. The
		 * map accessor must return a copy; setContextMap(null) clears context.
		 * Implementations own their storage, concurrency and inheritance policies.
		 */
		public interface MDCAdapter {

			default void put(String key, @Nullable String value) {
			}

			default @Nullable String get(String key) {
				return null;
			}

			default void remove(String key) {
			}

			default void clear() {
			}

			default @Nullable Map<String, @Nullable String> getCopyOfContextMap() {
				return null;
			}

			default void setContextMap(@Nullable Map<String, @Nullable String> contextMap) {
			}

		}

	}

	/**
	 * Pushes context synchronously without invoking a logging facade. Providers run in
	 * ascending order; later providers override earlier values for the same key. Equal
	 * orders retain discovery order. Fluent event fields have final precedence. Do not
	 * retain the consumer. The context budget is shared by all providers. Null keys are
	 * skipped with a truncation marker; null values produce empty fields.
	 */
	abstract non-sealed class KeyValuesProvider implements BalsaService {

		public KeyValuesProvider() {
		}

		/**
		 * Stable precedence, evaluated during initialization. Higher values run later.
		 */
		public int order() {
			return 0;
		}

		public abstract void keyValues(BiConsumer<@Nullable String, @Nullable String> consumer);

	}

}
