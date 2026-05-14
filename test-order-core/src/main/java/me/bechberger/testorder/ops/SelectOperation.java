package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

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
		// without also setting topN — or when topN=-1 is explicitly combined with randomM.
		// Skip this warning for default configuration to avoid noise on every run.
		if (config.topN() == -1 && config.randomM() > 0 && config.randomM() != 10) {
			config.log().warn("[test-order] randomM=" + config.randomM()
					+ " has no effect when topN=-1 (all tests selected). Set topN to a positive number to use random sampling.");
		}
		// R15-5: Warn if randomM is used without a seed — only when topN is positive
		// (i.e. actual subsetting is happening), since non-determinism matters only
		// when a subset is being selected.
		if (config.topN() > 0 && config.randomM() > 0 && config.seed() == null) {
			config.log().warn("[test-order] Selection is non-deterministic (no seed set). "
					+ "Set testorder.select.seed for reproducible CI runs.");
		}

		TestSelector.Selection selection = new TestSelector(config.depMap(), config.state(), config.changedClasses(),
				config.changedTests(), config.weights(),
				new TestSelector.Config(config.topN(), config.randomM(), config.seed()), config.alwaysRunClasses(),
				config.changeComplexity()).select();

		if (config.selectedFile() != null) {
			TestSelector.writeTestList(selection.selected(), config.selectedFile());
		}
		if (config.remainingFile() != null && !selection.remaining().isEmpty()) {
			TestSelector.writeTestList(selection.remaining(), config.remainingFile());
		}

		boolean allSelected = selection.remaining().isEmpty();
		if (allSelected) {
			config.log().info("[test-order] Selected all " + selection.selected().size()
					+ " tests (no subset — all will run in priority order)");
		} else {
			config.log().info("[test-order] Selected " + selection.selected().size() + " tests, deferred "
					+ selection.remaining().size());
		}

		return new SelectResult(selection, allSelected);
	}
}
