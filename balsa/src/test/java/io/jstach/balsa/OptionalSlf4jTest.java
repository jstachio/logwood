package io.jstach.balsa;

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
		coreJar = temp.resolve("balsa.jar");
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
			Path descriptor = folder.resolve("META-INF/services/io.jstach.balsa.spi.BalsaService");
			Files.createDirectories(descriptor.getParent());
			Files.writeString(descriptor, provider);
		}
		String tests = Path.of("target/test-classes").toAbsolutePath().toString();
		var command = new ArrayList<String>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		for (String property : properties)
			command.add("-D" + property);
		Path trace = folder.resolve("load.log");
		command.add("-Xlog:class+load=info:file=" + trace);
		if (modular) {
			String modules = jar().toString();
			if (observableApi) {
				Class<?> api = Class.forName("org.slf4j.Logger");
				modules += File.pathSeparator
						+ Path.of(api.getProtectionDomain().getCodeSource().getLocation().toURI());
			}
			command.addAll(List.of("--module-path", modules, "--add-modules", "io.jstach.balsa", "-cp",
					tests + File.pathSeparator + folder));
		}
		else
			command.addAll(List.of("-cp", tests + File.pathSeparator + jar() + File.pathSeparator + folder));
		command.addAll(List.of("balsa.smoke.SystemOnlySmoke", scenario));
		String output = launch(command, folder);
		String loaded = Files.readString(trace);
		assertTrue(loaded.contains("io.jstach.balsa.BalsaSystemLogger"));
		assertFalse(loaded.contains("org.slf4j."));
		assertFalse(loaded.contains("io.jstach.balsa.spi.BalsaProvider"));
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
	@CsvSource({ "false,false", "true,false", "true,true" })
	void spiAndContextWorkWithoutLoadingAnySlf4jTypes(boolean modular, boolean observableApi) throws Exception {
		assertEquals(
				"""
						time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:system\tmessage:system only\tkv.source:direct
						time:<time>\tlevel:WARN\tthread:main\tthreadId:<id>\tlogger:system\tmessage:argument value\tkv.source:direct
						""",
				run(modular, observableApi, "balsa.smoke.SystemOnlySmoke$Settings\nbalsa.smoke.SystemOnlySmoke$Values",
						"basic"));
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
		String failure = run(modular, false, "balsa.smoke.SystemOnlySmoke$BadSettings", "basic");
		assertEquals(
				"""
						time:<time>\tlevel:ERROR\tthreadId:<id>\tlogger:io.jstach.balsa\tmessage:Balsa disabled: invalid configuration
						""",
				failure);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void oneDiscoveryPassBuildsOrderedProvidersOnceAndKeepsEqualOrdersStable(boolean modular) throws Exception {
		String services = """
				balsa.smoke.SystemOnlySmoke$HighValues
				balsa.smoke.SystemOnlySmoke$CountingConfig
				balsa.smoke.SystemOnlySmoke$SecondValues
				balsa.smoke.SystemOnlySmoke$FirstValues
				balsa.smoke.SystemOnlySmoke$LowValues
				""";
		String output = run(modular, false, services, "services");
		String record = """
				time:<time>\tlevel:INFO\tthread:main\tthreadId:<id>\tlogger:system\tmessage:services\tkv.winner:high\tkv.low.instances:1\tkv.tie:first\tkv.high.instances:1\tkv.config.instances:1
				""";
		assertEquals(record.repeat(2), output);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void badProviderOrderingDisablesLoggingWithoutExposingTheFailure(boolean modular) throws Exception {
		String services = """
				balsa.smoke.SystemOnlySmoke$BadOrder
				balsa.smoke.SystemOnlySmoke$LowValues
				""";
		assertEquals(
				"""
						time:<time>\tlevel:ERROR\tthreadId:<id>\tlogger:io.jstach.balsa\tmessage:Balsa disabled: invalid configuration
						""",
				run(modular, false, services, "basic"));
	}

	@Test
	void namedApplicationCompilesAndDiscoversConfigWithoutTheSlf4jJar() throws Exception {
		Path source = temp.resolve("source"), app = source.resolve("sample/app/Main.java");
		Files.createDirectories(app.getParent());
		Files.writeString(source.resolve("module-info.java"), """
				module sample.app {
				  requires io.jstach.balsa;
				  provides io.jstach.balsa.spi.BalsaService with sample.app.Settings, sample.app.Values;
				}
				""");
		Files.writeString(source.resolve("sample/app/Settings.java"), """
				package sample.app;
				public final class Settings extends io.jstach.balsa.spi.BalsaService.BalsaConfig {
				  public Settings() {}
				  @Override public System.Logger.Level levelOrNull(String name) {
				    return name.isEmpty() ? System.Logger.Level.DEBUG : null;
				  }
				}
				""");
		Path values = source.resolve("sample/app/Values.java");
		Files.writeString(values, """
				package sample.app;
				public final class Values extends io.jstach.balsa.spi.BalsaService.KeyValuesProvider {
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
		String output = launch(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"--module-path", jar() + File.pathSeparator + classes, "-m", "sample.app/sample.app.Main"), temp);
		assertEquals(
				"""
						time:<time>\tlevel:DEBUG\tthread:main\tthreadId:<id>\tlogger:app\tmessage:named application\tkv.app:named
						""",
				output);
	}

	@Test
	void namedApplicationsCannotAccessTheImplementationPackage() throws Exception {
		Path sources = temp.resolve("hidden"), app = sources.resolve("sample/Main.java");
		Files.createDirectories(app.getParent());
		Files.writeString(sources.resolve("module-info.java"), "module sample.app { requires io.jstach.balsa; }");
		Files.writeString(app, "package sample; public class Main { io.jstach.balsa.Balsa implementation; }");
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
			assertTrue(Files.readString(diagnostics).contains("io.jstach.balsa"));
		}
		finally {
			compiler.destroyForcibly();
		}
	}

}
