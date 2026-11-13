package io.jstach.logwood;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;

class EncoderBoundaryTest {

	static Stream<Arguments> textCases() {
		return Stream.of(Arguments.of("\u001f !\u007e\u007f\u009f\u00a0", "\\u001f !~\\u007f\\u009f\u00a0"),
				Arguments.of("\t\n\r\\\"", "\\t\\n\\r\\\\\""), Arguments.of("\ud800", "\\ud800"),
				Arguments.of("\udfff", "\\udfff"), Arguments.of("\ud800x\udfff", "\\ud800x\\udfff"),
				Arguments.of("\ud800\ud800\udfff", "\\ud800\ud800\udfff"),
				Arguments.of("\udfff\ud800", "\\udfff\\ud800"),
				Arguments.of("\u088f\u0890\u0891\u0892", "\u088f\\u0890\\u0891\u0892"),
				Arguments.of(new String(Character.toChars(0x13438)), "\\ud80d\\udc38"),
				Arguments.of(new String(Character.toChars(0x13439)), "\\ud80d\\udc39"),
				Arguments.of(new String(Character.toChars(0x1343f)), "\\ud80d\\udc3f"),
				Arguments.of(new String(Character.toChars(0x13440)), new String(Character.toChars(0x13440))));
	}

	@ParameterizedTest
	@MethodSource("textCases")
	void escapeSpellingsAndUnicodeBoundariesAreExact(String input, String expected) {
		var out = new RecordEncoder(-1, false);
		out.start("message");
		out.text(input);
		assertEquals("""
				message:%s
				""".formatted(expected), out.finish());
	}

	@ParameterizedTest
	@CsvSource({ "55295,%ed%9f%bf", "55296,%ud800", "56319,%udbff", "56320,%udc00", "57343,%udfff", "57344,%ee%80%80" })
	void labelsDistinguishLoneSurrogatesFromUtf8(int codeUnit, String expected) {
		var out = new RecordEncoder(-1, false);
		out.extra(Character.toString((char) codeUnit), "value");
		assertEquals("""
				%s:value
				""".formatted(expected), out.finish());
	}

	static Stream<Arguments> substitutions() {
		return Stream.of(Arguments.of("{}", null, 1, "{}"), Arguments.of("{}", new Object[] { "ignored" }, 0, "{}"),
				Arguments.of("{} {}", new Object[] { "one", "unused" }, 1, "one {}"),
				Arguments.of("{", new Object[] { "unused" }, 1, "{"),
				Arguments.of("", new Object[] { "unused" }, 1, ""),
				Arguments.of("{}x", new Object[] { "one" }, 1, "onex"));
	}

	@ParameterizedTest
	@MethodSource("substitutions")
	void slf4jUsesOnlyTheRequestedArguments(String pattern, Object[] args, int count, String expected) {
		var out = new RecordEncoder(-1, false);
		out.start("message");
		out.message(pattern, args, count);
		assertEquals("""
				message:%s
				""".formatted(expected), out.finish());
	}

	@Test
	void aNullCustomValueIsEmptyInBothFormats() {
		var ltsv = new RecordEncoder(-1, false);
		ltsv.extra("key", null);
		assertEquals("""
				key:
				""", ltsv.finish());
		var json = new RecordEncoder(-1, true);
		json.extra("key", null);
		assertEquals("""
				{"key":""}
				""", json.finish());
	}

	@Test
	void fatalErrorsFromFailureMessagesEscape() {
		var fatal = new OutOfMemoryError("test only");
		var failure = new IllegalArgumentException() {
			@Override
			public String getMessage() {
				throw fatal;
			}
		};
		var value = new Object() {
			@Override
			public String toString() {
				throw failure;
			}
		};
		var out = new RecordEncoder(-1, false);
		out.start("message");
		assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> out.system("{0}", new Object[] { value })));
	}

}
