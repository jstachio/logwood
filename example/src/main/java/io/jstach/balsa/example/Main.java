package io.jstach.balsa.example;

import java.lang.System.Logger.Level;
import java.util.function.BiConsumer;
import io.jstach.balsa.spi.BalsaService.BalsaConfig;
import io.jstach.balsa.spi.BalsaService.KeyValuesProvider;
import org.slf4j.LoggerFactory;

public final class Main {

	private Main() {
	}

	public static void main(String[] args) {
		String name = args.length == 0 ? "world" : args[0];
		System.getLogger("example.system").log(Level.INFO, "Hello {0}", name);
		var logger = LoggerFactory.getLogger("example.slf4j");
		logger.debug("DEBUG inherited from the example logger's configuration");
		logger.info("Hello {}", name);
		logger.atInfo().addKeyValue("component", "event").log("Fluent fields override provider fields");
	}

	public static final class Config extends BalsaConfig {

		public Config() {
		}

		@Override
		public Level levelOrNull(String name) {
			Level configured = super.levelOrNull(name);
			return configured != null ? configured : name.equals("example") ? Level.DEBUG : null;
		}

	}

	public static final class DefaultFields extends KeyValuesProvider {

		public DefaultFields() {
		}

		@Override
		public int order() {
			return -10;
		}

		@Override
		public void keyValues(BiConsumer<String, String> consumer) {
			consumer.accept("app", "fallback");
			consumer.accept("component", "default");
		}

	}

	public static final class ApplicationFields extends KeyValuesProvider {

		public ApplicationFields() {
		}

		@Override
		public void keyValues(BiConsumer<String, String> consumer) {
			consumer.accept("app", "balsa-example");
			consumer.accept("component", "application");
		}

	}

}
