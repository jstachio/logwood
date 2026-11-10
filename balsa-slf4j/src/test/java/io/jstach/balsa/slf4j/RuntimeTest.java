package io.jstach.balsa.slf4j;

import io.jstach.balsa.spi.BalsaService.BalsaConfig;

import static org.junit.jupiter.api.Assertions.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RuntimeTest {

	@TempDir
	Path temp;

	private Path configModule;

	private boolean traceInitialization;

	private String lastOutput;

	private List<Map<String, String>> run(String scenario, boolean modular, String provider, String... properties)
			throws Exception {
		Path folder = Files.createTempDirectory(temp, "process-");
		if (provider != null) {
			Path descriptor = folder.resolve("META-INF/services/io.jstach.balsa.spi.BalsaService");
			Files.createDirectories(descriptor.getParent());
			Files.writeString(descriptor, provider, StandardCharsets.UTF_8);
		}
		String api = Path.of(org.slf4j.Logger.class.getProtectionDomain().getCodeSource().getLocation().toURI())
			.toString();
		String main = Path.of(BalsaConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
		String registration = Path.of("target/classes").toAbsolutePath().toString();
		String tests = Path.of("target/test-classes").toAbsolutePath().toString();
		var command = new ArrayList<String>();
		command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
		for (String property : properties)
			command.add("-D" + property);
		Path initialization = folder.resolve("initialization.log");
		if (traceInitialization)
			command.add("-Xlog:class+init=info:file=" + initialization);
		if (modular) {
			String modules = "io.jstach.balsa.slf4j",
					path = main + File.pathSeparator + registration + File.pathSeparator + api;
			if (configModule != null) {
				modules += ",test.config";
				path += File.pathSeparator + configModule;
			}
			command.addAll(List.of("--module-path", path, "--add-modules", modules));
			command.addAll(List.of("-cp", tests + File.pathSeparator + folder));
		}
		else
			command.addAll(List.of("-cp", tests + File.pathSeparator + main + File.pathSeparator + registration
					+ File.pathSeparator + api + File.pathSeparator + folder));
		command.addAll(List.of("balsa.smoke.Smoke", scenario));
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
		if (traceInitialization) {
			String trace = Files.readString(initialization);
			assertTrue(trace.contains("Initializing 'io/jstach/balsa/Balsa'"), trace);
			for (String type : List.of("org/slf4j/LoggerFactory", "org/slf4j/MDC", "org/slf4j/MarkerFactory",
					"io/jstach/balsa/spi/BalsaProvider"))
				assertFalse(trace.contains("Initializing '" + type + "'"),
						type + " initialized during System.Logger use");
		}
		String data = Files.readString(stdout);
		lastOutput = stableThreadIds(data);
		assertFalse(data.contains("secret=do-not-print"));
		assertFalse(data.contains("\r"));
		List<Map<String, String>> records = new ArrayList<>();
		for (String line : data.lines().toList()) {
			assertTrue(line.length() < 16_384);
			Map<String, String> fields = new HashMap<>();
			if (line.startsWith("<")) {
				fields.put("priority", line.substring(1, 2));
				line = line.substring(3);
			}
			for (String field : line.split("\t", -1)) {
				int colon = field.indexOf(':');
				assertTrue(colon > 0, field);
				String key = field.substring(0, colon);
				assertTrue(key.matches("[A-Za-z0-9_.%\\-]+"), key);
				assertNull(fields.put(key, decode(field.substring(colon + 1))), "duplicate field " + key);
			}
			records.add(fields);
		}
		return records;
	}

	private static String stableThreadIds(String output) {
		// Keep numeric IDs in parsed records; normalize only golden text expectations.
		return output.replaceAll("\\tthreadId:[1-9][0-9]*\\t", "\tthreadId:<id>\t");
	}

	private static String decode(String input) {
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
	@ValueSource(booleans = { false, true })
	void bothApisAreDiscoveredAndUseTheSameFormat(boolean modular) throws Exception {
		var records = run("basic", modular, null);
		assertEquals(3, records.size());
		assertEquals(List.of("INFO", "WARN", "ERROR"), records.stream().map(r -> r.get("level")).toList());
		assertEquals("hello world", records.get(0).get("message"));
		assertEquals("hello world", records.get(1).get("message"));
		assertEquals("spoof", records.get(2).get("kv.level"));
		assertEquals("ERROR", records.get(2).get("level"));
		assertEquals(2, run("system-first", modular, null).size());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void configurationSpiMdcAndBootstrapAreShared(boolean modular) throws Exception {
		String provider = "balsa.smoke.Smoke$MdcConfig\nbalsa.smoke.Smoke$MdcValues";
		var records = run("spi", modular, provider);
		assertEquals(List.of("custom", "early after initialization"),
				records.stream().map(r -> r.get("message")).toList());
		records = run("mdc", modular, provider);
		assertEquals("INFO", records.get(0).get("level"));
		assertEquals("also spoof", records.get(0).get("kv.level"));
		assertEquals("v\n", records.get(0).get("kv.a%3a%09"));
		assertEquals("also spoof", records.get(0).get("kv.level"));
		assertFalse(run("mdc", modular, null).get(0).containsKey("kv.a%3a%09"));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void slf4jMdcDelegatesToTheJdkOnlyStorageContract(boolean modular) throws Exception {
		var records = run("mdc-contract", modular, "balsa.smoke.Smoke$MdcConfig\nbalsa.smoke.Smoke$MdcValues");
		assertEquals(1, records.size());
		assertEquals("MDC bridge passed", records.getFirst().get("message"));
		records = run("mdc-noop", modular, null);
		assertEquals(1, records.size());
		assertEquals("MDC no-op passed", records.getFirst().get("message"));
	}

	@Test
	void levelInheritanceAllOffAndDisabledEvaluation() throws Exception {
		assertEquals(List.of(), run("disabled", false, null));
		var levels = run("levels", false, null, "balsa.level=ALL");
		assertEquals(
				List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "ALL", "TRACE", "DEBUG", "INFO", "WARN", "ERROR"),
				levels.stream().map(r -> r.get("level")).toList());
		assertEquals(List.of(), run("levels", false, null, "balsa.level=OFF"));
		var records = run("basic", false, null, "balsa.level=ERROR", "balsa.level.sample=WARN");
		assertEquals(1, records.size());
		assertEquals("structured", records.get(0).get("message"));
	}

	@Test
	void configurationFailuresDisableLoggingAndNeverExposeValues() throws Exception {
		for (String provider : List.of("balsa.smoke.Smoke$BadConfig",
				"balsa.smoke.Smoke$MdcConfig\nbalsa.smoke.Smoke$OtherConfig")) {
			var records = run("basic", false, provider);
			assertEquals(1, records.size());
			assertEquals("Balsa disabled: invalid configuration", records.get(0).get("message"));
		}
		for (String property : List.of("balsa.level=not-a-level", "balsa.journald=not-a-boolean"))
			assertEquals(1, run("basic", true, null, property).size());
		var records = run("basic", false, null, "balsa.level.sample=not-a-level");
		assertEquals(2, records.size());
		assertEquals("Balsa disabled a logger: invalid level configuration", records.get(0).get("message"));
		assertEquals("system", records.get(1).get("logger"));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void injectedTextAndCyclicErrorsStayInTheirFields(boolean modular) throws Exception {
		var records = run("injection", modular, null);
		assertEquals(2, records.size());
		assertEquals("a\tb\nforged:yes\u001b[8m", records.get(0).get("logger"));
		assertTrue(records.get(0).get("message").contains("arg\n\u2066"));
		assertFalse(records.get(0).containsKey("forged"));
		assertEquals("true", records.get(1).get("truncated"));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void reentrantCallbacksAreDroppedAndJournaldPrioritiesAreAccurate(boolean modular) throws Exception {
		var records = run("reentrant", modular, null);
		assertEquals(List.of("outer", "supplier", "object", "event"),
				records.stream().map(r -> r.get("message")).toList());
		records = run("basic", modular, null, "balsa.journald=true");
		assertEquals(List.of("6", "4", "3"), records.stream().map(r -> r.get("priority")).toList());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void systemLoggerLocalizesAndTreatsThrowableArgumentsAsArguments(boolean modular) throws Exception {
		var records = run("localized", modular, null);
		assertEquals("Bonjour 42", records.get(0).get("message"));
		assertEquals("missing", records.get(1).get("message"));
		assertEquals("value java.lang.IllegalStateException: as argument", records.get(2).get("message"));
		assertFalse(records.get(2).containsKey("error"));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void concurrentWritersProduceWholeUniqueRecords(boolean modular) throws Exception {
		var records = run("concurrent", modular, null);
		assertEquals(800, records.size());
		var unique = new HashSet<String>();
		for (var record : records)
			assertTrue(unique.add(record.get("message")));
	}

	@ParameterizedTest
	@CsvSource({ "false, platform-named", "true, platform-named", "false, platform-unnamed", "true, platform-unnamed",
			"false, virtual-named", "true, virtual-named", "false, virtual-unnamed", "true, virtual-unnamed" })
	void bothApisIdentifyThreadsEvenWhenTheirNamesAreEmptyOrShared(boolean modular, String scenario) throws Exception {
		var records = run(scenario, modular, null);
		assertEquals(6, records.size());
		var counts = new HashMap<String, Integer>();
		for (var record : records) {
			String id = record.get("threadId");
			assertTrue(Long.parseLong(id) > 0);
			assertEquals(record.get("message"), id);
			assertEquals(scenario.endsWith("-unnamed") ? "" : "worker", record.get("thread"));
			counts.merge(id, 1, Integer::sum);
		}
		assertEquals(2, counts.size());
		assertTrue(counts.values().stream().allMatch(count -> count == 3));
		assertEquals(2, records.stream().filter(record -> "spoof".equals(record.get("kv.threadId"))).count());
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void systemObjectOverloadUsesToStringRatherThanMessageFormatOrArrayFormatting(boolean modular) throws Exception {
		var records = run("objects", modular, null, "user.timezone=UTC");
		assertEquals("1234", records.get(0).get("message"));
		assertEquals("Thu Jan 01 00:00:00 UTC 1970", records.get(1).get("message"));
		assertEquals(records.get(2).get("message"), records.get(3).get("message"));
	}

	@ParameterizedTest
	@CsvSource({ "false, default", "true, default", "false, SystemOnlyConfig", "true, SystemOnlyConfig",
			"false, MdcConfig", "true, MdcConfig", "false, BadMdcConfig", "true, BadMdcConfig" })
	void systemLoggerNeverInitializesSlf4jFacadesOrCreatesItsAdapter(boolean modular, String provider)
			throws Exception {
		traceInitialization = true;
		String services = provider.equals("default") ? null : "balsa.smoke.Smoke$" + provider;
		if (provider.equals("SystemOnlyConfig"))
			services += "\nbalsa.smoke.Smoke$SystemValues";
		if (provider.equals("MdcConfig"))
			services += "\nbalsa.smoke.Smoke$MdcValues";
		var records = run("system-only", modular, services);
		assertEquals(List.of("system only", "argument value"), records.stream().map(r -> r.get("message")).toList());
		if (provider.equals("SystemOnlyConfig"))
			assertEquals("direct", records.getFirst().get("kv.source"));
		if (provider.equals("default"))
			assertEquals("""
					level:INFO\tthread:main\tthreadId:<id>\tlogger:system\tmessage:system only
					level:WARN\tthread:main\tthreadId:<id>\tlogger:system\tmessage:argument value
					""", lastOutput);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void contextCallbackWorksWithoutSlf4jAndDoesNotLeakAfterScope(boolean modular) throws Exception {
		traceInitialization = true;
		var records = run("context", modular, "balsa.smoke.Smoke$CallbackValues");
		assertEquals("spoof", records.getFirst().get("kv.level"));
		assertEquals("v\n\u001b[8m", records.getFirst().get("kv.a%3a%09"));
		assertEquals("INFO", records.getFirst().get("level"));
		assertFalse(records.get(1).containsKey("kv.level"));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void markerArgumentsAndEventMarkerAccessorsAreIgnored(boolean modular) throws Exception {
		var records = run("markers", modular, null);
		assertEquals(List.of("classic", "fluent", "event"), records.stream().map(r -> r.get("message")).toList());
		for (var record : records) {
			assertFalse(record.containsKey("marker"));
			assertFalse(record.containsKey("markers"));
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void contextCallbackFailuresAreContainedWithoutRecursingOrLeakingDetails(boolean modular) throws Exception {
		traceInitialization = true;
		var records = run("system-only", modular, "balsa.smoke.Smoke$BadContextValues");
		assertEquals(1, records.size());
		assertEquals("Balsa could not encode a log event", records.getFirst().get("message"));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void invalidMdcAdapterDisablesLoggingWhenSlf4jInitializes(boolean modular) throws Exception {
		var records = run("basic", modular, "balsa.smoke.Smoke$BadMdcConfig");
		assertEquals(1, records.size());
		assertEquals("Balsa disabled: invalid configuration", records.getFirst().get("message"));
	}

	@Test
	void configurationProviderCanBeANamedModuleWithoutAServiceFile() throws Exception {
		Path sources = temp.resolve("source/test/config");
		Files.createDirectories(sources);
		Path moduleInfo = temp.resolve("source/module-info.java");
		Files.writeString(moduleInfo, """
				module test.config {
				  requires io.jstach.balsa;
				  provides io.jstach.balsa.spi.BalsaService with test.config.Settings;
				}
				""");
		Path config = sources.resolve("Settings.java");
		Files.writeString(config, """
				package test.config;

				public final class Settings extends io.jstach.balsa.spi.BalsaService.BalsaConfig {
				  public Settings() {}
				  @Override public System.Logger.Level levelOrNull(String name) {
				    return name.isEmpty() ? System.Logger.Level.OFF : null;
				  }
				}
				""");
		configModule = temp.resolve("config-classes");
		String api = Path.of(org.slf4j.Logger.class.getProtectionDomain().getCodeSource().getLocation().toURI())
			.toString();
		var command = List.of(Path.of(System.getProperty("java.home"), "bin", "javac").toString(), "--release", "21",
				"--module-path",
				Path.of(BalsaConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI())
						+ File.pathSeparator + api + File.pathSeparator
						+ Path.of(org.jspecify.annotations.Nullable.class.getProtectionDomain()
							.getCodeSource()
							.getLocation()
							.toURI()),
				"-d", configModule.toString(), moduleInfo.toString(), config.toString());
		Path diagnostics = temp.resolve("javac.txt");
		var compiler = new ProcessBuilder(command).redirectErrorStream(true)
			.redirectOutput(diagnostics.toFile())
			.start();
		try {
			assertTrue(compiler.waitFor(30, TimeUnit.SECONDS));
			assertEquals(0, compiler.exitValue(), Files.readString(diagnostics));
		}
		finally {
			compiler.destroyForcibly();
		}
		assertEquals(List.of(), run("basic", true, null));
	}

	@Test
	void namedSlf4jApplicationDiscoversTheRegistrationModuleThroughServiceBinding() throws Exception {
		Path source = temp.resolve("source"), app = source.resolve("sample/Main.java");
		Files.createDirectories(app.getParent());
		Files.writeString(source.resolve("module-info.java"), "module sample.app { requires org.slf4j; }");
		Files.writeString(app, """
				package sample;
				public final class Main {
				  public static void main(String[] args) {
				    org.slf4j.LoggerFactory.getLogger("app").info("SLF4J module discovery");
				    System.getLogger("app").log(System.Logger.Level.INFO, "System.Logger module discovery");
				  }
				}
				""");
		String modules = String.join(File.pathSeparator,
				Path.of(BalsaConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
				Path.of("target/classes").toAbsolutePath().toString(),
				Path.of(org.slf4j.Logger.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
		Path classes = temp.resolve("app-classes"), diagnostics = temp.resolve("diagnostics");
		var compiler = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "javac").toString(),
				"--release", "21", "--module-path", modules, "-d", classes.toString(),
				source.resolve("module-info.java").toString(), app.toString())
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
		var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"--module-path", modules + File.pathSeparator + classes, "-m", "sample.app/sample.Main")
			.redirectErrorStream(true)
			.redirectOutput(diagnostics.toFile())
			.start();
		try {
			assertTrue(child.waitFor(30, TimeUnit.SECONDS));
			assertEquals(0, child.exitValue(), Files.readString(diagnostics));
			assertEquals("""
					level:INFO\tthread:main\tthreadId:<id>\tlogger:app\tmessage:SLF4J module discovery
					level:INFO\tthread:main\tthreadId:<id>\tlogger:app\tmessage:System.Logger module discovery
					""", stableThreadIds(Files.readString(diagnostics)));
		}
		finally {
			child.destroyForcibly();
		}
	}

}
