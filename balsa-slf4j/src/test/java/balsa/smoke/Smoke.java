package balsa.smoke;

import org.jspecify.annotations.Nullable;

import java.lang.System.Logger.Level;
import java.util.ListResourceBundle;
import java.util.Map;
import java.util.function.BiConsumer;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import io.jstach.balsa.spi.BalsaService.BalsaConfig;
import io.jstach.balsa.spi.BalsaService.KeyValuesProvider;

public final class Smoke {

	private Smoke() {
	}

	public static void main(String[] args) throws Exception {
		switch (args[0]) {
			case "basic" -> {
				LoggerFactory.getLogger("sample").info("hello {}", "world");
				System.getLogger("system").log(Level.WARNING, "hello {0}", "world");
				LoggerFactory.getLogger("sample").atError().addKeyValue("level", "spoof").log("structured");
			}
			case "system-only" -> {
				var logger = System.getLogger("system");
				logger.log(Level.INFO, "system only");
				logger.log(Level.WARNING, "argument {0}", "value");
				logger.log(Level.DEBUG, () -> {
					throw new AssertionError("disabled supplier evaluated");
				});
			}
			case "context" -> {
				var logger = System.getLogger("system");
				CallbackValues.CONTEXT.set(Map.of("level", "spoof", "a:\t", "v\n\u001b[8m"));
				try {
					logger.log(Level.INFO, "with context");
				}
				finally {
					CallbackValues.CONTEXT.remove();
				}
				logger.log(Level.INFO, "without context");
			}
			case "markers" -> {
				var logger = LoggerFactory.getLogger("sample");
				var marker = org.slf4j.MarkerFactory.getMarker("ignored");
				logger.info(marker, "classic");
				logger.atInfo().addMarker(marker).log("fluent");
				var event = new org.slf4j.event.DefaultLoggingEvent(org.slf4j.event.Level.INFO, logger) {
					@Override
					public java.util.List<org.slf4j.Marker> getMarkers() {
						throw new AssertionError("marker accessor must not be read");
					}
				};
				event.setMessage("event");
				((org.slf4j.spi.LoggingEventAware) logger).log(event);
			}
			case "disabled" -> {
				var logger = LoggerFactory.getLogger("sample");
				logger.debug("{}", new Object() {
					@Override
					public String toString() {
						throw new AssertionError("evaluated");
					}
				});
				System.getLogger("system").log(Level.DEBUG, () -> {
					throw new AssertionError("evaluated");
				});
				System.getLogger("system").log(Level.OFF, () -> {
					throw new AssertionError("evaluated");
				});
			}
			case "levels" -> {
				var logger = LoggerFactory.getLogger("sample");
				logger.trace("trace");
				logger.debug("debug");
				logger.info("info");
				logger.warn("warn");
				logger.error("error");
				for (var level : Level.values())
					System.getLogger("system").log(level, level.name());
			}
			case "injection" -> {
				Thread.currentThread().setName("t\r\n\u009b8m\u202e");
				var logger = LoggerFactory.getLogger("a\tb\nforged:yes\u001b[8m");
				logger.info("line\t\n\r\u001b[8m {} \\n", "arg\n\u2066");
				var a = new IllegalStateException("a\nforged:yes\u001b[8m");
				var b = new IllegalStateException("b");
				a.initCause(b);
				b.initCause(a);
				a.addSuppressed(b);
				b.addSuppressed(a);
				logger.error("failure", a);
			}
			case "reentrant" -> {
				var logger = LoggerFactory.getLogger("sample");
				logger.info("{}", new Object() {
					@Override
					public String toString() {
						LoggerFactory.getLogger("nested").error("nested");
						return "outer";
					}
				});
				System.getLogger("system").log(Level.INFO, () -> {
					System.getLogger("nested").log(Level.ERROR, "nested");
					return "supplier";
				});
				System.getLogger("system").log(Level.INFO, new Object() {
					@Override
					public String toString() {
						System.getLogger("nested").log(Level.ERROR, "nested");
						return "object";
					}
				});
				var event = new org.slf4j.event.DefaultLoggingEvent(org.slf4j.event.Level.INFO, logger) {
					@Override
					public String getMessage() {
						logger.error("nested");
						return "event";
					}
				};
				((org.slf4j.spi.LoggingEventAware) logger).log(event);
			}
			case "mdc" -> {
				MDC.put("level", "spoof");
				MDC.put("a:\t", "v\n");
				LoggerFactory.getLogger("sample").atInfo().addKeyValue("level", "also spoof").log("context");
				MDC.clear();
			}
			case "mdc-noop" -> {
				MDC.put("key", "ignored");
				MDC.setContextMap(Map.of("key", "ignored"));
				if (MDC.get("key") != null || MDC.getCopyOfContextMap() != null)
					throw new AssertionError("stored context");
				MDC.remove("key");
				MDC.clear();
				MDC.setContextMap(null);
				MDC.pushByKey("stack", "ignored");
				var adapter = MDC.getMDCAdapter();
				if (MDC.popByKey("stack") != null || adapter.getCopyOfDequeByKey("stack") != null)
					throw new AssertionError("stored deque");
				adapter.clearDequeByKey("stack");
				LoggerFactory.getLogger("sample").info("MDC no-op passed");
			}
			case "mdc-contract" -> {
				MDC.put("key", "value");
				if (!"value".equals(MDC.get("key")))
					throw new AssertionError("put/get");
				var copy = MDC.getCopyOfContextMap();
				copy.put("key", "changed");
				if (!"value".equals(MDC.get("key")))
					throw new AssertionError("copy was live");
				MDC.setContextMap(copy);
				copy.put("key", "later");
				if (!"changed".equals(MDC.get("key")))
					throw new AssertionError("set did not copy");
				MDC.remove("key");
				if (MDC.get("key") != null)
					throw new AssertionError("remove");
				MDC.put("key", "clear");
				MDC.clear();
				if (MDC.get("key") != null)
					throw new AssertionError("clear");
				MDC.put("nullable", null);
				var nullCopy = MDC.getCopyOfContextMap();
				if (!nullCopy.containsKey("nullable") || nullCopy.get("nullable") != null)
					throw new AssertionError("null value not copied");
				MDC.clear();
				MDC.setContextMap(nullCopy);
				if (!MDC.getCopyOfContextMap().containsKey("nullable"))
					throw new AssertionError("null value not set");
				MDC.clear();
				MDC.put("key", "clear with null");
				MDC.setContextMap(null);
				if (MDC.get("key") != null)
					throw new AssertionError("set null");
				MDC.put("stack", "map value");
				MDC.pushByKey("stack", "outer");
				MDC.pushByKey("stack", "inner");
				var adapter = MDC.getMDCAdapter();
				if (adapter.getCopyOfDequeByKey("stack") != null || MDC.popByKey("stack") != null)
					throw new AssertionError("stored deque with custom map adapter");
				adapter.clearDequeByKey("stack");
				if (!"map value".equals(MDC.get("stack")))
					throw new AssertionError("deque operation changed map context");
				MDC.remove("stack");
				System.getLogger("system").log(Level.INFO, "MDC bridge passed");
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
					@Override
					protected Object[][] getContents() {
						return new Object[][] { { "greeting", "Bonjour {0,number,integer}" } };
					}
				};
				var logger = System.getLogger("system", bundle);
				logger.log(Level.INFO, "greeting", 42);
				logger.log(Level.INFO, "missing", new Object[] { "ignored" });
				logger.log(Level.INFO, "value {0}", new Object[] { new IllegalStateException("as argument") });
			}
			case "objects" -> {
				var logger = System.getLogger("system");
				logger.log(Level.INFO, Integer.valueOf(1234));
				logger.log(Level.INFO, new java.util.Date(0));
				Object[] array = { "x" };
				logger.log(Level.INFO, (Object) array);
				logger.log(Level.INFO, array.toString());
			}
			case "clock" -> {
				var logger = LoggerFactory.getLogger("clock");
				var system = System.getLogger("clock");
				logger.debug("disabled");
				system.log(Level.DEBUG, "disabled");
				logger.info("classic");
				logger.atInfo().addKeyValue("time", "spoof").log("fluent");
				system.log(Level.INFO, "system");
			}
			case "platform-named", "platform-unnamed", "virtual-named", "virtual-unnamed" -> {
				var logger = LoggerFactory.getLogger("threads");
				var system = System.getLogger("threads");
				Thread.Builder builder = args[0].startsWith("virtual") ? Thread.ofVirtual() : Thread.ofPlatform();
				builder.name(args[0].endsWith("-unnamed") ? "" : "worker");
				Runnable body = () -> {
					String id = Long.toString(Thread.currentThread().threadId());
					logger.info(id);
					logger.atInfo().addKeyValue("threadId", "spoof").log(id);
					system.log(Level.INFO, id);
				};
				var first = builder.unstarted(body);
				var second = builder.unstarted(body);
				first.start();
				second.start();
				first.join();
				second.join();
			}
			case "concurrent" -> {
				var logger = LoggerFactory.getLogger("sample");
				Thread[] threads = new Thread[8];
				for (int i = 0; i < threads.length; i++) {
					final int id = i;
					threads[i] = new Thread(() -> {
						for (int n = 0; n < 100; n++)
							logger.info("{}:{}", id, n);
					});
					threads[i].start();
				}
				for (var thread : threads)
					thread.join();
			}
			default -> throw new AssertionError(args[0]);
		}
	}

	private static final class MemoryMDC implements BalsaConfig.MDCAdapter {

		private final ThreadLocal<Map<String, String>> context = new ThreadLocal<>();

		@Override
		public void put(String key, String value) {
			var map = context.get();
			if (map == null) {
				map = new java.util.HashMap<>();
				context.set(map);
			}
			map.put(key, value);
		}

		@Override
		public String get(String key) {
			var map = context.get();
			return map == null ? null : map.get(key);
		}

		@Override
		public void remove(String key) {
			var map = context.get();
			if (map != null)
				map.remove(key);
		}

		@Override
		public void clear() {
			context.remove();
		}

		@Override
		public Map<String, String> getCopyOfContextMap() {
			var map = context.get();
			return map == null ? null : new java.util.HashMap<>(map);
		}

		@Override
		public void setContextMap(Map<String, String> values) {
			if (values == null)
				context.remove();
			else
				context.set(new java.util.HashMap<>(values));
		}

	}

	public static final class MdcConfig extends BalsaConfig {

		static final System.Logger EARLY = System.getLogger("early");

		public MdcConfig() {
			EARLY.log(Level.ERROR, "bootstrap should be suppressed");
		}

		@Override
		public Level levelOrNull(String name) {
			return name.equals("sample") ? Level.DEBUG : super.levelOrNull(name);
		}

		@Override
		public MDCAdapter createMDCAdapter() {
			return CONTEXT;
		}

	}

	private static final BalsaConfig.MDCAdapter CONTEXT = new MemoryMDC();

	public static final class MdcValues extends KeyValuesProvider {

		public MdcValues() {
		}

		@Override
		public void keyValues(BiConsumer<@Nullable String, @Nullable String> consumer) {
			var values = CONTEXT.getCopyOfContextMap();
			if (values != null)
				values.forEach(consumer);
		}

	}

	public static final class SystemOnlyConfig extends BalsaConfig {

		public SystemOnlyConfig() {
		}

		@Override
		public MDCAdapter createMDCAdapter() {
			throw new AssertionError("System.Logger must not create an MDC adapter");
		}

	}

	public static final class SystemValues extends KeyValuesProvider {

		public SystemValues() {
		}

		@Override
		public void keyValues(BiConsumer<@Nullable String, @Nullable String> consumer) {
			consumer.accept("source", "direct");
		}

	}

	public static final class CallbackValues extends KeyValuesProvider {

		static final ThreadLocal<Map<String, String>> CONTEXT = new ThreadLocal<>();

		public CallbackValues() {
		}

		@Override
		public void keyValues(BiConsumer<@Nullable String, @Nullable String> consumer) {
			var context = CONTEXT.get();
			if (context != null)
				context.forEach(consumer);
		}

	}

	public static final class BadMdcConfig extends BalsaConfig {

		public BadMdcConfig() {
		}

		@Override
		public MDCAdapter createMDCAdapter() {
			throw new IllegalStateException("secret=do-not-print");
		}

	}

	public static final class BadContextValues extends KeyValuesProvider {

		public BadContextValues() {
		}

		@Override
		public void keyValues(BiConsumer<@Nullable String, @Nullable String> consumer) {
			System.getLogger("nested").log(Level.ERROR, "nested");
			throw new IllegalArgumentException("secret=do-not-print\nforged:yes");
		}

	}

	public static final class ClockConfig extends BalsaConfig {

		private final java.util.concurrent.atomic.AtomicLong calls = new java.util.concurrent.atomic.AtomicLong();

		public ClockConfig() {
		}

		@Override
		public long now() {
			if (Boolean.getBoolean("test.clock.fail"))
				throw new IllegalArgumentException("secret=do-not-print\nforged:yes");
			System.getLogger("nested").log(Level.ERROR, "clock reentry should be suppressed");
			return 1_700_000_000_123L + calls.getAndIncrement();
		}

	}

	public static final class BadConfig extends BalsaConfig {

		public BadConfig() {
			throw new IllegalArgumentException("secret=do-not-print\nforged:yes");
		}

	}

	public static final class OtherConfig extends BalsaConfig {

		public OtherConfig() {
		}

	}

}
