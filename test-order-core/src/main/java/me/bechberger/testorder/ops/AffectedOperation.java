package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.bechberger.testorder.CoverageClosure;
import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestSelector;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;

/**
 * Framework-agnostic test selection orchestration. Runs TestSelector and writes
 * the selected/remaining test lists to disk.
 */
public final class AffectedOperation {

	private AffectedOperation() {
	}

	/** Input configuration for test selection. */
	public record SelectConfig(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
			Set<String> changedTests, TestOrderState.ScoringWeights weights, int topN, int randomM, Long seed,
			Set<String> alwaysRunClasses, Path selectedFile, Path remainingFile, PluginLog log,
			Map<String, Double> changeComplexity, TestSelector.CacheConfig cacheConfig, ChangedMembers changedMembers,
			boolean coverageEnforce) {
		public SelectConfig(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
				Set<String> changedTests, TestOrderState.ScoringWeights weights, int topN, int randomM, Long seed,
				Set<String> alwaysRunClasses, Path selectedFile, Path remainingFile, PluginLog log,
				Map<String, Double> changeComplexity, TestSelector.CacheConfig cacheConfig) {
			this(depMap, state, changedClasses, changedTests, weights, topN, randomM, seed, alwaysRunClasses,
					selectedFile, remainingFile, log, changeComplexity, cacheConfig, null, false);
		}

		public SelectConfig(DependencyMap depMap, TestOrderState state, Set<String> changedClasses,
				Set<String> changedTests, TestOrderState.ScoringWeights weights, int topN, int randomM, Long seed,
				Set<String> alwaysRunClasses, Path selectedFile, Path remainingFile, PluginLog log,
				Map<String, Double> changeComplexity) {
			this(depMap, state, changedClasses, changedTests, weights, topN, randomM, seed, alwaysRunClasses,
					selectedFile, remainingFile, log, changeComplexity, TestSelector.CacheConfig.DISABLED);
		}
	}

	/** Result of test selection. */
	public record SelectResult(TestSelector.Selection selection, boolean allSelected, SelectionSummary summary,
			CoverageClosure.Status coverageStatus) {

		/** Backward-compatible constructor without coverage status. */
		public SelectResult(TestSelector.Selection selection, boolean allSelected, SelectionSummary summary) {
			this(selection, allSelected, summary, selection.coverageStatus());
		}

		/**
		 * Backward-compatible factory without summary (for synthesized full-run
		 * results).
		 */
		public static SelectResult of(TestSelector.Selection selection, boolean allSelected) {
			return new SelectResult(selection, allSelected, SelectionSummary.from(selection, 0, 0, 0, 0));
		}
	}

