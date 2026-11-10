package io.jstach.balsa.spi;

import org.jspecify.annotations.Nullable;

import io.jstach.balsa.Balsa;
import io.jstach.balsa.BalsaLog;
import io.jstach.balsa.spi.BalsaService.BalsaConfig;

import java.util.Objects;
import java.util.Map;
import java.util.Deque;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

@SuppressWarnings("exports") // SLF4J consumers explicitly require its optional module.
public class BalsaProvider implements SLF4JServiceProvider, ILoggerFactory, MDCAdapter {

	private volatile BalsaConfig.@Nullable MDCAdapter mdc;

	public BalsaProvider() {
	}

	@Override
	public void initialize() {
		var balsa = Balsa.instance();
		var config = balsa.configuration();
		if (config == null)
			return;
		try {
			mdc = config.createMDCAdapter();
		}
		catch (Throwable failure) {
			Balsa.nonFatal(failure);
			balsa.disable();
		}
	}

	@Override
	public ILoggerFactory getLoggerFactory() {
		return this;
	}

	@Override
	public IMarkerFactory getMarkerFactory() {
		// Required by SLF4J; markers are never read or emitted by Balsa.
		return new BasicMarkerFactory();
	}

	@Override
	public MDCAdapter getMDCAdapter() {
		return this;
	}

	@Override
	public String getRequestedApiVersion() {
		return "2.0.99";
	}

	@Override
	public org.slf4j.Logger getLogger(String name) {
		return new BalsaLog(Objects.requireNonNull(name, "name"), Balsa.instance());
	}

	@Override
	public void put(String key, @Nullable String value) {
		var adapter = mdc;
		if (adapter != null)
			adapter.put(key, value);
	}

	@Override
	public @Nullable String get(String key) {
		var adapter = mdc;
		return adapter == null ? null : adapter.get(key);
	}

	@Override
	public void remove(String key) {
		var adapter = mdc;
		if (adapter != null)
			adapter.remove(key);
	}

	@Override
	public void clear() {
		var adapter = mdc;
		if (adapter != null)
			adapter.clear();
	}

	// SLF4J permits null MDC values but leaves its generic map type unannotated.
	@SuppressWarnings("NullAway")
	@Override
	public @Nullable Map<String, @Nullable String> getCopyOfContextMap() {
		var adapter = mdc;
		return adapter == null ? null : adapter.getCopyOfContextMap();
	}

	// Preserve SLF4J's nullable values across its unannotated generic boundary.
	@SuppressWarnings("NullAway")
	@Override
	public void setContextMap(@Nullable Map<String, @Nullable String> contextMap) {
		var adapter = mdc;
		if (adapter != null)
			adapter.setContextMap(contextMap);
	}

	// Required SLF4J compatibility methods are no-ops.
	@Override
	public void pushByKey(String key, String value) {
	}

	@Override
	public @Nullable String popByKey(String key) {
		return null;
	}

	@Override
	public @Nullable Deque<String> getCopyOfDequeByKey(String key) {
		return null;
	}

	@Override
	public void clearDequeByKey(String key) {
	}

}
