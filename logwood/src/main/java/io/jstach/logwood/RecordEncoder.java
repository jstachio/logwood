package io.jstach.logwood;

import org.jspecify.annotations.Nullable;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Date;
import java.util.Locale;
import io.jstach.logwood.spi.LogwoodService.KeyValuesProvider;
import io.jstach.logwood.spi.LogwoodService.KeyValuesMode;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

final class RecordEncoder implements BiConsumer<@Nullable String, @Nullable String> {

	static final int RECORD = 16_384, VALUE = 4096, KEY = 256;

	private static final int CONTENT = RECORD - 32;

	private static final Set<String> RESERVED = Set.of("time", "level", "logger", "message", "thread", "threadId",
			"threadName", "error", "stacktrace", "keyValues", "truncated");

	private static final String HEX = "0123456789abcdef";

	private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder().appendInstant(3)
		.toFormatter(Locale.ROOT);

	private final StringBuilder out = new StringBuilder(128);

	private final boolean json;

	private final KeyValuesMode keyValuesMode;

	private boolean quoted;

	private int end, frames, throwables, fields;

	private @Nullable Map<String, @Nullable Object> keyValues;

	private boolean collecting;

	boolean truncated;

	RecordEncoder(int priority, boolean json) {
		this(priority, json, KeyValuesMode.FLAT);
	}

	RecordEncoder(int priority, boolean json, KeyValuesMode keyValuesMode) {
		this.json = json;
		this.keyValuesMode = keyValuesMode;
		if (priority >= 0)
			out.append('<').append(priority).append('>');
		if (json)
			out.append('{');
	}

	boolean room() {
		return out.length() < CONTENT;
	}

	void start(String label) {
		start(label, true, 0);
	}

	private boolean start(String label, boolean string, int reserve) {
		int before = out.length();
		if ((long) before + label.length() + (json ? 6 : 2) + reserve > CONTENT) {
			truncated = true;
			end = out.length();
			return false;
		}
		if (json) {
			if (quoted)
				out.append('"');
			if (fields != 0)
				out.append(',');
			out.append('"');
			end = CONTENT - 3 - reserve;
			if (!text(label, 0, label.length())) {
				// A member name must be complete; clipping it could merge distinct keys.
				out.setLength(before);
				end = before;
				return false;
			}
			out.append("\":");
			if (string)
				out.append('"');
			quoted = string;
		}
		else {
			if (fields != 0)
				out.append('\t');
			out.append(label).append(':');
		}
		fields++;
		end = Math.min(CONTENT, out.length() + VALUE);
		return true;
	}

	void numberField(String label, long value) {
		if (start(label, false, 20))
			number(value);
	}

	void number(long value) {
		if (end - out.length() >= 20)
			out.append(value);
		else
			text(Long.toString(value));
	}

	void time(long epochMillis) {
		start("time");
		text(TIME.format(Instant.ofEpochMilli(epochMillis)));
	}

	void text(@Nullable String value) {
		if (value == null)
			value = "null";
		text(value, 0, value.length());
	}

	private boolean text(String value, int start, int limit) {
		int i = start;
		while (i < limit && out.length() < end) {
			int first = i;
			int fence = Math.min(limit, i + end - out.length());
			while (i < fence) {
				char c = value.charAt(i);
				if (c == '\\' || json && c == '"' || escaped(c) || Character.isSurrogate(c))
					break;
				i++;
			}
			out.append(value, first, i);
			if (i == limit)
				return true;
			if (i == fence && out.length() == end)
				break;
			char c = value.charAt(i++);
			if (Character.isHighSurrogate(c) && i < limit && Character.isLowSurrogate(value.charAt(i))) {
				char low = value.charAt(i++);
				if (format(Character.toCodePoint(c, low))) {
					if (end - out.length() < 12) {
						truncated = true;
						return false;
					}
					hex(c);
					hex(low);
				}
				else {
					if (end - out.length() < 2) {
						truncated = true;
						return false;
					}
					out.append(c).append(low);
				}
			}
			else {
				int length = c == '\\' || json && c == '"' || c == '\t' || c == '\n' || c == '\r' ? 2 : 6;
				if (end - out.length() < length) {
					truncated = true;
					return false;
				}
				if (c == '\\')
					out.append("\\\\");
				else if (json && c == '"')
					out.append("\\\"");
				else if (c == '\t')
					out.append("\\t");
				else if (c == '\n')
					out.append("\\n");
				else if (c == '\r')
					out.append("\\r");
				else
					hex(c);
			}
		}
		if (i < limit)
			truncated = true;
		return i == limit;
	}

