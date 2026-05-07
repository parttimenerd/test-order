package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

class ModeResolverOperationTest {

	@TempDir
	Path tempDir;

	Path indexFile;
	Path stateFile;

	@BeforeEach
	void setup() throws IOException {
		indexFile = tempDir.resolve("test-dependencies.lz4");
		stateFile = tempDir.resolve("test-order.state");

		// Create a minimal index so auto mode doesn't immediately select learn
		DependencyMap map = new DependencyMap();
		map.put("com.example.FooTest", Set.of("com.example.Foo"));
		map.save(indexFile);
	}

	// ── Basic mode resolution ───────────────────────────────────────

	@Test
	void explicitLearnReturnsLearn() {
		var config = new ModeResolverOperation.ModeConfig("learn", indexFile, stateFile,
				10, 0, null, null, null, PluginLog.NOOP);
		var decision = ModeResolverOperation.resolve(config);
		assertEquals("learn", decision.effectiveMode());
	}

	@Test
	void explicitOrderWithNoIndexReturnsSkip() {
		var config = new ModeResolverOperation.ModeConfig("order",
				tempDir.resolve("nonexistent.lz4"), stateFile,
				10, 0, null, null, null, PluginLog.NOOP);
		var decision = ModeResolverOperation.resolve(config);
		assertEquals("skip", decision.effectiveMode());
	}

