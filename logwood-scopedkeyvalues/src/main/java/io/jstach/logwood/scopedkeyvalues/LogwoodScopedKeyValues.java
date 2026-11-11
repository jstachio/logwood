package io.jstach.logwood.scopedkeyvalues;

import org.jspecify.annotations.Nullable;

import java.lang.ScopedValue;
import io.jstach.logwood.spi.LogwoodService.KeyValuesProvider;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import io.jstach.rainbowgum.scopedkeyvalues.ScopedKeyValues;
import io.jstach.rainbowgum.scopedkeyvalues.spi.ScopedKeyValuesProvider;
import io.jstach.rainbowgum.scopedkeyvalues.spi.ScopedKeyValuesProviderFactory;

public final class LogwoodScopedKeyValues extends KeyValuesProvider
		implements ScopedKeyValuesProviderFactory, ScopedKeyValuesProvider {

	private static final ScopedValue<Map<String, String>> VALUES = ScopedValue.newInstance();

	private static final int KEYS = 32, KEY = 256, VALUE = 4096;

	public LogwoodScopedKeyValues() {
	}

	@Override
	public int order() {
		return -100;
	}

	@Override
	public ScopedKeyValuesProvider provide() {
		return this;
	}

	@Override
	public ScopedKeyValues.Builder builder() {
		return new Builder();
	}

	@Override
	public Map<String, String> currentMerged() {
		return VALUES.orElse(Map.of());
	}

	@Override
	public void keyValues(BiConsumer<@Nullable String, @Nullable String> consumer) {
		Objects.requireNonNull(consumer, "consumer");
		VALUES.orElse(Map.of()).forEach(consumer);
	}

	private static final class Builder implements ScopedKeyValues.Builder {

		private final Map<String, String> values = new LinkedHashMap<>();

		@Override
		public void accept(String key, String value) {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(value, "value");
			if (key.length() > KEY || value.length() > VALUE)
				throw new IllegalArgumentException("Scoped key or value exceeds Logwood's limit");
			put(values, key, value);
		}

		private static void put(Map<String, String> target, String key, String value) {
			if (target.size() == KEYS && !target.containsKey(key))
				throw new IllegalArgumentException("Too many scoped keys for Logwood");
			target.put(key, value);
		}

		private Map<String, String> snapshot() {
			var merged = new LinkedHashMap<>(VALUES.orElse(Map.of()));
			values.forEach((key, value) -> put(merged, key, value));
			return Collections.unmodifiableMap(merged);
		}

		@Override
		public void run(Runnable body) {
			Objects.requireNonNull(body, "body");
			ScopedValue.where(VALUES, snapshot()).run(body);
		}

		@Override
		public <T, X extends Throwable> T call(ScopedKeyValues.CallableOp<T, X> body) throws X {
			Objects.requireNonNull(body, "body");
			return ScopedValue.where(VALUES, snapshot()).call(body::call);
		}

	}

}
