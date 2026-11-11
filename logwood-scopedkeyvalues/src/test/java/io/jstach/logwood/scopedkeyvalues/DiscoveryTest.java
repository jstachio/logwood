package io.jstach.logwood.scopedkeyvalues;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.core.JsonFactoryBuilder;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadFeature;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

class DiscoveryTest {

	@TempDir
	Path temp;

	private Path configModule;

	private boolean includeSlf4j = true;

	private boolean json;

	private String location(Class<?> type) throws Exception {
		return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
	}

	private String modules() throws Exception {
		var paths = new ArrayList<>(List.of(location(LogwoodScopedKeyValues.class),
				location(io.jstach.logwood.spi.LogwoodService.LogwoodConfig.class),
				location(io.jstach.rainbowgum.scopedkeyvalues.ScopedKeyValues.class)));
		if (includeSlf4j) {
			paths.add(location(Class.forName("org.slf4j.Logger")));
			paths.add(location(Class.forName("io.jstach.logwood.slf4j.Slf4jProvider")));
		}
		return String.join(File.pathSeparator, paths);
	}

	private String run(boolean modular, boolean both, String... properties) throws Exception {

		String modules = modules();
		if (configModule != null)
			modules += File.pathSeparator + configModule;
		String tests = Path.of("target/test-classes").toAbsolutePath().toString();
		Path trace = temp.resolve("initialization.log"), stdout = temp.resolve("out"), stderr = temp.resolve("err");
		var command = new ArrayList<String>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		if (json)
			command.add("-Dlogging.format=jsonl");
		for (String property : properties)
			command.add("-D" + property);
		command.add("-Xlog:class+load=info,class+init=info:file=" + trace);
		if (modular) {
			String roots = "io.jstach.logwood.scopedkeyvalues";
			if (configModule != null)
				roots += ",test.config";
			if (both)
				roots += ",io.jstach.logwood.slf4j";
			command.addAll(List.of("--module-path", modules, "--add-modules", roots, "-cp",
					tests + File.pathSeparator + temp));
		}
		else
			command.addAll(List.of("-cp", tests + File.pathSeparator + temp + File.pathSeparator + modules));
		command.addAll(List.of("logwood.scoped.smoke.ScopedSmoke", both ? "both" : "system",
				includeSlf4j ? "available" : "absent"));
		var child = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
		try {
			assertTrue(child.waitFor(30, TimeUnit.SECONDS));
			assertEquals(0, child.exitValue(), Files.readString(stderr));
			assertEquals("", Files.readString(stderr));
		}
		finally {
			child.destroyForcibly();
		}
		String initialization = Files.readString(trace);
		assertTrue(initialization.contains("Initializing 'io/jstach/logwood/scopedkeyvalues/LogwoodScopedKeyValues'"));
		assertFalse(initialization.contains("Initializing 'io/jstach/rainbowgum/RainbowGum'"));
		if (!both)
			assertFalse(initialization.contains("org.slf4j."));
		if (!both)
			for (String type : List.of("org/slf4j/LoggerFactory", "org/slf4j/MDC", "org/slf4j/MarkerFactory",
					"io/jstach/logwood/spi/LogwoodProvider"))
				assertFalse(initialization.contains("Initializing '" + type + "'"), type);
		// Keep the exact record shape independent of wall time and JVM-assigned IDs.
		return Files.readString(stdout)
			.replaceAll("(?m)^time:[^\\t\\r\\n]+\\t", "time:<time>\t")
			.replaceAll("\\tthreadId:[1-9][0-9]*\\t", "\tthreadId:<id>\t");
	}

