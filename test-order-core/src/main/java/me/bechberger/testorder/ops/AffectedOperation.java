package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestSelector;

/**
 * Framework-agnostic test selection orchestration. Runs TestSelector and writes
 * the selected/remaining test lists to disk.
 */
public final class AffectedOperation {

	private AffectedOperation() {
	}

	/** Input configuration for test selection. */
	public record SelectConfig(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTests, TestOrderState.ScoringWeights weights, int topN, int randomM, Long seed,
			Set<String> alwaysRunClasses, Path selectedFile, Path remainingFile, PluginLog log,
			Map<String, Double> changeComplexity) {
	}

	/** Result of test selection. */
	public record SelectResult(TestSelector.Selection selection, boolean allSelected, SelectionSummary summary) {

		/**
		 * Backward-compatible factory without summary (for synthesized full-run
		 * results).
		 */
		public static SelectResult of(TestSelector.Selection selection, boolean allSelected) {
			return new SelectResult(selection, allSelected, SelectionSummary.from(selection, 0, 0, 0, 0));
		}
	}

	/**
	 * Counts and the top-scored test name from a selection. Both Maven and Gradle
	 * plugins log {@link #format()} so the end-of-run summary is consistent.
	 */
	public record SelectionSummary(int selectedCount, int deferredCount, String topScorerName, int scoredCount,
			int newCount, int alwaysRunCount, int fastCount) {

		/** Total tests in the index for this selection (selected + deferred). */
		public int totalCount() {
			return selectedCount + deferredCount;
		}

		/** Percentage of tests selected (0 when total is 0). */
		public int percentSelected() {
			int total = totalCount();
			return total == 0 ? 0 : (100 * selectedCount) / total;
		}

		static SelectionSummary from(TestSelector.Selection selection, int scoredCount, int newCount,
				int alwaysRunCount, int fastCount) {
			String top = selection.selected().isEmpty() ? null : selection.selected().get(0);
			return new SelectionSummary(selection.selected().size(), selection.remaining().size(), top, scoredCount,
					newCount, alwaysRunCount, fastCount);
		}

		/** Multi-line summary block prefixed with "[test-order] ". */
		public String format() {
			return format(PluginContext.BuildSystem.MAVEN);
		}

		/** Multi-line summary block with build-system-appropriate commands. */
		public String format(PluginContext.BuildSystem buildSystem) {
			StringBuilder sb = new StringBuilder();
			int total = totalCount();
			sb.append("[test-order] Selected ").append(selectedCount).append(" / ").append(total).append(" tests");
			if (total > 0)
				sb.append(" (").append(percentSelected()).append("%)");
			if (topScorerName != null)
				sb.append("\n[test-order] Top: ").append(topScorerName);
			if (deferredCount > 0)
				sb.append("\n[test-order] ").append(deferredCount).append(" deferred — see remaining-file");
			sb.append("\n[test-order] Run `").append(buildSystem.showCommand()).append("` for the full ranking, or `")
					.append(buildSystem.dashboardCommand()).append("` for HTML.");
			return sb.toString();
		}
	}

