package io.jstach.logwood;

import static io.jstach.logwood.RecordEncoderTest.decode;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.util.Date;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/*
 * Each documented limit from SECURITY.md, exactly at the limit and one past it.
 */
class RecordEncoderLimitTest {

	// RecordEncoder.CONTENT: the record limit less room for the marker and LF.
	private static final int CONTENT = RecordEncoder.RECORD - 32;

	private static final String MARKER = "\ttruncated:true\n";

	private static String ltsv(Consumer<RecordEncoder> body) {
		var out = new RecordEncoder(-1, false);
		body.accept(out);
		return out.finish();
	}

	private static String value(String input) {
		return ltsv(out -> {
			out.start("m");
			out.text(input);
		});
	}

	private static String x(int count) {
		return "x".repeat(count);
	}

	private static int length(RecordEncoder out) {
		try {
			var field = RecordEncoder.class.getDeclaredField("out");
			field.setAccessible(true);
			return ((StringBuilder) field.get(out)).length();
		}
		catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	// Complete fields of x until exactly target units are written.
	private static RecordEncoder filled(boolean json, int target) {
		var out = new RecordEncoder(-1, json);
		while (length(out) < target) {
			out.start("f");
			int room = target - length(out), count = Math.min(room, RecordEncoder.VALUE);
			if (room - count > 0 && room - count < 8)
				count -= 8;
			out.text(x(count));
		}
		assertEquals(target, length(out));
		assertFalse(out.truncated);
		return out;
	}

	@Test
	void valueFillsItsLimitExactly() {
		assertEquals("m:" + x(4096) + "\n", value(x(4096)));
		assertEquals("m:" + x(4096) + MARKER, value(x(4097)));
	}

	@ParameterizedTest
	@ValueSource(strings = { "\\", "\t", "\n", "\r" })
	void twoUnitEscapesAreNeverSplitAtTheValueLimit(String c) {
		String escaped = value(c).substring(2, 4);
		assertEquals("m:" + x(4094) + escaped + "\n", value(x(4094) + c));
		assertEquals("m:" + x(4095) + MARKER, value(x(4095) + c));
	}

	@Test
	void unicodeEscapesAreNeverSplitAtTheValueLimit() {
		assertEquals("m:" + x(4090) + "\\u0001\n", value(x(4090) + "\u0001"));
		assertEquals("m:" + x(4091) + MARKER, value(x(4091) + "\u0001"));
	}

	@Test
	void surrogatePairsAreNeverSplitAtTheValueLimit() {
		assertEquals("m:" + x(4094) + "😀\n", value(x(4094) + "😀"));
		assertEquals("m:" + x(4095) + MARKER, value(x(4095) + "😀"));
		String tag = new String(Character.toChars(0xe0020));
		assertEquals("m:" + x(4084) + "\\udb40\\udc20\n", value(x(4084) + tag));
		assertEquals("m:" + x(4085) + MARKER, value(x(4085) + tag));
	}

	@Test
	void jsonQuoteEscapesAreNeverSplitAtTheValueLimit() {
		var out = new RecordEncoder(-1, true);
		out.start("m");
		out.text(x(4094) + "\"");
		assertEquals("{\"m\":\"" + x(4094) + "\\\"\"}\n", out.finish());
		out = new RecordEncoder(-1, true);
		out.start("m");
		out.text(x(4095) + "\"");
		assertEquals("{\"m\":\"" + x(4095) + "\",\"truncated\":true}\n", out.finish());
	}

	// These fixtures contain three full values and one partial value. Keep the
	// expected prefix independent of filled(), which exercises the real encoder.
	private static String ltsvPrefix(int size) {
		return ("f:" + x(4096) + "\t").repeat(3) + "f:" + x(size - 12299);
	}

	private static String jsonPrefix(int size) {
		return "{" + ("\"f\":\"" + x(4096) + "\",").repeat(3) + "\"f\":\"" + x(size - 12315);
	}

	@Test
	void ltsvFieldStartsOnlyWhileItsLabelFits() {
		var out = filled(false, CONTENT - 3);
		out.start("b");
		out.text("v");
		String record = out.finish();
		assertEquals(ltsvPrefix(CONTENT - 3) + "\tb:" + MARKER, record);
		assertEquals(CONTENT + MARKER.length(), record.length());

		out = filled(false, CONTENT - 2);
		out.start("b");
		record = out.finish();
		assertEquals(ltsvPrefix(CONTENT - 2) + MARKER, record);
		assertEquals(CONTENT - 2 + MARKER.length(), record.length());
	}

	@Test
	void jsonFieldStartsOnlyWhileItsLabelFits() {
		var out = filled(true, CONTENT - 7);
		out.start("b");
		out.text("v");
		assertEquals(jsonPrefix(CONTENT - 7) + "\",\"b\":\"\",\"truncated\":true}\n", out.finish());

		out = filled(true, CONTENT - 6);
		out.start("b");
		assertEquals(jsonPrefix(CONTENT - 6) + "\",\"truncated\":true}\n", out.finish());
	}

	@Test
	void jsonMemberNamesAreWrittenWholeOrNotAtAll() {
		// Ten U+0001 become sixty units, then ":" and the opening quote.
		String key = "\u0001".repeat(10), name = "\\u0001".repeat(10);
		var out = filled(true, CONTENT - 66);
		out.extra(key, "v");
		assertEquals(jsonPrefix(CONTENT - 66) + "\",\"" + name + "\":\"\",\"truncated\":true}\n", out.finish());

		out = filled(true, CONTENT - 65);
		out.extra(key, "v");
		String record = out.finish();
		assertEquals(jsonPrefix(CONTENT - 65) + "\",\"truncated\":true}\n", record);
	}

	@Test
	void numberFieldsReserveRoomForTheLongestLong() {
		var out = filled(false, CONTENT - 23);
		out.numberField("n", Long.MIN_VALUE);
		assertEquals(ltsvPrefix(CONTENT - 23) + "\tn:-9223372036854775808\n", out.finish());

		out = filled(false, CONTENT - 22);
		out.numberField("n", Long.MIN_VALUE);
		assertEquals(ltsvPrefix(CONTENT - 22) + MARKER, out.finish());
	}

	@Test
	void numbersAreClippedLikeTextNearTheValueLimit() {
		assertEquals("m:" + x(4080) + "-922337203685477" + MARKER, ltsv(out -> {
			out.start("m");
			out.text(x(4080));
			out.number(Long.MIN_VALUE);
		}));
		assertEquals("m:" + x(4080) + "123\n", ltsv(out -> {
			out.start("m");
			out.text(x(4080));
			out.number(123);
		}));
	}

	@Test
	void journalPriorityZeroIsWritten() {
		var out = new RecordEncoder(0, false);
		out.start("a");
		out.text("x");
		assertEquals("<0>a:x\n", out.finish());
	}

	@Test
	void jsonTruncationMarkerStandsAloneWithoutFields() {
		var out = new RecordEncoder(-1, true);
		out.extra(null, "v");
		assertEquals("{\"truncated\":true}\n", out.finish());
	}

	@Test
	void slf4jPatternScanStopsAtTheValueLimit() {
		Object[] args = { "A" };
		assertEquals("m:" + x(4094) + "A\n", ltsv(out -> {
			out.start("m");
			out.message(x(4094) + "{}", args, 1);
		}));
		assertEquals("m:" + x(4095) + "{" + MARKER, ltsv(out -> {
			out.start("m");
			out.message(x(4095) + "{}", args, 1);
		}));
	}

	@Test
	void slf4jInterpolatesAtMostSixtyFourArguments() {
		Object[] args = new Object[65];
		java.util.Arrays.fill(args, "a");
		assertEquals("m:" + "a".repeat(64) + "\n", ltsv(out -> {
			out.start("m");
			out.message("{}".repeat(64), args, 64);
		}));
		assertEquals("m:" + "a".repeat(64) + "\n", ltsv(out -> {
			out.start("m");
			out.message("{}".repeat(64), args, 65);
		}));
		assertEquals("m:" + "a".repeat(64) + "{}" + MARKER, ltsv(out -> {
			out.start("m");
			out.message("{}".repeat(65), args, 65);
		}));
	}

	@Test
	void arraysNestEightDeepAndListThirtyTwoElements() {
		Object nested = "x";
		for (int i = 0; i < 8; i++)
			nested = new Object[] { nested };
		Object eight = nested, nine = new Object[] { nested };
		assertEquals("m:[[[[[[[[x]]]]]]]]\n", ltsv(out -> {
			out.start("m");
			out.object(eight, 0);
		}));
		assertEquals("m:[[[[[[[[[...]]]]]]]]]" + MARKER, ltsv(out -> {
			out.start("m");
			out.object(nine, 0);
		}));

		Object[] full = new Object[32], over = new Object[33];
		java.util.Arrays.fill(full, 1);
		java.util.Arrays.fill(over, 1);
		String listed = "[" + String.join(", ", java.util.Collections.nCopies(32, "1"));
		assertEquals("m:" + listed + "]\n", ltsv(out -> {
			out.start("m");
			out.object(full, 0);
		}));
		assertEquals("m:" + listed + ", ...]" + MARKER, ltsv(out -> {
			out.start("m");
			out.object(over, 0);
		}));
	}

	private static String system(String pattern, Object... args) {
		return ltsv(out -> {
			out.start("m");
			out.system(pattern, args);
		});
	}

	private static String formatted(String pattern, Object... args) {
		String record = system(pattern, args);
		return decode(record.substring(2, record.length() - 1));
	}

	@Test
	void systemPatternsAreFormattedUpTo1024Units() {
		assertEquals("A" + x(1021), formatted("{0}" + x(1021), "A"));
		assertEquals("m:{0}" + x(1022) + MARKER, system("{0}" + x(1022), "A"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "a''b {/", "a''b {:", "a''b 1", "a''b {" })
	void onlyBracesFollowedByDigitsMakeAPatternIndexed(String pattern) {
		assertEquals("m:" + pattern + "\n", system(pattern, "A"));
	}

	@Test
	void systemPatternsWithoutArgumentsAreNotFormatted() {
		assertEquals("m:it''s {0}\n", system("it''s {0}"));
		assertEquals("m:it''s {0}\n", ltsv(out -> {
			out.start("m");
			out.system("it''s {0}", null);
		}));
	}

	private static final Object HOSTILE = new Object() {
		@Override
		public String toString() {
			throw new AssertionError("unreferenced argument read");
		}
	};

	@ParameterizedTest
	@ValueSource(strings = { "'{0}' {1}", "'it''s {0}' {1}", "'a' '{0}' {1}", "{1} '{0}" })
	void quotedArgumentsAreNeverRead(String pattern) {
		assertEquals(MessageFormat.format(pattern, HOSTILE, "B"), formatted(pattern, HOSTILE, "B"));
	}

	@Test
	void multiDigitIndexesReadOnlyTheirArgument() {
		Object[] args = new Object[11];
		java.util.Arrays.fill(args, HOSTILE);
		args[10] = "K";
		assertEquals("K", formatted("{10}", args));
	}

	@Test
	void indexedPatternsUseMessageFormatQuoting() {
		assertEquals("a'b A", formatted("a''b {0}", "A"));
		assertEquals("A", formatted("{9}", 0, 1, 2, 3, 4, 5, 6, 7, 8, "A"));
	}

	@Test
	void systemArgumentIndexesStopAtThirtyOne() {
		Object[] args = new Object[33];
		for (int i = 0; i < args.length; i++)
			args[i] = i;
		assertEquals("31", formatted("{31}", args));
		assertEquals("m:{32} [format rejected]" + MARKER, system("{32}", args));
	}

	@Test
	void systemPatternsHaveAtMostSixtyFourFormatElements() {
		assertEquals("A".repeat(64), formatted("{0}".repeat(64), "A"));
		assertEquals("m:" + "{0}".repeat(65) + " [format rejected]" + MARKER, system("{0}".repeat(65), "A"));
	}

	private static String choices(int depth) {
		String pattern = "{0}";
		for (int i = 1; i < depth; i++)
			pattern = "{0,choice,0#" + pattern + "}";
		return pattern;
	}

	@Test
	void systemPatternsNestBracesEightDeep() {
		// MessageFormat's support for nested choices varies by JDK. Compare the
		// complete accepted result or diagnostic against that JDK's formatter.
		String expected;
		try {
			expected = "m:" + MessageFormat.format(choices(8), 0) + "\n";
		}
		catch (IllegalArgumentException e) {
			expected = "m:" + choices(8) + " [MessageFormat failed: " + e.getMessage() + "]\n";
		}
		assertEquals(expected, system(choices(8), 0));
		assertEquals("""
				m:%s [format rejected]\ttruncated:true
				""".formatted(choices(9)), system(choices(9), 0));
	}

	@ParameterizedTest
	@ValueSource(strings = { "byte", "short", "int", "long", "float", "double", "BigInteger", "BigDecimal" })
	void systemNumbersKeepNumberFormats(String type) {
		Object number = switch (type) {
			case "byte" -> (byte) 2;
			case "short" -> (short) 2;
			case "int" -> 2;
			case "long" -> 2L;
			case "float" -> 2f;
			case "double" -> 2d;
			case "BigInteger" -> BigInteger.TWO;
			case "BigDecimal" -> BigDecimal.valueOf(2);
			default -> throw new AssertionError(type);
		};
		assertEquals("2.0", formatted("{0,number,0.0}", number));
	}

	@Test
	void systemDatesKeepDateFormats() {
		var date = new Date(86_400_000L);
		assertEquals(MessageFormat.format("{0,date,yyyy-MM-dd}", date), formatted("{0,date,yyyy-MM-dd}", date));
	}

	@Test
	void systemBigNumbersAreFormattedOnlyWithinBounds() {
		var big = BigInteger.ONE.shiftLeft(4095);
		assertEquals(MessageFormat.format("{0,number,#}", big), formatted("{0,number,#}", big));
		assertEquals("m:{0,number,#} [MessageFormat failed: Cannot format given Object as a Number]\n",
				system("{0,number,#}", big.shiftLeft(1)));

		var scaled = new BigDecimal(BigInteger.ONE, 4096);
		assertEquals("0", formatted("{0,number,#}", scaled));
		assertEquals("m:{0,number,#} [MessageFormat failed: Cannot format given Object as a Number]\n",
				system("{0,number,#}", new BigDecimal(BigInteger.ONE, 4097)));
		assertEquals("m:{0,number,#} [MessageFormat failed: Cannot format given Object as a Number]\n",
				system("{0,number,#}", new BigDecimal(BigInteger.ONE, -4097)));
		var wide = new BigDecimal(BigInteger.ONE.shiftLeft(4096), 0);
		assertEquals("m:{0,number,#} [MessageFormat failed: Cannot format given Object as a Number]\n",
				system("{0,number,#}", wide));
	}

	@Test
	void systemArgumentsNotInThePatternAreNeverRead() {
		var hostile = new Object() {
			@Override
			public String toString() {
				throw new AssertionError("unreferenced argument read");
			}
		};
		assertEquals("1", formatted("{0}", 1, hostile));
	}

	@Test
	void ltsvKeysAreLimitedTo256RawAndEncodedUnits() {
		assertEquals(x(256) + ":v\n", ltsv(out -> out.extra(x(256), "v")));
		assertEquals("truncated:true\n", ltsv(out -> out.extra(x(257), "v")).substring(1));
		assertEquals(x(250) + "%c3%a9:v\n", ltsv(out -> out.extra(x(250) + "é", "v")));
		assertEquals("truncated:true\n", ltsv(out -> out.extra(x(251) + "é", "v")).substring(1));
	}

	@Test
	void storedKeysAreLimitedTo256Units() {
		assertEquals(x(256) + ":v\n", ltsv(out -> {
			out.eventValue(x(256), "v");
			out.writeKeyValues();
		}));
		assertEquals("truncated:true\n", ltsv(out -> {
			out.eventValue(x(257), "v");
			out.writeKeyValues();
		}).substring(1));
	}

	@ParameterizedTest
	@ValueSource(strings = { "\u007f|%7f", "\u0080|%c2%80", "\u07ff|%df%bf", "\u0800|%e0%a0%80", "\uffff|%ef%bf%bf",
			"\ud800\udc00|%f0%90%80%80", "\udbff\udfff|%f4%8f%bf%bf", "😀|%f0%9f%98%80", "\ud83d|%ud83d",
			"\ud83dA|%ud83dA", "\ude00|%ude00", "\ude00\ude00|%ude00%ude00", "\ud7ff|%ed%9f%bf", "\ue000|%ee%80%80",
			"`{@[/:|%60%7b%40%5b%2f%3a", "azAZ09_.-|azAZ09_.-" })
	void ltsvLabelsPercentEncodeUtf8AndMarkLoneSurrogates(String example) {
		String key = example.substring(0, example.indexOf('|')), label = example.substring(example.indexOf('|') + 1);
		assertEquals(label + ":v\n", ltsv(out -> out.extra(key, "v")));
	}

	@Test
	void jsonKeyValuesWithoutValuesAreEmptyStrings() {
		var out = new RecordEncoder(-1, true);
		out.extra("k", null);
		assertEquals("{\"k\":\"\"}\n", out.finish());
	}

	private static Throwable error(String message, StackTraceElement... frames) {
		var error = new IllegalStateException(message);
		error.setStackTrace(frames);
		return error;
	}

	private static StackTraceElement[] frames(int count) {
		var frames = new StackTraceElement[count];
		for (int i = 0; i < count; i++)
			frames[i] = new StackTraceElement("a.B", "m" + i, "B.java", i + 1);
		return frames;
	}

	private static String trace(Throwable thrown) {
		return ltsv(out -> {
			out.start("error");
			out.throwable(thrown);
		});
	}

	private static String decodedTrace(Throwable thrown) {
		String record = trace(thrown);
		return decode(record.substring("error:".length(),
				record.indexOf('\t') < 0 ? record.length() - 1 : record.indexOf('\t')));
	}

	@Test
	void stackFramesShowTheirSource() {
		var thrown = error("boom", new StackTraceElement("a.B", "c", "B.java", 7),
				new StackTraceElement("a.B", "n", null, -2), new StackTraceElement("a.B", "u", null, -1),
				new StackTraceElement("a.B", "f", "B.java", -1), new StackTraceElement("a.B", "z", "B.java", 0));
		assertEquals("""
				java.lang.IllegalStateException: boom
				\tat a.B.c(B.java:7)
				\tat a.B.n(Native Method)
				\tat a.B.u(Unknown Source)
				\tat a.B.f(B.java)
				\tat a.B.z(B.java:0)""", decodedTrace(thrown));
	}

	private static String expectedFrames(int count) {
		return java.util.stream.IntStream.range(0, count)
			.mapToObj(i -> "\\n\\tat a.B.m%d(B.java:%d)".formatted(i, i + 1))
			.collect(java.util.stream.Collectors.joining());
	}

	@ParameterizedTest
	@ValueSource(ints = { 64, 65 })
	void stackTracesShowAtMostSixtyFourFramesInTotal(int count) {
		assertEquals("""
				error:java.lang.IllegalStateException: boom%s%s
				""".formatted(expectedFrames(64), count == 64 ? "" : "\\n[frame limit]\ttruncated:true"),
				trace(error("boom", frames(count))));
	}

	@Test
	void causesShareTheFrameBudget() {
		var outer = error("outer", frames(60));
		outer.initCause(error("cause", frames(5)));
		assertEquals(
				"""
						error:java.lang.IllegalStateException: outer%s\\nCaused by: java.lang.IllegalStateException: cause%s\\n[frame limit]\ttruncated:true
						"""
					.formatted(expectedFrames(60), expectedFrames(4)),
				trace(outer));
	}

	@ParameterizedTest
	@ValueSource(ints = { 4, 5 })
	void eachThrowableShowsAtMostFourSuppressed(int count) {
		var outer = error("outer");
		for (int i = 0; i < count; i++)
			outer.addSuppressed(error("s" + i));
		assertEquals("""
				error:java.lang.IllegalStateException: outer\
				\\nSuppressed: java.lang.IllegalStateException: s0\
				\\nSuppressed: java.lang.IllegalStateException: s1\
				\\nSuppressed: java.lang.IllegalStateException: s2\
				\\nSuppressed: java.lang.IllegalStateException: s3%s
				""".formatted(count == 4 ? "" : "\ttruncated:true"), trace(outer));
	}

	private static Throwable chain(int length) {
		Throwable thrown = error("e" + (length - 1));
		for (int i = length - 2; i >= 0; i--) {
			var outer = error("e" + i);
			outer.initCause(thrown);
			thrown = outer;
		}
		return thrown;
	}

	@ParameterizedTest
	@ValueSource(ints = { 8, 9 })
	void throwableGraphsShowAtMostEightThrowables(int count) {
		assertEquals("""
				error:java.lang.IllegalStateException: e0\
				\\nCaused by: java.lang.IllegalStateException: e1\
				\\nCaused by: java.lang.IllegalStateException: e2\
				\\nCaused by: java.lang.IllegalStateException: e3\
				\\nCaused by: java.lang.IllegalStateException: e4\
				\\nCaused by: java.lang.IllegalStateException: e5\
				\\nCaused by: java.lang.IllegalStateException: e6\
				\\nCaused by: java.lang.IllegalStateException: e7%s
				""".formatted(count == 8 ? "" : "\\nCaused by: [throwable limit]\ttruncated:true"),
				trace(chain(count)));
	}

	@Test
	void suppressedPastTheThrowableLimitAreMarked() {
		var a = error("a");
		for (int i = 0; i < 4; i++)
			a.addSuppressed(error("a" + i));
		var b = error("b");
		b.addSuppressed(error("b0"));
		var outer = error("outer");
		outer.addSuppressed(a);
		outer.addSuppressed(b);
		String expected = """
				error:java.lang.IllegalStateException: outer\
				\\nSuppressed: java.lang.IllegalStateException: a\
				\\nSuppressed: java.lang.IllegalStateException: a0\
				\\nSuppressed: java.lang.IllegalStateException: a1\
				\\nSuppressed: java.lang.IllegalStateException: a2\
				\\nSuppressed: java.lang.IllegalStateException: a3\
				\\nSuppressed: java.lang.IllegalStateException: b\
				\\nSuppressed: java.lang.IllegalStateException: b0%s
				""";
		assertEquals(expected.formatted(""), trace(outer));
		outer.addSuppressed(error("c"));
		assertEquals(expected.formatted("\ttruncated:true"), trace(outer));
	}

	@Test
	void failingThrowableAccessorsAreReportedInPlace() {
		var thrown = new IllegalStateException("boom") {
			@Override
			public StackTraceElement[] getStackTrace() {
				throw new IllegalArgumentException("no frames");
			}
		};
		assertEquals("error:java.lang.IllegalStateException: boom [throwable failed]\n",
				trace(thrown).replace(thrown.getClass().getName(), "java.lang.IllegalStateException"));
	}

	@Test
	void jsonKeysAreLimitedTo256Units() {
		var out = new RecordEncoder(-1, true);
		out.extra(x(256), "v");
		assertEquals("{\"" + x(256) + "\":\"v\"}\n", out.finish());
		out = new RecordEncoder(-1, true);
		out.extra(x(257), "v");
		assertEquals("{\"truncated\":true}\n", out.finish());
	}

	@Test
	void slf4jNullPatternsAreWrittenAsNull() {
		assertEquals("m:null\n", ltsv(out -> {
			out.start("m");
			out.message(null, new Object[] { "A" }, 1);
		}));
	}

	private static Object fatal() {
		return new Object() {
			@Override
			public String toString() {
				throw new OutOfMemoryError("fatal");
			}
		};
	}

	@Test
	void fatalErrorsAreNeverSwallowed() {
		assertThrows(OutOfMemoryError.class, () -> ltsv(out -> {
			out.start("m");
			out.object(fatal(), 0);
		}));
		assertThrows(OutOfMemoryError.class, () -> system("{0}", fatal()));
		var thrown = new IllegalStateException("boom") {
			@Override
			public StackTraceElement[] getStackTrace() {
				throw new OutOfMemoryError("fatal");
			}
		};
		assertThrows(OutOfMemoryError.class, () -> trace(thrown));
	}

}