	@ParameterizedTest
	@CsvSource({ "false,false", "true,false", "false,true", "true,true" })
	void scopeDiscoveryAndSystemLoggingNeverInitializeSlf4j(boolean modular, boolean withSlf4j) throws Exception {
		includeSlf4j = withSlf4j;
		assertEquals(
				"""
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:outer\trequest:outer\ta%3a%09:v\\n\\u001b[8m
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:inner\trequest:inner\ta%3a%09:v\\n\\u001b[8m
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:restored\trequest:outer\ta%3a%09:v\\n\\u001b[8m
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:unbound
						""",
				run(modular, false));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void scopedJsonLinesWorkWithoutSlf4jOrAJsonLibrary(boolean modular) throws Exception {
		includeSlf4j = false;
		json = true;
		String output = run(modular, false);
		assertFalse(output.contains("\u001b"));
		var lines = output.lines().toList();
		assertEquals(4, lines.size());
		var expected = List.of("outer", "inner", "outer", "");
		var factory = new JsonFactoryBuilder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
		for (int i = 0; i < lines.size(); i++) {
			var fields = new HashMap<String, String>();
			try (var parser = factory.createParser(lines.get(i))) {
				assertEquals(JsonToken.START_OBJECT, parser.nextToken());
				while (parser.nextToken() != JsonToken.END_OBJECT) {
					assertEquals(JsonToken.FIELD_NAME, parser.currentToken());
					String key = parser.currentName();
					assertEquals(key.equals("threadId") ? JsonToken.VALUE_NUMBER_INT : JsonToken.VALUE_STRING,
							parser.nextToken());
					assertNull(fields.put(key, parser.getText()));
				}
				assertNull(parser.nextToken());
			}
			assertEquals(expected.get(i), fields.getOrDefault("request", ""));
			if (i < 3)
				assertEquals("v\n\u001b[8m", fields.get("a:\t"));
			else
				assertFalse(fields.containsKey("request"));
		}
	}

	@ParameterizedTest
	@CsvSource({ "false,false,none", "true,false,none", "false,true,none", "true,true,none", "false,false,prefixed",
			"true,false,prefixed", "false,true,prefixed", "true,true,prefixed" })
	void scopedValuesFollowTheModeWithoutSlf4j(boolean modular, boolean json, String mode) throws Exception {
		includeSlf4j = false;
		this.json = json;
		String output = run(modular, false, "logging.keyvalues.mode=" + mode);
		var lines = output.lines().toList();
		assertEquals(4, lines.size());
		if (mode.equals("none")) {
			assertFalse(output.contains("request"));
			assertFalse(output.contains("v\\n\\u001b[8m"));
			assertFalse(output.contains("keyValues."));
			assertFalse(output.contains("a%3a%09"));
		}
		else {
			String request = json ? "\"keyValues.request\":\"" : "\tkeyValues.request:";
			assertTrue(lines.get(0).contains(request + "outer"));
			assertTrue(lines.get(1).contains(request + "inner"));
			assertTrue(lines.get(2).contains(request + "outer"));
			assertFalse(lines.get(3).contains(request));
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void fluentValuesOverrideScopedContextWithoutDuplicateLabels(boolean modular) throws Exception {
		String output = run(modular, true);
		List<String> lines = output.lines().toList();
		assertEquals(5, lines.size());
		assertEquals(
				"""
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:fluent\trequest:last event\ta%3a%09:v\\n\\u001b[8m
						"""
					.stripTrailing(),
				lines.get(2));
		assertEquals(1, lines.get(2).split("request:", -1).length - 1);
		assertFalse(lines.get(4).contains("\trequest:"));
		assertFalse(lines.get(4).contains("\ta%3a%09:"));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void applicationCanRegisterItsOwnNamedConfigurationModule(boolean withSlf4j) throws Exception {
		includeSlf4j = withSlf4j;
		Path source = temp.resolve("source"), settings = source.resolve("test/config/Settings.java");
		Files.createDirectories(settings.getParent());
		Files.writeString(source.resolve("module-info.java"), """
				module test.config {
				  requires io.jstach.logwood.scopedkeyvalues;
				  provides io.jstach.logwood.spi.LogwoodService with test.config.Settings, test.config.Values;
				}
				""");
		Files.writeString(settings, """
				package test.config;
				public final class Settings extends io.jstach.logwood.spi.LogwoodService.LogwoodConfig {
				  public Settings() {}
				  @Override public String propertyOrNull(String key) {
				    return key.equals("logging.journald") ? "true" : super.propertyOrNull(key);
				  }
				}
				""");
		Path values = source.resolve("test/config/Values.java");
		Files.writeString(values, """
				package test.config;
				public final class Values extends io.jstach.logwood.spi.LogwoodService.KeyValuesProvider {
				  public Values() {}
				  @Override public void keyValues(java.util.function.BiConsumer<String, String> consumer) {
				    consumer.accept("app", "custom"); consumer.accept("request", "configured");
				  }
				}
				""");

		String modules = modules() + File.pathSeparator + location(org.jspecify.annotations.Nullable.class);
		configModule = temp.resolve("config-classes");
		Path diagnostics = temp.resolve("javac.log");
		var compiler = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
				"--release", "25", "--module-path", modules, "-d", configModule.toString(),
				source.resolve("module-info.java").toString(), settings.toString(), values.toString())
			.redirectErrorStream(true)
			.redirectOutput(diagnostics.toFile())
			.start();
		try {
			assertTrue(compiler.waitFor(30, TimeUnit.SECONDS));
			assertEquals(0, compiler.exitValue(), Files.readString(diagnostics));
		}
		finally {
			compiler.destroyForcibly();
		}
		String output = run(true, false);
		assertEquals(4, output.lines().count());
		assertTrue(output.lines().allMatch(line -> line.startsWith("<6>")), output);
		assertTrue(output.lines().allMatch(line -> line.contains("\trequest:configured")), output);
		assertTrue(output.lines().allMatch(line -> line.contains("\tapp:custom")), output);
	}

}
