package me.bechberger.testorder.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Verifies that test-order honors Surefire {@code <excludes>} patterns when
 * building the {@code -Dtest=} selection. Surefire's {@code -Dtest=} parameter
 * overrides {@code <excludes>}, so without this filtering test-order would run
 * deliberately-excluded tests (BUG-168, discovered on commons-csv where
 * {@code **}{@code /perf/PerformanceTest.java} was excluded yet promoted to the
 * top of the affected selection and executed as a ~14s [NEW] test).
 */
class SurefireExcludeFilterTest {

	@Test
	void globMatchesExcludedClassInSubpackage() {
		List<String> patterns = List.of("**/perf/PerformanceTest.java");
		assertThat(SurefireHelper.matchesAnySurefirePattern("org.apache.commons.csv.perf.PerformanceTest", patterns))
				.isTrue();
	}

	@Test
	void globDoesNotMatchUnrelatedClass() {
		List<String> patterns = List.of("**/perf/PerformanceTest.java");
		assertThat(SurefireHelper.matchesAnySurefirePattern("org.apache.commons.csv.CSVFormatTest", patterns))
				.isFalse();
	}

	@Test
	void suffixWildcardMatchesITButNotTest() {
		List<String> patterns = List.of("**/*IT.java");
		assertThat(SurefireHelper.matchesAnySurefirePattern("com.foo.BarIT", patterns)).isTrue();
		assertThat(SurefireHelper.matchesAnySurefirePattern("com.foo.BarTest", patterns)).isFalse();
	}

	@Test
	void prefixWildcardMatchesAbstractClasses() {
		List<String> patterns = List.of("**/Abstract*.java");
		assertThat(SurefireHelper.matchesAnySurefirePattern("com.foo.AbstractThing", patterns)).isTrue();
		assertThat(SurefireHelper.matchesAnySurefirePattern("com.foo.ConcreteThing", patterns)).isFalse();
	}

	@Test
	void innerClassNormalizedToEnclosingBeforeMatch() {
		List<String> patterns = List.of("**/OuterTest.java");
		assertThat(SurefireHelper.matchesAnySurefirePattern("com.foo.OuterTest$Inner", patterns)).isTrue();
	}

	@Test
	void leadingDoubleStarMatchesRootLevelClass() {
		List<String> patterns = List.of("**/*IT.java");
		assertThat(SurefireHelper.matchesAnySurefirePattern("RootIT", patterns)).isTrue();
	}

	@Test
	void dotClassSuffixPatternAlsoMatches() {
		List<String> patterns = List.of("**/perf/PerformanceTest.class");
		assertThat(SurefireHelper.matchesAnySurefirePattern("org.apache.commons.csv.perf.PerformanceTest", patterns))
				.isTrue();
	}

	@Test
	void regexPatternMatchesFqcnForm() {
		List<String> patterns = List.of("%regex[.*PerformanceTest]");
		assertThat(SurefireHelper.matchesAnySurefirePattern("org.apache.commons.csv.perf.PerformanceTest", patterns))
				.isTrue();
		assertThat(SurefireHelper.matchesAnySurefirePattern("org.apache.commons.csv.CSVFormatTest", patterns))
				.isFalse();
	}

	@Test
	void emptyPatternListExcludesNothing() {
		assertThat(SurefireHelper.matchesAnySurefirePattern("com.foo.BarTest", List.of())).isFalse();
	}

	@Test
	void blankPatternIgnored() {
		assertThat(SurefireHelper.matchesAnySurefirePattern("com.foo.BarTest", java.util.Arrays.asList("", "   ")))
				.isFalse();
	}
}
