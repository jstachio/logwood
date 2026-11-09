package io.jstach.balsa;

import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

public final class BalsaProvider implements SLF4JServiceProvider {
  public BalsaProvider() {}
  @Override public void initialize() { Balsa.instance().initialize(); }
  @Override public ILoggerFactory getLoggerFactory() { return Balsa.instance(); }
  @Override public IMarkerFactory getMarkerFactory() { return Balsa.instance(); }
  @Override public MDCAdapter getMDCAdapter() { return Balsa.instance().mdc; }
  @Override public String getRequestedApiVersion() { return "2.0.99"; }
}
