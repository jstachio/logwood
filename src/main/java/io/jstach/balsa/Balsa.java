package io.jstach.balsa;

import java.lang.System.Logger.Level;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Marker;
import org.slf4j.event.LoggingEvent;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;

final class Balsa implements ILoggerFactory, IMarkerFactory {
  static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();
  private static final Balsa INSTANCE = new Balsa();
  private final AtomicBoolean reported = new AtomicBoolean();
  private final BasicMarkerFactory detached = new BasicMarkerFactory();
  private final Map<String, WeakReference<Marker>> markers = new HashMap<>();
  private volatile int state; // 0=new, 1=configuring, 2=ready, 3=disabled
  private BalsamConfig config;
  private int rootLevel;
  private boolean journal;
  MDCAdapter mdc = new NOPMDCAdapter();

  private Balsa() {}
  static Balsa instance() { INSTANCE.initialize(); return INSTANCE; }

  void initialize() {
    if (state != 0) return;
    synchronized (this) {
      if (state != 0) return;
      state = 1;
    }
    // No application code under an initialization monitor or class-init lock.
    try {
      var providers = ServiceLoader.load(BalsamConfig.class, BalsamConfig.class.getClassLoader()).stream().iterator();
      var provider = providers.hasNext() ? providers.next() : null;
      if (providers.hasNext()) throw new IllegalStateException("Multiple BalsamConfig providers");
      BalsamConfig selected = provider == null ? new BalsamConfig() : provider.get();
      Level level = selected.levelOrNull("");
      String prefix = selected.propertyOrNull("balsa.journald");
      if (prefix != null && !prefix.equalsIgnoreCase("true") && !prefix.equalsIgnoreCase("false"))
        throw new IllegalArgumentException("Invalid journald option");
      MDCAdapter adapter = Objects.requireNonNull(selected.createMDCAdapter(), "MDC adapter");
      config = selected;
      rootLevel = level == null ? Level.INFO.getSeverity() : level.getSeverity();
      journal = "true".equalsIgnoreCase(prefix);
      mdc = adapter;
      state = 2;
    } catch (Throwable failure) {
      nonFatal(failure);
      state = 3;
      report("Balsa disabled: invalid configuration");
    }
  }

  @Override public BalsaLog getLogger(String name) { return new BalsaLog(Objects.requireNonNull(name, "name"), this); }

  int threshold(String name) {
    if (state == 3) return Integer.MAX_VALUE;
    if (state != 2 || ACTIVE.get() == Boolean.TRUE) return -1;
    ACTIVE.set(Boolean.TRUE);
    try {
      if (name.length() > 1024) return disabledLogger();
      String current = name;
      for (int depth = 0; !current.isEmpty(); depth++) {
        if (depth == 64) return disabledLogger();
        Level level = config.levelOrNull(current);
        if (level != null) return level.getSeverity();
        int dot = current.lastIndexOf('.');
        if (dot < 0) break;
        current = current.substring(0, dot);
      }
      return rootLevel;
    } catch (Throwable failure) {
      nonFatal(failure);
      return disabledLogger();
    } finally { ACTIVE.set(Boolean.FALSE); }
  }
  private int disabledLogger() {
    report("Balsa disabled a logger: invalid level configuration");
    return Integer.MAX_VALUE;
  }

