package me.bechberger.testorder.ops.fixtures;

import org.junit.jupiter.api.Test;

/**
 * Fixture: an abstract test base whose simple name carries "Abstract" in the
 * MIDDLE (not as a prefix), mirroring real-world names like commons-io's
 * {@code ComparatorAbstractTest}. Used to verify that abstract detection is
 * bytecode-based ({@code ACC_ABSTRACT}) rather than a fragile
 * {@code startsWith("Abstract")} name heuristic (BUG-169).
 */
public abstract class ComparatorAbstractFixtureTest {

	protected abstract Object subject();

	@Test
	void inheritedTestMethod() {
		subject();
	}
}
