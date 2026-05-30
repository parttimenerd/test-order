package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared helper that computes the "uncertain" class set for selective learn
 * mode. Both the online (agent-based, via Maven/Gradle plugin learn mode) and
 * the offline instrumentation paths call this.
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
	 * Richer result from {@link #computeStaticAnalysisData} that carries both the
	 * uncertain class set (needed by the instrumentation runtime) and the full
	 * change picture (needed by the dashboard sidecar): per-class member changes,
	 * change kinds, type-level flags, static-field changes, and per-file summaries.
	 */
	public record StaticAnalysisData(
			/** FQCNs to instrument — feeds {@code OfflineInstrumentor} unchanged. */
			Set<String> uncertainClasses,
			/** FQCN → BFS hop depth from the nearest seed (0 = directly changed). */
			Map<String, Integer> classDepths,
			/** FQCN → nearest BFS-predecessor class (absent for seeds). */
			Map<String, String> classParents, boolean degraded, int seedSize, int expandedSize,
			/** Seed classes (depth 0). Subset of {@code uncertainClasses}. */
			Set<String> changedClasses,
			/** FQCN → set of changed member names within that class. */
			Map<String, Set<String>> membersByClass,
			/**
			 * {@code "fqcn#memberName"} → coarse change kind
			 * (BODY/SIGNATURE/ADDED/REMOVED).
			 */
			Map<String, StructuralChangeAnalyzer.ChangeKind> memberChangeKinds,
			/**
			 * FQCNs that have type-level changes (added/removed/signature-changed types).
			 */
			Set<String> classesWithTypeChanges,
			/** {@code "fqcn#fieldName"} keys for changed static fields. */
			Set<String> changedStaticFieldKeys, List<FileSummary> fileSummaries) {

		/** One entry per modified source file — kind counts and total changed lines. */
		public record FileSummary(String path, int added, int removed, int signature, int body, int totalLines) {
		}
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

	/**
	 * Like {@link #computeUncertainClasses} but returns a richer
	 * {@link StaticAnalysisData} that also carries hop-depth and parent-class
	 * information for the dashboard sidecar. Returns {@code null} when computation
	 * is not possible (null inputs, IOException), and a zero-class result when no
	 * structural changes are detected.
	 */
	public static StaticAnalysisData computeStaticAnalysisData(Path projectRoot, Path classesDir,
			ChangeDetector.Mode mode) {
		return computeStaticAnalysisData(projectRoot, classesDir, mode, DEFAULT_EXPAND_DEPTH);
	}

	/**
	 * Like {@link #computeStaticAnalysisData(Path, Path, ChangeDetector.Mode)} but
	 * with an explicit BFS depth limit.
	 */
	public static StaticAnalysisData computeStaticAnalysisData(Path projectRoot, Path classesDir,
			ChangeDetector.Mode mode, int expandDepth) {
		if (projectRoot == null || classesDir == null) {
			return null;
		}
		try {
			StructuralChangeAnalyzer.AnalysisResult analysis = (mode == ChangeDetector.Mode.SINCE_LAST_COMMIT)
					? StructuralChangeAnalyzer.analyzeSinceLastCommitFull(projectRoot)
					: StructuralChangeAnalyzer.analyzeUncommittedFull(projectRoot);
			StructuralChangeAnalyzer.ChangedMembers changed = analysis.changedMembers();
			List<StaticAnalysisData.FileSummary> fileSummaries = buildFileSummaries(analysis.diffs(), projectRoot);
			if (changed.changedClasses().isEmpty()) {
				return new StaticAnalysisData(Set.of(), Map.of(), Map.of(), false, 0, 0, Set.of(), Map.of(), Map.of(),
						Set.of(), Set.of(), fileSummaries);
			}
			StaticCallGraphAnalyzer.Report report = StaticCallGraphAnalyzer.expandWithReport(changed,
					List.of(classesDir), expandDepth, StaticCallGraphAnalyzer.DEFAULT_DEGRADATION_RATIO);
			return new StaticAnalysisData(report.expanded().changedClasses(), report.classDepths(),
					report.classParents(), report.degraded(), report.seedSize(), report.expandedSize(),
					changed.changedClasses(), changed.membersByClass(), changed.memberChangeKinds(),
					changed.classesWithTypeChanges(), changed.changedStaticFieldKeys(), fileSummaries);
		} catch (IOException e) {
			return null;
		}
	}

	private static List<StaticAnalysisData.FileSummary> buildFileSummaries(List<StructuralDiff.FileDiff> diffs,
			Path projectRoot) {
		if (diffs == null || diffs.isEmpty()) {
			return List.of();
		}
		List<StaticAnalysisData.FileSummary> out = new java.util.ArrayList<>(diffs.size());
		for (StructuralDiff.FileDiff fd : diffs) {
			int added = 0, removed = 0, signature = 0, body = 0;
			for (StructuralDiff.Change c : fd.changes()) {
				switch (c.kind()) {
					case ADDED -> added++;
					case REMOVED -> removed++;
					case SIGNATURE_CHANGED -> signature++;
					case MODIFIED -> body++;
				}
			}
			int totalLines = 0;
			for (StructuralDiff.BodyChange bc : fd.bodyChanges()) {
				totalLines += bc.changedLineCount();
			}
			Path file = fd.file();
			String rel;
			try {
				rel = projectRoot != null ? projectRoot.relativize(file).toString() : file.toString();
			} catch (IllegalArgumentException ex) {
				rel = file.toString();
			}
			out.add(new StaticAnalysisData.FileSummary(rel.replace('\\', '/'), added, removed, signature, body,
					totalLines));
		}
		return List.copyOf(out);
	}
}
