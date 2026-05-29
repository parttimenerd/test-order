package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestSelector;

/**
 * Framework-agnostic test selection orchestration. Runs TestSelector and writes
 * the selected/remaining test lists to disk.
 */
public final class SelectOperation {

	private SelectOperation() {
	}

	/** Input configuration for test selection. */
	public record SelectConfig(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTests, TestOrderState.ScoringWeights weights, int topN, int randomM, Long seed,
			Set<String> alwaysRunClasses, Path selectedFile, Path remainingFile, PluginLog log,
			Map<String, Double> changeComplexity) {
	}

	/** Result of test selection. */
	public record SelectResult(TestSelector.Selection selection, boolean allSelected) {
	}

	/**
	 * Runs test selection and writes lists to disk.
	 *
	 * @throws IOException
	 *             if writing test lists fails
	 */
	public static SelectResult select(SelectConfig config) throws IOException {
		// R14-6: Warn if randomM is explicitly configured but topN=-1 makes it a no-op
		// Only warn when topN is *not* the default (-1), meaning the user set randomM
		// without also setting topN — or when topN=-1 is explicitly combined with
		// randomM.
		// Skip this warning for default configuration to avoid noise on every run.
		if (config.topN() == -1 && config.randomM() > 0 && config.randomM() != 10) {
			config.log().warn("[test-order] randomM=" + config.randomM()
					+ " has no effect when topN=-1 (all tests selected). Set topN to a positive number to use random sampling.");
		}
		// When no explicit seed is provided, derive one from the changed-class set so
		// selection is deterministic for the same inputs. Users who need to pin
		// reproducibility across machines can still set testorder.select.seed
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

		// Warn when a changed class appears in deps of >50% of all tests: the scorer
		// can't discriminate and the selection is essentially a random subset.
		if (!config.changedClasses().isEmpty()) {
			int totalTests = config.depMap().testClasses().size();
			if (totalTests > 0) {
				for (String changed : config.changedClasses()) {
					long matchCount = config.depMap().testClasses().stream()
							.filter(t -> config.depMap().get(t).contains(changed)).count();
					if (matchCount > totalTests / 2) {
						config.log()
								.warn("[test-order] Changed class " + changed + " appears in deps of " + matchCount
										+ "/" + totalTests + " tests (" + (100 * matchCount / totalTests) + "%). "
										+ "Selection signal is weak — results may be near-random. "
										+ "Consider running the full suite.");
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
		if (selection.selected().isEmpty() && selection.remaining().isEmpty()) {
			// no tests in depMap — nothing to report
		} else if (allSelected) {
			config.log().info("[test-order] Selected all " + selection.selected().size()
					+ " tests (no subset — all will run in priority order)");
		} else {
			config.log().info("[test-order] Selected " + selection.selected().size() + " tests, deferred "
					+ selection.remaining().size());
		}

		return new SelectResult(selection, allSelected);
	}
}
