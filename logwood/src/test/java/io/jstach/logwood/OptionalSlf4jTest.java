package io.jstach.logwood;

import org.jspecify.annotations.Nullable;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OptionalSlf4jTest {

	@TempDir
	Path temp;

	private Path coreJar;

	private Path jar() throws Exception {
		if (coreJar != null)
			return coreJar;
		coreJar = temp.resolve("logwood.jar");
		Path classes = Path.of("target/classes");
		try (var out = new JarOutputStream(Files.newOutputStream(coreJar)); var files = Files.walk(classes)) {
			for (var file : files.filter(Files::isRegularFile).sorted().toList()) {
				out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace(File.separatorChar, '/')));
				Files.copy(file, out);
				out.closeEntry();
			}
		}
		return coreJar;
	}

	private String compilationModules() throws Exception {
		return jar() + File.pathSeparator + Path
			.of(org.jspecify.annotations.Nullable.class.getProtectionDomain().getCodeSource().getLocation().toURI());
	}

	private String run(boolean modular, boolean observableApi, String provider, String scenario, String... properties)
			throws Exception {
		Path folder = Files.createTempDirectory(temp, "child-");
		if (provider != null) {
			Path descriptor = folder.resolve("META-INF/services/io.jstach.logwood.spi.LogwoodService");
			Files.createDirectories(descriptor.getParent());
			Files.writeString(descriptor, provider);
		}
		String tests = Path.of("target/test-classes").toAbsolutePath().toString();
		var command = java();
		for (String property : properties)
			command.add("-D" + property);
		Path trace = folder.resolve("load.log");
		command.add("-Xlog:class+load=info,class+init=info:file=" + trace);
		if (modular) {
			String modules = jar().toString();
			if (observableApi) {
				Class<?> api = Class.forName("org.slf4j.Logger");
				modules += File.pathSeparator
						+ Path.of(api.getProtectionDomain().getCodeSource().getLocation().toURI());
			}
			command.addAll(List.of("--module-path", modules, "--add-modules", "io.jstach.logwood", "-cp",
					tests + File.pathSeparator + folder));
		}
		else
			command.addAll(List.of("-cp", tests + File.pathSeparator + jar() + File.pathSeparator + folder));
		command.addAll(List.of("logwood.smoke.SystemOnlySmoke", scenario));
		String output = launch(command, folder);
		String loaded = Files.readString(trace);
		var classes = classEvents(loaded, "\\[class,load\\] ([^ ]+)");
		assertEquals(java.util.Set.of("io.jstach.logwood.LogwoodSystemLogger"),
				classes.stream()
					.filter(c -> c.startsWith("org.slf4j.") || c.equals("io.jstach.logwood.LogwoodSystemLogger")
							|| c.equals("io.jstach.logwood.spi.LogwoodProvider"))
					.collect(java.util.stream.Collectors.toSet()));
		if (scenario.equals("basic") || scenario.equals("unicode"))
			assertEquals(
					scenario.equals("unicode") ? java.util.Set.of("io/jstach/logwood/RecordEncoder$Escapes")
							: java.util.Set.of(),
					classEvents(loaded, "Initializing '([^']+)'").stream()
						.filter(c -> c.equals("io/jstach/logwood/RecordEncoder$Escapes"))
						.collect(java.util.stream.Collectors.toSet()));
		return output;
	}

	// JVM trace text varies by runtime. Parse class events, then compare the full
	// set relevant to the isolation contract (including any forbidden SLF4J class).
	private static java.util.Set<String> classEvents(String trace, String pattern) {
		return java.util.regex.Pattern.compile(pattern)
			.matcher(trace)
			.results()
			.map(m -> m.group(1))
			.collect(java.util.stream.Collectors.toSet());
	}

	private static String stableJson(String output) {
		return output.replaceAll("\"time\":\"[^\"]+\"", "\"time\":\"<time>\"")
			.replaceAll("\"threadId\":[1-9][0-9]*", "\"threadId\":0");
	}

	private String launch(List<String> command, Path folder) throws Exception {
		Path stdout = folder.resolve("out"), stderr = folder.resolve("err");
		var child = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
		try {
			assertTrue(child.waitFor(30, TimeUnit.SECONDS), "child JVM timed out");
			assertEquals(0, child.exitValue(), Files.readString(stderr));
			assertEquals("", Files.readString(stderr));
		}
		finally {
			child.destroyForcibly();
		}
		// JVM-assigned IDs vary across JDKs; retain exact checks for all other data.
		return Files.readString(stdout)
			.replaceAll("(?m)^time:[^\\t\\r\\n]+\\t", "time:<time>\t")
			.replaceAll("\\tthreadId:[1-9][0-9]*\\t", "\tthreadId:<id>\t");
	}

	@ParameterizedTest
	@CsvSource({ "false,false", "true,false", "true,true" })
	void systemLoggerWorksWithSlf4jAbsentOrUnresolved(boolean modular, boolean observableApi) throws Exception {
		assertEquals("""
				time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:system\tmessage:system only
				time:<time>\tlevel:WARN\tthread:main\tthreadId:<id>\tlogger:system\tmessage:argument value
				""", run(modular, observableApi, null, "basic"));
	}

	@ParameterizedTest
	@CsvSource({ "false,false", "true,false", "true,true" })
	void jsonLinesWorkWithoutSlf4jOrAJsonLibraryAtRuntime(boolean modular, boolean observableApi) throws Exception {
		String output = run(modular, observableApi, null, "basic", "logging.format=jsonl");
		var lines = output.lines().toList();
		assertEquals(2, lines.size());
		var first = JsonlTest.parse(lines.getFirst());
		var second = JsonlTest.parse(lines.get(1));
		assertEquals("system only", first.get("message"));
		assertEquals("argument value", second.get("message"));
		assertEquals("WARN", second.get("level"));
		assertTrue(first.get("threadId") instanceof Long);
		java.time.Instant.parse((String) first.get("time"));
	}

	@ParameterizedTest
	@CsvSource({ "false,ltsv", "true,ltsv", "false,jsonl", "true,jsonl" })
	void unicodeInitializesTheEscapeTableWithoutLoadingSlf4j(boolean modular, String format) throws Exception {
		String output = run(modular, false, null, "unicode", "logging.format=" + format);
		assertEquals(
				format.equals("jsonl")
						? """
								{"time":"<time>","level":"INFO","thread":"main","threadId":0,"logger":"system","message":"日本語😀\\u202e\\u009b"}
								"""
						: """
								time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:system\tmessage:日本語😀\\u202e\\u009b
								""",
				stableJson(output));
	}

	@ParameterizedTest
	@CsvSource({ "false,false", "true,false", "true,true" })
	void spiAndContextWorkWithoutLoadingAnySlf4jTypes(boolean modular, boolean observableApi) throws Exception {
		assertEquals(
				"""
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:system\tmessage:system only\tsource:direct
						time:<time>\tlevel:WARN\tthread:main\tthreadId:<id>\tlogger:system\tmessage:argument value\tsource:direct
						""",
				run(modular, observableApi,
						"logwood.smoke.SystemOnlySmoke$Settings\nlogwood.smoke.SystemOnlySmoke$Values", "basic"));
	}

	@ParameterizedTest
	@CsvSource({ "false,ltsv,prefixed", "true,ltsv,prefixed", "false,jsonl,prefixed", "true,jsonl,prefixed",
			"false,ltsv,none", "true,jsonl,none" })
	void modesWorkWhenSlf4jIsAbsent(boolean modular, String format, String mode) throws Exception {
		String output = run(modular, false, "logwood.smoke.SystemOnlySmoke$Values", "basic", "logging.format=" + format,
				"logging.keyvalues.mode=" + mode);
		String context = mode.equals("none") ? ""
				: format.equals("jsonl") ? ",\"keyValues.source\":\"direct\"" : "\tkeyValues.source:direct";
		assertEquals((format.equals("jsonl")
				? """
						{"time":"<time>","level":"INFO","thread":"main","threadId":0,"logger":"system","message":"system only"%s}
						{"time":"<time>","level":"WARN","thread":"main","threadId":0,"logger":"system","message":"argument value"%s}
						"""
				: """
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:system\tmessage:system only%s
						time:<time>\tlevel:WARN\tthread:main\tthreadId:<id>\tlogger:system\tmessage:argument value%s
						""")
			.formatted(context, context), stableJson(output));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void systemOverloadsAndFailureDiagnosticsRemainSafeWithoutSlf4j(boolean modular) throws Exception {
		String output = run(modular, false, null, "overloads");
		assertEquals(
				"""
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:system\tmessage:1234
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:system\tmessage:supplier
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:system\tmessage:Bonjour world
						time:<time>\tlevel:WARN\tthread:main\tthreadId:<id>\tlogger:system\tmessage:value java.lang.IllegalStateException: argument
						time:<time>\tlevel:ERROR\tthread:main\tthreadId:<id>\tlogger:system\tmessage:failure\terror:java.lang.IllegalStateException: line\\n\\u001b[8m\\n\\tat sample.App.run(App.java:42)
						""",
				output);
		String failure = run(modular, false, "logwood.smoke.SystemOnlySmoke$BadSettings", "basic");
		assertEquals(
				"""
						time:<time>\tlevel:ERROR\tthreadId:<id>\tlogger:io.jstach.logwood\tmessage:Logwood disabled: invalid configuration
						""",
				failure);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void oneDiscoveryPassBuildsOrderedProvidersOnceAndKeepsEqualOrdersStable(boolean modular) throws Exception {
		String services = """
				logwood.smoke.SystemOnlySmoke$HighValues
				logwood.smoke.SystemOnlySmoke$CountingConfig
				logwood.smoke.SystemOnlySmoke$SecondValues
				logwood.smoke.SystemOnlySmoke$FirstValues
				logwood.smoke.SystemOnlySmoke$LowValues
				""";
		String output = run(modular, false, services, "services");
		String record = """
				time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:system\tmessage:services\twinner:high\tlow.instances:1\ttie:first\thigh.instances:1\tconfig.instances:1
				""";
		assertEquals(record.repeat(2), output);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void badProviderOrderingDisablesLoggingWithoutExposingTheFailure(boolean modular) throws Exception {
		String services = """
				logwood.smoke.SystemOnlySmoke$BadOrder
				logwood.smoke.SystemOnlySmoke$LowValues
				""";
		assertEquals(
				"""
						time:<time>\tlevel:ERROR\tthreadId:<id>\tlogger:io.jstach.logwood\tmessage:Logwood disabled: invalid configuration
						""",
				run(modular, false, services, "basic"));
	}

	@Test
	void namedApplicationCompilesAndDiscoversConfigWithoutTheSlf4jJar() throws Exception {
		Path source = temp.resolve("source"), app = source.resolve("sample/app/Main.java");
		Files.createDirectories(app.getParent());
		Files.writeString(source.resolve("module-info.java"), """
				module sample.app {
				  requires io.jstach.logwood;
				  provides io.jstach.logwood.spi.LogwoodService with sample.app.Settings, sample.app.Values;
				}
				""");
		Files.writeString(source.resolve("sample/app/Settings.java"), """
				package sample.app;
				public final class Settings extends io.jstach.logwood.spi.LogwoodService.LogwoodConfig {
				  public Settings() {}
				  @Override public System.Logger.Level levelOrNull(String name) {
				    return name.isEmpty() ? System.Logger.Level.DEBUG : null;
				  }
				}
				""");
		Path values = source.resolve("sample/app/Values.java");
		Files.writeString(values, """
				package sample.app;
				public final class Values extends io.jstach.logwood.spi.LogwoodService.KeyValuesProvider {
				  public Values() {}
				  @Override public void keyValues(java.util.function.BiConsumer<String, String> consumer) {
				    consumer.accept("app", "named");
				  }
				}
				""");

		Files.writeString(app,
				"""
						package sample.app;
						public final class Main {
						  public static void main(String[] args) throws Exception {
						    if (ModuleLayer.boot().findModule("org.slf4j").isPresent()) throw new AssertionError("SLF4J resolved");
						    try { Class.forName("org.slf4j.Logger"); throw new AssertionError("SLF4J present"); }
						    catch (ClassNotFoundException expected) {}
						    System.getLogger("app").log(System.Logger.Level.DEBUG, "named application");
						  }
						}
						""");
		Path classes = temp.resolve("app-classes");
		var compiler = List.of(Path.of(System.getProperty("java.home"), "bin", "javac").toString(), "--release", "21",
				"--module-path", compilationModules(), "-d", classes.toString(),
				source.resolve("module-info.java").toString(), source.resolve("sample/app/Settings.java").toString(),
				values.toString(), app.toString());
		assertEquals("", launch(compiler, temp));
		String output = launch(
				java("--module-path", jar() + File.pathSeparator + classes, "-m", "sample.app/sample.app.Main"), temp);
		assertEquals("""
				time:<time>\tlevel:DEBUG\tthread:main\tthreadId:<id>\tlogger:app\tmessage:named application\tapp:named
				""", output);
	}

	@Test
	void namedApplicationsCannotAccessTheImplementationPackage() throws Exception {
		Path sources = temp.resolve("hidden"), app = sources.resolve("sample/Main.java");
		Files.createDirectories(app.getParent());
		Files.writeString(sources.resolve("module-info.java"), "module sample.app { requires io.jstach.logwood; }");
		Files.writeString(app, "package sample; public class Main { io.jstach.logwood.Logwood implementation; }");
		Path diagnostics = temp.resolve("hidden-errors");
		var compiler = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
				"-XDrawDiagnostics", "--release", "21", "--module-path", compilationModules(), "-d",
				temp.resolve("hidden-classes").toString(), sources.resolve("module-info.java").toString(),
				app.toString())
			.redirectErrorStream(true)
			.redirectOutput(diagnostics.toFile())
			.start();
		try {
			assertTrue(compiler.waitFor(30, TimeUnit.SECONDS));
			assertNotEquals(0, compiler.exitValue(), "implementation package was exported");
			assertEquals(
					"""
							Main.java:1:46: compiler.err.package.not.visible: io.jstach.logwood, (compiler.misc.not.def.access.not.exported: io.jstach.logwood, io.jstach.logwood)
							1 error
							""",
					Files.readString(diagnostics).replace("\r\n", "\n"));
		}
		finally {
			compiler.destroyForcibly();
		}
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
