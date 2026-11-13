package io.jstach.logwood;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LoggerLevelsTest {

	@ParameterizedTest
	@CsvSource({ "ALL, true, true, true, true, true", "TRACE, true, true, true, true, true",
			"DEBUG, false, true, true, true, true", "INFO, false, false, true, true, true",
			"WARN, false, false, false, true, true", "ERROR, false, false, false, false, true",
			"OFF, false, false, false, false, false" })
	void everySlf4jThresholdIncludesItsOwnLevel(String threshold, boolean trace, boolean debug, boolean info,
			boolean warn, boolean error) throws Exception {
		String key = "logging.level.threshold-test", before = System.getProperty(key);
		try {
			System.setProperty(key, threshold);
			var constructor = Logwood.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			var core = constructor.newInstance();
			core.initialize();
			var logger = new LogwoodLog("threshold-test", core);
			assertEquals(List.of(trace, debug, info, warn, error), List.of(logger.isTraceEnabled(),
					logger.isDebugEnabled(), logger.isInfoEnabled(), logger.isWarnEnabled(), logger.isErrorEnabled()));
			// Resolution is cached for this logger, including OFF.
			System.setProperty(key, "ALL");
			assertEquals(List.of(trace, debug, info, warn, error), List.of(logger.isTraceEnabled(),
					logger.isDebugEnabled(), logger.isInfoEnabled(), logger.isWarnEnabled(), logger.isErrorEnabled()));
		}
		finally {
			if (before == null)
				System.clearProperty(key);
			else
				System.setProperty(key, before);
		}
	}

	@Test
	void suppressedResolutionCanBeRetriedOutsideTheCallback() {
		var logger = new LogwoodLog("callback-test", Logwood.instance());
		Logwood.ACTIVE.set(Boolean.TRUE);
		try {
			assertFalse(logger.isErrorEnabled());
		}
		finally {
			Logwood.ACTIVE.set(Boolean.FALSE);
		}
		assertTrue(logger.isErrorEnabled());
	}

}
