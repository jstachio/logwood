package io.jstach.balsa.bench;

import org.jspecify.annotations.Nullable;

import java.io.OutputStream;
import java.util.Objects;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import io.jstach.balsa.spi.BalsaProvider;
import org.openjdk.jmh.annotations.*;
import org.slf4j.Logger;
import org.slf4j.simple.SimpleServiceProvider;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class LoggingBench {

	private @Nullable Logger balsa, simple;

	private @Nullable PrintStream original;

	private Object argument = 42;

	@Setup(Level.Trial)
	public void setup() {
		original = System.out;
		System.setOut(new PrintStream(OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8));
		System.setProperty("org.slf4j.simpleLogger.logFile", "System.out");
		System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info");
		var provider = new BalsaProvider();
		provider.initialize();
		balsa = provider.getLoggerFactory().getLogger("bench");
		var reference = new SimpleServiceProvider();
		reference.initialize();
		simple = reference.getLoggerFactory().getLogger("bench");
	}

	@TearDown(Level.Trial)
	public void tearDown() {
		System.setOut(Objects.requireNonNull(original));
	}

	@Benchmark
	public void balsaDisabled() {
		Objects.requireNonNull(balsa).debug("value {}", argument);
	}

	@Benchmark
	public void simpleDisabled() {
		Objects.requireNonNull(simple).debug("value {}", argument);
	}

	@Benchmark
	public void balsaPlain() {
		Objects.requireNonNull(balsa).info("hello world");
	}

	@Benchmark
	public void simplePlain() {
		Objects.requireNonNull(simple).info("hello world");
	}

	@Benchmark
	public void balsaUnicode() {
		Objects.requireNonNull(balsa).info("日本語のログメッセージです。リクエストの処理が正常に完了しました。ユーザーの設定と実行結果を記録しています。");
	}

	@Benchmark
	public void simpleUnicode() {
		Objects.requireNonNull(simple).info("日本語のログメッセージです。リクエストの処理が正常に完了しました。ユーザーの設定と実行結果を記録しています。");
	}

	@Benchmark
	public void balsaArgument() {
		Objects.requireNonNull(balsa).info("value {}", argument);
	}

	@Benchmark
	public void simpleArgument() {
		Objects.requireNonNull(simple).info("value {}", argument);
	}

	@Benchmark
	public void balsaEscaping() {
		Objects.requireNonNull(balsa).info("line\n\u001b[8m {}", argument);
	}

	@Benchmark
	public void simpleEscaping() {
		Objects.requireNonNull(simple).info("line\n\u001b[8m {}", argument);
	}

}
