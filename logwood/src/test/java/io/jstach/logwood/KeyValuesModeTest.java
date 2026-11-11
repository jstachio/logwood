package io.jstach.logwood;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.jstach.logwood.spi.LogwoodService.KeyValuesMode;
import io.jstach.logwood.spi.LogwoodService.KeyValuesProvider;
import io.jstach.logwood.spi.LogwoodService.LogwoodConfig;

class KeyValuesModeTest {

	static Stream<Arguments> settings() {
		return Stream.of(Arguments.of(null, KeyValuesMode.FLAT), Arguments.of("flat", KeyValuesMode.FLAT),
				Arguments.of("TrUe", KeyValuesMode.FLAT), Arguments.of("none", KeyValuesMode.NONE),
				Arguments.of("FALSE", KeyValuesMode.NONE), Arguments.of("PrEfIxEd", KeyValuesMode.PREFIXED));
	}

	@ParameterizedTest
	@MethodSource("settings")
	void configurationUsesTypedModesAndBooleanAliases(@Nullable String value, KeyValuesMode expected) {
		var config = new LogwoodConfig() {
			@Override
			public @Nullable String propertyOrNull(String key) {
				assertEquals("logging.keyvalues.mode", key);
				return value;
			}
		};
		assertSame(expected, config.keyValuesMode());
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "collision", "prefix", " flat", "secret=do-not-print", "flat\n" })
	void invalidConfigurationDoesNotEchoTheProperty(String value) {
		var config = new LogwoodConfig() {
			@Override
			public String propertyOrNull(String key) {
				return value;
			}
		};
		assertEquals("Invalid key-values mode",
				assertThrows(IllegalArgumentException.class, config::keyValuesMode).getMessage());
	}

	static Stream<Arguments> modes() {
		return Stream.of(KeyValuesMode.values())
			.flatMap(mode -> Stream.of(Arguments.of(mode, false), Arguments.of(mode, true)));
	}

	@ParameterizedTest
	@MethodSource("modes")
	void ordinaryReservedAndNamespaceKeysStayDistinct(KeyValuesMode mode, boolean json) throws Exception {
		var out = new RecordEncoder(-1, json, mode);
		out.start("level");
		out.text("INFO");
		out.start("message");
		out.text("real");
		String[] keys = { "request", "time", "level", "logger", "message", "thread", "threadId", "threadName", "error",
				"stacktrace", "keyValues", "truncated", "keyValues.level", "keyValues.keyValues.level", "" };
		for (String key : keys)
			out.eventValue(key, key);
		out.eventValue("request", "last");
		out.writeKeyValues();
		var fields = parse(out.finish(), json);
		assertEquals("INFO", fields.get("level"));
		assertEquals("real", fields.get("message"));
		if (mode == KeyValuesMode.NONE) {
			assertEquals(Map.of("level", "INFO", "message", "real"), fields);
			return;
		}
		assertEquals(17, fields.size());
		assertEquals("last", fields.get(mode == KeyValuesMode.PREFIXED ? "keyValues.request" : "request"));
		for (int i = 1; i < keys.length - 1; i++)
			assertEquals(keys[i], fields.get("keyValues." + keys[i]));
		assertEquals("", fields.get((mode == KeyValuesMode.PREFIXED ? "keyValues." : "") + (json ? "" : "%")));
		assertFalse(fields.containsKey("truncated"));
	}

	@ParameterizedTest
	@MethodSource("modes")
	void applicationValuesCannotForgeTruncation(KeyValuesMode mode, boolean json) throws Exception {
		var out = new RecordEncoder(-1, json, mode);
		out.start("message");
		out.text("x".repeat(RecordEncoder.VALUE + 1));
		out.eventValue("truncated", "false");
		out.writeKeyValues();
		var fields = parse(out.finish(), json);
		assertEquals(json ? Boolean.TRUE : "true", fields.get("truncated"));
		if (mode == KeyValuesMode.NONE)
			assertFalse(fields.containsKey("keyValues.truncated"));
		else
			assertEquals("false", fields.get("keyValues.truncated"));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void noneSkipsProviderCallbacksAndDiscardedValues(boolean json) throws Exception {
		var out = new RecordEncoder(-1, json, KeyValuesMode.NONE);
		out.start("message");
		out.text("safe");
		out.keyValues(new KeyValuesProvider() {
			@Override
			public void keyValues(java.util.function.BiConsumer<@Nullable String, @Nullable String> consumer) {
				fail("disabled context provider called");
			}
		});
		var rendered = new AtomicInteger();
		var secret = new Object() {
			@Override
			public String toString() {
				rendered.incrementAndGet();
				return "secret=do-not-print";
			}
		};
		out.eventValue(null, secret);
		out.eventValue("x".repeat(1_000_000), secret);
		out.extra("token", secret);
		out.writeKeyValues();
		assertEquals(Map.of("message", "safe"), parse(out.finish(), json));
		assertEquals(0, rendered.get());
	}

	private static Map<String, Object> parse(String record, boolean json) throws Exception {
		if (json)
			return JsonlTest.parse(record);
		var fields = new LinkedHashMap<String, Object>();
		for (String field : record.stripTrailing().split("\t", -1)) {
			int colon = field.indexOf(':');
			assertTrue(colon > 0);
			assertNull(fields.put(field.substring(0, colon), field.substring(colon + 1)), "duplicate label");
		}
		return fields;
	}

}
