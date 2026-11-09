package balsa.smoke;

import java.lang.System.Logger.Level;
import java.util.ListResourceBundle;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import io.jstach.balsa.BalsamConfig;

public final class Smoke {
  private Smoke() {}
  public static void main(String[] args) throws Exception {
    switch (args[0]) {
      case "basic" -> {
        LoggerFactory.getLogger("sample").info("hello {}", "world");
        System.getLogger("system").log(Level.WARNING, "hello {0}", "world");
        LoggerFactory.getLogger("sample").atError().addKeyValue("level", "spoof").log("structured");
      }
      case "disabled" -> {
        var logger = LoggerFactory.getLogger("sample");
        logger.debug("{}", new Object() { @Override public String toString() { throw new AssertionError("evaluated"); } });
        System.getLogger("system").log(Level.DEBUG, () -> { throw new AssertionError("evaluated"); });
        System.getLogger("system").log(Level.OFF, () -> { throw new AssertionError("evaluated"); });
      }
      case "levels" -> {
        var logger = LoggerFactory.getLogger("sample");
        logger.trace("trace"); logger.debug("debug"); logger.info("info"); logger.warn("warn"); logger.error("error");
        for (var level : Level.values()) System.getLogger("system").log(level, level.name());
      }
      case "injection" -> {
        Thread.currentThread().setName("t\r\n\u009b8m\u202e");
        var logger = LoggerFactory.getLogger("a\tb\nforged:yes\u001b[8m");
        logger.info("line\t\n\r\u001b[8m {} \\n", "arg\n\u2066");
        var a = new IllegalStateException("a\nforged:yes\u001b[8m");
        var b = new IllegalStateException("b");
        a.initCause(b); b.initCause(a); a.addSuppressed(b); b.addSuppressed(a);
        logger.error("failure", a);
      }
      case "reentrant" -> {
        var logger = LoggerFactory.getLogger("sample");
        logger.info("{}", new Object() {
          @Override public String toString() { LoggerFactory.getLogger("nested").error("nested"); return "outer"; }
        });
        System.getLogger("system").log(Level.INFO, () -> {
          System.getLogger("nested").log(Level.ERROR, "nested"); return "supplier";
        });
        System.getLogger("system").log(Level.INFO, new Object() {
          @Override public String toString() { System.getLogger("nested").log(Level.ERROR, "nested"); return "object"; }
        });
        var event = new org.slf4j.event.DefaultLoggingEvent(org.slf4j.event.Level.INFO, logger) {
          @Override public String getMessage() { logger.error("nested"); return "event"; }
        };
        ((org.slf4j.spi.LoggingEventAware) logger).log(event);
      }
      case "mdc" -> {
        MDC.put("level", "spoof"); MDC.put("a:\t", "v\n");
        LoggerFactory.getLogger("sample").atInfo().addKeyValue("level", "also spoof").log("context");
        MDC.clear();
      }
      case "spi" -> {
        LoggerFactory.getLogger("sample.child").debug("custom");
        MdcConfig.EARLY.log(Level.INFO, "early after initialization");
      }
      case "system-first" -> {
        System.getLogger("sample").log(Level.INFO, "system first");
        LoggerFactory.getLogger("sample").info("slf4j second");
      }
      case "localized" -> {
        var bundle = new ListResourceBundle() {
          @Override protected Object[][] getContents() { return new Object[][] {{"greeting", "Bonjour {0,number,integer}"}}; }
        };
        var logger = System.getLogger("system", bundle);
        logger.log(Level.INFO, "greeting", 42);
        logger.log(Level.INFO, "missing", new Object[] {"ignored"});
        logger.log(Level.INFO, "value {0}", new Object[] {new IllegalStateException("as argument")});
      }
      case "objects" -> {
        var logger = System.getLogger("system");
        logger.log(Level.INFO, Integer.valueOf(1234));
        logger.log(Level.INFO, new java.util.Date(0));
        Object[] array = {"x"};
        logger.log(Level.INFO, (Object) array);
        logger.log(Level.INFO, array.toString());
      }
      case "concurrent" -> {
        var logger = LoggerFactory.getLogger("sample");
        Thread[] threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
          final int id = i;
          threads[i] = new Thread(() -> { for (int n = 0; n < 100; n++) logger.info("{}:{}", id, n); });
          threads[i].start();
        }
        for (var thread : threads) thread.join();
      }
      default -> throw new AssertionError(args[0]);
    }
  }
  public static final class MdcConfig extends BalsamConfig {
    static final System.Logger EARLY = System.getLogger("early");
    public MdcConfig() { EARLY.log(Level.ERROR, "bootstrap should be suppressed"); }
    @Override public Level levelOrNull(String name) {
      return name.equals("sample") ? Level.DEBUG : super.levelOrNull(name);
    }
    @Override public MDCAdapter createMDCAdapter() { return new BasicMDCAdapter(); }
  }
  public static final class BadConfig extends BalsamConfig {
    public BadConfig() { throw new IllegalArgumentException("secret=do-not-print\nforged:yes"); }
  }
  public static final class OtherConfig extends BalsamConfig { public OtherConfig() {} }
}