	/**
	 * Counts and the top-scored test name from a selection. Both Maven and Gradle
	 * plugins log {@link #format()} so the end-of-run summary is consistent.
	 */
	public record SelectionSummary(int selectedCount, int deferredCount, String topScorerName, int scoredCount,
			int newCount, int alwaysRunCount, int fastCount, int cachedCount, int elementsTotal, int elementsCovered,
			int elementsUncovered) {

		/** Backward-compatible constructor without coverage-closure counts. */
		public SelectionSummary(int selectedCount, int deferredCount, String topScorerName, int scoredCount,
				int newCount, int alwaysRunCount, int fastCount, int cachedCount) {
			this(selectedCount, deferredCount, topScorerName, scoredCount, newCount, alwaysRunCount, fastCount,
					cachedCount, 0, 0, 0);
		}

		/**
		 * Total tests in the index for this selection (selected + deferred + cached).
		 */
		public int totalCount() {
			return selectedCount + deferredCount + cachedCount;
		}

		/** Percentage of tests selected (0 when total is 0). */
		public int percentSelected() {
			int total = totalCount();
			return total == 0 ? 0 : (100 * selectedCount) / total;
		}

		static SelectionSummary from(TestSelector.Selection selection, int scoredCount, int newCount,
				int alwaysRunCount, int fastCount) {
			return from(selection, scoredCount, newCount, alwaysRunCount, fastCount, selection.coverageStatus());
		}

		static SelectionSummary from(TestSelector.Selection selection, int scoredCount, int newCount,
				int alwaysRunCount, int fastCount, CoverageClosure.Status coverageStatus) {
			String top = selection.selected().isEmpty() ? null : selection.selected().get(0);
			int total = coverageStatus == null ? 0 : coverageStatus.totalElements();
			int covered = coverageStatus == null ? 0 : coverageStatus.coveredCount();
			int uncovered = coverageStatus == null ? 0 : coverageStatus.uncoveredElements().size();
			return new SelectionSummary(selection.selected().size(), selection.remaining().size(), top, scoredCount,
					newCount, alwaysRunCount, fastCount, selection.cached().size(), total, covered, uncovered);
		}

		/**
		 * Multi-line summary block prefixed with "[test-order] ". Defaults to Maven
		 * format. Gradle callers should use {@link #format(PluginContext.BuildSystem)}
		 * instead.
		 */
		public String format() {
			return format(PluginContext.BuildSystem.MAVEN);
		}

		/** Multi-line summary block with build-system-appropriate commands. */
		public String format(PluginContext.BuildSystem buildSystem) {
			StringBuilder sb = new StringBuilder();
			int total = totalCount();
			sb.append("[test-order] Selected ").append(selectedCount).append(" / ").append(total).append(" tests");
			if (total > 0)
				sb.append(" (").append(percentSelected()).append("%)");
			if (topScorerName != null)
				sb.append("\n[test-order] Top: ").append(topScorerName);
			if (deferredCount > 0)
				sb.append("\n[test-order] ").append(deferredCount).append(" deferred — see remaining-file");
			if (cachedCount > 0)
				sb.append("\n[test-order] ").append(cachedCount).append(" cached (skipped — unchanged + pass streak)");
			if (elementsTotal > 0) {
				sb.append("\n[test-order] Changed elements covered: ").append(elementsCovered).append("/")
						.append(elementsTotal);
				if (elementsUncovered > 0)
					sb.append(" (+").append(elementsUncovered).append(" uncovered)");
			}
			sb.append("\n[test-order] Run `").append(buildSystem.showCommand()).append("` for the full ranking, or `")
					.append(buildSystem.dashboardCommand()).append("` for HTML.");
			return sb.toString();
		}
	}

