package me.bechberger.testorder;

import java.util.logging.Logger;

/**
 * Encapsulates all configuration parameters for test scoring and state
 * persistence.
 *
 * <p>
 * This class manages:
 * <ul>
 * <li>Failure decay rates (class-level and method-level)</li>
 * <li>Duration EMA (exponential moving average) alpha values (class and
 * method)</li>
 * <li>Failure pruning thresholds (minimum score to retain)</li>
 * <li>EMA variance thresholds (for adaptive alpha calculation)</li>
 * <li>Run history limits (max runs to retain in state file)</li>
 * <li>Run counters (runs since last learn mode)</li>
 * </ul>
 *
 * <p>
 * All default values are loaded from the {@code default-scoring-weights.toml}
 * resource file. All setters validate inputs to prevent invalid state
 * accumulation. Configuration parameters can be loaded from a TOML file or set
 * programmatically.
 */
public class StateConfiguration {

	private static final Logger LOG = Logger.getLogger(StateConfiguration.class.getName());

	// Configuration parameters with defaults from TestOrderState resource
	private double failureDecay = TestOrderState.DEFAULT_FAILURE_DECAY;
	private double methodFailureDecay = TestOrderState.DEFAULT_METHOD_FAILURE_DECAY;
	private double durationAlpha = TestOrderState.DEFAULT_DURATION_ALPHA;
	private double methodDurationAlpha = TestOrderState.DEFAULT_METHOD_DURATION_ALPHA;
	private double failurePruneThreshold = TestOrderState.DEFAULT_FAILURE_PRUNE_THRESHOLD;
	private double emaVarianceThreshold = TestOrderState.DEFAULT_EMA_VARIANCE_THRESHOLD;
	private int historyMaxRuns = TestOrderState.DEFAULT_HISTORY_MAX_RUNS;
	private int runsSinceLearn = 0;
	private String dependencyFingerprint = null;

	/**
	 * Constructs a configuration with all default values from the resource file.
	 */
	public StateConfiguration() {
	}

	/**
	 * Copy constructor.
	 */
	public StateConfiguration(StateConfiguration other) {
		this.failureDecay = other.failureDecay;
		this.methodFailureDecay = other.methodFailureDecay;
		this.durationAlpha = other.durationAlpha;
		this.methodDurationAlpha = other.methodDurationAlpha;
		this.failurePruneThreshold = other.failurePruneThreshold;
		this.emaVarianceThreshold = other.emaVarianceThreshold;
		this.historyMaxRuns = other.historyMaxRuns;
		this.runsSinceLearn = other.runsSinceLearn;
		this.dependencyFingerprint = other.dependencyFingerprint;
	}

	// ── Failure Decay ─────────────────────────────────────────────────

	/**
	 * Returns the per-run decay rate for class-level failure scores.
	 * <p>
	 * Each run, historical failure scores are multiplied by (1 - failureDecay).
	 * Valid range: [0, 1], where 0 means no decay and 1 means complete reset.
	 * </p>
	 *
	 * @return decay rate in [0, 1]
	 */
	public double failureDecay() {
		return failureDecay;
	}

	/**
	 * Sets the per-run decay rate for class-level failure scores.
	 *
	 * @param d
	 *            decay rate in [0, 1]
	 * @throws IllegalArgumentException
	 *             if d is not in [0, 1]
	 */
	public void setFailureDecay(double d) {
		if (Double.isNaN(d) || Double.isInfinite(d) || d < 0 || d > 1) {
			throw new IllegalArgumentException("failureDecay must be in [0, 1]: " + d);
		}
		this.failureDecay = d;
	}

	/**
	 * Returns the per-run decay rate for method-level failure scores.
	 *
	 * @return decay rate in [0, 1]
	 */
	public double methodFailureDecay() {
		return methodFailureDecay;
	}

