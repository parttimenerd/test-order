package me.bechberger.testorder;

import java.util.List;

import me.bechberger.testorder.TestOrderState.RunRecord;
import me.bechberger.testorder.annotations.ThreadSafe;

/**
 * Owns the capped run-history list, history-size configuration delegate, and
 * the {@code pendingRunCompleted} flag that tells the persistence layer whether
 * to apply a decay round at save time.
 *
 * <p>
 * Phase C3 of the facade refactor: pulls run-history fields and methods out of
 * {@link TestOrderState} so the outer class becomes a thinner facade. The
 * underlying {@link RunHistoryManager} stays unchanged. Persistence
 * orchestration of the {@code runs} key still lives on {@link TestOrderState}
 * until C4; this component exposes a package-private accessor
 * ({@link #manager()}) so the orchestrator can reach through.
 *
 * <p>
 * Thread-safety: {@link #addRunRecord(RunRecord)} and the access to
 * {@code pendingRunCompleted} are synchronised on this instance — preserves the
 * single-monitor semantics that {@link TestOrderState#addRunRecord} and the
 * persistence read in {@link TestOrderState#toPersistedRoot(boolean)}
 * previously held.
 */
@ThreadSafe
final class RunHistoryStorage {

	private final RunHistoryManager runHistory;
	private final StateConfiguration config;
	private boolean pendingRunCompleted;

	RunHistoryStorage(StateConfiguration config) {
		this.config = config;
		this.runHistory = new RunHistoryManager();
	}

	/** Package-private accessor for persistence orchestration. */
	RunHistoryManager manager() {
		return runHistory;
	}

	List<RunRecord> runs() {
		return runHistory.runs();
	}

	synchronized void addRunRecord(RunRecord record) {
		runHistory.add(record, config.historyMaxRuns());
		pendingRunCompleted = true;
	}

	int historyMaxRuns() {
		return config.historyMaxRuns();
	}

	synchronized void setHistoryMaxRuns(int maxRuns) {
		config.setHistoryMaxRuns(maxRuns);
		runHistory.trimToMax(config.historyMaxRuns());
	}

	synchronized boolean pendingRunCompleted() {
		return pendingRunCompleted;
	}

	synchronized void clearPendingRunCompleted() {
		pendingRunCompleted = false;
	}
}
