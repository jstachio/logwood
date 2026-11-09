package io.jstach.balsa;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.ServiceConfigurationError;
import org.junit.jupiter.api.Test;

class CoreTest {
  private static Balsa fresh() throws Exception {
    var constructor = Balsa.class.getDeclaredConstructor(); constructor.setAccessible(true);
    var core = constructor.newInstance(); core.initialize(); return core;
  }
  @Test void loggerNamesAreNotRetainedAndMarkerRetentionIsBoundedAndWeak() throws Exception {
    var core = fresh();
    assertNotSame(core.getLogger("same"), core.getLogger("same"));
    var marker = core.getMarker("live");
    assertSame(marker, core.getMarker("live"));
    assertTrue(core.exists("live"));
    for (int i = 0; i < 10_000; i++) core.getMarker("dynamic-" + i);
    var field = Balsa.class.getDeclaredField("markers"); field.setAccessible(true);
    var cache = (Map<?,?>) field.get(core);
    assertEquals(256, cache.size());
    for (Object value : cache.values()) assertInstanceOf(WeakReference.class, value);
    assertTrue(core.detachMarker("live")); assertFalse(core.exists("live"));
  }
  @Test void serializationRebindsAndResolvesThresholdInsteadOfEnablingEverything() throws Exception {
    var logger = Balsa.instance().getLogger("serialized");
    assertTrue(logger.isInfoEnabled());
    var bytes = new ByteArrayOutputStream();
    try (var output = new ObjectOutputStream(bytes)) { output.writeObject(logger); }
    String before = System.getProperty("balsa.level.serialized");
    try {
      System.setProperty("balsa.level.serialized", "OFF");
      try (var input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
        var restored = (BalsaLog) input.readObject();
        assertEquals("serialized", restored.getName());
        assertFalse(restored.isErrorEnabled());
      }
    } finally {
      if (before == null) System.clearProperty("balsa.level.serialized");
      else System.setProperty("balsa.level.serialized", before);
    }
  }
  @Test void callbackGuardDoesNotPermanentlyDisableAnUnresolvedLogger() {
    var logger = Balsa.instance().getLogger("guarded");
    Balsa.ACTIVE.set(Boolean.TRUE);
    try { assertFalse(logger.isInfoEnabled()); }
    finally { Balsa.ACTIVE.set(Boolean.FALSE); }
    assertTrue(logger.isInfoEnabled());
  }
  @Test void oversizedNamesDisableOnlyThoseLoggersWithOneSanitizedDiagnostic() throws Exception {
    var core = fresh();
    var before = System.out;
    var bytes = new ByteArrayOutputStream();
    try (var capture = new PrintStream(bytes, false, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      assertFalse(core.getLogger("secret".repeat(1000)).isInfoEnabled());
      assertFalse(core.getLogger("a.".repeat(100)).isErrorEnabled());
      assertTrue(core.getLogger("normal").isInfoEnabled());
    } finally { System.setOut(before); }
    assertEquals("level:ERROR\tlogger:io.jstach.balsa\tmessage:Balsa disabled a logger: invalid level configuration\n", bytes.toString(StandardCharsets.UTF_8));
  }
  @Test void fatalErrorsAreNotSwallowedEvenWhenServiceLoaderWrapsThem() {
    var fatal = new OutOfMemoryError("test only");
    var wrapped = new ServiceConfigurationError("provider failed", fatal);
    assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> Balsa.nonFatal(wrapped)));
  }
  @Test void outputIsUtf8EvenWhenThePrintStreamUsesAnotherCharset() throws Exception {
    var core = fresh();
    var before = System.out;
    var bytes = new ByteArrayOutputStream();
    try (var capture = new PrintStream(bytes, false, StandardCharsets.UTF_16BE)) {
      System.setOut(capture); core.getLogger("utf8").info("é😀");
    } finally { System.setOut(before); }
    assertEquals("level:INFO\tthread:" + Thread.currentThread().getName() + "\tlogger:utf8\tmessage:é😀\n", bytes.toString(StandardCharsets.UTF_8));
  }
  @Test void stdoutCallbacksCannotRecursivelyLog() throws Exception {
    var core = fresh(); var logger = core.getLogger("stdout");
    var before = System.out;
    var bytes = new ByteArrayOutputStream();
    try (var capture = new PrintStream(bytes, false, StandardCharsets.UTF_8) {
      @Override public void write(byte[] value, int offset, int length) {
        logger.error("nested"); super.write(value, offset, length);
      }
    }) {
      System.setOut(capture); logger.info("outer");
    } finally { System.setOut(before); }
    assertEquals("level:INFO\tthread:" + Thread.currentThread().getName() + "\tlogger:stdout\tmessage:outer\n", bytes.toString(StandardCharsets.UTF_8));
  }
}
