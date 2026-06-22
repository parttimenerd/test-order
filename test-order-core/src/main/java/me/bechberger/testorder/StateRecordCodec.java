package me.bechberger.testorder;

import java.util.List;
import java.util.logging.Logger;

import me.bechberger.util.json.Util;

/**
 * Encodes and decodes compact run outcome records for state persistence.
 */
final class StateRecordCodec {

	private StateRecordCodec() {
	}

	// ── Wire-format v1 array positions ────────────────────────────────────────
	// Format version 1: [testClass, flags, depOverlap, depTotal, failScore,
	// complexityOverlap, speedRatio, totalScore]
	// flags is a bitmask: bit0=isNew, bit1=isChanged, bit2=isFast, bit3=isSlow,
	// bit4=failed, bit5=hasStaticFieldOverlap
	static final int IDX_TEST_CLASS = 0;
	static final int IDX_FLAGS = 1;
	static final int IDX_DEP_OVERLAP = 2;
	static final int IDX_DEP_TOTAL = 3;
	static final int IDX_FAIL_SCORE = 4;
	static final int IDX_COMPLEXITY_OVERLAP = 5;
	static final int IDX_SPEED_RATIO = 6;
	static final int IDX_TOTAL_SCORE = 7;

	static List<Object> outcomeToCompact(TestOrderState.TestOutcome o) {
		int flags = (o.isNew() ? 1 : 0) | (o.isChanged() ? 2 : 0) | (o.isFast() ? 4 : 0) | (o.isSlow() ? 8 : 0)
				| (o.failed() ? 16 : 0) | (o.hasStaticFieldOverlap() ? 32 : 0);
		return List.of(o.testClass(), flags, o.depOverlap(), o.depTotal(), o.failScore(), o.complexityOverlap(),
				o.speedRatio(), o.totalScore());
	}

	/**
	 * Slim variant for all-pass runs: only testClass + zero flags. Sufficient for
	 * {@code passStreak()} (skip-if-unchanged cache) to detect that the test ran
	 * and passed; the score/breakdown fields are unused by the optimizer when no
	 * failures are present in the run.
	 */
	static List<Object> outcomeToSlimCompact(TestOrderState.TestOutcome o) {
		return List.of(o.testClass(), 0);
	}

	static TestOrderState.TestOutcome compactToOutcome(Object obj, Logger log) {
		if (!(obj instanceof List<?>)) {
			log.fine("Skipping non-list outcome entry (legacy format): "
					+ (obj == null ? "null" : obj.getClass().getSimpleName()));
			return null;
		}
		List<Object> arr = Util.asList(obj);
		if (arr.size() < 2 || !(arr.get(IDX_TEST_CLASS) instanceof String)) {
			log.fine("Skipping numeric/empty compact outcome entry (legacy format)");
			return null;
		}
		try {
			String tc = (String) arr.get(IDX_TEST_CLASS);
			int flags = toInt(arr.get(IDX_FLAGS));
			int totalScore = arr.size() > IDX_TOTAL_SCORE ? toInt(arr.get(IDX_TOTAL_SCORE)) : 0;
			return new TestOrderState.TestOutcome(tc, totalScore, (flags & 1) != 0, (flags & 2) != 0,
					Math.max(0, arr.size() > IDX_DEP_OVERLAP ? toInt(arr.get(IDX_DEP_OVERLAP)) : 0),
					Math.max(0, arr.size() > IDX_DEP_TOTAL ? toInt(arr.get(IDX_DEP_TOTAL)) : 0),
					Math.max(0.0, arr.size() > IDX_FAIL_SCORE ? toDouble(arr.get(IDX_FAIL_SCORE)) : 0.0),
					(flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0,
					Math.max(0.0,
							arr.size() > IDX_COMPLEXITY_OVERLAP ? toDouble(arr.get(IDX_COMPLEXITY_OVERLAP)) : 0.0),
					Math.max(0.0, arr.size() > IDX_SPEED_RATIO ? toDouble(arr.get(IDX_SPEED_RATIO)) : 0.0),
					(flags & 32) != 0);
		} catch (NumberFormatException e) {
			log.warning("Malformed compact outcome entry, skipping: " + e.getMessage());
			return null;
		}
	}

	private static int toInt(Object o) {
		if (o == null)
			return 0;
		if (o instanceof Number n)
			return n.intValue();
		return Integer.parseInt(o.toString());
	}

	private static double toDouble(Object o) {
		if (o == null)
			return 0.0;
		if (o instanceof Number n)
			return n.doubleValue();
		return Double.parseDouble(o.toString());
	}
}
