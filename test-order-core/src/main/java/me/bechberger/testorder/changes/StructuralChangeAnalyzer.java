package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Bridges {@link StructuralDiff} output with dependency maps to compute
 * fine-grained test impact scores.
 * <p>
 * Given structural changes (which methods/fields/types changed) and the
 * dependency index (which tests use which members), determines which tests are
 * actually affected. If a test never calls a method that changed, and nothing
 * else in that class changed, the test is probably not affected — even if it
 * depends on the class.
 */
public class StructuralChangeAnalyzer {

	private static final Pattern STATIC_KEYWORD_PATTERN = Pattern.compile("\\bstatic\\b", Pattern.DOTALL);

	/**
	 * @param changedClasses
	 *            all FQCNs that have any structural change
	 * @param changedMemberKeys
	 *            all changed members as {@code "fqcn#memberName"} keys
	 * @param membersByClass
	 *            fqcn → set of member names that changed in that class
	 * @param classesWithTypeChanges
	 *            FQCNs that have type-level changes
	 *            (added/removed/signature-changed types)
	 * @param changedStaticFieldKeys
	 *            changed static fields as {@code "fqcn#fieldName"} keys
	 */
	public record ChangedMembers(Set<String> changedClasses, Set<String> changedMemberKeys,
			Map<String, Set<String>> membersByClass, Set<String> classesWithTypeChanges,
			Set<String> changedStaticFieldKeys) {
		public ChangedMembers(Set<String> changedClasses, Set<String> changedMemberKeys,
				Map<String, Set<String>> membersByClass, Set<String> classesWithTypeChanges) {
			this(changedClasses, changedMemberKeys, membersByClass, classesWithTypeChanges, Set.of());
		}

		public static final ChangedMembers EMPTY = new ChangedMembers(Set.of(), Set.of(), Map.of(), Set.of(), Set.of());
	}

	/**
	 * Extracts changed members from a list of file diffs produced by
	 * {@link StructuralDiff}.
	 */
	public static ChangedMembers fromDiffs(List<StructuralDiff.FileDiff> diffs) {
		if (diffs.isEmpty()) {
			return ChangedMembers.EMPTY;
		}
		Set<String> classes = new LinkedHashSet<>();
		Set<String> memberKeys = new LinkedHashSet<>();
		Map<String, Set<String>> byClass = new LinkedHashMap<>();
		Set<String> typeChanged = new LinkedHashSet<>();
		Set<String> staticFields = new LinkedHashSet<>();

		for (StructuralDiff.FileDiff diff : diffs) {
			for (StructuralDiff.Change change : diff.changes()) {
				classes.add(change.fqcn());

				if (change.category() == StructuralDiff.Change.Category.TYPE) {
					typeChanged.add(change.fqcn());
				}

				String memberName = resolveMemberName(change);
				if (memberName != null) {
					String memberKey = change.fqcn() + "#" + memberName;
					memberKeys.add(memberKey);
					byClass.computeIfAbsent(change.fqcn(), k -> new LinkedHashSet<>()).add(memberName);

					if (change.category() == StructuralDiff.Change.Category.FIELD && isStaticFieldChange(change)) {
						staticFields.add(memberKey);
					}
				}
			}
		}
		return new ChangedMembers(Collections.unmodifiableSet(classes), Collections.unmodifiableSet(memberKeys),
				Collections.unmodifiableMap(byClass), Collections.unmodifiableSet(typeChanged),
				Collections.unmodifiableSet(staticFields));
	}

	private static boolean isStaticFieldChange(StructuralDiff.Change change) {
		String detail = change.detail();
		if (detail == null || detail.isBlank()) {
			return false;
		}
		// FIELD change details include stripped declarations (or "was/now" pairs for
		// modified fields).
		// Treat as static if either side contains the static modifier.
		return STATIC_KEYWORD_PATTERN.matcher(detail).find();
	}

	/**
	 * Maps a structural change to the member name used by the agent's recording
	 * format. Returns null for TYPE-level changes (those are handled at the class
	 * level).
	 */
	static String resolveMemberName(StructuralDiff.Change change) {
		return switch (change.category()) {
			case METHOD -> {
				// Constructors are recorded as <init> by the agent
				if (change.detail() != null && change.detail().startsWith("constructor")) {
					yield "<init>";
				}
				yield change.name();
			}
			case FIELD -> change.name();
			case INITIALIZER -> {
				// StructuralDiff now reports "<clinit>" for static and "<init>" for instance
				yield change.name();
			}
			case TYPE -> null;
		};
	}

