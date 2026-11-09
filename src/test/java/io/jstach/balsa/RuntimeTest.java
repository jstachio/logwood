package io.jstach.balsa;

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
import org.junit.jupiter.params.provider.ValueSource;

class RuntimeTest {
  @TempDir Path temp;
  private Path configModule;
  private List<Map<String,String>> run(String scenario, boolean modular, String provider, String... properties) throws Exception {
    Path folder = Files.createTempDirectory(temp, "process-");
    if (provider != null) {
      Path descriptor = folder.resolve("META-INF/services/io.jstach.balsa.BalsamConfig");
      Files.createDirectories(descriptor.getParent()); Files.writeString(descriptor, provider, StandardCharsets.UTF_8);
    }
    String api = Path.of(org.slf4j.Logger.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    String main = Path.of("target/classes").toAbsolutePath().toString();
    String tests = Path.of("target/test-classes").toAbsolutePath().toString();
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    for (String property : properties) command.add("-D" + property);
    if (modular) {
      String modules = "io.jstach.balsa", path = main + File.pathSeparator + api;
      if (configModule != null) { modules += ",test.config"; path += File.pathSeparator + configModule; }
      command.addAll(List.of("--module-path", path, "--add-modules", modules));
      command.addAll(List.of("-cp", tests + File.pathSeparator + folder));
    } else command.addAll(List.of("-cp", tests + File.pathSeparator + main + File.pathSeparator + api + File.pathSeparator + folder));
    command.addAll(List.of("balsa.smoke.Smoke", scenario));
    Path stdout = folder.resolve("out"), stderr = folder.resolve("err");
    var child = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
    try {
      assertTrue(child.waitFor(30, TimeUnit.SECONDS), "child JVM timed out");
      assertEquals(0, child.exitValue(), Files.readString(stderr));
      assertEquals("", Files.readString(stderr));
    } finally { child.destroyForcibly(); }
    String data = Files.readString(stdout);
    assertFalse(data.contains("secret=do-not-print"));
    assertFalse(data.contains("\r"));
    List<Map<String,String>> records = new ArrayList<>();
    for (String line : data.lines().toList()) {
      assertTrue(line.length() < Ltsv.RECORD);
      Map<String,String> fields = new HashMap<>();
      if (line.startsWith("<")) { fields.put("priority", line.substring(1, 2)); line = line.substring(3); }
      for (String field : line.split("\t", -1)) {
        int colon = field.indexOf(':'); assertTrue(colon > 0, field);
        String key = field.substring(0, colon);
        assertTrue(key.matches("[A-Za-z0-9_.%\\-]+"), key);
        assertNull(fields.put(key, LtsvTest.decode(field.substring(colon + 1))), "duplicate field " + key);
      }
      records.add(fields);
    }
    return records;
  }
  @ParameterizedTest @ValueSource(booleans={false,true})
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
  @ParameterizedTest @ValueSource(booleans={false,true})
  void configurationSpiMdcAndBootstrapAreShared(boolean modular) throws Exception {
    String provider = "balsa.smoke.Smoke$MdcConfig";
    var records = run("spi", modular, provider);
    assertEquals(List.of("custom", "early after initialization"), records.stream().map(r -> r.get("message")).toList());
    records = run("mdc", modular, provider);
    assertEquals("INFO", records.get(0).get("level"));
    assertEquals("spoof", records.get(0).get("mdc.level"));
    assertEquals("v\n", records.get(0).get("mdc.a%3a%09"));
    assertEquals("also spoof", records.get(0).get("kv.level"));
    assertFalse(run("mdc", modular, null).get(0).containsKey("mdc.level"));
  }
  @Test void levelInheritanceAllOffAndDisabledEvaluation() throws Exception {
    assertEquals(List.of(), run("disabled", false, null));
    var levels = run("levels", false, null, "balsa.level=ALL");
    assertEquals(List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "ALL", "TRACE", "DEBUG", "INFO", "WARN", "ERROR"),
        levels.stream().map(r -> r.get("level")).toList());
    assertEquals(List.of(), run("levels", false, null, "balsa.level=OFF"));
    var records = run("basic", false, null, "balsa.level=ERROR", "balsa.level.sample=WARN");
    assertEquals(1, records.size()); assertEquals("structured", records.get(0).get("message"));
  }
  @Test void configurationFailuresDisableLoggingAndNeverExposeValues() throws Exception {
    for (String provider : List.of("balsa.smoke.Smoke$BadConfig", "balsa.smoke.Smoke$MdcConfig\nbalsa.smoke.Smoke$OtherConfig")) {
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
  @Test void injectedTextAndCyclicErrorsStayInTheirFields() throws Exception {
    var records = run("injection", false, null);
    assertEquals(2, records.size());
    assertEquals("a\tb\nforged:yes\u001b[8m", records.get(0).get("logger"));
    assertTrue(records.get(0).get("message").contains("arg\n\u2066"));
    assertFalse(records.get(0).containsKey("forged"));
    assertEquals("true", records.get(1).get("truncated"));
  }
  @Test void reentrantCallbacksAreDroppedAndJournaldPrioritiesAreAccurate() throws Exception {
    var records = run("reentrant", false, null);
    assertEquals(List.of("outer", "supplier", "object", "event"), records.stream().map(r -> r.get("message")).toList());
    records = run("basic", false, null, "balsa.journald=true");
    assertEquals(List.of("6", "4", "3"), records.stream().map(r -> r.get("priority")).toList());
  }
  @Test void systemLoggerLocalizesAndTreatsThrowableArgumentsAsArguments() throws Exception {
    var records = run("localized", true, null);
    assertEquals("Bonjour 42", records.get(0).get("message"));
    assertEquals("missing", records.get(1).get("message"));
    assertEquals("value java.lang.IllegalStateException: as argument", records.get(2).get("message"));
    assertFalse(records.get(2).containsKey("error"));
  }
  @Test void concurrentWritersProduceWholeUniqueRecords() throws Exception {
    var records = run("concurrent", false, null);
    assertEquals(800, records.size());
    var unique = new HashSet<String>();
    for (var record : records) assertTrue(unique.add(record.get("message")));
  }
  @Test void systemObjectOverloadUsesToStringRatherThanMessageFormatOrArrayFormatting() throws Exception {
    var records = run("objects", false, null, "user.timezone=UTC");
    assertEquals("1234", records.get(0).get("message"));
    assertEquals("Thu Jan 01 00:00:00 UTC 1970", records.get(1).get("message"));
    assertEquals(records.get(2).get("message"), records.get(3).get("message"));
  }
  @Test void configurationProviderCanBeANamedModuleWithoutAServiceFile() throws Exception {
    Path sources = temp.resolve("source/test/config"); Files.createDirectories(sources);
    Path moduleInfo = temp.resolve("source/module-info.java");
    Files.writeString(moduleInfo, "module test.config { requires io.jstach.balsa; provides io.jstach.balsa.BalsamConfig with test.config.Settings; }");
    Path config = sources.resolve("Settings.java");
    Files.writeString(config, "package test.config; public final class Settings extends io.jstach.balsa.BalsamConfig { public Settings() {} public System.Logger.Level levelOrNull(String name) { return name.isEmpty() ? System.Logger.Level.OFF : null; } }");
    configModule = temp.resolve("config-classes");
    String api = Path.of(org.slf4j.Logger.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    var command = List.of(Path.of(System.getProperty("java.home"), "bin", "javac").toString(), "--release", "17",
        "--module-path", Path.of("target/classes").toAbsolutePath() + File.pathSeparator + api,
        "-d", configModule.toString(), moduleInfo.toString(), config.toString());
    Path diagnostics = temp.resolve("javac.txt");
    var compiler = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(diagnostics.toFile()).start();
    try {
      assertTrue(compiler.waitFor(30, TimeUnit.SECONDS));
      assertEquals(0, compiler.exitValue(), Files.readString(diagnostics));
    } finally { compiler.destroyForcibly(); }
    assertEquals(List.of(), run("basic", true, null));
  }
}
