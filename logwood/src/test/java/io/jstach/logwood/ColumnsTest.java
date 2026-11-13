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

	static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> columns() {
		return java.util.stream.Stream
			.of(org.junit.jupiter.params.provider.Arguments.of("time,level,logger,thread,message", """
					<time>\tINFO\tapp\t<thread>\t<id>\thello
					"""), org.junit.jupiter.params.provider.Arguments.of("thread,message", """
					<thread>\t<id>\thello\ttime:<time>\tlevel:INFO\tlogger:app
					"""), org.junit.jupiter.params.provider.Arguments.of(" MSG , Timestamp ", """
					hello\t<time>\tlevel:INFO\tthread:<thread>\tthreadId:<id>\tlogger:app
					"""));
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.MethodSource("columns")
	void selectedColumnsComeFirstWithoutLabels(String columns, String expected) throws Exception {
		assertEquals(expected, run(Map.of("logging.ltsv.columns", columns), "hello", null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " " })
	void blankMeansNoColumns(String value) throws Exception {
		assertEquals("""
				time:<time>\tlevel:INFO\tthread:<thread>\tthreadId:<id>\tlogger:app\tmessage:hello
				""", run(Map.of("logging.ltsv.columns", value), "hello", null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "message,message", "level,LEVEL", "message,", "threadId", "message level" })
	void invalidColumnsDisableLogging(String value) throws Exception {
		assertEquals(
				"""
						time:<time>\tlevel:ERROR\tthreadId:<id>\tlogger:io.jstach.logwood\tmessage:Logwood disabled: invalid configuration
						""",
				run(Map.of("logging.ltsv.columns", value), "hello", null));
	}

	@Test
	void journaldOmitsTheTimeColumn() throws Exception {
		assertEquals("""
				<6>INFO\thello\tthread:<thread>\tthreadId:<id>\tlogger:app
				""",
				run(Map.of("logging.ltsv.columns", "time,level,message", "logging.journald", "true"), "hello", null));
	}

	@Test
	void jsonLinesIgnoreTheOption() throws Exception {
		assertEquals("""
				{"time":"<time>","level":"INFO","thread":"<thread>","threadId":<id>,"logger":"app","message":"hello"}
				""", run(Map.of("logging.format", "jsonl", "logging.ltsv.columns", "message,level"), "hello", null));
	}

	@Test
	void emptyFirstColumnIsEscapedWithStackTraceLines() throws Exception {
		var thrown = new IllegalStateException("boom");
		thrown.setStackTrace(new StackTraceElement[] { new StackTraceElement("a.B", "c", "B.java", 1) });
		assertEquals(
				"""
						\\e\tINFO\ttime:<time>\tthread:<thread>\tthreadId:<id>\tlogger:app\terror:java.lang.IllegalStateException: boom
						\t\tat a.B.c(B.java:1)
						""",
				run(Map.of("logging.ltsv.columns", "message,level", "logging.ltsv.stacktrace", "lines"), "", thrown));
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource({ "lines, '', \\e", "lines, \\e, \\\\e", "lines, x, x",
			"inline, '', ''" })
	void firstColumnEscaping(String stacktrace, String message, String expected) throws Exception {
		assertEquals("""
				%s\tINFO\ttime:<time>\tthread:<thread>\tthreadId:<id>\tlogger:app
				""".formatted(expected), run(
				Map.of("logging.ltsv.columns", "message,level", "logging.ltsv.stacktrace", stacktrace), message, null));
	}

	@Test
	void failureDiagnosticsKeepTheirLabels() throws Exception {
		assertEquals(
				"""
						time:<time>\tlevel:ERROR\tthreadId:<id>\tlogger:io.jstach.logwood\tmessage:Logwood disabled a logger: invalid level configuration
						""",
				run(Map.of("logging.ltsv.columns", "message,level"),
						core -> core.getLogger("x".repeat(2000)).isLoggable(System.Logger.Level.INFO)));
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
		return GoldenOutput.normalize(bytes.toString(StandardCharsets.UTF_8))
			.replace("\t" + Thread.currentThread().getName() + "\t" + Thread.currentThread().threadId() + "\t",
					"\t<thread>\t<id>\t")
			.replaceFirst("^" + java.util.regex.Pattern.quote(Thread.currentThread().getName()) + "\t"
					+ Thread.currentThread().threadId() + "\t", "<thread>\t<id>\t");
	}

	private static void set(String key, String value) {
		if (value == null)
			System.clearProperty(key);
		else
			System.setProperty(key, value);
	}

}
