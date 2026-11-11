package io.jstach.logwood;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Supplier;

final class LogwoodSystemLogger implements System.Logger {

	private final String name;

	private final Logwood logwood;

	private volatile int threshold = -1;

	LogwoodSystemLogger(String name, Logwood logwood) {
		this.name = name;
		this.logwood = logwood;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public boolean isLoggable(Level level) {
		int severity = level.getSeverity();
		int current = threshold;
		if (current == -1) {
			current = logwood.threshold(name);
			if (current == -1)
				return false;
			threshold = current;
		}
		return current != Integer.MAX_VALUE && severity >= current && severity != Integer.MAX_VALUE;
	}

	@Override
	public void log(System.Logger.Level level, @Nullable ResourceBundle bundle, @Nullable String message,
			@Nullable Throwable thrown) {
		if (isLoggable(level))
			logwood.write(name, level, message, null, thrown, null, bundle, 1);
	}

	@Override
	public void log(System.Logger.Level level, @Nullable ResourceBundle bundle, @Nullable String pattern,
			@Nullable Object @Nullable ... args) {
		if (isLoggable(level))
			logwood.write(name, level, pattern, args, null, null, bundle, 1);
	}

	@Override
	public void log(System.Logger.Level level, Object object) {
		Objects.requireNonNull(object, "object");
		if (isLoggable(level))
			logwood.write(name, level, null, new Object[] { object }, null, null, null, 2);
	}

	@Override
	public void log(System.Logger.Level level, Supplier<String> supplier) {
		log(level, supplier, null);
	}

	@Override
	public void log(System.Logger.Level level, Supplier<String> supplier, @Nullable Throwable thrown) {
		Objects.requireNonNull(supplier, "supplier");
		if (!isLoggable(level) || Logwood.ACTIVE.get() == Boolean.TRUE)
			return;
		@Nullable String message;
		Logwood.ACTIVE.set(Boolean.TRUE);
		try {
			message = supplier.get();
		}
		catch (Throwable failure) {
			Logwood.nonFatal(failure);
			message = "[supplier failed]";
		}
		finally {
			Logwood.ACTIVE.set(Boolean.FALSE);
		}
		logwood.write(name, level, message, null, thrown, null, null, 1);
	}

}
