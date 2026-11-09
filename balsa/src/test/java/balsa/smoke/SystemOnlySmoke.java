package balsa.smoke;

import java.lang.System.Logger.Level;
import java.util.ListResourceBundle;
import java.util.function.BiConsumer;
import io.jstach.balsa.BalsaConfig;

public final class SystemOnlySmoke {

	private SystemOnlySmoke() {
	}

	public static void main(String[] args) throws Exception {
		if (ModuleLayer.boot().findModule("org.slf4j").isPresent())
			throw new AssertionError("SLF4J module resolved");
		try {
			Class.forName("org.slf4j.Logger", false, ClassLoader.getSystemClassLoader());
			throw new AssertionError("SLF4J API is present");
		}
		catch (ClassNotFoundException expected) {
		}
		// Reflecting the SPI must not require any SLF4J return or parameter types.
		BalsaConfig.class.getMethods();
		BalsaConfig.MDCAdapter.class.getMethods();
		var logger = System.getLogger("system");
		logger.getClass().getDeclaredMethods();
		if (args[0].equals("basic")) {
			logger.log(Level.INFO, "system only");
			logger.log(Level.WARNING, "argument {0}", "value");
			logger.log(Level.DEBUG, () -> {
				throw new AssertionError("disabled supplier evaluated");
			});
		}
		else {
			logger.log(Level.INFO, (Object) Integer.valueOf(1234));
			logger.log(Level.INFO, () -> {
				System.getLogger("nested").log(Level.ERROR, "nested");
				return "supplier";
			});
			var bundle = new ListResourceBundle() {
				@Override
				protected Object[][] getContents() {
					return new Object[][] { { "greeting", "Bonjour {0}" } };
				}
			};
			System.getLogger("system", bundle).log(Level.INFO, "greeting", "world");
			logger.log(Level.WARNING, "value {0}", new Object[] { new IllegalStateException("argument") });
			logger.log(Level.ERROR, "failure", new IllegalStateException("line\n\u001b[8m"));
			logger.log(Level.OFF, () -> {
				throw new AssertionError("OFF evaluated");
			});
		}
	}

	public static final class Settings extends BalsaConfig {

		public Settings() {
		}

		@Override
		public void keyValues(BiConsumer<String, String> consumer) {
			System.getLogger("nested").log(Level.ERROR, "nested");
			consumer.accept("source", "direct");
		}

		@Override
		public MDCAdapter createMDCAdapter() {
			throw new AssertionError("MDC requested");
		}

	}

	public static final class BadSettings extends BalsaConfig {

		public BadSettings() {
			throw new IllegalArgumentException("secret=do-not-print");
		}

	}

}
