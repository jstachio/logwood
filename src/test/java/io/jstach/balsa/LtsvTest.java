package io.jstach.balsa;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.text.MessageFormat;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.MessageFormatter;

class LtsvTest {
  private static String value(String input) {
    var out = new Ltsv(-1); out.start("m"); out.text(input);
    String record = out.finish();
    return record.substring(2, record.length() - 1);
  }
  static String decode(String input) {
    var out = new StringBuilder();
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (c != '\\') { out.append(c); continue; }
      c = input.charAt(++i);
      switch (c) {
        case '\\' -> out.append('\\');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'u' -> { out.append((char) Integer.parseInt(input.substring(i + 1, i + 5), 16)); i += 4; }
        default -> fail("Unknown escape: " + c);
      }
    }
    return out.toString();
  }
  @Test void everyUtf16UnitRoundTripsWithoutRawControlsOrMalformedUnicode() {
    for (int i = 0; i <= 0xffff; i++) {
      String input = String.valueOf((char) i), escaped = value(input);
      assertEquals(input, decode(escaped), "UTF-16 " + i);
      for (int p = 0; p < escaped.length(); p++) {
        char c = escaped.charAt(p);
        assertFalse(c < 32 || c >= 0x7f && c <= 0x9f || c == 0x2028 || c == 0x2029
            || Character.getType(c) == Character.FORMAT || Character.isSurrogate(c), "Raw control " + i);
      }
    }
    assertEquals("a😀é", decode(value("a😀é")));
    assertEquals("\\udb40\\udc20", value(new String(Character.toChars(0xe0020))));
  }
  @Test void keysAreInjectiveAndCannotCreateFields() {
    var labels = new HashSet<String>();
    for (int i = 0; i <= 0xffff; i++) {
      var out = new Ltsv(-1); out.extra("mdc.", String.valueOf((char) i), "v");
      String record = out.finish();
      assertEquals(1, record.chars().filter(c -> c == ':').count());
      assertFalse(record.contains("\t"));
      String label = record.substring(0, record.indexOf(':'));
      assertTrue(label.matches("[a-zA-Z0-9_.%\\-]+"));
      assertTrue(labels.add(label), "Key collision " + i);
    }
    var out = new Ltsv(-1); out.extra("mdc.", "", "");
    assertTrue(labels.add(out.finish().split(":", 2)[0]));
  }
  @Test void unicode17FormatControlsAreEscapedOnJava17Too() {
    for (int cp : new int[] {0x890, 0x891, 0x13439, 0x1343a, 0x1343b, 0x1343c, 0x1343d, 0x1343e, 0x1343f}) {
      String input = new String(Character.toChars(cp)), escaped = value(input);
      assertTrue(escaped.chars().allMatch(c -> c < 128));
      assertEquals(input, decode(escaped));
    }
  }
  @Test void literalEscapesRemainDistinctAndSeparatorsAreAlwaysPresent() {
    assertNotEquals(value("\n"), value("\\n"));
    var out = new Ltsv(6); out.start("a"); out.text(">"); out.start("b"); out.text("x");
    assertEquals("<6>a:>\tb:x\n", out.finish());
  }
  @Test void slf4jSubstitutionMatchesItsReferenceFormatter() {
    Object[] args = {"A", new int[] {1, 2}, "C"};
    for (String pattern : List.of("plain", "{}", "{} {} {} {}", "\\{} {}", "\\\\{} {}",
        "\\\\\\{} {}", "prefix {} suffix", "Set {1,2,3} {}", "{}\\{}{}")) {
      var out = new Ltsv(-1); out.start("m"); out.message(pattern, args, args.length);
      assertEquals(MessageFormatter.basicArrayFormat(pattern, args), decode(out.finish().substring(2).stripTrailing()), pattern);
    }
  }
  @Test void systemFormattingMatchesMessageFormatWithinSafetyLimits() {
    Object[] args = {2, "world"};
    for (String pattern : List.of("hello {1}", "'{0}' {1}", "''{0}", "{0,number,integer}",
        "{0,choice,0#none|1#one|1<many {1}}", "{4}")) {
      var out = new Ltsv(-1); out.start("m"); out.system(pattern, args);
      assertEquals(MessageFormat.format(pattern, args), decode(out.finish().substring(2).stripTrailing()), pattern);
    }
    var out = new Ltsv(-1); out.start("m"); out.system("don't touch quotes", args);
    assertEquals("m:don't touch quotes\n", out.finish());
  }
  @Test void limitsBoundDenseInputArrayCyclesAndThrowableCycles() {
    assertTimeout(Duration.ofSeconds(2), () -> {
      var out = new Ltsv(-1); out.start("m"); out.text("\u001b".repeat(1_000_000));
      String record = out.finish();
      assertTrue(record.length() <= Ltsv.RECORD);
      assertTrue(record.endsWith("\ttruncated:true\n"));
      Object[] array = new Object[1]; array[0] = array;
      out = new Ltsv(-1); out.start("m"); out.object(array, 0);
      assertTrue(out.finish().contains("truncated:true"));
      var a = new IllegalStateException("a"); var b = new IllegalStateException("b");
      a.initCause(b); b.initCause(a); a.addSuppressed(b); b.addSuppressed(a);
      out = new Ltsv(-1); out.start("error"); out.throwable(a);
      record = out.finish();
      assertTrue(record.length() <= Ltsv.RECORD);
      assertTrue(record.endsWith("\ttruncated:true\n"));
    });
  }
  @Test void hostileValuesAndOversizedFormatIndicesAreContained() {
    var out = new Ltsv(-1); out.start("m");
    out.object(new Object() { @Override public String toString() { throw new AssertionError("private secret"); } }, 0);
    assertEquals("m:[toString failed]\n", out.finish());
    out = new Ltsv(-1); out.start("m"); out.system("{2147483647}", new Object[] {1});
    assertTrue(out.finish().endsWith(" [format rejected]\ttruncated:true\n"));
    out = new Ltsv(-1);
    for (int i = 0; i < 1000; i++) { out.start("x" + i); out.text("x".repeat(4096)); }
    assertTrue(out.finish().length() <= Ltsv.RECORD);
  }
}
