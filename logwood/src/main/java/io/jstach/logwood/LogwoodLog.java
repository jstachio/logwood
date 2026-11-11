package io.jstach.logwood;

import org.jspecify.annotations.Nullable;

import java.io.ObjectStreamException;
import org.slf4j.Marker;
import org.slf4j.event.LoggingEvent;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.spi.LoggingEventAware;

public final class LogwoodLog extends LegacyAbstractLogger implements LoggingEventAware {

	private static final long serialVersionUID = 1L;

	private final transient Logwood logwood;

	private transient volatile int threshold = -1;

	public LogwoodLog(String name, Logwood logwood) {
		this.name = name;
		this.logwood = logwood;
	}

	private boolean enabled(int severity) {
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
	public boolean isTraceEnabled() {
		return enabled(400);
	}

	@Override
	public boolean isDebugEnabled() {
		return enabled(500);
	}

	@Override
	public boolean isInfoEnabled() {
		return enabled(800);
	}

	@Override
	public boolean isWarnEnabled() {
		return enabled(900);
	}

	@Override
	public boolean isErrorEnabled() {
		return enabled(1000);
	}

	private boolean isLoggable(System.Logger.Level level) {
		return enabled(level.getSeverity());
	}

	@Override
	protected String getFullyQualifiedCallerName() {
		return LogwoodLog.class.getName();
	}

	@Override
	protected Object readResolve() throws ObjectStreamException {
		return new LogwoodLog(name, Logwood.instance());
	}

	@Override
	protected void handleNormalizedLoggingCall(org.slf4j.event.Level level, @Nullable Marker marker,
			@Nullable String pattern, @Nullable Object @Nullable [] args, @Nullable Throwable thrown) {
		logwood.write(name, system(level), pattern, args, thrown, null, null, 0);
	}

	@Override
	public void log(LoggingEvent event) {
		if (!isErrorEnabled() || Logwood.ACTIVE.get() == Boolean.TRUE)
			return;
		System.Logger.Level level;
		@Nullable String message;
		@Nullable Object @Nullable [] args;
		@Nullable Throwable thrown;
		Logwood.ACTIVE.set(Boolean.TRUE);
		try {
			level = system(event.getLevel());
			if (!isLoggable(level))
				return;
			message = event.getMessage();
			args = event.getArgumentArray();
			thrown = event.getThrowable();
		}
		catch (Throwable failure) {
			Logwood.nonFatal(failure);
			logwood.report("Logwood could not encode a log event");
			return;
		}
		finally {
			Logwood.ACTIVE.set(Boolean.FALSE);
		}
		logwood.write(name, level, message, args, thrown, out -> {
			var pairs = event.getKeyValuePairs();
			if (pairs == null)
				return;
			int count = Math.min(pairs.size(), 32);
			for (int i = 0; i < count && out.room(); i++) {
				var pair = pairs.get(i);
				out.eventValue(pair.key, pair.value);
			}
			if (pairs.size() > count)
				out.truncated = true;
		}, null, 0);
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