	/**
	 * Sets the per-run decay rate for method-level failure scores.
	 *
	 * @param d
	 *            decay rate in [0, 1]
	 * @throws IllegalArgumentException
	 *             if d is not in [0, 1]
	 */
	public void setMethodFailureDecay(double d) {
		if (Double.isNaN(d) || Double.isInfinite(d) || d < 0 || d > 1) {
			throw new IllegalArgumentException("methodFailureDecay must be in [0, 1]: " + d);
		}
		this.methodFailureDecay = d;
	}

	// ── Duration EMA (Exponential Moving Average) ─────────────────────

	/**
	 * Returns the smoothing factor for class-level test duration EMA.
	 * <p>
	 * Formula: newDuration = alpha * measured + (1 - alpha) * previous. Higher
	 * alpha weights recent measurements more heavily.
	 * </p>
	 *
	 * @return alpha in [0, 1]
	 */
	public double durationAlpha() {
		return durationAlpha;
	}

	/**
	 * Sets the smoothing factor for class-level test duration EMA.
	 *
	 * @param a
	 *            alpha in [0, 1]
	 * @throws IllegalArgumentException
	 *             if a is not in [0, 1]
	 */
	public void setDurationAlpha(double a) {
		if (Double.isNaN(a) || Double.isInfinite(a) || a < 0 || a > 1) {
			throw new IllegalArgumentException("durationAlpha must be in [0, 1]: " + a);
		}
		this.durationAlpha = a;
	}

	/**
	 * Returns the smoothing factor for method-level test duration EMA.
	 *
	 * @return alpha in [0, 1]
	 */
	public double methodDurationAlpha() {
		return methodDurationAlpha;
	}

	/**
	 * Sets the smoothing factor for method-level test duration EMA.
	 *
	 * @param a
	 *            alpha in [0, 1]
	 * @throws IllegalArgumentException
	 *             if a is not in [0, 1]
	 */
	public void setMethodDurationAlpha(double a) {
		if (Double.isNaN(a) || Double.isInfinite(a) || a < 0 || a > 1) {
			throw new IllegalArgumentException("methodDurationAlpha must be in [0, 1]: " + a);
		}
		this.methodDurationAlpha = a;
	}

	// ── Pruning Thresholds ────────────────────────────────────────────

	/**
	 * Returns the minimum score below which a failure entry is pruned from the
	 * state file.
	 * <p>
	 * This prevents the state file from accumulating negligible failure records
	 * from tests that fail very rarely.
	 * </p>
	 *
	 * @return threshold, must be >= 0 and finite
	 */
	public double failurePruneThreshold() {
		return failurePruneThreshold;
	}

	/**
	 * Sets the minimum score below which a failure entry is pruned.
	 *
	 * @param t
	 *            threshold, must be >= 0 and finite
	 * @throws IllegalArgumentException
	 *             if t is negative, NaN, or infinite
	 */
	public void setFailurePruneThreshold(double t) {
		if (Double.isNaN(t) || Double.isInfinite(t) || t < 0) {
			throw new IllegalArgumentException("failurePruneThreshold must be >= 0 and finite: " + t);
		}
		this.failurePruneThreshold = t;
	}

	/**
	 * Returns the variance threshold used in adaptive EMA alpha calculation.
	 * <p>
	 * When the relative standard deviation of a test's duration exceeds this
	 * threshold, the effective EMA alpha is <em>reduced</em> (more aggressive
	 * smoothing) to damp out measurement noise. Tests with stable durations
	 * (relative std-dev below the threshold) use the configured base alpha
	 * unchanged.
	 * </p>
	 *
	 * @return threshold, must be >= 0
	 */
	public double emaVarianceThreshold() {
		return emaVarianceThreshold;
	}

	/**
	 * Sets the variance threshold for adaptive alpha calculation.
	 *
	 * @param threshold
	 *            variance threshold, must be >= 0
	 * @throws IllegalArgumentException
	 *             if threshold is negative, NaN, or infinite
	 */
	public void setEmaVarianceThreshold(double threshold) {
		if (Double.isNaN(threshold) || Double.isInfinite(threshold) || threshold < 0) {
			throw new IllegalArgumentException("emaVarianceThreshold must be >= 0 and finite: " + threshold);
		}
		this.emaVarianceThreshold = threshold;
	}

