package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import me.bechberger.testorder.CoverageClosure;
import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TieredTestSelector;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;

/**
 * Framework-agnostic tiered test selection orchestration. Runs
 * {@link TieredTestSelector} and writes the tier files to disk.
 *
 * <p>
 * Three-tiered CI execution:
 * <ol>
 * <li>Tier 1: change-affected tests (dep overlap, new,
 * changed, @AlwaysRun)</li>
 * <li>Tier 2: top-scored remaining tests (configurable fraction by
 * duration)</li>
 * <li>Tier 3: everything else</li>
 * </ol>
 */
public final class TieredSelectOperation {

	private TieredSelectOperation() {
	}

	/**
	 * Input configuration for tiered test selection.
	 *
	 * @param staticAnalysisDegraded
	 *            {@code true} when the caller's change analysis reported a degraded
	 *            static-analysis universe; {@code select} then synthesizes a
	 *            module-wide select-all (everything in tier 1, tiers 2/3 empty) and
	 *            never applies {@code coverageEnforce} — parity with the
	 *            affected/auto workflows' fail-safe select-all
	 */
	public record TieredSelectConfig(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTests, TestOrderState.ScoringWeights weights, double tier2Fraction,
			boolean weightByDuration, Set<String> alwaysRunClasses, Path tier1File, Path tier2File, Path tier3File,
			PluginLog log, ChangedMembers changedMembers, boolean coverageEnforce, boolean staticAnalysisDegraded) {

		/** Backward-compatible constructor without coverage-closure wiring. */
		public TieredSelectConfig(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
				Set<String> changedTests, TestOrderState.ScoringWeights weights, double tier2Fraction,
				boolean weightByDuration, Set<String> alwaysRunClasses, Path tier1File, Path tier2File, Path tier3File,
				PluginLog log) {
			this(depMap, state, changedClasses, changedTests, weights, tier2Fraction, weightByDuration,
					alwaysRunClasses, tier1File, tier2File, tier3File, log, null, false, false);
		}

		/** Backward-compatible constructor without the degradation flag. */
		public TieredSelectConfig(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
				Set<String> changedTests, TestOrderState.ScoringWeights weights, double tier2Fraction,
				boolean weightByDuration, Set<String> alwaysRunClasses, Path tier1File, Path tier2File, Path tier3File,
				PluginLog log, ChangedMembers changedMembers, boolean coverageEnforce) {
			this(depMap, state, changedClasses, changedTests, weights, tier2Fraction, weightByDuration,
					alwaysRunClasses, tier1File, tier2File, tier3File, log, changedMembers, coverageEnforce, false);
		}
	}

	/** Result of tiered test selection. */
	public record TieredSelectResult(TieredTestSelector.TieredSelection selection) {

		public boolean tier1Only() {
			return selection.tier2().isEmpty() && selection.tier3().isEmpty();
		}

		public int totalTests() {
			return selection.tier1().size() + selection.tier2().size() + selection.tier3().size();
		}
	}

	/**
	 * Runs tiered test selection and writes tier files to disk.
	 *
	 * <p>
	 * Coverage closure: the changed-element universe (changed classes ∪ changed
	 * members, member-level via {@link ChangedMembers} when available) must be
	 * exercised by the tier-1 set — tier 1 is the gate for tiers 2/3, so an
	 * uncovered changed element must be surfaced here. Uncovered elements are
	 * WARNed; with {@code coverageEnforce} the selection fails before the tier
	 * files are written (a failed-closure run never leaves tier files a subsequent
	 * CI step would consume).
	 *
	 * @throws IOException
	 *             if writing tier files fails
	 */
	public static TieredSelectResult select(TieredSelectConfig config) throws IOException {
		// Shared per-selection overlap-join memo (see AffectedOperation.select):
		// the closure and TieredTestSelector's TestScorer otherwise run the
		// identical per-test join twice. Thread-confined, cleared in finally.
		CoverageClosure.OverlapIndex overlapIndex = CoverageClosure.OverlapIndex.forSelection(config.depMap(),
				config.changedClasses(), config.changedTests(), config.changedMembers());
		CoverageClosure.OverlapIndex.install(overlapIndex);
		try {
			return selectWithSharedOverlaps(config, overlapIndex);
		} finally {
			CoverageClosure.OverlapIndex.clear();
		}
	}

