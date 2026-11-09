# Security model

Balsa treats log data as untrusted text. Its goals are record integrity,
terminal safety, bounded backend work, and minimal retained state. It does not
redact secrets intentionally passed to the logger.

## Record integrity

Only Balsa emits raw field delimiters and record terminators. Data cannot add a
new field, newline, journald priority, or terminal escape sequence. C0 controls,
DEL/C1, Unicode line separators, all Unicode FORMAT characters, backslashes, and
unpaired surrogates are escaped. The Unicode 17 FORMAT set is covered on the Java 21 baseline,
including controls absent from older character databases.
The set was checked against the [Unicode data](https://www.unicode.org/Public/17.0.0/ucd/UnicodeData.txt).
Valid ordinary Unicode is preserved without normalization or transliteration;
visual confusables remain application data.

A value containing an actual newline differs from a literal backslash followed
by `n`. Escapes and surrogate pairs are emitted atomically, including at limits.
Labels encode forbidden characters, percent signs, and malformed surrogates
without collisions. Context and fluent fields are namespaced away from metadata.
Marker arguments and event marker accessors are ignored.

Each finished record is encoded as UTF-8 and submitted in one
`System.out.writeBytes(byte[])` call. The standard PrintStream serializes that
call. Replacing System.out with a differently encoded PrintStream does not
change the LTSV encoding. Balsa never closes or caches stdout;
replacing System.out takes effect for later records. It does not coordinate
unrelated writers or sandbox an application-supplied PrintStream.

## Fixed limits

These are implementation limits, not configuration options. Counts use UTF-16
code units, not UTF-8 bytes.

| Resource | Limit |
| --- | --- |
| Complete record, including truncation marker and LF | 16,384 units |
| Encoded value | 4,096 units |
| Encoded custom key (namespace separate) | 256 units, otherwise the field is skipped |
| Context callback / fluent fields | 32 from each source |
| Array nesting / elements per array | 8 / 32 |
| Throwable graph / total stack frames | 8 throwables / 64 frames |
| Suppressed exceptions per throwable | 4 |
| SLF4J pattern scan / interpolated arguments | 4,096 units / 64 |
| System.Logger MessageFormat pattern | 1,024 units |
| MessageFormat argument index / format elements / brace depth | 31 / 64 / 8 |
| Logger name / hierarchy depth | 1,024 units / 64 |

Clipping emits `truncated:true`. Saturation does not switch to an unescaped
fallback. Unsafe formatting patterns produce an escaped rejection marker.
System.Logger arguments are bounded before MessageFormat expansion. Large
numeric values are not expanded without bounds. No input-sized formatter or
class-name cache is retained. Balsa retains no marker state. A context callback
must emit distinct keys and must not retain or asynchronously use its consumer;
late calls after the callback returns are ignored.

## Failures and reentry

Invalid root configuration, invalid journald settings, or
multiple configuration providers disable logging instead of falling back to
another backend. An MDC factory failure disables logging when SLF4J initializes;
System.Logger alone never requests that adapter. Bad per-name configuration disables that logger. One fixed
LTSV diagnostic reports the failure without printing property values, provider
exception messages, or their stack traces.

Nonfatal callback failures are contained. An event whose metadata cannot safely
be read may be dropped, with one fixed diagnostic. Nested calls during formatting are
dropped, preventing recursive toString, context, supplier, or stdout callbacks from
corrupting an outer record or recursing forever. Fatal VM errors and ThreadDeath
are rethrown; StackOverflowError from a callback is contained.

As with any in-process logger, application callbacks are code, not a sandbox.
They can block or allocate before returning. This includes toString, suppliers,
context providers, MDC adapters, resource bundles, throwable accessors, and code evaluated by a
facade before it reaches the backend. Throwable accessors may clone arrays.
An opt-in BalsaConfig.MDCAdapter controls its own storage. Its JDK-only contract
is bridged by the SLF4J provider, which is loaded only when SLF4J is used.
System.Logger and configuration discovery also work with the SLF4J API absent. Stdout I/O can block, and output
errors follow PrintStream's error-handling semantics. Balsa's own output and
traversal are bounded; it cannot bound arbitrary application execution.

## Validation

Tests cover every UTF-16 unit, label uniqueness, control/bidi escaping, malformed
Unicode, delimiter boundaries, cycles, dense input, oversized format indices,
reentrant callbacks, configuration failures, serialization, cache bounds,
concurrent records, localization, and actual provider discovery in fresh JVMs
on both the classpath and module path. Packaged-JAR tests omit the SLF4J JAR
entirely, verify its classes are unavailable and never loaded, and compile/run
a named application with only Balsa. Tests also cover observable-but-unresolved
SLF4J modules. Fresh JVM class-initialization traces
verify System.Logger leaves the SLF4J facades and provider uninitialized, with
both default settings and custom context/MDC configurations.

Report a vulnerability privately to the repository owner through the contact
information on https://github.com/agentgt. Do not post secrets in a public issue.
