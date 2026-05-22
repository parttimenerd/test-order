package me.bechberger.testorder.ops.detection;

/** Prerequisites that a detection algorithm may require. */
public enum Prerequisite {
	/** test-dependencies.lz4 exists */
	DEPENDENCY_MAP,
	/** MEMBER mode data available (field-level) */
	MEMBER_DEPS,
	/** At least 1 historical run in state */
	RUN_HISTORY,
	/** 3+ historical runs (for anomaly detection) */
	MULTIPLE_RUNS,
	/** At least one known all-passing order */
	PASSING_REFERENCE
}