	private static boolean escaped(char c) {
		return c < ' ' || c >= 0x7f && Escapes.contains(c);
	}

	// Cache BMP classification on first use; printable ASCII never initializes this.
	// Surrogate pairs still use format(int), and lone surrogates are handled by text.
	private static final class Escapes {

		private static final long[] BITS = bits();

		private static long[] bits() {
			long[] bits = new long[(Character.MAX_VALUE + 1) >>> 6];
			for (int c = 0x7f; c <= Character.MAX_VALUE; c++) {
				if (c <= 0x9f || c == 0x2028 || c == 0x2029 || format(c))
					bits[c >>> 6] |= 1L << (c & 63);
			}
			return bits;
		}

		static boolean contains(char c) {
			return (BITS[c >>> 6] & (1L << (c & 63))) != 0;
		}

	}

	private static boolean format(int c) {
		// Unicode 17 FORMAT additions, including runtimes with older Unicode data.
		return c == 0x890 || c == 0x891 || c >= 0x13439 && c <= 0x1343f || Character.getType(c) == Character.FORMAT;
	}

	private void hex(char c) {
		out.append("\\u")
			.append(HEX.charAt(c >>> 12))
			.append(HEX.charAt(c >>> 8 & 15))
			.append(HEX.charAt(c >>> 4 & 15))
			.append(HEX.charAt(c & 15));
	}

	void message(@Nullable String pattern, @Nullable Object @Nullable [] args, int count) {
		if (pattern == null || args == null || count == 0) {
			text(pattern);
			return;
		}
		int limit = Math.min(pattern.length(), VALUE), cursor = 0, argument = 0;
		while (cursor < limit && argument < count && out.length() < end) {
			int anchor = cursor;
			while (anchor + 1 < limit && (pattern.charAt(anchor) != '{' || pattern.charAt(anchor + 1) != '}'))
				anchor++;
			if (anchor + 1 >= limit)
				break;
			if (argument == 64) {
				truncated = true;
				break;
			}
			boolean slash = anchor > 0 && pattern.charAt(anchor - 1) == '\\';
			boolean doubled = anchor > 1 && pattern.charAt(anchor - 2) == '\\';
			text(pattern, cursor, slash ? anchor - 1 : anchor);
			if (slash && !doubled)
				text("{}");
			else
				object(args[argument++], 0);
			cursor = anchor + 2;
		}
		text(pattern, cursor, limit);
		if (limit < pattern.length())
			truncated = true;
	}

	void object(@Nullable Object value, int depth) {
		if (out.length() >= end) {
			truncated = true;
			return;
		}
		if (value == null) {
			text("null");
			return;
		}
		try {
			if (value instanceof String s) {
				text(s);
				return;
			}
			if (value.getClass().isArray()) {
				if (depth == 8) {
					text("[...]");
					truncated = true;
					return;
				}
				text("[");
				int size = Array.getLength(value), count = Math.min(size, 32);
				for (int i = 0; i < count && out.length() < end; i++) {
					if (i != 0)
						text(", ");
					object(Array.get(value, i), depth + 1);
				}
				if (size > count) {
					text(", ...");
					truncated = true;
				}
				text("]");
			}
			else
				text(value.toString());
		}
		catch (Throwable failure) {
			Logwood.nonFatal(failure);
			text("[toString failed]");
		}
	}

	void scalar(@Nullable Object value) {
		try {
			text(String.valueOf(value));
		}
		catch (Throwable failure) {
			Logwood.nonFatal(failure);
			text("[toString failed]");
		}
	}