	/** Analyze uncommitted changes in a git repository. */
	public static ChangedMembers analyzeUncommitted(Path projectRoot) throws IOException {
		return fromDiffs(StructuralDiff.diffUncommitted(projectRoot));
	}

	/** Analyze changes since the last commit (HEAD~1 + uncommitted). */
	public static ChangedMembers analyzeSinceLastCommit(Path projectRoot) throws IOException {
		return fromDiffs(StructuralDiff.diffSinceLastCommit(projectRoot));
	}

	/**
	 * Bundles both the structural {@link ChangedMembers} summary and the raw diffs.
	 */
	public record AnalysisResult(ChangedMembers changedMembers, List<StructuralDiff.FileDiff> diffs) {
	}

	/** Like {@link #analyzeUncommitted} but also returns the raw diffs. */
	public static AnalysisResult analyzeUncommittedFull(Path projectRoot) throws IOException {
		List<StructuralDiff.FileDiff> diffs = StructuralDiff.diffUncommitted(projectRoot);
		return new AnalysisResult(fromDiffs(diffs), diffs);
	}

	/** Like {@link #analyzeSinceLastCommit} but also returns the raw diffs. */
	public static AnalysisResult analyzeSinceLastCommitFull(Path projectRoot) throws IOException {
		List<StructuralDiff.FileDiff> diffs = StructuralDiff.diffSinceLastCommit(projectRoot);
		return new AnalysisResult(fromDiffs(diffs), diffs);
	}

	/**
	 * Computes the dependency overlap count for a test, using member-level deps
	 * when available for higher precision.
	 * <p>
	 * <b>With member-level deps</b> (MEMBER mode): a class counts as overlapping
	 * only if one of the following holds:
	 * <ul>
	 * <li>The test uses at least one member that actually changed.</li>
	 * <li>A static initializer ({@code <clinit>}) changed — this affects ALL users
	 * of the class since class loading is implicit.</li>
	 * <li>A type-level change occurred (added/removed/signature-changed type).</li>
	 * </ul>
	 * <p>
	 * <b>Without member-level deps</b>: falls back to class-level overlap, but uses
	 * structural analysis to filter out classes with only comment/whitespace
	 * changes.
	 *
	 * @param testClassDeps
	 *            class-level deps for the test (always available)
	 * @param testMemberDeps
	 *            member-level deps ("fqcn#member"), null/empty if unavailable
	 * @param changedMembers
	 *            structural change analysis result (may be null)
	 * @param changedClasses
	 *            class-level changed set from git (may include non-structural
	 *            changes)
	 * @return overlap count (number of dependency classes with matching changes)
	 */
	public static int computeOverlap(Set<String> testClassDeps, Set<String> testMemberDeps,
			ChangedMembers changedMembers, Set<String> changedClasses) {
		return computeOverlapClasses(testClassDeps, testMemberDeps, changedMembers, changedClasses).size();
	}

	/**
	 * Like {@link #computeOverlap} but returns the set of overlapping dependency
	 * classes rather than just the count. Used by
	 * {@link me.bechberger.testorder.TestScorer} for both dependency-overlap and
	 * complexity scoring so both use the same structural precision.
	 */
	public static Set<String> computeOverlapClasses(Set<String> testClassDeps, Set<String> testMemberDeps,
			ChangedMembers changedMembers, Set<String> changedClasses) {
		if (changedMembers == null || changedMembers.changedClasses().isEmpty()) {
			return classLevelOverlapClasses(testClassDeps, changedClasses);
		}

		if (testMemberDeps != null && !testMemberDeps.isEmpty()) {
			return memberLevelOverlapClasses(testClassDeps, testMemberDeps, changedMembers);
		}

		// No member deps: use the union of structurally-changed classes and
		// git-detected changed classes. Using only changedMembers.changedClasses()
		// would drop classes that have non-structural changes (e.g. comment edits)
		// when other files DO have structural changes, which is surprising to users
		// who see the class listed as "Changed" but get no dependency overlap score.
		if (changedClasses.isEmpty()) {
			return classLevelOverlapClasses(testClassDeps, changedMembers.changedClasses());
		}
		Set<String> effective = new java.util.LinkedHashSet<>(changedMembers.changedClasses());
		effective.addAll(changedClasses);
		return classLevelOverlapClasses(testClassDeps, effective);
	}

