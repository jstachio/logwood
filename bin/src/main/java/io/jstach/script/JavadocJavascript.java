/* Adapted from Rainbow Gum. See bin/LICENSE for its BSD-3-Clause license. */
package io.jstach.script;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Adds local guide navigation and resolves the documentation version token. */
public final class JavadocJavascript {

	public static void main(String[] args) throws IOException {
		if (args.length != 2)
			throw new IllegalArgumentException("Usage: JavadocJavascript DOC_DIRECTORY VERSION");
		Path root = Path.of(args[0]);
		if (!Files.isRegularFile(root.resolve("index.html"))
				|| !Files.isRegularFile(root.resolve("resources/logwood.js")))
			throw new IOException("Missing aggregate Javadoc or local navigation script");
		try (var files = Files.walk(root)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				String name = file.getFileName().toString();
				if (!name.endsWith(".html") && !name.endsWith(".md") && !name.endsWith(".txt"))
					continue;
				String text = Files.readString(file).replace("_VERSION_", args[1]);
				if (name.endsWith(".html") && !text.contains("data-logwood-docs")) {
					String script = file.getParent()
						.relativize(root.resolve("resources/logwood.js"))
						.toString()
						.replace('\\', '/');
					text = text.replace("</body>",
							"<script defer data-logwood-docs src=\"" + script + "\"></script>\n</body>");
				}
				Files.writeString(file, text);
			}
		}
		System.out.println("Prepared Logwood documentation " + args[1]);
	}

}
