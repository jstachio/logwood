# Working on Logwood

Read [README.md](README.md), [SECURITY.md](SECURITY.md), and
[bin/README.md](bin/README.md) before changing code or tests.

## Test expectations

- Assert complete string output with `assertEquals`. Prefer Java text blocks for
  log records, diagnostics, JSON, HTML, and other generated text. Include all
  fields, delimiters, escaping, ordering, truncation markers, and final newlines.
- Do not use `String.contains`, `startsWith`, or `endsWith` as output assertions.
  Do not substitute `indexOf`, permissive regexes, or sliced output to make an
  incomplete assertion pass. Full expected output is intentionally sensitive to
  changes: review the difference before updating an expectation.
- Normalize only nondeterministic values, such as wall time and JVM-assigned IDs.
  Keep the field and its delimiter visible. Give throwables explicit stack frames.
  Use placeholder replacement or `.formatted(...)` for large repetitive fixtures;
  never generate the expected result with the production encoder under test.
- Prefer parameterized tests with input/expected-output data when cases exercise
  the same behavior. Keep separate tests for different contracts.
- Parsing, collection membership, and independent structural/property checks are
  allowed. They complement complete output expectations, not replace them. For
  external JVM traces, parse events and compare the complete relevant set; do not
  snapshot unrelated runtime classes or search the trace for a substring.

## Verification before completion

Use the Maven wrapper with JDK 27 or newer. Core runtime support begins at Java 21;
ScopedKeyValues begins at Java 25. Keep fixtures portable across supported JDKs
and Windows (paths, line endings, thread IDs, process handling).

For Java changes, run formatting, tests, and analysis:

```sh
bin/reformat.sh
./mvnw clean verify
bin/analyze.sh
bin/test-tools.sh
```

`bin/analyze.sh` is required: it runs the configured Error Prone/NullAway checks.
Passing tests does not replace analysis. The profile currently enables nullness
checks, not every Error Prone check. Report any check that could not run.

For encoder or test-coverage changes, also run `bin/mutation.sh`. Compare PIT XML
results with an untouched baseline using the same JDK and configuration. Add
meaningful tests for survivors; do not weaken mutators, exclusions, or assertions
for a better score. Child-JVM tests are excluded from PIT because those child
processes execute unmutated classes; add in-process coverage where appropriate.
