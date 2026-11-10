package io.jstach.balsa;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonlTest {

	private static final JsonFactory JSON = new JsonFactoryBuilder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
		.build();

	static Map<String, Object> parse(String input) throws IOException {
		try (var parser = JSON.createParser(input)) {
			assertEquals(JsonToken.START_OBJECT, parser.nextToken());
			var values = new LinkedHashMap<String, Object>();
			while (parser.nextToken() != JsonToken.END_OBJECT) {
				assertEquals(JsonToken.FIELD_NAME, parser.currentToken());
				String key = parser.currentName();
				Object value = switch (parser.nextToken()) {
					case VALUE_STRING -> parser.getText();
					case VALUE_NUMBER_INT -> parser.getLongValue();
					case VALUE_TRUE -> Boolean.TRUE;
					default -> fail("Unexpected JSON value: " + parser.currentToken());
				};
				assertNull(values.put(key, value), "Duplicate key: " + key);
			}
			assertNull(parser.nextToken(), "More than one JSON value");
			return values;
		}
	}

	@Test
	void recordsHaveStringFieldsAndNumericThreadIds() throws IOException {
		var out = new RecordEncoder(-1, true);
		out.time(0);
		out.start("level");
		out.text("INFO");
		out.numberField("threadId", 42);
		out.start("message");
		out.message("value {}", new Object[] { "\"\n\u001b[8m" }, 1);
		String record = out.finish();
		assertEquals("""
				{"time":"1970-01-01T00:00:00.000Z","level":"INFO","threadId":42,"message":"value \\\"\\n\\u001b[8m"}
				""", record);
		var parsed = parse(record);
		assertEquals(42L, parsed.get("threadId"));
		assertEquals("value \"\n\u001b[8m", parsed.get("message"));
	}

	@Test
	void everyUtf16UnitRoundTripsWithSafeEscaping() throws IOException {
		for (int i = 0; i <= 0xffff; i++) {
			String input = String.valueOf((char) i);
			var out = new RecordEncoder(-1, true);
			out.start("message");
			out.text(input);
			out.extra("kv.", input, "value");
			String record = out.finish();
			var parsed = parse(record);
			assertEquals(input, parsed.get("message"), "UTF-16 " + i);
			assertEquals("value", parsed.get("kv." + input), "Key UTF-16 " + i);
			for (int p = 0; p < record.length() - 1; p++) {
				char c = record.charAt(p);
				assertFalse(
						c < 32 || c >= 0x7f && c <= 0x9f || c == 0x2028 || c == 0x2029
								|| Character.getType(c) == Character.FORMAT || Character.isSurrogate(c),
						"Raw control " + i);
			}
		}
	}

	@Test
	void originalKeysRemainDistinctAndCannotReplaceMetadata() throws IOException {
		var out = new RecordEncoder(-1, true);
		out.start("level");
		out.text("INFO");
		for (String key : new String[] { "", "%", "a:", "a%3a", "a\t", "a\"\\", "😀", "level", "truncated" })
			out.eventValue(key, key);
		out.eventValue("empty", null);
		out.writeKeyValues();
		var parsed = parse(out.finish());
		assertEquals(11, parsed.size());
		assertEquals("INFO", parsed.get("level"));
		assertEquals("", parsed.get("kv."));
		assertEquals("a:", parsed.get("kv.a:"));
		assertEquals("a%3a", parsed.get("kv.a%3a"));
		assertEquals("", parsed.get("kv.empty"));
		assertFalse(parsed.containsKey("truncated"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "x", "\"", "\\", "\u0000", "\u001b", "\u009b", "\u2028", "\ud800", "😀", "\udb40\udc20" })
	void clippedValuesAlwaysLeaveCompleteEscapesAndValidJson(String ending) throws IOException {
		for (int size = RecordEncoder.VALUE - 16; size <= RecordEncoder.VALUE + 1; size++) {
			var out = new RecordEncoder(-1, true);
			out.start("message");
			out.text("x".repeat(size) + ending);
			String record = out.finish();
			assertTrue(record.length() <= RecordEncoder.RECORD);
			var parsed = parse(record);
			assertTrue(parsed.containsKey("message"));
			String value = (String) parsed.get("message");
			assertTrue(value.equals("x".repeat(Math.min(size, RecordEncoder.VALUE)))
					|| value.equals("x".repeat(size) + ending));
			assertEquals(1, record.chars().filter(c -> c == '\n').count());
		}
	}

	@Test
	void recordLimitsNeverLeavePartialMembersOrNumericFields() {
		assertTimeout(Duration.ofSeconds(3), () -> {
			for (int width = 1; width <= 32; width++) {
				var out = new RecordEncoder(-1, true);
				var names = new java.util.HashSet<String>();
				for (int i = 0; i < 64; i++) {
					String key = i + "\u0000\"\\".repeat(width);
					names.add("kv." + key);
					out.extra("kv.", key, "x".repeat(4096));
				}
				out.numberField("threadId", Long.MIN_VALUE);
				out.start("last");
				out.text("\"".repeat(1_000_000));
				String record = out.finish();
				assertTrue(record.length() <= RecordEncoder.RECORD);
				var parsed = parse(record);
				assertEquals(Boolean.TRUE, parsed.get("truncated"));
				assertTrue(parsed.keySet().stream().filter(key -> key.startsWith("kv.")).allMatch(names::contains));
			}
		});
	}

	@Test
	void numericFieldsFitCompletelyOrAreOmitted() throws IOException {
		int included = 0, omitted = 0;
		for (int width = 3900; width <= 4100; width++) {
			var out = new RecordEncoder(-1, true);
			for (int i = 0; i < 3; i++) {
				out.start("m" + i);
				out.text("x".repeat(4096));
			}
			out.start("message");
			out.text("x".repeat(width));
			out.numberField("threadId", Long.MIN_VALUE);
			var parsed = parse(out.finish());
			if (parsed.containsKey("threadId")) {
				included++;
				assertEquals(Long.MIN_VALUE, parsed.get("threadId"));
			}
			else {
				omitted++;
				assertEquals(Boolean.TRUE, parsed.get("truncated"));
			}
		}
		assertTrue(included > 0 && omitted > 0);
	}

	@Test
	void rejectedKeysAndValuesAreNotEvaluated() throws IOException {
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		var hostile = new Object() {
			@Override
			public String toString() {
				calls.incrementAndGet();
				return "Rejected value evaluated";
			}
		};
		var out = new RecordEncoder(-1, true);
		out.extra("kv.", "x".repeat(1_000_000), hostile);
		assertEquals(Map.of("truncated", Boolean.TRUE), parse(out.finish()));
		out = new RecordEncoder(-1, true);
		for (int i = 0; i < 4; i++) {
			out.start("m" + i);
			out.text("x".repeat(4096));
		}
		out.extra("kv.", "\u0000".repeat(256), hostile);
		out.throwable(new IllegalStateException() {
			@Override
			public String getMessage() {
				calls.incrementAndGet();
				return "Rejected throwable evaluated";
			}
		});
		var parsed = parse(out.finish());
		assertFalse(parsed.containsKey("kv." + "\u0000".repeat(256)));
		assertEquals(Boolean.TRUE, parsed.get("truncated"));
		assertEquals(0, calls.get());
	}

	@Test
	void throwableCyclesStayInsideOneString() throws IOException {
		var first = new IllegalStateException("\"\n\u009b8m");
		var second = new IllegalArgumentException("second");
		first.initCause(second);
		second.initCause(first);
		first.addSuppressed(second);
		var out = new RecordEncoder(-1, true);
		out.start("error");
		out.throwable(first);
		String record = out.finish();
		assertTrue(record.length() <= RecordEncoder.RECORD);
		var parsed = parse(record);
		assertTrue(((String) parsed.get("error")).startsWith("java.lang.IllegalStateException: \"\n\u009b8m"));
		assertEquals(Boolean.TRUE, parsed.get("truncated"));
	}

}
