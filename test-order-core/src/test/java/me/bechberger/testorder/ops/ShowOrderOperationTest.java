package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;

/**
 * Tests for {@link ShowOrderOperation}'s test-class discovery, focused on
 * BUG-169: abstract test bases must be excluded from the selection candidate
 * set regardless of where "Abstract" appears in their name. A prior name-prefix
 * heuristic ({@code startsWith("Abstract")}) missed infix/suffix names like
 * commons-io's {@code ComparatorAbstractTest}, so the unrunnable abstract base
 * leaked into {@code collectAllTests}, was treated as a "new" test, and was
 * selected by set-cover — displacing its concrete subclasses (a coverage hole).
 */
class ShowOrderOperationTest {

	@TempDir
	Path tempDir;

	@Test
	void looksLikeTestClass_falseForPrefixAbstractBase() throws URISyntaxException {
		Class<?> abstractFixture = me.bechberger.testorder.ops.fixtures.AbstractFixtureTest.class;
		Path classFile = classFileOf(abstractFixture);
		assertFalse(ShowOrderOperation.looksLikeTestClass(classFile),
				"an abstract base named Abstract* must not look like a runnable test");
	}

	@Test
	void looksLikeTestClass_falseForInfixAbstractBase() throws URISyntaxException {
		// BUG-169: "Abstract" is in the MIDDLE of the name, so the old
		// startsWith("Abstract") heuristic wrongly classified it as a runnable test.
		Class<?> infixAbstract = me.bechberger.testorder.ops.fixtures.ComparatorAbstractFixtureTest.class;
		Path classFile = classFileOf(infixAbstract);
		assertFalse(ShowOrderOperation.looksLikeTestClass(classFile),
				"an abstract base with infix 'Abstract' must not look like a runnable test (BUG-169)");
	}

	@Test
	void looksLikeTestClass_trueForConcreteTestClass() throws URISyntaxException {
		Path classFile = classFileOf(getClass());
		assertTrue(ShowOrderOperation.looksLikeTestClass(classFile),
				"a concrete class with @Test methods must look like a runnable test");
	}

	@Test
	void collectAllTests_excludesInfixAbstractBase(@TempDir Path dir) throws IOException, URISyntaxException {
		// End-to-end: an abstract base copied into the compiled output must NOT enter
		// the collected candidate set, even when its name has an infix "Abstract".
		Path testClassesRoot = dir.resolve("test-classes");
		Path pkg = testClassesRoot.resolve("me/bechberger/testorder/ops/fixtures");
		Files.createDirectories(pkg);
		Class<?> infixAbstract = me.bechberger.testorder.ops.fixtures.ComparatorAbstractFixtureTest.class;
		Files.copy(classFileOf(infixAbstract), pkg.resolve("ComparatorAbstractFixtureTest.class"));

		Set<String> all = ShowOrderOperation.collectAllTests(new DependencyMap(), Set.of(), testClassesRoot);

		assertFalse(all.contains("me.bechberger.testorder.ops.fixtures.ComparatorAbstractFixtureTest"),
				"abstract test base must not enter the selection candidate set (BUG-169)");
	}

	private static Path classFileOf(Class<?> cls) throws URISyntaxException {
		return Path.of(cls.getProtectionDomain().getCodeSource().getLocation().toURI())
				.resolve(cls.getName().replace('.', '/') + ".class");
	}
}
