package io.jstach.balsa;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Supplier;

final class BalsaSystemLogger implements System.Logger {

	private final String name;

	private final Balsa balsa;

	private volatile int threshold = -1;

	BalsaSystemLogger(String name, Balsa balsa) {
		this.name = name;
		this.balsa = balsa;
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
			current = balsa.threshold(name);
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
			balsa.write(name, level, message, null, thrown, null, bundle, 1);
	}

	@Override
	public void log(System.Logger.Level level, @Nullable ResourceBundle bundle, @Nullable String pattern,
			@Nullable Object @Nullable ... args) {
		if (isLoggable(level))
			balsa.write(name, level, pattern, args, null, null, bundle, 1);
	}

	@Override
	public void log(System.Logger.Level level, Object object) {
		Objects.requireNonNull(object, "object");
		if (isLoggable(level))
			balsa.write(name, level, null, new Object[] { object }, null, null, null, 2);
	}

	@Override
	public void log(System.Logger.Level level, Supplier<String> supplier) {
		log(level, supplier, null);
	}

	@Override
	public void log(System.Logger.Level level, Supplier<String> supplier, @Nullable Throwable thrown) {
		Objects.requireNonNull(supplier, "supplier");
		if (!isLoggable(level) || Balsa.ACTIVE.get() == Boolean.TRUE)
			return;
		@Nullable String message;
		Balsa.ACTIVE.set(Boolean.TRUE);
		try {
			message = supplier.get();
		}
		catch (Throwable failure) {
			Balsa.nonFatal(failure);
			message = "[supplier failed]";
		}
		finally {
			Balsa.ACTIVE.set(Boolean.FALSE);
		}
		balsa.write(name, level, message, null, thrown, null, null, 1);
	}

}
