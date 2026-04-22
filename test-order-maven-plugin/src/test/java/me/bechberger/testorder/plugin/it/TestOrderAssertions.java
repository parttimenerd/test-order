package me.bechberger.testorder.plugin.it;

/**
 * Static factory entry point for all custom test-order AssertJ assertions.
 * <p>
 * Usage:
 * {@code import static me.bechberger.testorder.plugin.it.TestOrderAssertions.*;}
 */
public final class TestOrderAssertions {

	private TestOrderAssertions() {
	}

	public static MavenResultAssert assertThat(MavenResult result) {
		return MavenResultAssert.assertThat(result);
	}

	public static DependencyMapAssert assertThat(me.bechberger.testorder.DependencyMap depMap) {
		return DependencyMapAssert.assertThat(depMap);
	}

	public static TestOrderStateAssert assertThat(me.bechberger.testorder.TestOrderState state) {
		return TestOrderStateAssert.assertThat(state);
	}
}
