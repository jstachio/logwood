package io.jstach.logwood;

import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.HashSet;
import java.text.MessageFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import io.jstach.logwood.spi.LogwoodService.KeyValuesProvider;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.helpers.MessageFormatter;

class RecordEncoderTest {

	private static String value(String input) {
		var out = new RecordEncoder(-1, false);
		out.start("m");
		out.text(input);
		String record = out.finish();
		return record.substring(2, record.length() - 1);
	}

	static String decode(String input) {
		var out = new StringBuilder();
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c != '\\') {
				out.append(c);
				continue;
			}
			c = input.charAt(++i);
			switch (c) {
				case '\\' -> out.append('\\');
				case 'n' -> out.append('\n');
				case 'r' -> out.append('\r');
				case 't' -> out.append('\t');
				case 'u' -> {
					out.append((char) Integer.parseInt(input.substring(i + 1, i + 5), 16));
					i += 4;
				}
				default -> fail("Unknown escape: " + c);
			}
		}
		return out.toString();
	}

	@ParameterizedTest
	@CsvSource({ "0, 1970-01-01T00:00:00.000Z", "1, 1970-01-01T00:00:00.001Z", "-1, 1969-12-31T23:59:59.999Z",
			"-1000, 1969-12-31T23:59:59.000Z", "951782400123, 2000-02-29T00:00:00.123Z",
			"9223372036854775807, +292278994-08-17T07:12:55.807Z",
			"-9223372036854775808, -292275055-05-16T16:47:04.192Z" })
	void timeIsUtcWithExactlyMillisecondPrecisionAcrossTheEpochRange(long epochMillis, String expected) {
		var out = new RecordEncoder(-1, false);
		out.time(epochMillis);
		assertEquals("""
				time:%s
				""".formatted(expected), out.finish());
	}

	@Test
	void everyUtf16UnitRoundTripsWithoutRawControlsOrMalformedUnicode() {
		for (int i = 0; i <= 0xffff; i++) {
			String input = String.valueOf((char) i), escaped = value(input);
			assertEquals(input, decode(escaped), "UTF-16 " + i);
			for (int p = 0; p < escaped.length(); p++) {
				char c = escaped.charAt(p);
				assertFalse(
						c < 32 || c >= 0x7f && c <= 0x9f || c == 0x2028 || c == 0x2029
								|| Character.getType(c) == Character.FORMAT || Character.isSurrogate(c),
						"Raw control " + i);
			}
		}
		assertEquals("a😀é", decode(value("a😀é")));
		assertEquals("\\udb40\\udc20", value(new String(Character.toChars(0xe0020))));
	}

	@ParameterizedTest
	@ValueSource(strings = { "text", "message", "system", "object", "scalar" })
	void nullableDataInputsAreRenderedSafely(String kind) {
		var out = new RecordEncoder(-1, false);
		out.start("message");
		switch (kind) {
			case "text" -> out.text(null);
			case "message" -> out.message(null, null, 0);
			case "system" -> out.system(null, null);
			case "object" -> out.object(null, 0);
			case "scalar" -> out.scalar(null);
			default -> fail(kind);
		}
		assertEquals("""
				message:null
				""", out.finish());
	}

	@Test
	void nullArgumentElementsAndContextValuesKeepTheirMeaning() {
		var out = new RecordEncoder(-1, false);
		out.start("slf4j");
		out.message("value {}", new Object[] { null }, 1);
		out.start("system");
		out.system("value {0}", new Object[] { null });
		out.keyValues(new KeyValuesProvider() {
			@Override
			public void keyValues(java.util.function.BiConsumer<@Nullable String, @Nullable String> consumer) {
				consumer.accept("empty", null);
				consumer.accept(null, "ignored");
			}
		});
		out.writeKeyValues();
		assertEquals("""
				slf4j:value null\tsystem:value null\tempty:\ttruncated:true
				""", out.finish());
	}

	@Test
	void throwableCauseIsReadOncePerNode() {
		var reads = new java.util.concurrent.atomic.AtomicInteger();
		var cause = new IllegalArgumentException("cause");
		cause.setStackTrace(new StackTraceElement[0]);
		var error = new IllegalStateException("outer") {
			@Override
			public synchronized Throwable getCause() {
				if (reads.incrementAndGet() > 1)
					throw new AssertionError("cause read twice");
				return cause;
			}
		};
		error.setStackTrace(new StackTraceElement[0]);
		var out = new RecordEncoder(-1, false);
		out.start("error");
		out.throwable(error);
		String record = out.finish();
		assertEquals(1, reads.get());
		assertTrue(record.contains("Caused by: java.lang.IllegalArgumentException: cause"));
		assertFalse(record.contains("throwable failed"));
	}

	@Test
	void keysAreInjectiveAndCannotCreateFields() {
		var labels = new HashSet<String>();
		for (int i = 0; i <= 0xffff; i++) {
			var out = new RecordEncoder(-1, false);
			out.extra(String.valueOf((char) i), "v");
			String record = out.finish();
			assertEquals(1, record.chars().filter(c -> c == ':').count());
			assertFalse(record.contains("\t"));
			String label = record.substring(0, record.indexOf(':'));
			assertTrue(label.matches("[a-zA-Z0-9_.%\\-]+"));
			assertTrue(labels.add(label), "Key collision " + i);
		}
		var out = new RecordEncoder(-1, false);
		out.extra("", "");
		assertTrue(labels.add(out.finish().split(":", 2)[0]));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0x890, 0x891, 0x13439, 0x1343a, 0x1343b, 0x1343c, 0x1343d, 0x1343e, 0x1343f })
	void unicode17FormatControlsAreEscaped(int cp) {
		String input = new String(Character.toChars(cp)), escaped = value(input);
		assertTrue(escaped.chars().allMatch(c -> c < 128));
		assertEquals(input, decode(escaped));
	}

	@Test
	void literalEscapesRemainDistinctAndSeparatorsAreAlwaysPresent() {
		assertNotEquals(value("\n"), value("\\n"));
		var out = new RecordEncoder(6, false);
		out.start("a");
		out.text(">");
		out.start("b");
		out.text("x");
		assertEquals("<6>a:>\tb:x\n", out.finish());
	}

	@ParameterizedTest
	@ValueSource(strings = { "plain", "{}", "{} {} {} {}", "\\{} {}", "\\\\{} {}", "\\\\\\{} {}", "prefix {} suffix",
			"Set {1,2,3} {}", "{}\\{}{}" })
	void slf4jSubstitutionMatchesItsReferenceFormatter(String pattern) {
		Object[] args = { "A", new int[] { 1, 2 }, "C" };
		var out = new RecordEncoder(-1, false);
		out.start("m");
		out.message(pattern, args, args.length);
		assertEquals(MessageFormatter.basicArrayFormat(pattern, args),
				decode(out.finish().substring(2).stripTrailing()), pattern);
	}

	@ParameterizedTest
	@ValueSource(strings = { "hello {1}", "'{0}' {1}", "''{0}", "{0,number,integer}",
			"{0,choice,0#none|1#one|1<many {1}}", "{4}" })
	void systemFormattingMatchesMessageFormatWithinSafetyLimits(String pattern) {
		Object[] args = { 2, "world" };
		var out = new RecordEncoder(-1, false);
		out.start("m");
		out.system(pattern, args);
		assertEquals(MessageFormat.format(pattern, args), decode(out.finish().substring(2).stripTrailing()), pattern);
	}

	@Test
	void systemFormattingLeavesUnindexedQuotesAlone() {
		var out = new RecordEncoder(-1, false);
		out.start("m");
		out.system("don't touch quotes", new Object[] { 2, "world" });
		assertEquals("m:don't touch quotes\n", out.finish());
	}

	static final class BadToString {

		@Override
		public String toString() {
			throw new RuntimeException("expected");
		}

	}

	record Throws(Throwable thrown) {

		@Override
		public String toString() {
			if (thrown instanceof Error error)
				throw error;
			throw (RuntimeException) thrown;
		}

	}

	static final class Recursive {

		@Override
		public String toString() {
			return "x" + this;
		}

	}

	// Rainbow Gum's MessageFormatStyleTest cases and its default, JDK style, results.
	static Stream<Arguments> rainbowGumSystemCases() {
		var boom = new RuntimeException("boom");
		return Stream.of(Arguments.of("It's done", "It's done", new Object[] { "x" }),
				Arguments.of("It''s {0}", "It's x", new Object[] { "x" }),
				Arguments.of("It's {0}", "Its {0}", new Object[] { "x" }),
				Arguments.of("It's {0}", "It's {0}", new Object[] {}),
				Arguments.of("unmatched {", "unmatched {", new Object[] { "x" }),
				Arguments.of("unmatched {0", "unmatched {0 [MessageFormat failed: Unmatched braces in the pattern.]",
						new Object[] { "x" }),
				Arguments.of("{a} and {0}", "{a} and {0} [MessageFormat failed: can't parse argument number: a]",
						new Object[] { "x" }),
				Arguments.of("value {0}", "value {0} [MessageFormat failed: expected]",
						new Object[] { new BadToString() }),
				Arguments.of("value {0}", "value {0} [MessageFormat failed: assert]",
						new Object[] { new Throws(new AssertionError("assert")) }),
				Arguments.of("value {0}", "value {0} [MessageFormat failed: java.lang.StackOverflowError]",
						new Object[] { new Recursive() }),
				Arguments.of("value {0}", "value {0} [MessageFormat failed: java.lang.IllegalStateException]",
						new Object[] { new Throws(new IllegalStateException()) }),
				Arguments.of("a={0} b={1}", "a=X b={}", new Object[] { "X", "{}" }),
				Arguments.of("{0} {1} {2}", "a b c", new Object[] { "a", "b", "c" }),
				Arguments.of("failed {0}", "failed java.lang.RuntimeException: boom", new Object[] { boom }),
				Arguments.of("user {0} failed {1}", "user bob failed java.lang.RuntimeException: boom",
						new Object[] { "bob", boom }),
				Arguments.of("{0} {1} failed {2}", "a b failed java.lang.RuntimeException: boom",
						new Object[] { "a", "b", boom }));
	}

	@ParameterizedTest
	@MethodSource("rainbowGumSystemCases")
	void systemFormattingMatchesRainbowGum(String pattern, String expected, Object[] args) {
		var out = new RecordEncoder(-1, false);
		out.start("m");
		out.system(pattern, args);
		assertEquals("m:" + expected + "\n", out.finish());
	}

	@Test
	void systemFailingReasonFallsBackToTheClassName() {
		var thrown = new IllegalStateException() {
			@Override
			public String getMessage() {
				throw new IllegalStateException("message");
			}
		};
		var out = new RecordEncoder(-1, false);
		out.start("m");
		out.system("value {0}", new Object[] { new Throws(thrown) });
		assertEquals("m:value {0} [MessageFormat failed: " + thrown.getClass().getName() + "]\n", out.finish());
	}

	@Test
	void multilineTextCannotInjectRecordsOrFields() {
		String input = """
				first\tfield
				second\rline\u001b[8m
				""";
		var out = new RecordEncoder(-1, false);
		out.start("message");
		out.text(input);
		assertEquals("""
				message:first\\tfield\\nsecond\\rline\\u001b[8m\\n
				""", out.finish());
	}

	@Test
	void keyValueCallbacksAreBoundedAndTheirConsumerExpiresAfterTheCall() {
		var retained = new java.util.concurrent.atomic.AtomicReference<java.util.function.BiConsumer<@Nullable String, @Nullable String>>();
		var config = new KeyValuesProvider() {
			@Override
			public void keyValues(java.util.function.BiConsumer<@Nullable String, @Nullable String> consumer) {
				retained.set(consumer);
				for (int i = 0; i < 1000; i++)
					consumer.accept("key" + i, "value");
			}
		};
		var out = new RecordEncoder(-1, false);
		out.keyValues(config);
		retained.get().accept("late", "ignored");
		out.writeKeyValues();
		String record = out.finish();
		assertEquals(33, record.stripTrailing().split("\t").length);
		assertTrue(record.endsWith("\ttruncated:true\n"));
		assertFalse(record.contains("late"));
		assertFalse(record.contains("key32:"));
	}

	@Test
	void eventValuesOverrideContextAndDuplicateEventKeysWithoutRenderingDiscardedObjects() {
		var out = new RecordEncoder(-1, false);
		out.keyValues(new KeyValuesProvider() {
			@Override
			public void keyValues(java.util.function.BiConsumer<@Nullable String, @Nullable String> consumer) {
				consumer.accept("request", "outer");
				consumer.accept("request", "inner");
			}
		});
		out.eventValue("request", new Object() {
			@Override
			public String toString() {
				throw new AssertionError("discarded value evaluated");
			}
		});
		out.eventValue("request", "event");
		out.writeKeyValues();
		assertEquals("""
				request:event
				""", out.finish());
	}

	@Test
	void mergedContextAndEventFieldsHaveFixedBudgetsAndIgnoreInvalidKeyValues() {
		var out = new RecordEncoder(-1, false);
		out.keyValues(new KeyValuesProvider() {
			@Override
			public void keyValues(java.util.function.BiConsumer<@Nullable String, @Nullable String> consumer) {
				for (int i = 0; i < 32; i++)
					consumer.accept("context" + i, "value");
			}
		});
		for (int i = 0; i < 32; i++)
			out.eventValue("event" + i, "value");
		var hostile = new Object() {
			@Override
			public String toString() {
				throw new AssertionError("rejected field evaluated");
			}
		};
		out.eventValue("overflow", hostile);
		out.eventValue("x".repeat(1_000_000), hostile);
		out.writeKeyValues();
		String record = out.finish();
		assertEquals(65, record.stripTrailing().split("\t").length);
		assertTrue(record.contains("context31:value"));
		assertTrue(record.contains("event31:value"));
		assertFalse(record.contains("overflow"));
		assertFalse(record.contains("toString failed"));
		assertTrue(record.endsWith("\ttruncated:true\n"));
	}

	@Test
	void multipleProvidersShareTheContextBudgetWhileAllowingOverrides() {
		var out = new RecordEncoder(-1, false);
		out.keyValues(new KeyValuesProvider() {
			@Override
			public void keyValues(java.util.function.BiConsumer<@Nullable String, @Nullable String> consumer) {
				for (int i = 0; i < 32; i++)
					consumer.accept("key" + i, "original");
			}
		});
		out.keyValues(new KeyValuesProvider() {
			@Override
			public void keyValues(java.util.function.BiConsumer<@Nullable String, @Nullable String> consumer) {
				consumer.accept("key0", "override");
				consumer.accept("overflow", "ignored");
			}
		});
		out.writeKeyValues();
		String record = out.finish();
		assertEquals(33, record.stripTrailing().split("\t").length);
		assertTrue(record.startsWith("key0:override\t"));
		assertFalse(record.contains("overflow"));
		assertTrue(record.endsWith("\ttruncated:true\n"));
	}

	@Test
	void limitsBoundDenseInputArrayCyclesAndThrowableCycles() {
		assertTimeout(Duration.ofSeconds(2), () -> {
			var out = new RecordEncoder(-1, false);
			out.start("m");
			out.text("\u001b".repeat(1_000_000));
			String record = out.finish();
			assertTrue(record.length() <= RecordEncoder.RECORD);
			assertTrue(record.endsWith("\ttruncated:true\n"));
			Object[] array = new Object[1];
			array[0] = array;
			out = new RecordEncoder(-1, false);
			out.start("m");
			out.object(array, 0);
			assertTrue(out.finish().contains("truncated:true"));
			var a = new IllegalStateException("a");
			var b = new IllegalStateException("b");
			a.initCause(b);
			b.initCause(a);
			a.addSuppressed(b);
			b.addSuppressed(a);
			out = new RecordEncoder(-1, false);
			out.start("error");
			out.throwable(a);
			record = out.finish();
			assertTrue(record.length() <= RecordEncoder.RECORD);
			assertTrue(record.endsWith("\ttruncated:true\n"));
		});
	}

	@Test
	void hostileValuesAndOversizedFormatIndicesAreContained() {
		var out = new RecordEncoder(-1, false);
		out.start("m");
		out.object(new Object() {
			@Override
			public String toString() {
				throw new AssertionError("private secret");
			}
		}, 0);
		assertEquals("m:[toString failed]\n", out.finish());
		out = new RecordEncoder(-1, false);
		out.start("m");
		out.system("{2147483647}", new Object[] { 1 });
		assertTrue(out.finish().endsWith(" [format rejected]\ttruncated:true\n"));
		out = new RecordEncoder(-1, false);
		for (int i = 0; i < 1000; i++) {
			out.start("x" + i);
			out.text("x".repeat(4096));
		}
		assertTrue(out.finish().length() <= RecordEncoder.RECORD);
	}

}
