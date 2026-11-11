package io.jstach.logwood.scopedkeyvalues;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.jstach.rainbowgum.scopedkeyvalues.ScopedKeyValues;

class ScopedKeyValuesTest {

	@BeforeEach
	@AfterEach
	void scopeIsEmpty() {
		assertEquals(Map.of(), ScopedKeyValues.currentMerged());
	}

	@Test
	void publishedApiFindsTheLogwoodProvider() {
		var factory = new LogwoodScopedKeyValues();
		assertInstanceOf(LogwoodScopedKeyValues.class, factory);
		ScopedKeyValues.builder()
			.add("request", "value")
			.run(() -> assertEquals(Map.of("request", "value"), ScopedKeyValues.currentMerged()));
	}

	@Test
	void nestedBindingsOverrideAndRestoreTheOuterImmutableSnapshot() {
		ScopedKeyValues.builder().add("request", "outer").add("outer", "kept").run(() -> {
			Map<String, String> snapshot = ScopedKeyValues.currentMerged();
			assertThrows(UnsupportedOperationException.class, () -> snapshot.put("bad", "mutation"));
			ScopedKeyValues.builder()
				.add("request", "inner")
				.add("inner", "added")
				.run(() -> assertEquals(Map.of("request", "inner", "outer", "kept", "inner", "added"),
						ScopedKeyValues.currentMerged()));
			assertSame(snapshot, ScopedKeyValues.currentMerged());
			assertEquals("outer", snapshot.get("request"));
		});
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void exceptionalRunAndCallAlwaysUnbind(boolean call) {
		var error = new IllegalStateException("body failed");
		var builder = ScopedKeyValues.builder().add("request", "failed");
		var caught = assertThrows(IllegalStateException.class, () -> {
			if (call)
				builder.call(() -> {
					throw error;
				});
			else
				builder.run(() -> {
					throw error;
				});
		});
		assertSame(error, caught);
		scopeIsEmpty();
	}

	@Test
	void callPreservesItsResultAndCheckedExceptionType() throws IOException {
		assertEquals("value",
				ScopedKeyValues.builder().add("key", "value").call(() -> ScopedKeyValues.currentMerged().get("key")));
		var checked = new IOException("checked");
		assertSame(checked,
				assertThrows(IOException.class, () -> ScopedKeyValues.builder().add("key", "value").call(() -> {
					throw checked;
				})));
	}

	@Test
	void buildersSnapshotAtBindingTimeAndChangesDoNotAlterAnActiveScope() {
		var builder = ScopedKeyValues.builder().add("key", "first");
		ScopedKeyValues.builder().add("parent", "bound later").run(() -> builder.run(() -> {
			builder.add("key", "second");
			assertEquals(Map.of("parent", "bound later", "key", "first"), ScopedKeyValues.currentMerged());
		}));
		builder.run(() -> assertEquals(Map.of("key", "second"), ScopedKeyValues.currentMerged()));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void concurrentScopesAndUnrelatedWorkersDoNotShareBindings(boolean virtual) throws Exception {
		try (var executor = virtual ? Executors.newVirtualThreadPerTaskExecutor() : Executors.newFixedThreadPool(2)) {
			var barrier = new CyclicBarrier(2);
			var first = executor.submit(() -> ScopedKeyValues.builder().add("request", "first").call(() -> {
				barrier.await(10, TimeUnit.SECONDS);
				return ScopedKeyValues.currentMerged();
			}));
			var second = executor.submit(() -> ScopedKeyValues.builder().add("request", "second").call(() -> {
				barrier.await(10, TimeUnit.SECONDS);
				return ScopedKeyValues.currentMerged();
			}));
			assertEquals(Map.of("request", "first"), first.get(10, TimeUnit.SECONDS));
			assertEquals(Map.of("request", "second"), second.get(10, TimeUnit.SECONDS));
			ScopedKeyValues.builder().add("parent", "not inherited by unrelated workers").call(() -> {
				assertEquals(Map.of(), executor.submit(ScopedKeyValues::currentMerged).get(10, TimeUnit.SECONDS));
				return null;
			});
		}
	}

	@Test
	void distinctKeyLimitsAllowReplacementAndRejectMergedOverflowBeforeRunningBody() {
		var full = ScopedKeyValues.builder();
		for (int i = 0; i < 32; i++)
			full.add("key" + i, "value");
		full.add("key0", "updated");
		assertThrows(IllegalArgumentException.class, () -> full.add("overflow", "rejected"));
		full.run(() -> {
			assertEquals(32, ScopedKeyValues.currentMerged().size());
			assertEquals("updated", ScopedKeyValues.currentMerged().get("key0"));
			assertThrows(IllegalArgumentException.class,
					() -> ScopedKeyValues.builder().add("new", "value").run(() -> fail("body ran")));
			assertEquals(32, ScopedKeyValues.currentMerged().size());
		});
	}

	@ParameterizedTest
	@CsvSource({ "257, 1", "1, 4097" })
	void oversizedEntriesAreRejectedWithoutKeepingTheirValues(int keySize, int valueSize) {
		var builder = ScopedKeyValues.builder();
		assertThrows(IllegalArgumentException.class, () -> builder.add("k".repeat(keySize), "v".repeat(valueSize)));
		builder.run(() -> assertEquals(Map.of(), ScopedKeyValues.currentMerged()));
	}

	@Test
	void nullsAreRejectedAndBoundarySizedEntriesAreAccepted() {
		var builder = ScopedKeyValues.builder();
		assertThrows(NullPointerException.class, () -> builder.add(null, "value"));
		assertThrows(NullPointerException.class, () -> builder.add("key", null));
		assertThrows(NullPointerException.class, () -> builder.run(null));
		assertThrows(NullPointerException.class, () -> builder.call(null));
		builder.add("k".repeat(256), "v".repeat(4096));
		builder.run(() -> assertEquals(4096, ScopedKeyValues.currentMerged().values().iterator().next().length()));
	}

	@Test
	void providerPushesTheExistingSnapshotAndCallbackFailuresLeaveNoBindingsBehind() {
		var provider = new LogwoodScopedKeyValues();
		var result = new java.util.LinkedHashMap<String, String>();
		ScopedKeyValues.builder().add("request", "value").run(() -> {
			provider.keyValues(result::put);
			assertThrows(IllegalStateException.class, () -> provider.keyValues((key, value) -> {
				throw new IllegalStateException();
			}));
			assertEquals(Map.of("request", "value"), ScopedKeyValues.currentMerged());
		});
		assertEquals(Map.of("request", "value"), result);
	}

}
