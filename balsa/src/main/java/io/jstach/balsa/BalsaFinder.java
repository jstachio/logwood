package io.jstach.balsa;

import java.util.Objects;

public final class BalsaFinder extends System.LoggerFinder {

	public BalsaFinder() {
	}

	@Override
	public System.Logger getLogger(String name, Module module) {
		Objects.requireNonNull(module, "module");
		return Balsa.instance().getLogger(name);
	}

}