	// ── Run History Management ────────────────────────────────────────

	/**
	 * Returns the maximum number of run records to retain in the state file.
	 * <p>
	 * Older runs are sampled down when this limit is exceeded.
	 * </p>
	 *
	 * @return max runs, must be > 0
	 */
	public int historyMaxRuns() {
		return historyMaxRuns;
	}

	/**
	 * Sets the maximum number of run records to retain.
	 * <p>
	 * If the current run history exceeds this limit, older runs are sampled and
	 * discarded. This is called automatically when setting a new limit.
	 * </p>
	 *
	 * @param maxRuns
	 *            max runs, must be > 0
	 * @throws IllegalArgumentException
	 *             if maxRuns <= 0
	 * @see #historyMaxRuns()
	 */
	public void setHistoryMaxRuns(int maxRuns) {
		if (maxRuns <= 0) {
			throw new IllegalArgumentException("historyMaxRuns must be > 0: " + maxRuns);
		}
		this.historyMaxRuns = maxRuns;
	}

	// ── Run Counter ───────────────────────────────────────────────────

	/**
	 * Returns the count of order-mode test runs since the last learn mode.
	 * <p>
	 * This counter is used by the {@code auto} mojo to trigger periodic genetic
	 * algorithm weight optimization.
	 * </p>
	 *
	 * @return run count since learn, >= 0
	 */
	public int runsSinceLearn() {
		return runsSinceLearn;
	}

	/**
	 * Resets the order-mode run counter to zero.
	 * <p>
	 * Called when switching to learn mode.
	 * </p>
	 */
	public void resetRunsSinceLearn() {
		this.runsSinceLearn = 0;
	}

	/**
	 * Increments the order-mode run counter.
	 * <p>
	 * Called after each order-mode test run completes.
	 * </p>
	 */
	public void incrementRunsSinceLearn() {
		// Wrap at MAX_VALUE back to 1 to stay positive; the <= 0 guard in
		// AutoWorkflow.optimizeIfDue would silence the optimizer permanently if this
		// ever went negative (BUG-90).
		if (this.runsSinceLearn == Integer.MAX_VALUE) {
			this.runsSinceLearn = 1;
		} else {
			this.runsSinceLearn++;
		}
	}

	/**
	 * Sets the order-mode run counter to a specific value. Package-private; only
	 * used during state file deserialization.
	 */
	void setRunsSinceLearn(int value) {
		this.runsSinceLearn = Math.max(0, value);
	}

	// ── Dependency Fingerprint ────────────────────────────────────────

	/**
	 * Returns the stored fingerprint of build/dependency files (pom.xml,
	 * build.gradle, etc.) from the last learn run, or {@code null} if not yet
	 * recorded.
	 */
	public String dependencyFingerprint() {
		return dependencyFingerprint;
	}

	/**
	 * Sets the dependency fingerprint. Called after a learn run completes to record
	 * the current state of build files.
	 */
	public void setDependencyFingerprint(String fingerprint) {
		this.dependencyFingerprint = fingerprint;
	}

	/**
	 * Resets all configuration parameters to their default values from the resource
	 * file.
	 */
	public void reset() {
		this.failureDecay = TestOrderState.DEFAULT_FAILURE_DECAY;
		this.methodFailureDecay = TestOrderState.DEFAULT_METHOD_FAILURE_DECAY;
		this.durationAlpha = TestOrderState.DEFAULT_DURATION_ALPHA;
		this.methodDurationAlpha = TestOrderState.DEFAULT_METHOD_DURATION_ALPHA;
		this.failurePruneThreshold = TestOrderState.DEFAULT_FAILURE_PRUNE_THRESHOLD;
		this.emaVarianceThreshold = TestOrderState.DEFAULT_EMA_VARIANCE_THRESHOLD;
		this.historyMaxRuns = TestOrderState.DEFAULT_HISTORY_MAX_RUNS;
		this.runsSinceLearn = 0;
		this.dependencyFingerprint = null;
	}
}