	@Test
	void autoWithNoIndexReturnsLearn() {
		Path testSourceRoot = tempDir.resolve("src/test/java");
		try {
			Files.createDirectories(testSourceRoot.resolve("com/example"));
			Files.writeString(testSourceRoot.resolve("com/example/FooTest.java"), "class FooTest {}\n");
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		var config = new ModeResolverOperation.ModeConfig("auto",
				tempDir.resolve("nonexistent.lz4"), stateFile,
				10, 0, null, null, null, null, testSourceRoot, null, null, PluginLog.NOOP);
		var decision = ModeResolverOperation.resolve(config);
		assertEquals("learn", decision.effectiveMode());
	}

	@Test
	void autoWithIndexReturnsOrder() {
		var config = new ModeResolverOperation.ModeConfig("auto", indexFile, stateFile,
				10, 0, null, null, null, PluginLog.NOOP);
		var decision = ModeResolverOperation.resolve(config);
		assertEquals("order", decision.effectiveMode());
	}

	// ── Dependency fingerprint detection ────────────────────────────

	@Test
	void firstRunRecordsFingerprintWithoutTriggeringLearn() throws IOException {
		// Create a state file without a fingerprint
		TestOrderState state = new TestOrderState();
		state.save(stateFile);

		var config = new ModeResolverOperation.ModeConfig("auto", indexFile, stateFile,
				10, 0, null, null, null, null, null, null, () -> "abc123", PluginLog.NOOP);
		var decision = ModeResolverOperation.resolve(config);

		// Should NOT trigger learn — just records fingerprint
		assertEquals("order", decision.effectiveMode());

		// Verify fingerprint was stored
		TestOrderState reloaded = TestOrderState.load(stateFile);
		assertEquals("abc123", reloaded.dependencyFingerprint());
	}

	@Test
	void unchangedFingerprintDoesNotTriggerLearn() throws IOException {
		// Create a state file WITH a fingerprint that matches
		TestOrderState state = new TestOrderState();
		state.setDependencyFingerprint("same-hash");
		state.save(stateFile);

		var config = new ModeResolverOperation.ModeConfig("auto", indexFile, stateFile,
				10, 0, null, null, null, null, null, null, () -> "same-hash", PluginLog.NOOP);
		var decision = ModeResolverOperation.resolve(config);

		assertEquals("order", decision.effectiveMode());
	}

	@Test
	void changedFingerprintTriggersLearn() throws IOException {
		// Create state with an old fingerprint
		TestOrderState state = new TestOrderState();
		state.setDependencyFingerprint("old-hash");
		state.save(stateFile);

		var config = new ModeResolverOperation.ModeConfig("auto", indexFile, stateFile,
				10, 0, null, null, null, null, null, null, () -> "new-hash", PluginLog.NOOP);
		var decision = ModeResolverOperation.resolve(config);

		assertEquals("learn", decision.effectiveMode());
		assertTrue(decision.reason().contains("Dependency change detected"));
		assertTrue(decision.stateModified());

		// Verify fingerprint was updated in state
		TestOrderState reloaded = TestOrderState.load(stateFile);
		assertEquals("new-hash", reloaded.dependencyFingerprint());
		assertEquals(0, reloaded.runsSinceLearn());
	}

	@Test
	void nullFingerprintSupplierSkipsDependencyCheck() throws IOException {
		// State with a stored fingerprint, but supplier is null
		TestOrderState state = new TestOrderState();
		state.setDependencyFingerprint("some-hash");
		state.save(stateFile);

		var config = new ModeResolverOperation.ModeConfig("auto", indexFile, stateFile,
				10, 0, null, null, null, null, null, null, null, PluginLog.NOOP);
		var decision = ModeResolverOperation.resolve(config);

		assertEquals("order", decision.effectiveMode());
	}

	@Test
	void nullFingerprintResultSkipsDependencyCheck() throws IOException {
		// Supplier returns null (e.g. no JARs found)
		TestOrderState state = new TestOrderState();
		state.setDependencyFingerprint("some-hash");
		state.save(stateFile);

		var config = new ModeResolverOperation.ModeConfig("auto", indexFile, stateFile,
				10, 0, null, null, null, null, null, null, () -> null, PluginLog.NOOP);
		var decision = ModeResolverOperation.resolve(config);

		assertEquals("order", decision.effectiveMode());
	}

	@Test
	void dependencyChangeDetectionWorksWithoutPriorState() {
		// No state file at all — fingerprint check should be skipped gracefully
		var config = new ModeResolverOperation.ModeConfig("auto", indexFile, stateFile,
				10, 0, null, null, null, null, null, null, () -> "some-hash", PluginLog.NOOP);
		// Note: stateFile doesn't exist here because we didn't save any state
		// The fingerprint check should be a no-op
		var decision = ModeResolverOperation.resolve(config);

		// Should still end up in "order" mode (index exists)
		assertEquals("order", decision.effectiveMode());
	}

	// ── Threshold-based auto-switching still works ──────────────────

	@Test
	void runCountThresholdTriggersLearn() throws IOException {
		TestOrderState state = new TestOrderState();
		// Simulate 10 runs since last learn
		for (int i = 0; i < 10; i++) {
			state.incrementRunsSinceLearn();
		}
		state.save(stateFile);

		var config = new ModeResolverOperation.ModeConfig("auto", indexFile, stateFile,
				10, 0, null, null, null, null, null, null, null, PluginLog.NOOP);
		var decision = ModeResolverOperation.resolve(config);

		assertEquals("learn", decision.effectiveMode());
		assertTrue(decision.reason().contains("Run count threshold"));
	}

	@Test
	void dependencyChangeHasPriorityOverRunThreshold() throws IOException {
		// Both dep change AND threshold are met — dep change triggers first (step 6 before step 7)
		TestOrderState state = new TestOrderState();
		state.setDependencyFingerprint("old-deps");
		for (int i = 0; i < 10; i++) {
			state.incrementRunsSinceLearn();
		}
		state.save(stateFile);

		var config = new ModeResolverOperation.ModeConfig("auto", indexFile, stateFile,
				10, 0, null, null, null, null, null, null, () -> "new-deps", PluginLog.NOOP);
		var decision = ModeResolverOperation.resolve(config);

		assertEquals("learn", decision.effectiveMode());
		// Should be the dependency change reason, not the threshold reason
		assertTrue(decision.reason().contains("Dependency change"));
	}
}
