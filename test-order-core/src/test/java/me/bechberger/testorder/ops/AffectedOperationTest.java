package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

class AffectedOperationTest {

	@TempDir
	Path tempDir;

	// ═══════════════════════════════════════════════════════════════════
	// Regression: stale remaining file deleted when all tests are selected
	// (Bug: AffectedOperation only wrote remaining file when non-empty, so a
	// stale file from a previous select run would persist and cause run-remaining
	// to re-execute already-selected tests)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void staleRemainingFileDeletedWhenAllSelected() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.FooTest", Set.of("com.example.Foo"));

		Path selectedFile = tempDir.resolve("selected.txt");
		Path remainingFile = tempDir.resolve("remaining.txt");

		// Simulate a stale remaining file from a previous run
		Files.writeString(remainingFile, "com.example.BarTest\n");
		assertTrue(Files.exists(remainingFile), "Precondition: stale remaining file should exist");

		TestOrderState state = new TestOrderState();
		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state, Set.of(), Set.of(),
				state.weights(), -1, 0, null, Set.of(), selectedFile, remainingFile, PluginLog.NOOP, null);

		AffectedOperation.select(config);

		assertFalse(Files.exists(remainingFile),
				"Stale remaining file should be deleted when all tests are selected (remaining is empty)");
		assertTrue(Files.exists(selectedFile), "Selected file should be created");
	}

	@Test
	void remainingFileWrittenWhenSubsetSelected() throws IOException {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.ATest", Set.of("com.example.A"));
		depMap.put("com.example.BTest", Set.of("com.example.B"));
		depMap.put("com.example.CTest", Set.of("com.example.C"));

		Path selectedFile = tempDir.resolve("selected.txt");
		Path remainingFile = tempDir.resolve("remaining.txt");

		TestOrderState state = new TestOrderState();
		// Select only 1 test — should leave 2 remaining
		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state, Set.of(), Set.of(),
				state.weights(), 1, 0, null, Set.of(), selectedFile, remainingFile, PluginLog.NOOP, null);

		AffectedOperation.select(config);

		assertTrue(Files.exists(selectedFile), "Selected file should be created");
		assertTrue(Files.exists(remainingFile), "Remaining file should be created when tests are deferred");

		var selected = me.bechberger.testorder.TestSelector.readTestList(selectedFile);
		var remaining = me.bechberger.testorder.TestSelector.readTestList(remainingFile);

		assertEquals(1, selected.size(), "Should select exactly 1 test");
		assertEquals(2, remaining.size(), "Should have 2 remaining tests");
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression BUG-88: new alwaysRun test double-counted in log message
	// (scoredCount = selected - alwaysRun - new - fast would go negative when a
	// test is both @AlwaysRun and "new" — not yet in the dep index)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void logBreakdown_newAlwaysRunTest_doesNotProduceNegativeScoredCount() throws IOException {
		DependencyMap depMap = new DependencyMap();
		// Two known tests in the index
		depMap.put("com.example.ATest", Set.of("com.example.A"));
		depMap.put("com.example.BTest", Set.of("com.example.B"));
		// "NewAlwaysTest" is NOT in the index (new) AND is in alwaysRunClasses

		Path selectedFile = tempDir.resolve("selected.txt");

		List<String> logLines = new ArrayList<>();
		PluginLog capturingLog = new PluginLog() {
			@Override
			public void info(String msg) {
				logLines.add("INFO: " + msg);
			}
			@Override
			public void warn(String msg) {
				logLines.add("WARN: " + msg);
			}
			@Override
			public void debug(String msg) {
			}
			@Override
			public void error(String msg) {
				logLines.add("ERROR: " + msg);
			}
		};

		TestOrderState state = new TestOrderState();
		// topN=1 means we don't select everything, so we hit the "else" branch that
		// computes the breakdown.
		AffectedOperation.SelectConfig config = new AffectedOperation.SelectConfig(depMap, state, Set.of(), Set.of(),
				state.weights(), 1, 0, null, Set.of("com.example.NewAlwaysTest"), selectedFile, null, capturingLog,
				null);

		AffectedOperation.SelectResult result = AffectedOperation.select(config);

		// The selection must have succeeded without exception.
		assertFalse(result.selection().selected().isEmpty());

		// The INFO log line describing the breakdown must not contain a negative
		// number.
		String summary = logLines.stream().filter(l -> l.contains("Selected") && l.contains("scored")).findFirst()
				.orElse("");
		// If scoredCount was negative, the log line would contain "-" before "scored"
		// e.g. "Selected 2 tests (-1 scored + 1 new + 1 always-run), deferred ..."
		assertFalse(summary.contains("-1 scored") || summary.contains("-2 scored"),
				"scoredCount must not be negative. Log: " + summary);
	}
}
