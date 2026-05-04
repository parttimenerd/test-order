package me.bechberger.testorder;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

final class PendingRunCoordinator {

	private static final ConcurrentHashMap<String, TestOrderState.ScoreBreakdown> PENDING_BREAKDOWNS = new ConcurrentHashMap<>();
	private static volatile String PENDING_STATE_PATH = null;

	private PendingRunCoordinator() {
	}

	static void recordBreakdown(String testClass, TestOrderState.ScoreBreakdown breakdown) {
		PENDING_BREAKDOWNS.put(testClass, breakdown);
	}

	static void setStatePath(String path) {
		PENDING_STATE_PATH = path;
	}

	static boolean hasPendingData() {
		return !PENDING_BREAKDOWNS.isEmpty() && PENDING_STATE_PATH != null;
	}

	static Map<String, TestOrderState.ScoreBreakdown> getPendingBreakdowns() {
		return Collections.unmodifiableMap(PENDING_BREAKDOWNS);
	}

	static String getPendingStatePath() {
		return PENDING_STATE_PATH;
	}

	static void resetPending() {
		PENDING_BREAKDOWNS.clear();
		PENDING_STATE_PATH = null;
	}
}
