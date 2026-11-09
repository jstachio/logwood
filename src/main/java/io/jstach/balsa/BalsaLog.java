package io.jstach.balsa;

import java.io.ObjectStreamException;
import java.util.ResourceBundle;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Marker;
import org.slf4j.event.LoggingEvent;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.spi.LoggingEventAware;

final class BalsaLog extends LegacyAbstractLogger implements System.Logger, LoggingEventAware {
  private static final long serialVersionUID = 1L;
  private final transient Balsa balsa;
  private transient volatile int threshold = -1;
  BalsaLog(String name, Balsa balsa) { this.name = name; this.balsa = balsa; }

  private boolean enabled(int severity) {
    int current = threshold;
    if (current == -1) {
      current = balsa.threshold(name);
      if (current == -1) return false;
      threshold = current;
    }
    return current != Integer.MAX_VALUE && severity >= current && severity != Integer.MAX_VALUE;
  }
  @Override public boolean isTraceEnabled() { return enabled(400); }
  @Override public boolean isDebugEnabled() { return enabled(500); }
  @Override public boolean isInfoEnabled() { return enabled(800); }
  @Override public boolean isWarnEnabled() { return enabled(900); }
  @Override public boolean isErrorEnabled() { return enabled(1000); }
  @Override public boolean isLoggable(System.Logger.Level level) { return enabled(level.getSeverity()); }
  @Override protected String getFullyQualifiedCallerName() { return BalsaLog.class.getName(); }
  @Override protected Object readResolve() throws ObjectStreamException { return Balsa.instance().getLogger(name); }

  @Override protected void handleNormalizedLoggingCall(org.slf4j.event.Level level, Marker marker,
      String pattern, Object[] args, Throwable thrown) {
    balsa.write(name, system(level), pattern, args, thrown, marker, null, null, 0);
  }
  @Override public void log(LoggingEvent event) {
    if (!isErrorEnabled() || Balsa.ACTIVE.get() == Boolean.TRUE) return;
    System.Logger.Level level;
    String message;
    Object[] args;
    Throwable thrown;
    Balsa.ACTIVE.set(Boolean.TRUE);
    try {
      level = system(event.getLevel());
      if (!isLoggable(level)) return;
      message = event.getMessage(); args = event.getArgumentArray(); thrown = event.getThrowable();
    } catch (Throwable failure) {
      Balsa.nonFatal(failure); balsa.report("Balsa could not encode a log event"); return;
    } finally { Balsa.ACTIVE.set(Boolean.FALSE); }
    balsa.write(name, level, message, args, thrown, null, event, null, 0);
  }
  @Override public void log(System.Logger.Level level, ResourceBundle bundle, String message, Throwable thrown) {
    if (isLoggable(level)) balsa.write(name, level, message, null, thrown, null, null, bundle, 1);
  }
  @Override public void log(System.Logger.Level level, ResourceBundle bundle, String pattern, Object... args) {
    if (isLoggable(level)) balsa.write(name, level, pattern, args, null, null, null, bundle, 1);
  }
  @Override public void log(System.Logger.Level level, Object object) {
    Objects.requireNonNull(object, "object");
    if (isLoggable(level)) balsa.write(name, level, null, new Object[] {object}, null, null, null, null, 2);
  }
  @Override public void log(System.Logger.Level level, Supplier<String> supplier) { log(level, supplier, null); }
  @Override public void log(System.Logger.Level level, Supplier<String> supplier, Throwable thrown) {
    Objects.requireNonNull(supplier, "supplier");
    if (!isLoggable(level) || Balsa.ACTIVE.get() == Boolean.TRUE) return;
    String message;
    Balsa.ACTIVE.set(Boolean.TRUE);
    try { message = supplier.get(); }
    catch (Throwable failure) { Balsa.nonFatal(failure); message = "[supplier failed]"; }
    finally { Balsa.ACTIVE.set(Boolean.FALSE); }
    balsa.write(name, level, message, null, thrown, null, null, null, 1);
  }
  private static System.Logger.Level system(org.slf4j.event.Level level) {
    return switch (level.toInt()) {
      case 0 -> System.Logger.Level.TRACE;
      case 10 -> System.Logger.Level.DEBUG;
      case 20 -> System.Logger.Level.INFO;
      case 30 -> System.Logger.Level.WARNING;
      case 40 -> System.Logger.Level.ERROR;
      default -> System.Logger.Level.OFF;
    };
  }
}
