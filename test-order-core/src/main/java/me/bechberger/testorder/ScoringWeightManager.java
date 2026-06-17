package me.bechberger.testorder;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import me.bechberger.testorder.TestOrderState.MethodScoringWeights;
import me.bechberger.testorder.TestOrderState.OptimizeResult;
import me.bechberger.testorder.TestOrderState.RunRecord;
import me.bechberger.testorder.TestOrderState.ScoringWeights;
import me.bechberger.testorder.TestOrderState.WeightDef;
import me.bechberger.testorder.annotations.NotThreadSafe;

/**
 * Owns scoring weights, method-scoring weights, mutation kill rates, and
 * mutation totals — plus the genetic-algorithm optimizer entry points that
 * consume those weights.
 *
 * <p>
 * Phase C2 of the facade refactor: pulls weight-related fields and methods out
 * of {@link TestOrderState} so the outer class becomes a thinner facade. Inner
 * records ({@link ScoringWeights}, {@link MethodScoringWeights},
 * {@link WeightDef}, {@link OptimizeResult}) intentionally stay on
 * {@link TestOrderState} — they're public API referenced by external callers
 * (PriorityClassOrderer, AbstractTestOrderMojo, HelpMojo). Moving them would
 * force import changes, which is the precise breakage the facade pattern exists
 * to prevent.
 *
 * <p>
 * Persistence orchestration of the {@code weights}, {@code methodWeights},
 * {@code killRates}, {@code mutationTotalMutants}, {@code mutationTotalKilled}
 * keys still lives on {@link TestOrderState} until C4. This component exposes
 * the underlying state via package-private accessors so the orchestrator can
 * reach through.
 */
@NotThreadSafe
final class ScoringWeightManager {

	private ScoringWeights weights;
	private MethodScoringWeights methodScoringWeights;
	private Map<String, Double> killRates;
	private int mutationTotalMutants;
	private int mutationTotalKilled;

	ScoringWeightManager() {
		this.weights = ScoringWeights.DEFAULT;
		this.methodScoringWeights = MethodScoringWeights.DEFAULT;
		this.killRates = new HashMap<>();
		this.mutationTotalMutants = 0;
		this.mutationTotalKilled = 0;
	}

	// ── Class-level scoring weights ─────────────────────────────────────

	ScoringWeights weights() {
		return weights;
	}

	void setWeights(ScoringWeights w) {
		this.weights = w;
	}

	// ── Method-level scoring weights ────────────────────────────────────

	MethodScoringWeights methodScoringWeights() {
		return methodScoringWeights;
	}

	void setMethodScoringWeights(MethodScoringWeights w) {
		this.methodScoringWeights = w;
	}

	// ── Mutation kill rates ─────────────────────────────────────────────

	Map<String, Double> getKillRates() {
		return Collections.unmodifiableMap(killRates);
	}

	void setKillRates(Map<String, Double> rates) {
		this.killRates = new HashMap<>(rates);
	}

	/** Package-private accessor for persistence orchestration. */
	Map<String, Double> killRatesRaw() {
		return killRates;
	}

	/** Package-private setter for raw persistence load (preserves order). */
	void replaceKillRatesRaw(Map<String, Double> rates) {
		this.killRates = rates;
	}

	int getMutationTotalMutants() {
		return mutationTotalMutants;
	}

	int getMutationTotalKilled() {
		return mutationTotalKilled;
	}

	void setMutationTotals(int totalMutants, int totalKilled) {
		this.mutationTotalMutants = totalMutants;
		this.mutationTotalKilled = totalKilled;
	}

	// ── Optimizer ───────────────────────────────────────────────────────

	OptimizeResult optimize(List<RunRecord> runs, Map<String, Long> classDurations, List<WeightDef> defs, Logger log) {
		return ScoringOptimizer.optimize(runs, classDurations, defs, log);
	}
}
