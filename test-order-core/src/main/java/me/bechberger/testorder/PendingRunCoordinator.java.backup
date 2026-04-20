package me.bechberger.testorder;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class PendingRunCoordinator {

    private record PendingRunData(Map<String, TestOrderState.ScoreBreakdown> breakdowns, String statePath) {
        private static final PendingRunData EMPTY = new PendingRunData(Map.of(), null);

        PendingRunData withBreakdown(String testClass, TestOrderState.ScoreBreakdown breakdown) {
            Map<String, TestOrderState.ScoreBreakdown> updated = new LinkedHashMap<>(breakdowns);
            updated.put(testClass, breakdown);
            return new PendingRunData(Collections.unmodifiableMap(updated), statePath);
        }

        PendingRunData withStatePath(String newStatePath) {
            return new PendingRunData(breakdowns, newStatePath);
        }
    }

    private static final AtomicReference<PendingRunData> PENDING_RUN_DATA =
            new AtomicReference<>(PendingRunData.EMPTY);

    private PendingRunCoordinator() {}

    static void recordBreakdown(String testClass, TestOrderState.ScoreBreakdown breakdown) {
        PENDING_RUN_DATA.updateAndGet(current -> current.withBreakdown(testClass, breakdown));
    }

    static void setStatePath(String path) {
        PENDING_RUN_DATA.updateAndGet(current -> current.withStatePath(path));
    }

    static boolean hasPendingData() {
        PendingRunData current = PENDING_RUN_DATA.get();
        return !current.breakdowns().isEmpty() && current.statePath() != null;
    }

    static Map<String, TestOrderState.ScoreBreakdown> getPendingBreakdowns() {
        return PENDING_RUN_DATA.get().breakdowns();
    }

    static String getPendingStatePath() {
        return PENDING_RUN_DATA.get().statePath();
    }

    static void resetPending() {
        PENDING_RUN_DATA.set(PendingRunData.EMPTY);
    }
}
