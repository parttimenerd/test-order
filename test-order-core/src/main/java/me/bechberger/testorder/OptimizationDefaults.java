package me.bechberger.testorder;

/**
 * Public optimization defaults used across core and plugin modules.
 */
public final class OptimizationDefaults {

	private OptimizationDefaults() {
	}

	/** Minimum runs with failures needed for meaningful optimisation. */
	public static final int MIN_RUNS_FOR_OPTIMISATION = ScoringOptimizer.MIN_RUNS_FOR_OPTIMISATION;
}
