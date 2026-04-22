package me.bechberger.testorder.plugin.it;

import org.assertj.core.api.AbstractAssert;

import me.bechberger.testorder.TestOrderState;

/**
 * AssertJ assertion for {@link TestOrderState}.
 * <p>
 * Usage: {@code assertThat(state).isLoaded().hasDuration("com.example.MyTest")}
 */
public class TestOrderStateAssert extends AbstractAssert<TestOrderStateAssert, TestOrderState> {

	private TestOrderStateAssert(TestOrderState actual) {
		super(actual, TestOrderStateAssert.class);
	}

	public static TestOrderStateAssert assertThat(TestOrderState state) {
		return new TestOrderStateAssert(state);
	}

	/** Assert the state is not null. */
	public TestOrderStateAssert isLoaded() {
		if (actual == null) {
			failWithMessage("Expected state file to exist but it was null");
		}
		return this;
	}

	/** Assert a duration is recorded for the given test class. */
	public TestOrderStateAssert hasDuration(String testClass) {
		isNotNull();
		long dur = actual.getDuration(testClass, -1);
		if (dur < 0) {
			failWithMessage("Expected duration for '%s' but none was recorded", testClass);
		}
		return this;
	}

	/** Assert a duration is within a range (inclusive). */
	public TestOrderStateAssert hasDurationBetween(String testClass, long minMs, long maxMs) {
		isNotNull();
		long dur = actual.getDuration(testClass, -1);
		if (dur < minMs || dur > maxMs) {
			failWithMessage("Expected duration for '%s' between %d–%d ms but was %d", testClass, minMs, maxMs, dur);
		}
		return this;
	}

	/** Assert that at least one run record exists. */
	public TestOrderStateAssert hasRuns() {
		isNotNull();
		if (actual.runs().isEmpty()) {
			failWithMessage("Expected at least one run record but found none");
		}
		return this;
	}

	/** Assert the number of run records. */
	public TestOrderStateAssert hasRunCount(int expected) {
		isNotNull();
		int count = actual.runs().size();
		if (count != expected) {
			failWithMessage("Expected %d run records but found %d", expected, count);
		}
		return this;
	}

	/** Assert failure records exist for the given test class. */
	public TestOrderStateAssert hasFailureFor(String testClass) {
		isNotNull();
		double score = actual.failureScore(testClass);
		if (score <= 0) {
			var scores = actual.getFailureScores();
			failWithMessage("Expected failure record for '%s' but found none. Recorded failures: %s", testClass,
					scores.keySet());
		}
		return this;
	}

	/** Assert no failure records exist for the given test class. */
	public TestOrderStateAssert hasNoFailureFor(String testClass) {
		isNotNull();
		double score = actual.failureScore(testClass);
		if (score > 0) {
			failWithMessage("Expected no failure for '%s' but found score %.2f", testClass, score);
		}
		return this;
	}
}
