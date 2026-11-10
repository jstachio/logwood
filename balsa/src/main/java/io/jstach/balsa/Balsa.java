package io.jstach.balsa;

import org.jspecify.annotations.Nullable;

import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import io.jstach.balsa.spi.BalsaService;
import io.jstach.balsa.spi.BalsaService.BalsaConfig;
import io.jstach.balsa.spi.BalsaService.KeyValuesProvider;
import java.util.ResourceBundle;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class Balsa {

	static final ThreadLocal<@Nullable Boolean> ACTIVE = new ThreadLocal<>();

	private static final Balsa INSTANCE = new Balsa();

	private final AtomicBoolean reported = new AtomicBoolean();

	private volatile int state; // 0=new, 1=configuring, 2=ready, 3=disabled

	private @Nullable BalsaConfig config;

	private KeyValuesProvider @Nullable [] keyValues;

	private int rootLevel;

	private boolean journal;

	private Balsa() {
	}

	public static Balsa instance() {
		INSTANCE.initialize();
		return INSTANCE;
	}

	void initialize() {
		if (state != 0)
			return;
		synchronized (this) {
			if (state != 0)
				return;
			state = 1;
		}
		// No application code under an initialization monitor or class-init lock.
		try {
			BalsaConfig selected = null;
			var contributors = new ArrayList<KeyValuesProvider>();
			for (var service : ServiceLoader.load(BalsaService.class, BalsaService.class.getClassLoader())) {
				if (service instanceof BalsaConfig settings) {
					if (selected != null)
						throw new IllegalStateException("Multiple BalsaConfig providers");
					selected = settings;
				}
				else if (service instanceof KeyValuesProvider values)
					contributors.add(values);
			}
			if (selected == null)
				selected = new BalsaConfig();
			contributors.sort(Comparator.comparingInt(KeyValuesProvider::order));
			KeyValuesProvider[] values = contributors.toArray(KeyValuesProvider[]::new);

			Level level = selected.levelOrNull("");
			String prefix = selected.propertyOrNull("balsa.journald");
			if (prefix != null && !prefix.equalsIgnoreCase("true") && !prefix.equalsIgnoreCase("false"))
				throw new IllegalArgumentException("Invalid journald option");
			config = selected;
			keyValues = values;
			rootLevel = level == null ? Level.INFO.getSeverity() : level.getSeverity();
			journal = "true".equalsIgnoreCase(prefix);
			state = 2;
		}
		catch (Throwable failure) {
			nonFatal(failure);
			state = 3;
			report("Balsa disabled: invalid configuration");
		}
	}

	BalsaSystemLogger getLogger(String name) {
		return new BalsaSystemLogger(Objects.requireNonNull(name, "name"), this);
	}

	int threshold(String name) {
		if (state == 3)
			return Integer.MAX_VALUE;
		if (state != 2 || ACTIVE.get() == Boolean.TRUE)
			return -1;
		ACTIVE.set(Boolean.TRUE);
		try {
			if (name.length() > 1024)
				return disabledLogger();
			var settings = Objects.requireNonNull(config);
			String current = name;
			for (int depth = 0; !current.isEmpty(); depth++) {
				if (depth == 64)
					return disabledLogger();
				Level level = settings.levelOrNull(current);
				if (level != null)
					return level.getSeverity();
				int dot = current.lastIndexOf('.');
				if (dot < 0)
					break;
				current = current.substring(0, dot);
			}
			return rootLevel;
		}
		catch (Throwable failure) {
			nonFatal(failure);
			return disabledLogger();
		}
		finally {
			ACTIVE.set(Boolean.FALSE);
		}
	}

	private int disabledLogger() {
		report("Balsa disabled a logger: invalid level configuration");
		return Integer.MAX_VALUE;
	}

	void write(String name, Level level, @Nullable String message, @Nullable Object @Nullable [] args,
			@Nullable Throwable thrown, @Nullable Consumer<Ltsv> eventValues, @Nullable ResourceBundle bundle,
			int mode) {
		if (state != 2 || ACTIVE.get() == Boolean.TRUE)
			return;
		ACTIVE.set(Boolean.TRUE);
		try {
			var out = new Ltsv(journal ? priority(level) : -1);
			out.start("level");
			out.text(level == Level.WARNING ? "WARN" : level.name());
			out.start("thread");
			out.text(Thread.currentThread().getName());
			out.start("logger");
			out.text(name);
			out.start("message");
			int count = args == null ? 0 : args.length;
			if (mode == 0 && thrown == null && args != null && count > 0 && args[count - 1] instanceof Throwable t) {
				thrown = t;
				count--;
			}
			if (mode == 1) {
				if (bundle != null && message != null) {
					try {
						message = bundle.getString(message);
					}
					catch (java.util.MissingResourceException ignored) {
						/* The key is the fallback. */ }
				}
				out.system(message, args);
			}
			else if (mode == 2)
				out.scalar(Objects.requireNonNull(args)[0]);
			else
				out.message(message, args, count);
			if (thrown != null) {
				out.start("error");
				out.throwable(thrown);
			}
			for (var provider : Objects.requireNonNull(keyValues))
				out.keyValues(provider);
			if (eventValues != null)
				eventValues.accept(out);
			out.writeKeyValues();
			print(out);
		}
		catch (Throwable failure) {
			nonFatal(failure);
			report("Balsa could not encode a log event");
		}
		finally {
			ACTIVE.set(Boolean.FALSE);
		}
	}

	public @Nullable BalsaConfig configuration() {
		return state == 2 ? config : null;
	}

	public void disable() {
		state = 3;
		report("Balsa disabled: invalid configuration");
	}

	void report(String message) {
		if (!reported.compareAndSet(false, true))
			return;
		try {
			var out = new Ltsv(journal ? 3 : -1);
			out.start("level");
			out.text("ERROR");
			out.start("logger");
			out.text("io.jstach.balsa");
			out.start("message");
			out.text(message);
			print(out);
		}
		catch (Throwable failure) {
			nonFatal(failure);
		}
	}

	private static void print(Ltsv out) {
		System.out.writeBytes(out.finish().getBytes(StandardCharsets.UTF_8));
	}

	@SuppressWarnings("removal") // Preserve fatal-error behavior for legacy ThreadDeath.
	public static void nonFatal(@Nullable Throwable failure) {
		for (int depth = 0; failure != null && depth < 8; depth++) {
			if (failure instanceof VirtualMachineError fatal && !(failure instanceof StackOverflowError))
				throw fatal;
			if (failure instanceof ThreadDeath death)
				throw death;
			Class<?> type = failure.getClass();
			if (type != ServiceConfigurationError.class && type != ExceptionInInitializerError.class
					&& type != java.lang.reflect.InvocationTargetException.class)
				return;
			failure = failure.getCause();
		}
	}

	private static int priority(Level level) {
		int severity = level.getSeverity();
		return severity >= 1000 ? 3 : severity >= 900 ? 4 : severity >= 800 ? 6 : 7;
	}

}
