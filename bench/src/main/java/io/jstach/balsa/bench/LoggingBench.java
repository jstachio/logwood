package io.jstach.balsa.bench;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import io.jstach.balsa.BalsaProvider;
import org.openjdk.jmh.annotations.*;
import org.slf4j.Logger;
import org.slf4j.simple.SimpleServiceProvider;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations=3, time=1)
@Measurement(iterations=5, time=1)
@Fork(2)
public class LoggingBench {
  private Logger balsa, simple;
  private PrintStream original;
  private Object argument = 42;
  @Setup(Level.Trial) public void setup() {
    original = System.out;
    System.setOut(new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8));
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");
    System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
    var provider = new BalsaProvider(); provider.initialize();
    balsa = provider.getLoggerFactory().getLogger("bench");
    var reference = new SimpleServiceProvider(); reference.initialize();
    simple = reference.getLoggerFactory().getLogger("bench");
  }
  @TearDown(Level.Trial) public void tearDown() { System.setOut(original); }
  @Benchmark public void balsaDisabled() { balsa.debug("value {}", argument); }
  @Benchmark public void simpleDisabled() { simple.debug("value {}", argument); }
  @Benchmark public void balsaPlain() { balsa.info("hello world"); }
  @Benchmark public void simplePlain() { simple.info("hello world"); }
  @Benchmark public void balsaArgument() { balsa.info("value {}", argument); }
  @Benchmark public void simpleArgument() { simple.info("value {}", argument); }
  @Benchmark public void balsaEscaping() { balsa.info("line\n\u001b[8m {}", argument); }
  @Benchmark public void simpleEscaping() { simple.info("line\n\u001b[8m {}", argument); }
}
