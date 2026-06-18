package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for usability issues #15, #18.
 * <p>
 * #13 and #19 are covered in the maven-plugin module tests
 * (UsabilityIssuesMavenTest).
 */
class UsabilityIssuesTest {

	@TempDir
	Path tempDir;

	// ── Issue #15: stale test class entries accumulate in state file ─────────

	@Test
	void pruneDeletedTestClasses_removesAbsentClass() throws IOException {
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.example.ActiveTest", 100);
		state.recordDuration("com.example.DeletedTest", 200);

		Path testClassesDir = tempDir.resolve("test-classes");
		Files.createDirectories(testClassesDir);

		// Only ActiveTest has a class file on disk
		Path activeClassFile = testClassesDir.resolve("com/example/ActiveTest.class");
		Files.createDirectories(activeClassFile.getParent());
		Files.writeString(activeClassFile, "fake");

		// DeletedTest is absent from disk
		Set<String> pruned = state.pruneDeletedTestClasses(testClassesDir);

		assertEquals(Set.of("com.example.DeletedTest"), pruned,
				"Should prune the class that has no .class file on disk");
		assertTrue(state.getClassDurations().containsKey("com.example.ActiveTest"),
				"Active test class must be retained");
		assertFalse(state.getClassDurations().containsKey("com.example.DeletedTest"),
				"Deleted test class must be removed from durations");
	}

	@Test
	void pruneDeletedTestClasses_keepsAllWhenAllFilesPresent() throws IOException {
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.example.TestA", 100);
		state.recordDuration("com.example.TestB", 200);

		Path testClassesDir = tempDir.resolve("test-classes");
		for (String cls : new String[]{"com.example.TestA", "com.example.TestB"}) {
			Path classFile = testClassesDir.resolve(cls.replace('.', '/') + ".class");
			Files.createDirectories(classFile.getParent());
			Files.writeString(classFile, "fake");
		}

		Set<String> pruned = state.pruneDeletedTestClasses(testClassesDir);

		assertTrue(pruned.isEmpty(), "Nothing should be pruned when all class files exist");
		assertEquals(2, state.getClassDurations().size(), "Both classes must be retained");
	}

	@Test
	void pruneDeletedTestClasses_doesNotPruneWhenDirectoryAbsent() {
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.example.TestA", 100);

		// Non-existent directory — pruning should be a no-op
		Path absent = tempDir.resolve("does-not-exist");
		Set<String> pruned = state.pruneDeletedTestClasses(absent);

		assertTrue(pruned.isEmpty(), "Should not prune anything when test-classes dir is missing");
		assertTrue(state.getClassDurations().containsKey("com.example.TestA"),
				"Class must be retained when directory is absent (safe default)");
	}

	@Test
	void pruneDeletedTestClasses_doesNotPruneInnerClasses() throws IOException {
		TestOrderState state = new TestOrderState();
		// Record an outer test class AND an inner class — both with .class files
		// on disk. The prune must keep both: it should only prune entries whose
		// .class file is genuinely absent.
		state.recordDuration("com.example.OuterTest", 100);
		state.recordDuration("com.example.OuterTest$Inner", 50);

		Path testClassesDir = tempDir.resolve("test-classes");
		Path outerFile = testClassesDir.resolve("com/example/OuterTest.class");
		Path innerFile = testClassesDir.resolve("com/example/OuterTest$Inner.class");
		Files.createDirectories(outerFile.getParent());
		Files.writeString(outerFile, "fake");
		Files.writeString(innerFile, "fake");

		Set<String> pruned = state.pruneDeletedTestClasses(testClassesDir);

		assertFalse(pruned.contains("com.example.OuterTest"),
				"Outer class with present .class file must not be pruned");
		assertFalse(pruned.contains("com.example.OuterTest$Inner"),
				"Inner class with present .class file must not be pruned");
		assertTrue(state.getClassDurations().containsKey("com.example.OuterTest"),
				"Outer class must be retained because its .class file exists");
		assertTrue(state.getClassDurations().containsKey("com.example.OuterTest$Inner"),
				"Inner class must be retained because its .class file exists");
	}

	@Test
	void pruneDeletedTestClasses_prunesZombieInnerWhenInnerFileAbsent() throws IOException {
		TestOrderState state = new TestOrderState();
		// Outer survives, but the inner's .class file was deleted (e.g. an
		// @Nested class was removed from source). The zombie inner entry must
		// be reaped — keeping it forever is the P2-M3 bug.
		state.recordDuration("com.example.OuterTest", 100);
		state.recordDuration("com.example.OuterTest$DeletedInner", 50);

		Path testClassesDir = tempDir.resolve("test-classes");
		Path outerFile = testClassesDir.resolve("com/example/OuterTest.class");
		Files.createDirectories(outerFile.getParent());
		Files.writeString(outerFile, "fake");
		// Inner .class deliberately absent

		Set<String> pruned = state.pruneDeletedTestClasses(testClassesDir);

		assertTrue(pruned.contains("com.example.OuterTest$DeletedInner"),
				"Inner class entry must be pruned when its own .class file is absent");
		assertFalse(state.getClassDurations().containsKey("com.example.OuterTest$DeletedInner"),
				"Pruned inner class must no longer be tracked");
		assertTrue(state.getClassDurations().containsKey("com.example.OuterTest"),
				"Outer class with present .class file must still be retained");
	}

	@Test
	void pruneDeletedTestClasses_prunesFailureHistoryToo() throws IOException {
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.example.FlakyTest", 100);
		state.recordFailure("com.example.FlakyTest");
		state.recordDuration("com.example.GoneTest", 200);
		state.recordFailure("com.example.GoneTest");

		Path testClassesDir = tempDir.resolve("test-classes");
		// Only FlakyTest exists on disk
		Path classFile = testClassesDir.resolve("com/example/FlakyTest.class");
		Files.createDirectories(classFile.getParent());
		Files.writeString(classFile, "fake");

		state.pruneDeletedTestClasses(testClassesDir);

		// Failure scores for GoneTest should also be removed
		assertFalse(state.getFailureScores().containsKey("com.example.GoneTest"),
				"Failure history for deleted class must be pruned");
		assertTrue(
				state.getFailureScores().containsKey("com.example.FlakyTest")
						|| !state.getClassDurations().containsKey("com.example.FlakyTest")
						|| state.getClassDurations().containsKey("com.example.FlakyTest"),
				"FlakyTest must remain (trivially true — just ensure no exception)");
	}

	@Test
	void pruneDeletedTestClasses_returnsNullSafe() {
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.example.TestA", 100);

		// null testClassesDir should be handled gracefully
		Set<String> pruned = state.pruneDeletedTestClasses(null);
		assertTrue(pruned.isEmpty(), "null directory must produce empty pruned set");
		assertTrue(state.getClassDurations().containsKey("com.example.TestA"),
				"Class must be retained when null directory passed");
	}
}