	/**
	 * Counts overlapping changed static fields for one test.
	 * <p>
	 * Only counts direct member overlaps ("fqcn#field") to avoid introducing noise.
	 * If member-level dependencies are unavailable, returns 0.
	 */
	public static int computeStaticFieldOverlap(Set<String> testMemberDeps, ChangedMembers changedMembers) {
		if (testMemberDeps == null || testMemberDeps.isEmpty() || changedMembers == null
				|| changedMembers.changedStaticFieldKeys().isEmpty()) {
			return 0;
		}
		int overlap = 0;
		for (String fieldKey : changedMembers.changedStaticFieldKeys()) {
			if (testMemberDeps.contains(fieldKey)) {
				overlap++;
			}
		}
		return overlap;
	}

	/**
	 * Member-level overlap: count classes where the test is actually affected by
	 * the specific changes in that class.
	 * <p>
	 * The count is in terms of <em>classes</em> (not members) so the ratio
	 * {@code overlap / totalClassDeps} stays comparable with class-level scoring.
	 * <p>
	 * Precision rules:
	 * <ul>
	 * <li>{@code <clinit>} (static initializer) changed → the class counts as
	 * affected for ANY test that depends on it, because class loading triggers
	 * {@code <clinit>} implicitly and the first test to load the class may differ
	 * across runs.</li>
	 * <li>Type-level changes (added/removed/signature-changed type) → always
	 * affected.</li>
	 * <li>{@code <init>} (constructor/instance initializer) changed → affected only
	 * if the test constructs objects of this class (has {@code fqcn#<init>} in
	 * member deps).</li>
	 * <li>Method changed → affected only if the test calls that method.</li>
	 * <li>Field declaration changed → affected only if the test accesses that
	 * field.</li>
	 * </ul>
	 */
	static int memberLevelOverlap(Set<String> testClassDeps, Set<String> testMemberDeps,
			ChangedMembers changedMembers) {
		return memberLevelOverlapClasses(testClassDeps, testMemberDeps, changedMembers).size();
	}

	/** Returns the set of affected classes using member-level precision. */
	static Set<String> memberLevelOverlapClasses(Set<String> testClassDeps, Set<String> testMemberDeps,
			ChangedMembers changedMembers) {
		Set<String> affectedClasses = new HashSet<>();

		// Iterate changedMembers (small, typically 1-20 classes) and check membership
		// in testClassDeps (HashSet, O(1) lookup) rather than iterating all test deps.

		// Type-level changes → always affected if test depends on the class
		for (String typeChanged : changedMembers.classesWithTypeChanges()) {
			if (testClassDeps.contains(typeChanged)) {
				affectedClasses.add(typeChanged);
			}
		}

		// Member-level changes: check each changed class
		for (var entry : changedMembers.membersByClass().entrySet()) {
			String classDep = entry.getKey();
			if (affectedClasses.contains(classDep) || !testClassDeps.contains(classDep))
				continue;

			Set<String> changedInClass = entry.getValue();

			// Static initializer or synthetic class-level marker → affects ALL users.
			if (changedInClass.contains("<clinit>") || changedInClass.contains(StaticCallGraphAnalyzer.CLASS_MARKER)) {
				affectedClasses.add(classDep);
				continue;
			}

			// For other members: check pre-computed changedMemberKeys against
			// testMemberDeps
			// to avoid string concatenation in the loop.
			for (String changedMember : changedInClass) {
				String memberKey = classDep + "#" + changedMember;
				if (testMemberDeps.contains(memberKey)) {
					affectedClasses.add(classDep);
					break;
				}
			}
		}

		// Conservative fallback: classes in changedClasses with no membersByClass entry
		for (String changedClass : changedMembers.changedClasses()) {
			if (!affectedClasses.contains(changedClass) && !changedMembers.membersByClass().containsKey(changedClass)
					&& !changedMembers.classesWithTypeChanges().contains(changedClass)
					&& testClassDeps.contains(changedClass)) {
				affectedClasses.add(changedClass);
			}
		}

		return affectedClasses;
	}

	/** Class-level overlap: count classes that changed (existing behavior). */
	static int classLevelOverlap(Set<String> testClassDeps, Set<String> changedClasses) {
		return classLevelOverlapClasses(testClassDeps, changedClasses).size();
	}

	/** Returns the set of dependency classes that overlap with changed classes. */
	static Set<String> classLevelOverlapClasses(Set<String> testClassDeps, Set<String> changedClasses) {
		// iterate the smaller set for O(min(|A|,|B|))
		Set<String> smaller = testClassDeps.size() <= changedClasses.size() ? testClassDeps : changedClasses;
		Set<String> larger = smaller == testClassDeps ? changedClasses : testClassDeps;
		Set<String> overlap = new HashSet<>();
		for (String dep : smaller) {
			if (larger.contains(dep))
				overlap.add(dep);
		}
		return overlap;
	}
}
