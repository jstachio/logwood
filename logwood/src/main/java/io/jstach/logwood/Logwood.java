package io.jstach.logwood;

import org.jspecify.annotations.Nullable;

import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import io.jstach.logwood.spi.LogwoodService;
import io.jstach.logwood.spi.LogwoodService.LogwoodConfig;
import io.jstach.logwood.spi.LogwoodService.KeyValuesProvider;
import io.jstach.logwood.spi.LogwoodService.KeyValuesMode;
import java.util.ResourceBundle;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class Logwood {

	static final ThreadLocal<@Nullable Boolean> ACTIVE = new ThreadLocal<>();

	private static final Logwood INSTANCE = new Logwood();

	private final AtomicBoolean reported = new AtomicBoolean();

	private volatile int state; // 0=new, 1=configuring, 2=ready, 3=disabled

	private @Nullable LogwoodConfig config;

	private KeyValuesProvider @Nullable [] keyValues;

	private int rootLevel;

	private boolean journal;

	private boolean json;

	private boolean lines;

	private static final String[] LABELS = { "time", "level", "thread", "logger", "message" };

	// Indexes into LABELS: the first columns are written without labels.
	private int[] order = { 0, 1, 2, 3, 4 };

	private int columns;

	private KeyValuesMode keyValuesMode = KeyValuesMode.FLAT;

	private Logwood() {
	}

	public static Logwood instance() {
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
			LogwoodConfig selected = null;
			var contributors = new ArrayList<KeyValuesProvider>();
			for (var service : ServiceLoader.load(LogwoodService.class, LogwoodService.class.getClassLoader())) {
				if (service instanceof LogwoodConfig settings) {
					if (selected != null)
						throw new IllegalStateException("Multiple LogwoodConfig providers");
					selected = settings;
				}
				else if (service instanceof KeyValuesProvider values)
					contributors.add(values);
			}
			if (selected == null)
				selected = new LogwoodConfig();
			String format = selected.propertyOrNull("logging.format");
			if (format != null && !format.equalsIgnoreCase("ltsv") && !format.equalsIgnoreCase("jsonl"))
				throw new IllegalArgumentException("Invalid log format");
			json = "jsonl".equalsIgnoreCase(format);
			String stacktrace = selected.propertyOrNull("logging.ltsv.stacktrace");
			boolean lines = "lines".equalsIgnoreCase(stacktrace) || "true".equalsIgnoreCase(stacktrace);
			if (stacktrace != null && !lines && !"inline".equalsIgnoreCase(stacktrace)
					&& !"default".equalsIgnoreCase(stacktrace) && !"false".equalsIgnoreCase(stacktrace))
				throw new IllegalArgumentException("Invalid stack trace option");
			keyValuesMode = Objects.requireNonNull(selected.keyValuesMode());
			if (keyValuesMode == KeyValuesMode.NONE)
				contributors.clear();
			else
				contributors.sort(Comparator.comparingInt(KeyValuesProvider::order));
			KeyValuesProvider[] values = contributors.toArray(KeyValuesProvider[]::new);
			Level level = selected.levelOrNull("");
			int[] order = { 0, 1, 2, 3, 4 };
			int columns = 0;
			String list = selected.propertyOrNull("logging.ltsv.columns");
			if (list != null && !list.isBlank()) {
				for (String column : list.split(",", -1)) {
					String name = column.strip().toLowerCase(Locale.ROOT);
					int field = name.equals("timestamp") ? 0
							: name.equals("msg") ? 4 : java.util.List.of(LABELS).indexOf(name);
					// Unknown (-1) and repeated fields are not found among the remaining
					// ones.
					int at = columns;
					while (at < order.length && order[at] != field)
						at++;
					if (at == order.length)
						throw new IllegalArgumentException("Invalid columns option");
					System.arraycopy(order, columns, order, columns + 1, at - columns);
					order[columns++] = field;
				}
			}
			String prefix = selected.propertyOrNull("logging.journald");
			if (prefix != null && !prefix.equalsIgnoreCase("true") && !prefix.equalsIgnoreCase("false"))
				throw new IllegalArgumentException("Invalid journald option");
			config = selected;
			keyValues = values;
			rootLevel = level == null ? Level.INFO.getSeverity() : level.getSeverity();
			journal = "true".equalsIgnoreCase(prefix);
			this.lines = lines && !json;
			if (!json) {
				this.order = order;
				this.columns = columns;
			}
			state = 2;
		}
		catch (Throwable failure) {
			nonFatal(failure);
			state = 3;
			report("Logwood disabled: invalid configuration");
		}
	}

	LogwoodSystemLogger getLogger(String name) {
		return new LogwoodSystemLogger(Objects.requireNonNull(name, "name"), this);
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
		report("Logwood disabled a logger: invalid level configuration");
		return Integer.MAX_VALUE;
	}

	void write(String name, Level level, @Nullable String message, @Nullable Object @Nullable [] args,
			@Nullable Throwable thrown, @Nullable Consumer<RecordEncoder> eventValues, @Nullable ResourceBundle bundle,
			int mode) {
		if (state != 2 || ACTIVE.get() == Boolean.TRUE)
			return;
		ACTIVE.set(Boolean.TRUE);
		try {
			var out = new RecordEncoder(journal ? priority(level) : -1, json, keyValuesMode, lines);
			int count = args == null ? 0 : args.length;
			if (mode == 0 && thrown == null && args != null && count > 0 && args[count - 1] instanceof Throwable t) {
				thrown = t;
				count--;
			}
			var thread = Thread.currentThread();
			for (int i = 0; i < order.length; i++) {
				// A column is written without its label.
				String label = i < columns ? "" : LABELS[order[i]];
				switch (order[i]) {
					case 0 -> {
						if (!journal)
							out.time(label, Objects.requireNonNull(config).now());
					}
					case 1 -> {
						out.start(label);
						out.text(level == Level.WARNING ? "WARN" : level.name());
					}
					case 2 -> {
						out.start(label);
						out.text(thread.getName());
						out.numberField(i < columns ? "" : "threadId", thread.threadId());
					}
					case 3 -> {
						out.start(label);
						out.text(name);
					}
					default -> {
						out.start(label);
						message(out, message, args, count, bundle, mode);
					}
				}
			}
			if (thrown != null)
				out.error(thrown);
			if (keyValuesMode != KeyValuesMode.NONE) {
				for (var provider : Objects.requireNonNull(keyValues))
					out.keyValues(provider);
				if (eventValues != null)
					eventValues.accept(out);
				out.writeKeyValues();
			}
			print(out);
		}
		catch (Throwable failure) {
			nonFatal(failure);
			report("Logwood could not encode a log event");
		}
		finally {
			ACTIVE.set(Boolean.FALSE);
		}
	}

	private static void message(RecordEncoder out, @Nullable String message, @Nullable Object @Nullable [] args,
			int count, @Nullable ResourceBundle bundle, int mode) {
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
	}

	public @Nullable LogwoodConfig configuration() {
		return state == 2 ? config : null;
	}

	public void disable() {
		state = 3;
		report("Logwood disabled: invalid configuration");
	}

	void report(String message) {
		if (!reported.compareAndSet(false, true))
			return;
		try {
			var out = new RecordEncoder(journal ? 3 : -1, json);
			if (!journal)
				out.time(System.currentTimeMillis());
			out.start("level");
			out.text("ERROR");
			out.numberField("threadId", Thread.currentThread().threadId());
			out.start("logger");
			out.text("io.jstach.logwood");
			out.start("message");
			out.text(message);
			print(out);
		}
		catch (Throwable failure) {
			nonFatal(failure);
		}
	}

	private static void print(RecordEncoder out) {
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
