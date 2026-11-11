package logwood.scoped.smoke;

import java.lang.System.Logger.Level;
import io.jstach.rainbowgum.scopedkeyvalues.ScopedKeyValues;

public final class ScopedSmoke {

	private ScopedSmoke() {
	}

	public static void main(String[] args) throws Exception {
		try {
			Class.forName("com.fasterxml.jackson.core.JsonFactory");
			throw new AssertionError("JSON parser present at runtime");
		}
		catch (ClassNotFoundException expected) {
		}
		if (ModuleLayer.boot().findModule("org.jspecify").isPresent())
			throw new AssertionError("JSpecify module resolved");
		try {
			Class.forName("org.jspecify.annotations.Nullable");
			throw new AssertionError("JSpecify present");
		}
		catch (ClassNotFoundException expected) {
		}
		if (args[1].equals("absent")) {
			if (ModuleLayer.boot().findModule("org.slf4j").isPresent())
				throw new AssertionError("SLF4J module resolved");
			try {
				Class.forName("org.slf4j.Logger");
				throw new AssertionError("SLF4J present");
			}
			catch (ClassNotFoundException expected) {
			}
		}
		var logger = System.getLogger("scoped");
		ScopedKeyValues.builder().add("request", "outer").add("a:\t", "v\n\u001b[8m").run(() -> {
			logger.log(Level.INFO, "outer");
			ScopedKeyValues.builder().add("request", "inner").run(() -> {
				logger.log(Level.INFO, "inner");
				if (args[0].equals("both"))
					logFluent();
			});
			logger.log(Level.INFO, "restored");
		});
		logger.log(Level.INFO, "unbound");
	}

	private static void logFluent() {
		try {
			Class<?> factory = Class.forName("org.slf4j.LoggerFactory");
			Object logger = factory.getMethod("getLogger", String.class).invoke(null, "scoped");
			Object builder = Class.forName("org.slf4j.Logger").getMethod("atInfo").invoke(logger);
			Class<?> type = Class.forName("org.slf4j.spi.LoggingEventBuilder");
			type.getMethod("addKeyValue", String.class, Object.class).invoke(builder, "request", "event");
			type.getMethod("addKeyValue", String.class, Object.class).invoke(builder, "request", "last event");
			type.getMethod("log", String.class).invoke(builder, "fluent");
		}
		catch (ReflectiveOperationException failure) {
			throw new AssertionError(failure);
		}
	}

}