	private static TieredSelectResult selectWithSharedOverlaps(TieredSelectConfig config,
			CoverageClosure.OverlapIndex overlapIndex) throws IOException {
		if (config.staticAnalysisDegraded()) {
			return degradedSelectAll(config, overlapIndex);
		}
		CoverageClosure closure = CoverageClosure.compute(config.depMap(), config.changedClasses(),
				config.changedTests(), config.changedMembers(), overlapIndex);
		TieredTestSelector.TieredSelection selection = new TieredTestSelector(config.depMap(), config.state(),
				config.changedClasses(), config.changedTests(), config.weights(),
				new TieredTestSelector.Config(config.tier2Fraction(), config.weightByDuration()),
				config.alwaysRunClasses(), closure).select();

		// ── Tier-1 closure outcome: WARN on uncovered changed elements; with
		// coverageEnforce the selection fails with an actionable message instead.
		CoverageClosure.Status status = selection.coverageStatus();
		String coverageSummary = "";
		if (status != null && status.totalElements() > 0) {
			coverageSummary = "; changed elements covered by tier 1: " + status.coveredCount() + "/"
					+ status.totalElements()
					+ (status.uncoveredElements().isEmpty()
							? ""
							: " (+" + status.uncoveredElements().size() + " uncovered)");
			if (!status.allCovered()) {
				List<String> uncovered = status.uncoveredElements();
				String fullList = String.join(", ", uncovered);
				String summaryLine = status.coveredCount() + "/" + status.totalElements()
						+ " changed elements are exercised by the tier-1 subset; " + uncovered.size() + " uncovered";
				if (config.coverageEnforce()) {
					throw new IllegalStateException("[test-order] Tier-1 coverage closure failed: " + summaryLine
							+ ". Uncovered: " + fullList + ". No tier-1 test exercises these changed elements — the "
							+ "dependency index may be stale (tests added/changed since the last learn phase). Run a "
							+ "learn phase to rebuild the index, plan to run tier 2/3 regardless of tier-1 results, "
							+ "or disable enforcement (-Dtestorder.coverage.enforce=false).");
				}
				config.log()
						.warn("[test-order] Tier-1 coverage closure: " + summaryLine + ". Uncovered: " + fullList
								+ ". Tier 1 is the gate for tiers 2/3 — consider a learn phase or running tier 2/3 "
								+ "regardless of tier-1 results.");
			}
		}

		TieredTestSelector.writeTierFiles(selection, config.tier1File(), config.tier2File(), config.tier3File());

		config.log()
				.info("[test-order] Tiered selection: " + selection.tier1().size() + " tier-1 (change-affected), "
						+ selection.tier2().size() + " tier-2 (top-scored), " + selection.tier3().size()
						+ " tier-3 (rest)" + coverageSummary);

		return new TieredSelectResult(selection);
	}

	/**
	 * Fail-safe select-all for a degraded static-analysis universe: ALL tests of
	 * the module run in tier 1 (tiers 2/3 empty), the coverage status is computed
	 * over the full set so the summary line stays visible, and
	 * {@code coverageEnforce} is deliberately NOT applied — an unreliable universe
	 * must fail safe (select-all), not fail closed. Same synthesis as
	 * {@code AffectedWorkflow#fullSelection}.
	 */
	private static TieredSelectResult degradedSelectAll(TieredSelectConfig config,
			CoverageClosure.OverlapIndex overlapIndex) throws IOException {
		java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(config.depMap().testClasses());
		all.addAll(config.changedTests());
		if (config.coverageEnforce()) {
			config.log().info("[test-order] testorder.coverage.enforce requested but the static analysis is degraded — "
					+ "enforcement is skipped (fail-safe select-all applies instead).");
		}
		config.log()
				.warn("[test-order] Static analysis degraded — the changed-element universe is unreliable "
						+ "(class-level fallback). Selecting ALL " + all.size()
						+ " tests of this module in tier 1 (fail-safe select-all; tiers 2/3 are empty).");
		CoverageClosure closure = CoverageClosure.compute(config.depMap(), config.changedClasses(),
				config.changedTests(), config.changedMembers(), overlapIndex);
		CoverageClosure.Status status = closure.status(all);
		warnUncoveredElements(config.log(), status);
		TieredTestSelector.TieredSelection selection = new TieredTestSelector.TieredSelection(
				new java.util.ArrayList<>(all), java.util.List.of(), java.util.List.of(), status);
		TieredTestSelector.writeTierFiles(selection, config.tier1File(), config.tier2File(), config.tier3File());
		config.log().info("[test-order] Tiered selection (degraded select-all): " + all.size()
				+ " tier-1 (all module tests), 0 tier-2, 0 tier-3");
		return new TieredSelectResult(selection);
	}

	/**
	 * Same uncovered-elements WARN as the normal tier-1 closure check (and
	 * {@code AffectedOperation}): even a select-all set can leave changed elements
	 * unexercised when the dependency index is stale.
	 */
	static void warnUncoveredElements(PluginLog log, CoverageClosure.Status status) {
		if (status == null || status.totalElements() <= 0 || status.allCovered()) {
			return;
		}
		List<String> uncovered = status.uncoveredElements();
		String sample = uncovered.stream().limit(3).collect(java.util.stream.Collectors.joining(", "));
		String fullList = String.join(", ", uncovered);
		log.warn("[test-order] Coverage closure: " + status.coveredCount() + "/" + status.totalElements()
				+ " changed elements are exercised by the selected test subset; " + uncovered.size() + " uncovered"
				+ " (e.g. " + sample + "). Uncovered: " + fullList
				+ ". Consider a learn phase (mvn test -Dtestorder.mode=learn) or running the full suite.");
	}
}
