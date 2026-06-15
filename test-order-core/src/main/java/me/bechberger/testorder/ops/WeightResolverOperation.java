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
				// recognizedKeyCount == 0 means the TOML parsed successfully but contained no
				// weight keys we know about — almost certainly a misconfigured file.
				if (loaded.recognizedKeyCount() == 0) {
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
			Integer changeComplexity, Integer staticFieldBonus, Integer coverageBonus, Integer killRateBonus,
			Integer packageProximityBonus) {
		return new TestOrderState.ScoringWeights(newTest != null ? newTest : base.newTest(),
				changedTest != null ? changedTest : base.changedTest(),
				maxFailure != null ? maxFailure : base.maxFailure(), speed != null ? speed : base.speed(),
				speedPenalty != null ? speedPenalty : base.speedPenalty(),
				depOverlap != null ? depOverlap : base.depOverlap(),
				changeComplexity != null ? changeComplexity : base.changeComplexity(),
				staticFieldBonus != null ? staticFieldBonus : base.staticFieldBonus(),
				coverageBonus != null ? coverageBonus : base.coverageBonus(),
				killRateBonus != null ? killRateBonus : base.killRateBonus(),
				packageProximityBonus != null ? packageProximityBonus : base.packageProximityBonus());
	}

	/**
	 * Builds a map of score overrides from individual values. Only non-null values
	 * are included.
	 *
	 * @return override map, or {@code null} if all values are null
	 */
	public static Map<String, Integer> buildScoreOverrides(Integer newTest, Integer changedTest, Integer maxFailure,
			Integer speed, Integer speedPenalty, Integer depOverlap, Integer changeComplexity, Integer staticFieldBonus,
			Integer coverageBonus, Integer killRateBonus, Integer packageProximityBonus) {
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
		if (killRateBonus != null)
			scores.put("killRateBonus", killRateBonus);
		if (packageProximityBonus != null)
			scores.put("packageProximityBonus", packageProximityBonus);
		return scores.isEmpty() ? null : scores;
	}
}
