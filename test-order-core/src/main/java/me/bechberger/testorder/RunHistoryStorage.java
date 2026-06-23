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
 * Thread-safety: all mutating and reading operations are synchronised on this
 * instance so that an iteration of the run list cannot race a concurrent
 * {@link #addRunRecord(RunRecord)} or {@link #setHistoryMaxRuns(int)} call.
 * {@link #runs()} returns a snapshot (defensive copy) rather than a live view,
 * so callers may iterate it outside the monitor.
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

	synchronized List<RunRecord> runs() {
		return List.copyOf(runHistory.runs());
	}

	synchronized void addRunRecord(RunRecord record) {
		runHistory.add(record, config.historyMaxRuns());
		pendingRunCompleted = true;
	}

	/** Replaces the entire run list atomically under the monitor. */
	synchronized void replaceRuns(List<RunRecord> updatedRuns) {
		runHistory.replace(updatedRuns);
	}

	/** Appends a raw record without capping (used during deserialization). */
	synchronized void addRaw(RunRecord record) {
		runHistory.addRaw(record);
	}

	/** Trims history to the configured maximum, under the monitor. */
	synchronized void trimToMax(int maxRuns) {
		runHistory.trimToMax(maxRuns);
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