	void system(@Nullable String pattern, @Nullable Object @Nullable [] args) {
		if (pattern == null || args == null || args.length == 0) {
			text(pattern);
			return;
		}
		if (pattern.length() > 1024) {
			text(pattern);
			truncated = true;
			return;
		}
		boolean indexed = false;
		for (int i = 0; i + 1 < pattern.length(); i++)
			if (pattern.charAt(i) == '{' && pattern.charAt(i + 1) >= '0' && pattern.charAt(i + 1) <= '9')
				indexed = true;
		if (!indexed) {
			text(pattern);
			return;
		}
		long indices = indices(pattern);
		if (indices < 0) {
			text(pattern);
			text(" [format rejected]");
			truncated = true;
			return;
		}
		@Nullable Object[] safe = new @Nullable Object[Math.min(args.length, 32)];
		try {
			for (int i = 0; i < safe.length; i++) {
				if ((indices & (1L << i)) == 0)
					continue;
				Object value = args[i];
				if (value == null || value instanceof Byte || value instanceof Short || value instanceof Integer
						|| value instanceof Long || value instanceof Float || value instanceof Double)
					safe[i] = value;
				else if (value instanceof Date d)
					safe[i] = new Date(d.getTime());
				else if (value instanceof BigInteger n && n.bitLength() <= 4096)
					safe[i] = n;
				else if (value instanceof BigDecimal n && n.unscaledValue().bitLength() <= 4096
						&& Math.abs((long) n.scale()) <= 4096)
					safe[i] = n;
				else {
					String s = String.valueOf(value);
					if (s != null && s.length() > VALUE) {
						s = s.substring(0, VALUE);
						truncated = true;
					}
					safe[i] = s;
				}
			}
			text(MessageFormat.format(pattern, safe));
		}
		catch (Throwable failure) {
			Logwood.nonFatal(failure);
			text(pattern);
			text(" [MessageFormat failed: ");
			@Nullable String reason = null;
			try {
				reason = failure.getMessage();
			}
			catch (Throwable accessor) {
				Logwood.nonFatal(accessor);
			}
			text(reason == null ? failure.getClass().getName() : reason);
			text("]");
		}
	}

