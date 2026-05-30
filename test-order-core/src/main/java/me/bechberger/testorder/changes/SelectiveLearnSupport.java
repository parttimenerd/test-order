package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Shared helper that computes the "uncertain" class set for selective learn
 * mode. Both the online (agent) path via {@code LearnWorkflow} and the offline
 * instrumentation path via {@code InstrumentMojo} / Gradle call this.
 *
 * <p>
 * The uncertain set is the transitive closure of classes that may be affected
 * by the current source changes, computed by:
 * <ol>
 * <li>Detecting structural changes ({@link StructuralChangeAnalyzer})</li>
 * <li>Expanding via the static call/field graph
 * ({@link StaticCallGraphAnalyzer#expand})</li>
 * </ol>
 *
 * <p>
 * If no changes are detected the set is empty — the caller should write an
 * empty file and the agent will instrument nothing (correct: nothing to learn).
 * If the project root or classes directory is unavailable, returns {@code null}
 * to signal "can't compute; fall back to full instrumentation".
 */
public final class SelectiveLearnSupport {

	/** BFS depth used when expanding the changed set through the call graph. */
	public static final int DEFAULT_EXPAND_DEPTH = 4;

	private SelectiveLearnSupport() {
	}

	/**
	 * Computes the set of FQCNs that must be instrumented for a selective learn
	 * run.
	 *
	 * @param projectRoot
	 *            root of the git repository (for structural diff)
	 * @param classesDir
	 *            compiled class output directory (for call-graph scan)
	 * @param mode
	 *            resolved change-detection mode; {@code SINCE_LAST_COMMIT} uses git
	 *            HEAD diff, all others use uncommitted changes. Use
	 *            {@link me.bechberger.testorder.changes.ChangeDetectionSupport#resolveMode}
	 *            to translate the user-facing {@code changeMode} string (including
	 *            {@code auto}) to a concrete mode before calling this method.
	 * @return set of FQCNs, or {@code null} when computation is not possible (no
	 *         project root / no class dir)
	 */
	public static Set<String> computeUncertainClasses(Path projectRoot, Path classesDir, ChangeDetector.Mode mode) {
		return computeUncertainClasses(projectRoot, classesDir, mode, DEFAULT_EXPAND_DEPTH);
	}

	/**
	 * Like {@link #computeUncertainClasses(Path, Path, ChangeDetector.Mode)} but
	 * with an explicit BFS depth limit.
	 */
	public static Set<String> computeUncertainClasses(Path projectRoot, Path classesDir, ChangeDetector.Mode mode,
			int expandDepth) {
		if (projectRoot == null || classesDir == null) {
			return null;
		}
		try {
			StructuralChangeAnalyzer.AnalysisResult analysis = (mode == ChangeDetector.Mode.SINCE_LAST_COMMIT)
					? StructuralChangeAnalyzer.analyzeSinceLastCommitFull(projectRoot)
					: StructuralChangeAnalyzer.analyzeUncommittedFull(projectRoot);
			StructuralChangeAnalyzer.ChangedMembers changed = analysis.changedMembers();
			if (changed.changedClasses().isEmpty()) {
				return Set.of();
			}
			StructuralChangeAnalyzer.ChangedMembers expanded = StaticCallGraphAnalyzer.expand(changed,
					List.of(classesDir), expandDepth);
			return expanded.changedClasses();
		} catch (IOException e) {
			return null;
		}
	}
}
