package me.bechberger.testorder;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class PendingRunCoordinator {

	private static final Object LOCK = new Object();
	private static final ConcurrentHashMap<String, TestOrderState.ScoreBreakdown> PENDING_BREAKDOWNS = new ConcurrentHashMap<>();
	private static volatile String PENDING_STATE_PATH = null;

	private PendingRunCoordinator() {
	}

	static void recordBreakdown(String testClass, TestOrderState.ScoreBreakdown breakdown) {
		synchronized (LOCK) {
			PENDING_BREAKDOWNS.put(testClass, breakdown);
		}
	}

	static void setStatePath(String path) {
		synchronized (LOCK) {
			PENDING_STATE_PATH = path;
		}
	}

	static boolean hasPendingData() {
		synchronized (LOCK) {
			// Both conditions must be stable through the read to avoid race where
			// one is true, then both become false before caller reads them.
			return !PENDING_BREAKDOWNS.isEmpty() && PENDING_STATE_PATH != null;
		}
	}

	static Map<String, TestOrderState.ScoreBreakdown> getPendingBreakdowns() {
		synchronized (LOCK) {
			return Map.copyOf(PENDING_BREAKDOWNS);
		}
	}

	static String getPendingStatePath() {
		synchronized (LOCK) {
			return PENDING_STATE_PATH;
		}
	}

	static void resetPending() {
		synchronized (LOCK) {
			PENDING_BREAKDOWNS.clear();
			PENDING_STATE_PATH = null;
		}
	}
}
