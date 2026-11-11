package io.jstach.logwood;

import java.util.Objects;

public final class LogwoodFinder extends System.LoggerFinder {

	public LogwoodFinder() {
	}

	@Override
	public System.Logger getLogger(String name, Module module) {
		Objects.requireNonNull(module, "module");
		return Logwood.instance().getLogger(name);
	}

}