  void write(String name, Level level, String message, Object[] args, Throwable thrown,
      Marker marker, LoggingEvent event, ResourceBundle bundle, int mode) {
    if (state != 2 || ACTIVE.get() == Boolean.TRUE) return;
    ACTIVE.set(Boolean.TRUE);
    try {
      var out = new Ltsv(journal ? priority(level) : -1);
      out.start("level"); out.text(level == Level.WARNING ? "WARN" : level.name());
      out.start("thread"); out.text(Thread.currentThread().getName());
      out.start("logger"); out.text(name);
      out.start("message");
      int count = args == null ? 0 : args.length;
      if (mode == 0 && thrown == null && count > 0 && args[count - 1] instanceof Throwable t) {
        thrown = t; count--;
      }
      if (mode == 1) {
        if (bundle != null && message != null) {
          try { message = bundle.getString(message); }
          catch (java.util.MissingResourceException ignored) { /* The key is the fallback. */ }
        }
        out.system(message, args);
      } else if (mode == 2) out.scalar(args[0]);
      else out.message(message, args, count);
      if (marker != null) { out.start("marker"); out.text(marker.getName()); }
      if (thrown != null) { out.start("error"); out.throwable(thrown); }
      if (!(mdc instanceof NOPMDCAdapter)) {
        var context = mdc.getCopyOfContextMap();
        if (context != null) {
          var entries = context.entrySet().iterator();
          int n = 0;
          while (n < 32 && out.room() && entries.hasNext()) {
            n++;
            var entry = entries.next();
            out.extra("mdc.", entry.getKey(), entry.getValue());
          }
          if (entries.hasNext()) out.truncated = true;
        }
      }
      if (event != null) {
        var pairs = event.getKeyValuePairs();
        if (pairs != null) {
          int countPairs = Math.min(pairs.size(), 32);
          for (int i = 0; i < countPairs && out.room(); i++) {
            var pair = pairs.get(i);
            out.extra("kv.", pair.key, pair.value);
          }
          if (pairs.size() > countPairs) out.truncated = true;
        }
        var eventMarkers = event.getMarkers();
        if (eventMarkers != null && !eventMarkers.isEmpty()) {
          out.start("markers");
          for (int i = 0; i < Math.min(eventMarkers.size(), 4); i++) {
            if (i != 0) out.text(", ");
            out.text(eventMarkers.get(i).getName());
          }
          if (eventMarkers.size() > 4) out.truncated = true;
        }
      }
      print(out);
    } catch (Throwable failure) {
      nonFatal(failure);
      report("Balsa could not encode a log event");
    } finally { ACTIVE.set(Boolean.FALSE); }
  }

  void report(String message) {
    if (!reported.compareAndSet(false, true)) return;
    try {
      var out = new Ltsv(journal ? 3 : -1);
      out.start("level"); out.text("ERROR");
      out.start("logger"); out.text("io.jstach.balsa");
      out.start("message"); out.text(message);
      print(out);
    } catch (Throwable failure) { nonFatal(failure); }
  }
  private static void print(Ltsv out) { System.out.writeBytes(out.finish().getBytes(StandardCharsets.UTF_8)); }

  static void nonFatal(Throwable failure) {
    for (int depth = 0; failure != null && depth < 8; depth++) {
      if (failure instanceof VirtualMachineError fatal && !(failure instanceof StackOverflowError)) throw fatal;
      if (failure instanceof ThreadDeath death) throw death;
      Class<?> type = failure.getClass();
      if (type != ServiceConfigurationError.class && type != ExceptionInInitializerError.class
          && type != java.lang.reflect.InvocationTargetException.class) return;
      failure = failure.getCause();
    }
  }
  private static int priority(Level level) {
    int severity = level.getSeverity();
    return severity >= 1000 ? 3 : severity >= 900 ? 4 : severity >= 800 ? 6 : 7;
  }

  @Override public Marker getMarker(String name) {
    if (name == null) throw new IllegalArgumentException("Marker name must not be null");
    synchronized (markers) {
      var reference = markers.get(name);
      Marker marker = reference == null ? null : reference.get();
      if (marker == null) {
        marker = detached.getDetachedMarker(name);
        if (name.length() <= 1024 && (reference != null || markers.size() < 256))
          markers.put(name, new WeakReference<>(marker));
      }
      return marker;
    }
  }
  @Override public boolean exists(String name) {
    synchronized (markers) {
      var reference = markers.get(name);
      return reference != null && reference.get() != null;
    }
  }
  @Override public boolean detachMarker(String name) {
    synchronized (markers) { return markers.remove(name) != null; }
  }
  @Override public Marker getDetachedMarker(String name) { return detached.getDetachedMarker(name); }
}
