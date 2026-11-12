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
		assertTrue(loaded.contains("io.jstach.logwood.LogwoodSystemLogger"));
		assertFalse(loaded.contains("org.slf4j."));
		assertFalse(loaded.contains("io.jstach.logwood.spi.LogwoodProvider"));
		if (scenario.equals("basic") || scenario.equals("unicode"))
			assertEquals(scenario.equals("unicode"),
					loaded.contains("Initializing 'io/jstach/logwood/RecordEncoder$Escapes'"));
		return output;
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
		assertTrue(output.contains("日本語😀\\u202e\\u009b"));
		if (format.equals("jsonl"))
			assertEquals("日本語😀\u202e\u009b", JsonlTest.parse(output).get("message"));
		else
			assertTrue(output.endsWith("message:日本語😀\\u202e\\u009b\n"));
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
		if (format.equals("jsonl")) {
			for (String line : output.lines().toList()) {
				var record = JsonlTest.parse(line);
				assertEquals(mode.equals("none") ? null : "direct", record.get("keyValues.source"));
				assertFalse(record.containsKey("source"));
			}
		}
		else {
			assertEquals(!mode.equals("none"), output.contains("\tkeyValues.source:direct"));
			assertFalse(output.contains("\tsource:"));
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void systemOverloadsAndFailureDiagnosticsRemainSafeWithoutSlf4j(boolean modular) throws Exception {
		String output = run(modular, false, null, "overloads");
		var lines = output.lines().toList();
		assertEquals(5, lines.size());
		assertTrue(lines.get(0).endsWith("message:1234"));
		assertTrue(lines.get(1).endsWith("message:supplier"));
		assertTrue(lines.get(2).endsWith("message:Bonjour world"));
		assertTrue(lines.get(3).endsWith("message:value java.lang.IllegalStateException: argument"));
		assertTrue(lines.get(4).contains("\terror:java.lang.IllegalStateException: line\\n\\u001b[8m"));
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
				"--release", "21", "--module-path", compilationModules(), "-d",
				temp.resolve("hidden-classes").toString(), sources.resolve("module-info.java").toString(),
				app.toString())
			.redirectErrorStream(true)
			.redirectOutput(diagnostics.toFile())
			.start();
		try {
			assertTrue(compiler.waitFor(30, TimeUnit.SECONDS));
			assertNotEquals(0, compiler.exitValue(), "implementation package was exported");
			assertTrue(Files.readString(diagnostics).contains("io.jstach.logwood"));
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
