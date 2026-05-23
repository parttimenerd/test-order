package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

class SelectOperationTest {

	@TempDir
	Path tempDir;

	// ═══════════════════════════════════════════════════════════════════
	// Regression: stale remaining file deleted when all tests are selected
	// (Bug: SelectOperation only wrote remaining file when non-empty, so a
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
		SelectOperation.SelectConfig config = new SelectOperation.SelectConfig(depMap, state, Set.of(), Set.of(),
				state.weights(), -1, 0, null, Set.of(), selectedFile, remainingFile, PluginLog.NOOP, null);

		SelectOperation.select(config);

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
		SelectOperation.SelectConfig config = new SelectOperation.SelectConfig(depMap, state, Set.of(), Set.of(),
				state.weights(), 1, 0, null, Set.of(), selectedFile, remainingFile, PluginLog.NOOP, null);

		SelectOperation.select(config);

		assertTrue(Files.exists(selectedFile), "Selected file should be created");
		assertTrue(Files.exists(remainingFile), "Remaining file should be created when tests are deferred");

		var selected = me.bechberger.testorder.TestSelector.readTestList(selectedFile);
		var remaining = me.bechberger.testorder.TestSelector.readTestList(remainingFile);

		assertEquals(1, selected.size(), "Should select exactly 1 test");
		assertEquals(2, remaining.size(), "Should have 2 remaining tests");
	}
}