	/**
	 * Runs test selection and writes lists to disk.
	 *
	 * @throws IOException
	 *             if writing test lists fails
	 */
	public static SelectResult select(SelectConfig config) throws IOException {
		// Warn when topN=-1 (all tests) is combined with a non-default randomM, since
		// randomM has no effect in that case. Skip when randomM is also the default
		// (10)
		// to avoid noise on every run.
		if (config.topN() == -1 && config.randomM() > 0 && config.randomM() != 10) {
			config.log().warn("[test-order] randomM=" + config.randomM()
					+ " has no effect when topN=-1 (all tests selected). Set topN to a positive number to use random sampling.");
		}
		// When no explicit seed is provided, derive one from the changed-class set so
		// selection is deterministic for the same inputs. Users who need to pin
		// reproducibility across machines can still set testorder.affected.seed
		// explicitly.
		Long effectiveSeed = config.seed();
		if (effectiveSeed == null && config.topN() > 0 && config.randomM() > 0) {
			int totalTests = config.depMap().testClasses().size();
			if (config.topN() < totalTests) {
				effectiveSeed = (long) config.changedClasses().stream().sorted()
						.collect(java.util.stream.Collectors.joining(",")).hashCode();
			}
		}

		// One per-selection overlap-join memo shared by the coverage closure and
		// TestScorer: without it both pipelines run the identical per-test
		// StructuralChangeAnalyzer.computeOverlapClasses join (~2x per affected
		// test). Thread-confined and cleared in finally — it never outlives this
		// select() call, and consumers on other threads / show-diagnostic paths see
		// no index and keep their direct computation.
		CoverageClosure.OverlapIndex overlapIndex = CoverageClosure.OverlapIndex.forSelection(config.depMap(),
				config.changedClasses(), config.changedTests(), config.changedMembers());
		CoverageClosure.OverlapIndex.install(overlapIndex);
		try {
			CoverageClosure closure = CoverageClosure.compute(config.depMap(), config.changedClasses(),
					config.changedTests(), config.changedMembers(), overlapIndex);
			TestSelector.Selection selection = new TestSelector(config.depMap(), config.state(),
					config.changedClasses(), config.changedTests(), config.weights(),
					new TestSelector.Config(config.topN(), config.randomM(), effectiveSeed), config.alwaysRunClasses(),
					config.changeComplexity(),
					config.cacheConfig() != null ? config.cacheConfig() : TestSelector.CacheConfig.DISABLED, closure)
					.select();
			CoverageClosure.Status coverageStatus = selection.coverageStatus();
			if (closure.memberPrecisionUnavailable()) {
				config.log()
						.info("[test-order] Coverage closure runs at class-level precision: the dependency index has "
								+ "no MEMBER instrumentation data. Re-run a learn phase to capture method-level dependencies "
								+ "for method-level precision.");
			}
			// Coverage-closure outcome: WARN on uncovered changed elements; with
			// coverageEnforce the build fails with an actionable message instead.
			// Full file contract: the throw happens BEFORE the fresh selected/remaining
			// lists of THIS run are written, and any stale selected/remaining files from
			// a PREVIOUS run are deleted first — so a failed-closure run leaves neither
			// a fresh nor a stale list that a file-existence-keyed CI step could consume.
			if (coverageStatus != null && coverageStatus.totalElements() > 0 && !coverageStatus.allCovered()) {
				List<String> uncovered = coverageStatus.uncoveredElements();
				String sample = uncovered.stream().limit(3).collect(java.util.stream.Collectors.joining(", "));
				String fullList = String.join(", ", uncovered);
				String summaryLine = coverageStatus.coveredCount() + "/" + coverageStatus.totalElements()
						+ " changed elements are exercised by the selected test subset; " + uncovered.size()
						+ " uncovered";
				if (config.coverageEnforce()) {
					deleteSelectionArtifacts(config);
					throw new IllegalStateException("[test-order] Coverage closure failed: " + summaryLine
							+ ". Uncovered: " + fullList + ". No selected test exercises these changed elements — the "
							+ "dependency index may be stale (tests added/changed since the last learn phase). Run a "
							+ "learn phase to rebuild the index, run the full test suite, or disable enforcement "
							+ "(-Dtestorder.coverage.enforce=false).");
				}
				config.log()
						.warn("[test-order] Coverage closure: " + summaryLine + " (e.g. " + sample + "). Uncovered: "
								+ fullList + ". Consider a learn phase ("
								+ "mvn test -Dtestorder.mode=learn) or running the full suite.");
			}

			// Warn when changed classes appear in deps of >50% of all tests: the scorer
			// can't discriminate and the selection is essentially a random subset.
			// Collapse into one summary when many classes exceed the threshold.
			if (!config.changedClasses().isEmpty()) {
				int totalTests = config.depMap().testClasses().size();
				if (totalTests > 0) {
					List<String> weakSignalClasses = new ArrayList<>();
					for (String changed : config.changedClasses()) {
						long matchCount = config.depMap().getAffectedTests(Set.of(changed)).size();
						if (matchCount > totalTests / 2) {
							weakSignalClasses.add(changed + " (" + (100 * matchCount / totalTests) + "%)");
						}
					}
					if (!weakSignalClasses.isEmpty()) {
						if (weakSignalClasses.size() == 1) {
							config.log().warn("[test-order] Changed class " + weakSignalClasses.get(0)
									+ " appears in deps of >50% of tests. Selection signal is weak — results may be"
									+ " near-random. Consider running the full suite.");
						} else {
							String sample = weakSignalClasses.stream().limit(3)
									.collect(java.util.stream.Collectors.joining(", "));
							config.log()
									.warn("[test-order] " + weakSignalClasses.size()
											+ " changed classes appear in deps of >50% of tests (e.g. " + sample
											+ "). Selection signal is weak — consider running the full suite.");
						}
					}
				}
			}

			if (config.selectedFile() != null) {
				TestSelector.writeTestList(selection.selected(), config.selectedFile());
			}
			if (config.remainingFile() != null) {
				if (!selection.remaining().isEmpty()) {
					TestSelector.writeTestList(selection.remaining(), config.remainingFile());
				} else {
					// Delete stale remaining file from a previous run to prevent run-remaining
					// from re-executing deferred tests that are no longer relevant.
					try {
						java.nio.file.Files.deleteIfExists(config.remainingFile());
					} catch (IOException e) {
						config.log().warn("[test-order] Could not delete stale remaining file " + config.remainingFile()
								+ ": " + e.getMessage());
					}
				}
			}

			boolean allSelected = selection.remaining().isEmpty() && selection.cached().isEmpty();
			int alwaysRunCount = 0;
			int newCount = 0;
			int fastCount = selection.randomFastCount();
			int scoredCount = 0;
			if (!selection.selected().isEmpty() || !selection.remaining().isEmpty()) {
				alwaysRunCount = (int) selection.selected().stream().filter(t -> config.alwaysRunClasses().contains(t))
						.count();
				newCount = (int) selection.selected().stream().filter(
						t -> !config.depMap().testClasses().contains(t) && !config.alwaysRunClasses().contains(t))
						.count();
				scoredCount = Math.max(0, selection.selected().size() - alwaysRunCount - newCount - fastCount);
			}
			if (selection.selected().isEmpty() && selection.remaining().isEmpty()) {
				// no tests in depMap — nothing to report
			} else if (allSelected) {
				config.log().info("[test-order] Selected all " + selection.selected().size()
						+ " affected tests (topN=-1, running in priority order)");
			} else {
				StringBuilder bd = new StringBuilder();
				if (scoredCount > 0)
					bd.append(scoredCount).append(" scored");
				if (newCount > 0) {
					if (bd.length() > 0)
						bd.append(" + ");
					bd.append(newCount).append(" new");
				}
				if (alwaysRunCount > 0) {
					if (bd.length() > 0)
						bd.append(" + ");
					bd.append(alwaysRunCount).append(" always-run");
				}
				if (fastCount > 0) {
					if (bd.length() > 0)
						bd.append(" + ");
					bd.append(fastCount).append(" fast-diverse");
				}
				config.log().info("[test-order] Selected " + selection.selected().size() + " tests (" + bd
						+ "), deferred " + selection.remaining().size());
			}

			SelectionSummary summary = SelectionSummary.from(selection, scoredCount, newCount, alwaysRunCount,
					fastCount, coverageStatus);
			return new SelectResult(selection, allSelected, summary, coverageStatus);
		} finally {
			CoverageClosure.OverlapIndex.clear();
		}
	}

	/**
	 * Deletes the selected and remaining list files (from this or a previous run)
	 * so a failed run — e.g. a coverage-enforce failure thrown before the fresh
	 * lists are written — cannot leave file-existence-keyed CI consumers reading
	 * outdated selections. Failures to delete are logged, not thrown: the enforce
	 * failure itself must still surface.
	 */
	private static void deleteSelectionArtifacts(SelectConfig config) {
		deleteQuietly(config.selectedFile(), config);
		deleteQuietly(config.remainingFile(), config);
	}

	private static void deleteQuietly(Path file, SelectConfig config) {
		if (file == null) {
			return;
		}
		try {
			java.nio.file.Files.deleteIfExists(file);
		} catch (IOException e) {
			config.log().warn("[test-order] Could not delete stale selection file " + file + ": " + e.getMessage());
		}
	}
}
