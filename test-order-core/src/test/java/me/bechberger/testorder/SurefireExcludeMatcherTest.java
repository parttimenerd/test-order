package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SurefireExcludeMatcher}, the core (Maven-independent)
 * matcher for Surefire {@code <excludes>} glob / {@code %regex[...]} patterns.
 * This is the single source of truth shared by the run path
 * ({@code SurefireHelper.configureIncludes}, BUG-168) and the reporting path
 * (the {@code show} Selection Preview, BUG-172), so both drop the same
 * deliberately-excluded classes (e.g.
 * {@code **}{@code /*PerformanceTest.java}).
 */
class SurefireExcludeMatcherTest {

	@Test
	void globMatchesExcludedClassInSubpackage() {
		List<String> patterns = List.of("**/perf/PerformanceTest.java");
		assertTrue(SurefireExcludeMatcher.matches("org.apache.commons.csv.perf.PerformanceTest", patterns));
	}

	@Test
	void globDoesNotMatchUnrelatedClass() {
		List<String> patterns = List.of("**/perf/PerformanceTest.java");
		assertFalse(SurefireExcludeMatcher.matches("org.apache.commons.csv.CSVFormatTest", patterns));
	}

	@Test
	void suffixWildcardMatchesITButNotTest() {
		List<String> patterns = List.of("**/*IT.java");
		assertTrue(SurefireExcludeMatcher.matches("com.foo.BarIT", patterns));
		assertFalse(SurefireExcludeMatcher.matches("com.foo.BarTest", patterns));
	}

	@Test
	void prefixWildcardMatchesAbstractClasses() {
		List<String> patterns = List.of("**/Abstract*.java");
		assertTrue(SurefireExcludeMatcher.matches("com.foo.AbstractThing", patterns));
		assertFalse(SurefireExcludeMatcher.matches("com.foo.ConcreteThing", patterns));
	}

	@Test
	void innerClassNormalizedToEnclosingBeforeMatch() {
		List<String> patterns = List.of("**/OuterTest.java");
		assertTrue(SurefireExcludeMatcher.matches("com.foo.OuterTest$Inner", patterns));
	}

	@Test
	void leadingDoubleStarMatchesRootLevelClass() {
		List<String> patterns = List.of("**/*IT.java");
		assertTrue(SurefireExcludeMatcher.matches("RootIT", patterns));
	}

	@Test
	void dotClassSuffixPatternAlsoMatches() {
		List<String> patterns = List.of("**/perf/PerformanceTest.class");
		assertTrue(SurefireExcludeMatcher.matches("org.apache.commons.csv.perf.PerformanceTest", patterns));
	}

	@Test
	void regexPatternMatchesFqcnForm() {
		List<String> patterns = List.of("%regex[.*PerformanceTest]");
		assertTrue(SurefireExcludeMatcher.matches("org.apache.commons.csv.perf.PerformanceTest", patterns));
		assertFalse(SurefireExcludeMatcher.matches("org.apache.commons.csv.CSVFormatTest", patterns));
	}

	@Test
	void emptyPatternListExcludesNothing() {
		assertFalse(SurefireExcludeMatcher.matches("com.foo.BarTest", List.of()));
	}

	@Test
	void nullInputsExcludeNothing() {
		assertFalse(SurefireExcludeMatcher.matches(null, List.of("**/*Test.java")));
		assertFalse(SurefireExcludeMatcher.matches("com.foo.BarTest", null));
	}

	@Test
	void blankPatternIgnored() {
		assertFalse(SurefireExcludeMatcher.matches("com.foo.BarTest", java.util.Arrays.asList("", "   ")));
	}
}
