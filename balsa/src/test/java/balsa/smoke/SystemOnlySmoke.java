package balsa.smoke;

import java.lang.System.Logger.Level;
import java.util.ListResourceBundle;
import java.util.function.BiConsumer;
import io.jstach.balsa.spi.BalsaService;
import io.jstach.balsa.spi.BalsaService.BalsaConfig;
import io.jstach.balsa.spi.BalsaService.KeyValuesProvider;

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
		BalsaService.class.getPermittedSubclasses();
		BalsaConfig.class.getMethods();
		BalsaConfig.MDCAdapter.class.getMethods();
		var logger = System.getLogger("system");
		logger.getClass().getDeclaredMethods();
		if (args[0].equals("services")) {
			logger.log(Level.INFO, "services");
			logger.log(Level.INFO, "services");
			return;
		}
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
		public MDCAdapter createMDCAdapter() {
			throw new AssertionError("MDC requested");
		}

	}

	public static final class Values extends KeyValuesProvider {

		public Values() {
		}

		@Override
		public void keyValues(BiConsumer<String, String> consumer) {
			System.getLogger("nested").log(Level.ERROR, "nested");
			consumer.accept("source", "direct");
		}

	}

	public static final class BadSettings extends BalsaConfig {

		public BadSettings() {
			throw new IllegalArgumentException("secret=do-not-print");
		}

	}

	public static final class CountingConfig extends BalsaConfig {

		static int instances;

		public CountingConfig() {
			if (++instances != 1)
				throw new AssertionError("config constructed twice");
			System.getLogger("bootstrap").log(Level.ERROR, "bootstrap should be suppressed");
		}

	}

	public static final class LowValues extends KeyValuesProvider {

		static int instances;

		public LowValues() {
			if (++instances != 1)
				throw new AssertionError("provider constructed twice");
		}

		@Override
		public int order() {
			return Integer.MIN_VALUE;
		}

		@Override
		public void keyValues(BiConsumer<String, String> consumer) {
			consumer.accept("winner", "low");
			consumer.accept("low.instances", Integer.toString(instances));
		}

	}

	public static final class HighValues extends KeyValuesProvider {

		static int instances;

		public HighValues() {
			if (++instances != 1)
				throw new AssertionError("provider constructed twice");
		}

		@Override
		public int order() {
			return Integer.MAX_VALUE;
		}

		@Override
		public void keyValues(BiConsumer<String, String> consumer) {
			consumer.accept("winner", "high");
			consumer.accept("high.instances", Integer.toString(instances));
			consumer.accept("config.instances", Integer.toString(CountingConfig.instances));
		}

	}

	public static final class FirstValues extends KeyValuesProvider {

		public FirstValues() {
		}

		@Override
		public void keyValues(BiConsumer<String, String> consumer) {
			consumer.accept("tie", "first");
		}

	}

	public static final class SecondValues extends KeyValuesProvider {

		public SecondValues() {
		}

		@Override
		public void keyValues(BiConsumer<String, String> consumer) {
			consumer.accept("tie", "second");
		}

	}

	public static final class BadOrder extends KeyValuesProvider {

		public BadOrder() {
		}

		@Override
		public int order() {
			throw new IllegalStateException("secret=do-not-print");
		}

		@Override
		public void keyValues(BiConsumer<String, String> consumer) {
			throw new AssertionError("called");
		}

	}

}
