package io.jstach.balsa;

import java.util.Objects;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.helpers.NOPMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

public final class BalsaProvider implements SLF4JServiceProvider {

	private volatile MDCAdapter mdc = new NOPMDCAdapter();

	public BalsaProvider() {
	}

	@Override
	public void initialize() {
		var balsa = Balsa.instance();
		var config = balsa.configuration();
		if (config == null)
			return;
		try {
			mdc = Objects.requireNonNull(config.createMDCAdapter(), "MDC adapter");
		}
		catch (Throwable failure) {
			Balsa.nonFatal(failure);
			balsa.disable();
		}
	}

	@Override
	public ILoggerFactory getLoggerFactory() {
		return Balsa.instance();
	}

	@Override
	public IMarkerFactory getMarkerFactory() {
		// Required by SLF4J; markers are never read or emitted by Balsa.
		return new BasicMarkerFactory();
	}

	@Override
	public MDCAdapter getMDCAdapter() {
		return mdc;
	}

	@Override
	public String getRequestedApiVersion() {
		return "2.0.99";
	}

}
