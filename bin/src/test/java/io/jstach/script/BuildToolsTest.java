package io.jstach.script;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

/** Standalone regression checks; no testing dependency enters the runtime. */
public final class BuildToolsTest {

	public static void main(String[] args) throws Exception {
		check(Version.of("v1.0.0").tag().equals("v1.0.0"), "version tag");
		check(Version.of("1.1.0-SNAPSHOT").snapshot(), "snapshot parsing");
		for (String bad : new String[] { "1x0y0", "1.0", "1.0.0;echo injected", "1.0.0-SNAPSHOTx" }) {
			try {
				Version.of(bad);
				throw new AssertionError("Invalid version accepted: " + bad);
			}
			catch (IllegalArgumentException expected) {
			}
		}
		Command.validatePom(Version.of("1.1.0-SNAPSHOT"), Version.of("1.0.0"));
		var output = new StringBuilder();
		var error = new StringBuilder();
		int status = Command.execute("for ((i=0; i<20000; i++)); do echo stdout; echo stderr >&2; done", output, error,
				1);
		check(status == 0 && output.toString().equals("stdout\n"), "large child stdout and line limit");
		check(error.length() == 20000 * 7, "large child stderr drained");
		String value = "1<&\"'";
		String svg = DocBadge.badge("docs", value, "#007ec6");
		var factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
		check(document.getDocumentElement().getAttribute("aria-label").equals("docs: " + value), "badge XML escaping");
		var directory = Files.createTempDirectory("logwood-doc-test-");
		try {
			Files.createDirectories(directory.resolve("resources"));
			Files.writeString(directory.resolve("resources/logwood.js"), "// local navigation");
			Files.writeString(directory.resolve("index.html"), "<html><body>_VERSION_</body></html>");
			Files.writeString(directory.resolve("overview.md"), "# _VERSION_");
			JavadocJavascript.main(new String[] { directory.toString(), "1.0.0" });
			JavadocJavascript.main(new String[] { directory.toString(), "1.0.0" });
			String html = Files.readString(directory.resolve("index.html"));
			check(html.indexOf("data-logwood-docs") == html.lastIndexOf("data-logwood-docs"), "idempotent doc scripts");
			check(html.contains("resources/logwood.js") && !html.contains("_VERSION_"), "local versioned HTML");
			check(Files.readString(directory.resolve("overview.md")).equals("# 1.0.0"), "versioned Markdown");
			Path report = directory.resolve("jacoco.xml"), summary = directory.resolve("summary.md"),
					coverage = directory.resolve("coverage.svg");
			Files.writeString(report, """
					<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
					<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
					<report name="logwood"><package name="p"><counter type="LINE" missed="9" covered="9"/></package>
					<counter type="INSTRUCTION" missed="1" covered="9"/><counter type="BRANCH" missed="0" covered="0"/>
					<counter type="LINE" missed="1" covered="3"/><counter type="METHOD" missed="0" covered="2"/>
					<counter type="CLASS" missed="0" covered="1"/></report>
					""");
			CoverageReport.run(new String[] { report.toString(), "--summary", summary.toString(), "--badge",
					coverage.toString() });
			check(Files.readString(summary).contains("| Line | 75.0% | 3 | 1 |"), "coverage uses report counters");
			check(Files.readString(summary).contains("| Branch | 100.0% | 0 | 0 |"), "coverage without branches");
			check(factory.newDocumentBuilder()
				.parse(coverage.toFile())
				.getDocumentElement()
				.getAttribute("aria-label")
				.equals("coverage: 75.0%"), "coverage badge");
		}
		finally {
			try (var paths = Files.walk(directory)) {
				for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
					Files.delete(path);
			}
		}
		System.out.println("Build tool checks passed");
	}

	private static void check(boolean condition, String message) {
		if (!condition)
			throw new AssertionError(message);
	}

}
