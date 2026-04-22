package me.bechberger.testorder.plugin.it;

import java.util.List;

import org.assertj.core.api.AbstractAssert;

/**
 * AssertJ assertion for {@link MavenResult}.
 * <p>
 * Usage: {@code assertThat(result).succeeded().outputContains("[test-order]")}
 */
public class MavenResultAssert extends AbstractAssert<MavenResultAssert, MavenResult> {

	private MavenResultAssert(MavenResult actual) {
		super(actual, MavenResultAssert.class);
	}

	public static MavenResultAssert assertThat(MavenResult result) {
		return new MavenResultAssert(result);
	}

	/** Assert the Maven run exited with code 0. */
	public MavenResultAssert succeeded() {
		isNotNull();
		if (!actual.isSuccess()) {
			failWithMessage(
					"Expected Maven to succeed (exit 0) but got exit %d.%nCommand: %s%nOutput (last 80 lines):%n%s",
					actual.exitCode(), actual.args(), lastLines(actual.output(), 80));
		}
		return this;
	}

	/** Assert the Maven run exited with a non-zero code. */
	public MavenResultAssert failed() {
		isNotNull();
		if (actual.isSuccess()) {
			failWithMessage("Expected Maven to fail but it succeeded.%nCommand: %s", actual.args());
		}
		return this;
	}

	/** Assert the output contains the given substring. */
	public MavenResultAssert outputContains(String substring) {
		isNotNull();
		if (!actual.output().contains(substring)) {
			failWithMessage("Expected output to contain:%n  \"%s\"%nbut it did not.%nOutput (last 40 lines):%n%s",
					substring, lastLines(actual.output(), 40));
		}
		return this;
	}

	/** Assert the output does NOT contain the given substring. */
	public MavenResultAssert outputDoesNotContain(String substring) {
		isNotNull();
		if (actual.output().contains(substring)) {
			List<String> matches = actual.grepOutput(substring);
			failWithMessage("Expected output to NOT contain \"%s\" but found %d match(es):%n%s", substring,
					matches.size(), String.join("\n", matches));
		}
		return this;
	}

	/** Assert the output matches a regex pattern (anywhere in output). */
	public MavenResultAssert outputMatches(String regex) {
		isNotNull();
		if (!actual.output().matches("(?s).*" + regex + ".*")) {
			failWithMessage("Expected output to match regex:%n  %s%nbut it did not.%nOutput (last 40 lines):%n%s",
					regex, lastLines(actual.output(), 40));
		}
		return this;
	}

	private static String lastLines(String text, int n) {
		List<String> lines = text.lines().toList();
		int start = Math.max(0, lines.size() - n);
		return String.join("\n", lines.subList(start, lines.size()));
	}
}
