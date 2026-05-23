package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import me.bechberger.testorder.TestOrderState;

/**
 * Framework-agnostic scoring weight resolution. Loads weights from file, state,
 * or defaults — then applies user-specified overrides.
 */
public final class WeightResolverOperation {

	private WeightResolverOperation() {
	}

	/**
	 * Resolves loaded weights with full metadata (weight defs, decay values).
	 * Priority: weightsFile → state file weights → defaults.
	 */
	public static TestOrderState.LoadedWeights resolveLoadedWeights(Path weightsFile, TestOrderState state,
			PluginLog log) {
		if (weightsFile != null && Files.exists(weightsFile)) {
			try {
				TestOrderState.LoadedWeights loaded = TestOrderState.ScoringWeights.loadFromFile(weightsFile);
				// Validate that file contained at least some recognized weight keys
				if (loaded.weights().newTest() == TestOrderState.WEIGHT_DEFS.get(0).defaultValue()
						&& loaded.weights().changedTest() == TestOrderState.WEIGHT_DEFS.get(1).defaultValue()
						&& loaded.weights().maxFailure() == TestOrderState.WEIGHT_DEFS.get(2).defaultValue()) {
					// All weights are at defaults, likely means TOML file had no recognized keys
					log.warn("[test-order] Weights file " + weightsFile + " contains no recognized weight keys. "
							+ "Ensure TOML file uses bare key names (e.g. 'speed = 200', not 'testorder.score.speed = 200').");
				}
				log.info("[test-order] Loaded scoring weights from: " + weightsFile);
				return loaded;
			} catch (IOException e) {
				log.warn("[test-order] Failed to load weights file " + weightsFile + ": " + e.getMessage()
						+ " — using defaults.");
			}
		} else if (weightsFile != null) {
			log.warn("[test-order] Weights file does not exist: " + weightsFile.toAbsolutePath()
					+ " — using defaults. Check the path specified by -Dtestorder.weights.file.");
		}
		TestOrderState.ScoringWeights sw = state.weights();
		return new TestOrderState.LoadedWeights(sw, TestOrderState.WEIGHT_DEFS, state.failureDecay(),
				state.methodFailureDecay(), state.durationAlpha(), state.methodDurationAlpha(),
				state.failurePruneThreshold());
	}

	/**
	 * Resolves just the scoring weights (without metadata).
	 */
	public static TestOrderState.ScoringWeights resolveWeights(Path weightsFile, TestOrderState state, PluginLog log) {
		return resolveLoadedWeights(weightsFile, state, log).weights();
	}

	/**
	 * Applies user-specified score overrides to base weights. Each non-null
	 * override replaces the corresponding component.
	 */
	public static TestOrderState.ScoringWeights applyOverrides(TestOrderState.ScoringWeights base, Integer newTest,
			Integer changedTest, Integer maxFailure, Integer speed, Integer speedPenalty, Integer depOverlap,
			Integer changeComplexity, Integer staticFieldBonus, Integer coverageBonus) {
		return new TestOrderState.ScoringWeights(newTest != null ? newTest : base.newTest(),
				changedTest != null ? changedTest : base.changedTest(),
				maxFailure != null ? maxFailure : base.maxFailure(), speed != null ? speed : base.speed(),
				speedPenalty != null ? speedPenalty : base.speedPenalty(),
				depOverlap != null ? depOverlap : base.depOverlap(),
				changeComplexity != null ? changeComplexity : base.changeComplexity(),
				staticFieldBonus != null ? staticFieldBonus : base.staticFieldBonus(),
				coverageBonus != null ? coverageBonus : base.coverageBonus());
	}

	/**
	 * Builds a map of score overrides from individual values. Only non-null values
	 * are included.
	 *
	 * @return override map, or {@code null} if all values are null
	 */
	public static Map<String, Integer> buildScoreOverrides(Integer newTest, Integer changedTest, Integer maxFailure,
			Integer speed, Integer speedPenalty, Integer depOverlap, Integer changeComplexity, Integer staticFieldBonus,
			Integer coverageBonus) {
		Map<String, Integer> scores = new LinkedHashMap<>();
		if (newTest != null)
			scores.put("newTest", newTest);
		if (changedTest != null)
			scores.put("changedTest", changedTest);
		if (maxFailure != null)
			scores.put("maxFailure", maxFailure);
		if (speed != null)
			scores.put("speed", speed);
		if (speedPenalty != null)
			scores.put("speedPenalty", speedPenalty);
		if (depOverlap != null)
			scores.put("depOverlap", depOverlap);
		if (changeComplexity != null)
			scores.put("changeComplexity", changeComplexity);
		if (staticFieldBonus != null)
			scores.put("staticFieldBonus", staticFieldBonus);
		if (coverageBonus != null)
			scores.put("coverageBonus", coverageBonus);
		return scores.isEmpty() ? null : scores;
	}
}
