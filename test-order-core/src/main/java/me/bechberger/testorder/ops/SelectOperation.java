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
		TestSelector.Selection selection = new TestSelector(config.depMap(), config.state(), config.changedClasses(),
				config.changedTests(), config.weights(),
				new TestSelector.Config(config.topN(), config.randomM(), config.seed()), config.alwaysRunClasses(),
				config.changeComplexity())
				.select();

		if (config.selectedFile() != null) {
			TestSelector.writeTestList(selection.selected(), config.selectedFile());
		}
		if (config.remainingFile() != null) {
			TestSelector.writeTestList(selection.remaining(), config.remainingFile());
		}

		boolean allSelected = selection.remaining().isEmpty();
		if (allSelected) {
			config.log().info("[test-order] Running full test suite (selection covered all tests)");
		} else {
			config.log().info("[test-order] Selected " + selection.selected().size() + " tests, deferred "
					+ selection.remaining().size());
		}

		return new SelectResult(selection, allSelected);
	}
}
