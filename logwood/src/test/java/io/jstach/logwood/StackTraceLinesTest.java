package io.jstach.logwood;

import static io.jstach.logwood.RecordEncoderTest.decode;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.jstach.logwood.spi.LogwoodService.KeyValuesMode;

/*
 * logging.ltsv.stacktrace=lines: the record line is LTSV, and each stack trace line
 * follows on a line of its own after a tab.
 */
class StackTraceLinesTest {

	private static Throwable error(String message, int frames) {
		var error = new IllegalStateException(message);
		var stack = new StackTraceElement[frames];
		for (int i = 0; i < frames; i++)
			stack[i] = new StackTraceElement("a.B", "m" + i, "B.java", i + 1);
		error.setStackTrace(stack);
		return error;
	}

	private static String record(boolean lines, Throwable thrown) {
		var out = new RecordEncoder(-1, false, KeyValuesMode.FLAT, lines);
		out.start("message");
		out.text("failed");
		out.error(thrown);
		out.eventValue("request", "42");
		out.writeKeyValues();
		return out.finish();
	}

	private static Map<String, String> fields(String line) {
		var fields = new LinkedHashMap<String, String>();
		for (String field : line.split("\t", -1)) {
			int colon = field.indexOf(':');
			assertTrue(colon > 0, line);
			assertNull(fields.put(field.substring(0, colon), field.substring(colon + 1)), "duplicate label");
		}
		return fields;
	}

	/*
	 * The record line, and the error value with its continuation lines joined back.
	 */
	private static Map<String, String> read(String record) {
		assertTrue(record.endsWith("\n"));
		String[] lines = record.substring(0, record.length() - 1).split("\n", -1);
		assertFalse(lines[0].startsWith("\t"));
		var fields = fields(lines[0]);
		var error = new StringBuilder(decode(fields.get("error")));
		for (int i = 1; i < lines.length; i++) {
			assertTrue(lines[i].startsWith("\t"), lines[i]);
			error.append('\n').append(decode(lines[i].substring(1)));
		}
		fields.put("error", error.toString());
		return fields;
	}

	@Test
	void continuationLinesJoinBackToTheInlineValue() {
		var thrown = error("two\nlines\twith a tab", 3);
		thrown.addSuppressed(error("suppressed", 1));
		thrown.initCause(error("cause", 2));
		String inline = record(false, thrown), lines = record(true, thrown);

		assertEquals("""
				message:failed\terror:java.lang.IllegalStateException: two\\nlines\\twith a tab\trequest:42
				\t\tat a.B.m0(B.java:1)
				\t\tat a.B.m1(B.java:2)
				\t\tat a.B.m2(B.java:3)
				\tSuppressed: java.lang.IllegalStateException: suppressed
				\t\tat a.B.m0(B.java:1)
				\tCaused by: java.lang.IllegalStateException: cause
				\t\tat a.B.m0(B.java:1)
				\t\tat a.B.m1(B.java:2)
				""", lines);
		var expected = fields(inline.substring(0, inline.length() - 1));
		expected.put("error", decode(expected.get("error")));
		assertEquals(expected, read(lines));
	}

	@Test
	void aThrowableWithoutFramesStaysOnTheRecordLine() {
		assertEquals("""
				message:failed\terror:java.lang.IllegalStateException: none\trequest:42
				""", record(true, error("none", 0)));
	}

	@Test
	void truncationIsMarkedOnTheRecordLine() {
		String record = record(true, error("boom", 65));
		assertTrue(record.startsWith("message:failed\terror:java.lang.IllegalStateException: boom\trequest:42"
				+ "\ttruncated:true\n\t\tat a.B.m0(B.java:1)\n"), record);
		assertTrue(record.endsWith("\n\t\tat a.B.m63(B.java:64)\n\t[frame limit]\n"), record);
		assertEquals("true", read(record).get("truncated"));
	}

	@Test
	void aClippedTraceStillEndsOnAContinuationLine() {
		var thrown = error("x".repeat(4000), 64);
		String record = record(true, thrown);
		var fields = read(record);
		assertEquals("true", fields.get("truncated"));
		assertEquals("42", fields.get("request"));
		// The value limit clips the last line like any other value.
		assertTrue(record.endsWith("\n\t\tat a.B.m1(B.java:2)\n\t\tat a.B.m2(B.java\n"), record);
	}

	@ParameterizedTest
	@ValueSource(strings = { "lines", "LINES", "true" })
	void selectedByProperty(String value) throws Exception {
		String output = run(value, null);
		assertTrue(output.contains("\terror:java.lang.IllegalStateException: boom\n\t\tat a.B.m0(B.java:1)\n"), output);
	}

	@ParameterizedTest
	@ValueSource(strings = { "inline", "false" })
	void inlineIsTheDefault(String value) throws Exception {
		assertEquals(run(null, null), run(value, null));
		assertTrue(
				run(value, null).contains("\terror:java.lang.IllegalStateException: boom\\n\\tat a.B.m0(B.java:1)\n"));
	}

	@Test
	void jsonLinesIgnoreTheOption() throws Exception {
		assertEquals(run(null, "jsonl"), run("lines", "jsonl"));
	}

	@Test
	void invalidOptionsDisableLogging() throws Exception {
		String output = run("multi", null);
		assertTrue(output.contains("message:Logwood disabled: invalid configuration\n"), output);
		assertFalse(output.contains("boom"));
	}

	private static String run(String stacktrace, String format) throws Exception {
		String before = System.getProperty("logging.ltsv.stacktrace"),
				beforeFormat = System.getProperty("logging.format");
		var stdout = System.out;
		var bytes = new ByteArrayOutputStream();
		try (var capture = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
			set("logging.ltsv.stacktrace", stacktrace);
			set("logging.format", format);
			System.setOut(capture);
			var constructor = Logwood.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			var core = constructor.newInstance();
			core.initialize();
			core.getLogger("app").log(System.Logger.Level.ERROR, "failed", error("boom", 1));
		}
		finally {
			System.setOut(stdout);
			set("logging.ltsv.stacktrace", before);
			set("logging.format", beforeFormat);
		}
		return bytes.toString(StandardCharsets.UTF_8).replaceAll("\"?time\"?:\"?[^\\t,\"]+\"?[\\t,]", "");
	}

	private static void set(String key, String value) {
		if (value == null)
			System.clearProperty(key);
		else
			System.setProperty(key, value);
	}

}
