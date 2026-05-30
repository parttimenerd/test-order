package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Tests that {@link SelectiveLearnSupport#computeUncertainClasses} accepts
 * {@link ChangeDetector.Mode} rather than a boolean, and returns {@code null}
 * gracefully when inputs are missing.
 */
class SelectiveLearnSupportModeTest {

	@Test
	void returnsNullWhenProjectRootIsNull() {
		Set<String> result = SelectiveLearnSupport.computeUncertainClasses(null, null, ChangeDetector.Mode.UNCOMMITTED);
		assertNull(result, "Should return null when projectRoot is null");
	}

	@Test
	void returnsNullWhenClassesDirIsNull() {
		Set<String> result = SelectiveLearnSupport.computeUncertainClasses(java.nio.file.Path.of("/nonexistent"), null,
				ChangeDetector.Mode.UNCOMMITTED);
		assertNull(result, "Should return null when classesDir is null");
	}

	@Test
	void returnsNullForNonexistentProjectRoot() {
		// Non-existent path → git/structural analysis fails → return null (not throw)
		Set<String> result = SelectiveLearnSupport.computeUncertainClasses(
				java.nio.file.Path.of("/nonexistent/project"), java.nio.file.Path.of("/nonexistent/classes"),
				ChangeDetector.Mode.UNCOMMITTED);
		// May return null (IO error) or empty (no git repo found) — must not throw
		// Either is acceptable defensive behavior; we just ensure no exception
		assertTrue(result == null || result.isEmpty(),
				"Should return null or empty for non-existent project, got: " + result);
	}

	@Test
	void allModesAreAccepted() {
		// Verify all ChangeDetector.Mode values are accepted without
		// IllegalArgumentException
		for (ChangeDetector.Mode mode : ChangeDetector.Mode.values()) {
			assertDoesNotThrow(
					() -> SelectiveLearnSupport.computeUncertainClasses(java.nio.file.Path.of("/nonexistent"),
							java.nio.file.Path.of("/nonexistent/classes"), mode),
					"Should not throw for mode: " + mode);
		}
	}
}