	/**
	 * Runs test selection and writes lists to disk.
	 *
	 * @throws IOException
	 *             if writing test lists fails
	 */
	public static SelectResult select(SelectConfig config) throws IOException {
		// Warn when topN=-1 (all tests) is combined with a non-default randomM, since
		// randomM has no effect in that case. Skip when randomM is also the default
		// (10)
		// to avoid noise on every run.
		if (config.topN() == -1 && config.randomM() > 0 && config.randomM() != 10) {
			config.log().warn("[test-order] randomM=" + config.randomM()
					+ " has no effect when topN=-1 (all tests selected). Set topN to a positive number to use random sampling.");
		}
		// When no explicit seed is provided, derive one from the changed-class set so
		// selection is deterministic for the same inputs. Users who need to pin
		// reproducibility across machines can still set testorder.affected.seed
		// explicitly.
		Long effectiveSeed = config.seed();
		if (effectiveSeed == null && config.topN() > 0 && config.randomM() > 0) {
			int totalTests = config.depMap().testClasses().size();
			if (config.topN() < totalTests) {
				effectiveSeed = (long) config.changedClasses().stream().sorted()
						.collect(java.util.stream.Collectors.joining(",")).hashCode();
			}
		}

		TestSelector.Selection selection = new TestSelector(config.depMap(), config.state(), config.changedClasses(),
				config.changedTests(), config.weights(),
				new TestSelector.Config(config.topN(), config.randomM(), effectiveSeed), config.alwaysRunClasses(),
				config.changeComplexity()).select();

		// Warn when changed classes appear in deps of >50% of all tests: the scorer
		// can't discriminate and the selection is essentially a random subset.
		// Collapse into one summary when many classes exceed the threshold.
		if (!config.changedClasses().isEmpty()) {
			int totalTests = config.depMap().testClasses().size();
			if (totalTests > 0) {
				List<String> weakSignalClasses = new ArrayList<>();
				for (String changed : config.changedClasses()) {
					long matchCount = config.depMap().getAffectedTests(Set.of(changed)).size();
					if (matchCount > totalTests / 2) {
						weakSignalClasses.add(changed + " (" + (100 * matchCount / totalTests) + "%)");
					}
				}
				if (!weakSignalClasses.isEmpty()) {
					if (weakSignalClasses.size() == 1) {
						config.log()
								.warn("[test-order] Changed class " + weakSignalClasses.get(0)
										+ " appears in deps of >50% of tests. Selection signal is weak — results may be"
										+ " near-random. Consider running the full suite.");
					} else {
						String sample = weakSignalClasses.stream().limit(3)
								.collect(java.util.stream.Collectors.joining(", "));
						config.log()
								.warn("[test-order] " + weakSignalClasses.size()
										+ " changed classes appear in deps of >50% of tests (e.g. " + sample
										+ "). Selection signal is weak — consider running the full suite.");
					}
				}
			}
		}

		if (config.selectedFile() != null) {
			TestSelector.writeTestList(selection.selected(), config.selectedFile());
		}
		if (config.remainingFile() != null) {
			if (!selection.remaining().isEmpty()) {
				TestSelector.writeTestList(selection.remaining(), config.remainingFile());
			} else {
				// Delete stale remaining file from a previous run to prevent run-remaining
				// from re-executing deferred tests that are no longer relevant.
				try {
					java.nio.file.Files.deleteIfExists(config.remainingFile());
				} catch (IOException e) {
					config.log().warn("[test-order] Could not delete stale remaining file " + config.remainingFile()
							+ ": " + e.getMessage());
				}
			}
		}

		boolean allSelected = selection.remaining().isEmpty();
		int alwaysRunCount = 0;
		int newCount = 0;
		int fastCount = selection.randomFastCount();
		int scoredCount = 0;
		if (!selection.selected().isEmpty() || !selection.remaining().isEmpty()) {
			alwaysRunCount = (int) selection.selected().stream().filter(t -> config.alwaysRunClasses().contains(t))
					.count();
			newCount = (int) selection.selected().stream()
					.filter(t -> !config.depMap().testClasses().contains(t) && !config.alwaysRunClasses().contains(t))
					.count();
			scoredCount = selection.selected().size() - alwaysRunCount - newCount - fastCount;
		}
		if (selection.selected().isEmpty() && selection.remaining().isEmpty()) {
			// no tests in depMap — nothing to report
		} else if (allSelected) {
			config.log().info("[test-order] Selected all " + selection.selected().size()
					+ " affected tests (topN=-1, running in priority order)");
		} else {
			StringBuilder bd = new StringBuilder();
			if (scoredCount > 0)
				bd.append(scoredCount).append(" scored");
			if (newCount > 0) {
				if (bd.length() > 0)
					bd.append(" + ");
				bd.append(newCount).append(" new");
			}
			if (alwaysRunCount > 0) {
				if (bd.length() > 0)
					bd.append(" + ");
				bd.append(alwaysRunCount).append(" always-run");
			}
			if (fastCount > 0) {
				if (bd.length() > 0)
					bd.append(" + ");
				bd.append(fastCount).append(" fast-diverse");
			}
			config.log().info("[test-order] Selected " + selection.selected().size() + " tests (" + bd + "), deferred "
					+ selection.remaining().size());
		}

		SelectionSummary summary = SelectionSummary.from(selection, scoredCount, newCount, alwaysRunCount, fastCount);
		return new SelectResult(selection, allSelected, summary);
	}
}
