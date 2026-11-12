# LTSV encoding

LTSV is Logwood's default (`logging.format=ltsv`). Each record is UTF-8 text with
tab-separated `label:value` fields and exactly one final LF. Split a field at
its **first** colon: colons may occur in values. Values are never quoted.
These rules apply to messages, logger and thread names, context and fluent
values, and throwable text. Logwood does not emit ANSI colors.

## Values

The right column below is literal output text, not an instruction to emit a
control character.

| Input | Encoded value text |
| --- | --- |
| Backslash | `\\` |
| Tab (U+0009) | `\t` |
| LF (U+000A) | `\n` |
| CR (U+000D) | `\r` |
| Other C0 controls (U+0000–U+001F) | `\uXXXX` |
| DEL and C1 controls (U+007F–U+009F) | `\uXXXX` |
| Line/paragraph separators U+2028/U+2029 | `\u2028`, `\u2029` |
| Unicode format characters (category Cf), including bidi controls | `\uXXXX` per UTF-16 code unit |
| Unpaired UTF-16 surrogates | `\uXXXX` |

`XXXX` means exactly four **lowercase** hexadecimal digits. Supplementary
format characters use two escapes: U+1D173 becomes `\ud834\udd73`. The
Unicode 17 format set is covered even on Java 21's older character database.
Every input ESC is escaped, so SGR conceal/color sequences cannot act as
terminal commands: ESC followed by `[8m` becomes `\u001b[8m`. The C1 CSI
U+009B likewise becomes `\u009b`.

Other valid Unicode text, including ordinary supplementary characters, is
preserved without normalization. Double quotes, colons, slashes, and percent
signs in values stay literal. An actual LF becomes `\n`; a literal backslash
followed by `n` becomes `\\n`. A null context/fluent value is empty; a null
message is the text `null`.

## Labels

`logging.keyvalues.mode` selects application key-value output in both formats:

| Mode | Behavior |
| --- | --- |
| `none` / `false` | Omit all context and fluent fields; skip backend collection and rendering. |
| `flat` / `true` | Default: keep ordinary keys bare; prefix collisions with `keyValues.`. |
| `prefixed` | Prefix every application key with `keyValues.`. |

Values are case-insensitive and resolved once at initialization. Invalid values
disable logging with one fixed diagnostic. There is no nested-object option.
`LogwoodConfig.keyValuesMode()` exposes the same choice as the
`LogwoodService.KeyValuesMode` enum for programmatic configuration.

Reserved names are `time`, `level`, `logger`, `message`, `thread`, `threadId`,
`threadName`, `error`, `stacktrace`, `keyValues`, and `truncated`. Names starting
with `keyValues.` also receive another prefix, preserving distinct original keys:
`level` becomes `keyValues.level`, while `keyValues.level` becomes
`keyValues.keyValues.level`. Collision protection applies even when an optional
built-in field is absent. Prefixed JSON members remain dotted names; Logwood does
not expand dots into objects.

After choosing the namespace, encode the original key using these rules:

| Original key text | Encoded key (before an optional namespace prefix) |
| --- | --- |
| ASCII `A-Z a-z 0-9 _ . -` | Unchanged |
| Other valid Unicode | UTF-8 bytes, each written `%hh` with lowercase hex digits |
| Empty key | `%` |
| Unpaired UTF-16 surrogate | `%uXXXX` with four lowercase hex digits |

In the default flat mode, `a b` becomes `a%20b`, `:` becomes `%3a`, `%` becomes
`%25`, `é` becomes `%c3%a9`, and `😀` becomes `%f0%9f%98%80`.
An unpaired U+D800 becomes `%ud800`; the literal text `%ud800` becomes
`%25ud800`. The empty key is `%`, distinct from a literal percent key. Prefixed
mode adds `keyValues.` to each result: for example `keyValues.a%20b`.
The `%uXXXX` form is an extension for malformed UTF-16, not a UTF-8 byte
escape. These encodings keep distinct keys distinct. Null keys are skipped
and set the truncation marker.

## Reading and limits

Separate records and fields **before** decoding escapes. Decode value
backslash escapes in one pass; recursively decoding `\\n` would incorrectly
turn literal escape text into a newline. For application labels, remove one
leading `keyValues.` namespace if present, then handle the empty-key sentinel,
`%uXXXX` code units, and UTF-8 percent bytes.
Do not percent-decode a decoded result again: `%25ud800` is literal `%ud800`.
This escaping is Logwood's convention layered on LTSV; a generic LTSV reader
can split fields but needs these rules to recover original text.

An encoded value is limited to 4,096 UTF-16 code units and a complete record
to 16,384, including the truncation marker and final LF. Escapes and valid
surrogate pairs are never split at a limit. Keys are skipped whole if the
raw key or encoded label (excluding an added `keyValues.` namespace) exceeds
256 code units. Clipping adds `truncated:true`; clipped values cannot recover
the omitted suffix.
See [the security model](../SECURITY.md) for the remaining work and storage
limits and failure behavior.

With `logging.journald=true`, the transport adds a syslog priority prefix
before the first field and omits `time`. Strip that prefix before using a
generic LTSV parser. JSONL uses JSON string/member-name escaping instead of
the label rules above; select it with `logging.format=jsonl`.

## Stack trace lines

`logging.ltsv.stacktrace=lines` (alias `true`) makes stack traces readable in a
terminal. The record's line is unchanged, except that `error` holds only the
throwable's first line: its class and message. The rest of the stack trace
follows the record, each line on a line of its own after one tab:

```text
time:2026-11-12T03:00:00.123Z	level:ERROR	thread:main	threadId:1	logger:app	message:failed	error:java.lang.IllegalStateException: boom
		at com.example.App.run(App.java:12)
	Caused by: java.io.IOException: disk full
		at com.example.Store.write(Store.java:40)
```

This is no longer strict LTSV, but it is still read by rule rather than by
guessing: a record never starts with a tab, and a line that does continues the
record before it. Remove that one tab, decode the line's escapes, and join the
lines to `error` with LF to get the value the default `inline` (alias `false`)
writes. Exception messages stay escaped, so they never add lines. The value and
record limits count the continuation lines, and `truncated:true` stays on the
record's line. Other values disable logging; JSON Lines ignores the option.
Journald stores each continuation line as its own entry, so keep the default
there.
