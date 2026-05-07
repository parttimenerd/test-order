package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TieredTestSelector;

/**
 * Framework-agnostic tiered test selection orchestration. Runs
 * {@link TieredTestSelector} and writes the tier files to disk.
 *
 * <p>
 * Three-tiered CI execution:
 * <ol>
 * <li>Tier 1: change-affected tests (dep overlap, new, changed, @AlwaysRun)</li>
 * <li>Tier 2: top-scored remaining tests (configurable fraction by duration)</li>
 * <li>Tier 3: everything else</li>
 * </ol>
 */
public final class TieredSelectOperation {

	private TieredSelectOperation() {
	}

	/** Input configuration for tiered test selection. */
	public record TieredSelectConfig(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTests, TestOrderState.ScoringWeights weights, double tier2Fraction,
			boolean weightByDuration, Set<String> alwaysRunClasses, Path tier1File, Path tier2File, Path tier3File,
			PluginLog log) {
	}

	/** Result of tiered test selection. */
	public record TieredSelectResult(TieredTestSelector.TieredSelection selection) {

		public boolean tier1Only() {
			return selection.tier2().isEmpty() && selection.tier3().isEmpty();
		}

		public int totalTests() {
			return selection.tier1().size() + selection.tier2().size() + selection.tier3().size();
		}
	}

	/**
	 * Runs tiered test selection and writes tier files to disk.
	 *
	 * @throws IOException
	 *             if writing tier files fails
	 */
	public static TieredSelectResult select(TieredSelectConfig config) throws IOException {
		TieredTestSelector.TieredSelection selection = new TieredTestSelector(config.depMap(), config.state(),
				config.changedClasses(), config.changedTests(), config.weights(),
				new TieredTestSelector.Config(config.tier2Fraction(), config.weightByDuration()),
				config.alwaysRunClasses()).select();

		TieredTestSelector.writeTierFiles(selection, config.tier1File(), config.tier2File(), config.tier3File());

		config.log().info("[test-order] Tiered selection: " + selection.tier1().size() + " tier-1 (change-affected), "
				+ selection.tier2().size() + " tier-2 (top-scored), " + selection.tier3().size() + " tier-3 (rest)");

		return new TieredSelectResult(selection);
	}
}
