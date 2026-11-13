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
		var command = java();
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
		var initialized = java.util.regex.Pattern.compile("Initializing '([^']+)'")
			.matcher(initialization)
			.results()
			.map(m -> m.group(1))
			.collect(java.util.stream.Collectors.toSet());
		assertEquals(java.util.Set.of("io/jstach/logwood/scopedkeyvalues/LogwoodScopedKeyValues"), initialized.stream()
			.filter(c -> c.equals("io/jstach/logwood/scopedkeyvalues/LogwoodScopedKeyValues")
					|| c.equals("io/jstach/rainbowgum/RainbowGum")
					|| !both && (c.startsWith("org/slf4j/") || c.equals("io/jstach/logwood/spi/LogwoodProvider")))
			.collect(java.util.stream.Collectors.toSet()));
		if (!both)
			assertEquals(List.of(),
					java.util.regex.Pattern.compile("\\[class,load\\] (org\\.slf4j\\.[^ ]+)")
						.matcher(initialization)
						.results()
						.map(m -> m.group(1))
						.toList());
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
		assertEquals(expectedScope(true, "flat"), stableJson(output));
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
		assertEquals(expectedScope(json, mode), stableJson(output));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void fluentValuesOverrideScopedContextWithoutDuplicateLabels(boolean modular) throws Exception {
		String output = run(modular, true);
		assertEquals(
				"""
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:outer\trequest:outer\ta%3a%09:v\\n\\u001b[8m
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:inner\trequest:inner\ta%3a%09:v\\n\\u001b[8m
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:fluent\trequest:last event\ta%3a%09:v\\n\\u001b[8m
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:restored\trequest:outer\ta%3a%09:v\\n\\u001b[8m
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:unbound
						""",
				output);
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
		assertEquals(
				"""
						<6>level:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:outer\trequest:configured\ta%3a%09:v\\n\\u001b[8m\tapp:custom
						<6>level:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:inner\trequest:configured\ta%3a%09:v\\n\\u001b[8m\tapp:custom
						<6>level:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:restored\trequest:configured\ta%3a%09:v\\n\\u001b[8m\tapp:custom
						<6>level:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:unbound\tapp:custom\trequest:configured
						""",
				output);
	}

	private static String stableJson(String output) {
		return output.replaceAll("\"time\":\"[^\"]+\"", "\"time\":\"<time>\"")
			.replaceAll("\"threadId\":[1-9][0-9]*", "\"threadId\":0");
	}

	private static String expectedScope(boolean json, String mode) {
		String prefix = mode.equals("prefixed") ? "keyValues." : "";
		String context = mode.equals("none") ? ""
				: json ? ",\"" + prefix + "request\":\"%s\",\"" + prefix + "a:\\t\":\"v\\n\\u001b[8m\""
						: "\t" + prefix + "request:%s\t" + prefix + "a%%3a%%09:v\\n\\u001b[8m";
		return (json ? """
				{"time":"<time>","level":"INFO","thread":"main","threadId":0,"logger":"scoped","message":"outer"%s}
				{"time":"<time>","level":"INFO","thread":"main","threadId":0,"logger":"scoped","message":"inner"%s}
				{"time":"<time>","level":"INFO","thread":"main","threadId":0,"logger":"scoped","message":"restored"%s}
				{"time":"<time>","level":"INFO","thread":"main","threadId":0,"logger":"scoped","message":"unbound"}
				""" : """
				time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:outer%s
				time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:inner%s
				time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:restored%s
				time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:scoped\tmessage:unbound
				""").formatted(context.formatted("outer"), context.formatted("inner"), context.formatted("outer"));
	}

	// The codecover build passes its JaCoCo agent on, so child JVMs are measured too.
	private static List<String> java(String... arguments) {
		var command = new ArrayList<String>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		String agent = System.getProperty("logwood.test.agent", "");
		if (!agent.isEmpty())
			command.add(agent);
		command.addAll(List.of(arguments));
		return command;
	}

}
