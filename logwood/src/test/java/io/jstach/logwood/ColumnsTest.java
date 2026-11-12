package io.jstach.logwood;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/*
 * logging.ltsv.columns: the listed fields come first, in order and without labels.
 */
class ColumnsTest {

	private static final String TIME = "\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z";

	private static final String THREAD = Thread.currentThread().getName() + "\t" + Thread.currentThread().threadId();

	@Test
	void everyFieldAsAColumn() throws Exception {
		String output = run(Map.of("logging.ltsv.columns", "time,level,logger,thread,message"), "hello", null);
		assertTrue(output.matches(TIME + "\tINFO\tapp\t" + THREAD + "\thello\n"), output);
	}

	@Test
	void unlistedFieldsFollowWithTheirLabels() throws Exception {
		String output = run(Map.of("logging.ltsv.columns", "thread,message"), "hello", null);
		assertTrue(output.matches(THREAD + "\thello\ttime:" + TIME + "\tlevel:INFO\tlogger:app\n"), output);
	}

	@Test
	void namesAreCaseInsensitiveWithAliases() throws Exception {
		String output = run(Map.of("logging.ltsv.columns", " MSG , Timestamp "), "hello", null);
		assertTrue(output.matches("hello\t" + TIME + "\tlevel:INFO\tthread:.*\tlogger:app\n"), output);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " " })
	void blankMeansNoColumns(String value) throws Exception {
		assertEquals(run(Map.of(), "hello", null).replaceAll(TIME, ""),
				run(Map.of("logging.ltsv.columns", value), "hello", null).replaceAll(TIME, ""));
	}

	@ParameterizedTest
	@ValueSource(strings = { "message,message", "level,LEVEL", "message,", "threadId", "message level" })
	void invalidColumnsDisableLogging(String value) throws Exception {
		String output = run(Map.of("logging.ltsv.columns", value), "hello", null);
		assertTrue(output.contains("message:Logwood disabled: invalid configuration\n"), output);
		assertFalse(output.contains("hello"));
	}

	@Test
	void journaldOmitsTheTimeColumn() throws Exception {
		String output = run(Map.of("logging.ltsv.columns", "time,level,message", "logging.journald", "true"), "hello",
				null);
		assertTrue(output.matches("<6>INFO\thello\tthread:.*\tlogger:app\n"), output);
	}

	@Test
	void jsonLinesIgnoreTheOption() throws Exception {
		assertEquals(run(Map.of("logging.format", "jsonl"), "hello", null).replaceAll(TIME, ""),
				run(Map.of("logging.format", "jsonl", "logging.ltsv.columns", "message,level"), "hello", null)
					.replaceAll(TIME, ""));
	}

	@Test
	void emptyFirstColumnIsEscapedWithStackTraceLines() throws Exception {
		var lines = Map.of("logging.ltsv.columns", "message,level", "logging.ltsv.stacktrace", "lines");
		var thrown = new IllegalStateException("boom");
		thrown.setStackTrace(new StackTraceElement[] { new StackTraceElement("a.B", "c", "B.java", 1) });
		String output = run(lines, "", thrown);
		assertTrue(output.startsWith("\\e\tINFO\t"), output);
		assertTrue(output.endsWith("\terror:java.lang.IllegalStateException: boom\n\t\tat a.B.c(B.java:1)\n"), output);

		assertTrue(run(lines, "\\e", null).startsWith("\\\\e\tINFO\t"));
		assertTrue(run(lines, "x", null).startsWith("x\tINFO\t"));
		// Without stack trace lines nothing follows a record, so an empty column stays
		// empty.
		assertTrue(run(Map.of("logging.ltsv.columns", "message,level"), "", null).startsWith("\tINFO\t"));
	}

	@Test
	void failureDiagnosticsKeepTheirLabels() throws Exception {
		String output = run(Map.of("logging.ltsv.columns", "message,level"),
				core -> core.getLogger("x".repeat(2000)).isLoggable(System.Logger.Level.INFO));
		assertTrue(output.matches("time:" + TIME
				+ "\tlevel:ERROR\tthreadId:\\d+\tlogger:io.jstach.logwood\tmessage:Logwood disabled a logger: invalid level configuration\n"),
				output);
	}

	private static String run(Map<String, String> properties, String message, Throwable thrown) throws Exception {
		return run(properties, core -> core.getLogger("app").log(System.Logger.Level.INFO, message, thrown));
	}

	private static final String[] KEYS = { "logging.ltsv.columns", "logging.ltsv.stacktrace", "logging.format",
			"logging.journald" };

	// Initializes a new Logwood with these properties and captures what it and body
	// print.
	private static String run(Map<String, String> properties, Consumer<Logwood> body) throws Exception {
		var before = new HashMap<String, String>();
		var stdout = System.out;
		var bytes = new ByteArrayOutputStream();
		try (var capture = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
			for (String key : KEYS) {
				before.put(key, System.getProperty(key));
				set(key, properties.get(key));
			}
			System.setOut(capture);
			var constructor = Logwood.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			var core = constructor.newInstance();
			core.initialize();
			body.accept(core);
		}
		finally {
			System.setOut(stdout);
			before.forEach(ColumnsTest::set);
		}
		return bytes.toString(StandardCharsets.UTF_8);
	}

	private static void set(String key, String value) {
		if (value == null)
			System.clearProperty(key);
		else
			System.setProperty(key, value);
	}

}
