package me.bechberger.testorder.ops.fixtures;

import org.junit.jupiter.api.Test;

/**
 * Fixture: an abstract test base carrying {@code @Test} methods. JUnit never
 * runs an abstract class as a standalone test — only its concrete subclasses
 * run. Used to verify that {@code TestClassDiscovery.hasTestAnnotations} treats
 * abstract classes as non-runnable even though they declare test annotations.
 */
public abstract class AbstractFixtureTest {

	protected abstract Object subject();

	@Test
	void inheritedTestMethod() {
		subject();
	}
}
