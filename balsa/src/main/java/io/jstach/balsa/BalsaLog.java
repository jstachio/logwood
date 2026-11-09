package io.jstach.balsa;

import java.io.ObjectStreamException;
import org.slf4j.Marker;
import org.slf4j.event.LoggingEvent;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.spi.LoggingEventAware;

final class BalsaLog extends LegacyAbstractLogger implements LoggingEventAware {

	private static final long serialVersionUID = 1L;

	private final transient Balsa balsa;

	private transient volatile int threshold = -1;

	BalsaLog(String name, Balsa balsa) {
		this.name = name;
		this.balsa = balsa;
	}

	private boolean enabled(int severity) {
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
		return BalsaLog.class.getName();
	}

	@Override
	protected Object readResolve() throws ObjectStreamException {
		return new BalsaLog(name, Balsa.instance());
	}

	@Override
	protected void handleNormalizedLoggingCall(org.slf4j.event.Level level, Marker marker, String pattern,
			Object[] args, Throwable thrown) {
		balsa.write(name, system(level), pattern, args, thrown, null, null, 0);
	}

	@Override
	public void log(LoggingEvent event) {
		if (!isErrorEnabled() || Balsa.ACTIVE.get() == Boolean.TRUE)
			return;
		System.Logger.Level level;
		String message;
		Object[] args;
		Throwable thrown;
		Balsa.ACTIVE.set(Boolean.TRUE);
		try {
			level = system(event.getLevel());
			if (!isLoggable(level))
				return;
			message = event.getMessage();
			args = event.getArgumentArray();
			thrown = event.getThrowable();
		}
		catch (Throwable failure) {
			Balsa.nonFatal(failure);
			balsa.report("Balsa could not encode a log event");
			return;
		}
		finally {
			Balsa.ACTIVE.set(Boolean.FALSE);
		}
		balsa.write(name, level, message, args, thrown, out -> {
			var pairs = event.getKeyValuePairs();
			if (pairs == null)
				return;
			int count = Math.min(pairs.size(), 32);
			for (int i = 0; i < count && out.room(); i++) {
				var pair = pairs.get(i);
				out.extra("kv.", pair.key, pair.value);
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