	private static long indices(String pattern) {
		long mask = 0;
		int depth = 0, fields = 0;
		boolean quote = false;
		for (int i = 0; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (c == '\'') {
				if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '\'')
					i++;
				else
					quote = !quote;
			}
			else if (c == '{') {
				if (++depth > 8 || ++fields > 64)
					return -1;
				if (quote)
					continue;
				int index = 0, j = i + 1;
				if (j == pattern.length() || pattern.charAt(j) < '0' || pattern.charAt(j) > '9')
					continue;
				while (j < pattern.length() && pattern.charAt(j) >= '0' && pattern.charAt(j) <= '9') {
					index = index * 10 + pattern.charAt(j++) - '0';
					if (index >= 32)
						return -1;
				}
				mask |= 1L << index;
			}
			else if (c == '}' && depth > 0)
				depth--;
		}
		return mask;
	}

	void keyValues(KeyValuesProvider provider) {
		if (keyValuesMode == KeyValuesMode.NONE)
			return;
		collecting = true;
		try {
			provider.keyValues(this);
		}
		finally {
			collecting = false;
		}
	}

	@Override
	public void accept(@Nullable String key, @Nullable String value) {
		if (collecting)
			put(key, value, 32);
	}

	void eventValue(@Nullable String key, @Nullable Object value) {
		put(key, value, 64);
	}

	private void put(@Nullable String key, @Nullable Object value, int limit) {
		if (keyValuesMode == KeyValuesMode.NONE)
			return;
		if (key == null || key.length() > KEY) {
			truncated = true;
			return;
		}
		if (keyValues == null)
			keyValues = new LinkedHashMap<>();
		if (keyValues.size() >= limit && !keyValues.containsKey(key)) {
			truncated = true;
			return;
		}
		keyValues.put(key, value);
	}

	void writeKeyValues() {
		if (keyValues == null)
			return;
		for (var entry : keyValues.entrySet()) {
			if (!room()) {
				truncated = true;
				break;
			}
			extra(entry.getKey(), entry.getValue());
		}
	}

	void extra(@Nullable String key, @Nullable Object value) {
		if (keyValuesMode == KeyValuesMode.NONE)
			return;
		if (key == null || key.length() > KEY) {
			truncated = true;
			return;
		}
		String prefix = keyValuesMode == KeyValuesMode.PREFIXED || RESERVED.contains(key)
				|| key.startsWith("keyValues.") ? "keyValues." : "";
		if (json) {
			if (start(prefix + key, true, 0) && value != null)
				object(value, 0);
			return;
		}
		var label = new StringBuilder(32);
		if (key.isEmpty())
			label.append('%');
		for (int i = 0; i < key.length(); i++) {
			int c = key.charAt(i);
			if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '.'
					|| c == '-')
				label.append((char) c);
			else {
				if (Character.isHighSurrogate((char) c) && i + 1 < key.length()
						&& Character.isLowSurrogate(key.charAt(i + 1)))
					c = Character.toCodePoint((char) c, key.charAt(++i));
				if (c >= 0xd800 && c <= 0xdfff) {
					label.append("%u");
					for (int shift = 12; shift >= 0; shift -= 4)
						label.append(HEX.charAt(c >>> shift & 15));
				}
				else if (c < 0x80)
					percent(label, c);
				else if (c < 0x800) {
					percent(label, 0xc0 | c >>> 6);
					percent(label, 0x80 | c & 63);
				}
				else if (c < 0x10000) {
					percent(label, 0xe0 | c >>> 12);
					percent(label, 0x80 | c >>> 6 & 63);
					percent(label, 0x80 | c & 63);
				}
				else {
					percent(label, 0xf0 | c >>> 18);
					percent(label, 0x80 | c >>> 12 & 63);
					percent(label, 0x80 | c >>> 6 & 63);
					percent(label, 0x80 | c & 63);
				}
			}
			if (label.length() > KEY) {
				truncated = true;
				return;
			}
		}
		start(prefix + label);
		if (value != null)
			object(value, 0);
	}

	private static void percent(StringBuilder output, int value) {
		output.append('%').append(HEX.charAt(value >>> 4 & 15)).append(HEX.charAt(value & 15));
	}

	void throwable(Throwable thrown) {
		if (out.length() >= end) {
			truncated = true;
			return;
		}
		if (throwables >= 8) {
			text("[throwable limit]");
			truncated = true;
			return;
		}
		throwables++;
		try {
			text(thrown.getClass().getName());
			text(": ");
			text(thrown.getMessage());
			var stack = thrown.getStackTrace();
			int count = Math.min(stack.length, Math.max(0, 64 - frames));
			for (int i = 0; i < count && out.length() < end; i++, frames++) {
				var frame = stack[i];
				text("\n\tat ");
				text(frame.getClassName());
				text(".");
				text(frame.getMethodName());
				text("(");
				if (frame.isNativeMethod())
					text("Native Method");
				else if (frame.getFileName() == null)
					text("Unknown Source");
				else {
					text(frame.getFileName());
					if (frame.getLineNumber() >= 0) {
						text(":");
						number(frame.getLineNumber());
					}
				}
				text(")");
			}
			if (stack.length > count) {
				text("\n[frame limit]");
				truncated = true;
			}
			if (out.length() >= end)
				return;
			var suppressed = thrown.getSuppressed();
			int shown = 0;
			for (; shown < Math.min(suppressed.length, 4) && throwables < 8 && out.length() < end; shown++) {
				text("\nSuppressed: ");
				throwable(suppressed[shown]);
			}
			if (suppressed.length > shown)
				truncated = true;
			var cause = thrown.getCause();
			if (cause != null && out.length() < end) {
				text("\nCaused by: ");
				throwable(cause);
			}
		}
		catch (Throwable failure) {
			Logwood.nonFatal(failure);
			text(" [throwable failed]");
		}
	}

	String finish() {
		if (json) {
			if (quoted)
				out.append('"');
			if (truncated) {
				if (fields != 0)
					out.append(',');
				out.append("\"truncated\":true");
			}
			return out.append("}\n").toString();
		}
		if (truncated)
			out.append("\ttruncated:true");
		return out.append('\n').toString();
	}

}
