/**
 * The exported Logwood SPI: configuration, ordered key-value contributions, and optional
 * SLF4J integration. Types are non-null by default; nullable data and absent settings are
 * explicit.
 * <p>
 * Output defaults to LTSV: tab-separated {@code label:value} fields terminated by LF.
 * Values are unquoted. Backslash, tab, LF, and CR become {@code \\}, {@code \t},
 * {@code \n}, and {@code \r}. Other C0 controls, DEL/C1, U+2028/U+2029, Unicode format
 * characters (category Cf, including the Unicode 17 additions), and unpaired surrogates
 * use <code>&#92;uXXXX</code> with lowercase hex digits per UTF-16 code unit. This
 * includes every input ESC, even SGR conceal/color sequences. Other valid Unicode is
 * preserved. Logwood emits no ANSI colors. Split records, tabs, and the first colon
 * before decoding escapes, and decode only once.
 * <p>
 * Application fields use {@link io.jstach.logwood.spi.LogwoodService.KeyValuesMode}. The
 * default flat mode keeps ordinary keys bare and prefixes reserved names and
 * {@code keyValues.} names with {@code keyValues.}. Prefixed mode applies that namespace
 * to every key. None mode omits key values without calling providers or rendering event
 * values. In the original key, ASCII letters, digits, underscore, dot, and hyphen are
 * preserved; other valid Unicode becomes UTF-8 percent bytes with lowercase hex digits.
 * Empty keys use {@code %}; unpaired surrogates use the extension {@code %uXXXX}. Literal
 * percent signs become {@code %25}, keeping these encodings distinct. Values and records
 * have fixed limits; clipping adds {@code truncated:true}, and oversized keys are skipped
 * whole. Selecting {@code logging.format=jsonl} uses JSON strings and member names
 * instead.
 * @see <a href="https://github.com/jstachio/logwood/blob/main/doc/ltsv.md">LTSV encoding
 * reference, including examples and limits</a>
 */
@org.jspecify.annotations.NullMarked
package io.jstach.logwood.spi;
