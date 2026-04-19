package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Bridges {@link StructuralDiff} output with dependency maps to compute
 * fine-grained test impact scores.
 * <p>
 * Given structural changes (which methods/fields/types changed) and
 * the dependency index (which tests use which members), determines
 * which tests are actually affected. If a test never calls a method
 * that changed, and nothing else in that class changed, the test is
 * probably not affected — even if it depends on the class.
 */
public class StructuralChangeAnalyzer {

    /**
     * @param changedClasses          all FQCNs that have any structural change
     * @param changedMemberKeys       all changed members as {@code "fqcn#memberName"} keys
     * @param membersByClass          fqcn → set of member names that changed in that class
     * @param classesWithTypeChanges  FQCNs that have type-level changes (added/removed/signature-changed types)
         * @param changedStaticFieldKeys   changed static fields as {@code "fqcn#fieldName"} keys
     */
    public record ChangedMembers(
            Set<String> changedClasses,
            Set<String> changedMemberKeys,
            Map<String, Set<String>> membersByClass,
            Set<String> classesWithTypeChanges,
            Set<String> changedStaticFieldKeys
    ) {
        public ChangedMembers(Set<String> changedClasses,
                      Set<String> changedMemberKeys,
                      Map<String, Set<String>> membersByClass,
                      Set<String> classesWithTypeChanges) {
            this(changedClasses, changedMemberKeys, membersByClass,
                classesWithTypeChanges, Set.of());
        }

        public static final ChangedMembers EMPTY =
            new ChangedMembers(Set.of(), Set.of(), Map.of(), Set.of(), Set.of());
    }

    /**
     * Extracts changed members from a list of file diffs produced by {@link StructuralDiff}.
     */
    public static ChangedMembers fromDiffs(List<StructuralDiff.FileDiff> diffs) {
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
                    memberKeys.add(change.fqcn() + "#" + memberName);
                    byClass.computeIfAbsent(change.fqcn(), k -> new LinkedHashSet<>())
                            .add(memberName);

                    if (change.category() == StructuralDiff.Change.Category.FIELD
                            && isStaticFieldChange(change)) {
                        staticFields.add(change.fqcn() + "#" + memberName);
                    }
                }
            }
        }
        return new ChangedMembers(
                Collections.unmodifiableSet(classes),
                Collections.unmodifiableSet(memberKeys),
                Collections.unmodifiableMap(byClass),
                Collections.unmodifiableSet(typeChanged),
                Collections.unmodifiableSet(staticFields));
    }

    private static boolean isStaticFieldChange(StructuralDiff.Change change) {
        String detail = change.detail();
        if (detail == null || detail.isBlank()) {
            return false;
        }
        // FIELD change details include stripped declarations (or "was/now" pairs for modified fields).
        // Treat as static if either side contains the static modifier.
        return detail.matches("(?s).*\\bstatic\\b.*");
    }

    /**
     * Maps a structural change to the member name used by the agent's recording format.
     * Returns null for TYPE-level changes (those are handled at the class level).
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

    /** Bundles both the structural {@link ChangedMembers} summary and the raw diffs. */
    public record AnalysisResult(ChangedMembers changedMembers, List<StructuralDiff.FileDiff> diffs) {}

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
     * Computes the dependency overlap count for a test, using member-level
     * deps when available for higher precision.
     * <p>
     * <b>With member-level deps</b> (FULL_MEMBER mode): a class counts as
     * overlapping only if one of the following holds:
     * <ul>
     *     <li>The test uses at least one member that actually changed.</li>
     *     <li>A static initializer ({@code <clinit>}) changed — this affects ALL
     *         users of the class since class loading is implicit.</li>
     *     <li>A type-level change occurred (added/removed/signature-changed type).</li>
     * </ul>
     * <p>
     * <b>Without member-level deps</b>: falls back to class-level overlap, but
     * uses structural analysis to filter out classes with only comment/whitespace changes.
     *
     * @param testClassDeps  class-level deps for the test (always available)
     * @param testMemberDeps member-level deps ("fqcn#member"), null/empty if unavailable
     * @param changedMembers structural change analysis result (may be null)
     * @param changedClasses class-level changed set from git (may include non-structural changes)
     * @return overlap count (number of dependency classes with matching changes)
     */
    public static int computeOverlap(Set<String> testClassDeps,
                                      Set<String> testMemberDeps,
                                      ChangedMembers changedMembers,
                                      Set<String> changedClasses) {
        return computeOverlapClasses(testClassDeps, testMemberDeps, changedMembers, changedClasses).size();
    }

    /**
     * Like {@link #computeOverlap} but returns the set of overlapping dependency classes
     * rather than just the count.  Used by {@link me.bechberger.testorder.TestScorer}
     * for both dependency-overlap and complexity scoring so both use the same structural
     * precision.
     */
    public static Set<String> computeOverlapClasses(Set<String> testClassDeps,
                                                     Set<String> testMemberDeps,
                                                     ChangedMembers changedMembers,
                                                     Set<String> changedClasses) {
        if (changedMembers == null || changedMembers.changedClasses().isEmpty()) {
            return classLevelOverlapClasses(testClassDeps, changedClasses);
        }

        if (testMemberDeps != null && !testMemberDeps.isEmpty()) {
            return memberLevelOverlapClasses(testClassDeps, testMemberDeps, changedMembers);
        }

        // No member deps: use structural analysis to refine class-level overlap.
        // Only count classes that have actual structural changes (not just comments/whitespace).
        return classLevelOverlapClasses(testClassDeps, changedMembers.changedClasses());
    }

    /**
     * Counts overlapping changed static fields for one test.
     * <p>
     * Only counts direct member overlaps ("fqcn#field") to avoid introducing noise.
     * If member-level dependencies are unavailable, returns 0.
     */
    public static int computeStaticFieldOverlap(Set<String> testMemberDeps,
                                                ChangedMembers changedMembers) {
        if (testMemberDeps == null || testMemberDeps.isEmpty()
                || changedMembers == null
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
     *     <li>{@code <clinit>} (static initializer) changed → the class counts as affected
     *         for ANY test that depends on it, because class loading triggers {@code <clinit>}
     *         implicitly and the first test to load the class may differ across runs.</li>
     *     <li>Type-level changes (added/removed/signature-changed type) → always affected.</li>
     *     <li>{@code <init>} (constructor/instance initializer) changed → affected only if
     *         the test constructs objects of this class (has {@code fqcn#<init>} in member deps).</li>
     *     <li>Method changed → affected only if the test calls that method.</li>
     *     <li>Field declaration changed → affected only if the test accesses that field.</li>
     * </ul>
     */
    static int memberLevelOverlap(Set<String> testClassDeps,
                                   Set<String> testMemberDeps,
                                   ChangedMembers changedMembers) {
        return memberLevelOverlapClasses(testClassDeps, testMemberDeps, changedMembers).size();
    }

    /** Returns the set of affected classes using member-level precision. */
    static Set<String> memberLevelOverlapClasses(Set<String> testClassDeps,
                                                  Set<String> testMemberDeps,
                                                  ChangedMembers changedMembers) {
        Set<String> affectedClasses = new HashSet<>();

        for (String classDep : testClassDeps) {
            if (affectedClasses.contains(classDep)) continue;

            // Type-level changes → always affected (class signature changed, type added/removed)
            if (changedMembers.classesWithTypeChanges().contains(classDep)) {
                affectedClasses.add(classDep);
                continue;
            }

            Set<String> changedInClass = changedMembers.membersByClass().get(classDep);
            if (changedInClass == null) {
                // Class is in changedClasses but has no member-level changes recorded.
                // This shouldn't happen (structural analysis populates membersByClass for
                // all non-type changes), but if it does, be conservative.
                if (changedMembers.changedClasses().contains(classDep)) {
                    affectedClasses.add(classDep);
                }
                continue;
            }

            // Static initializer changed → affects ALL users of this class.
            // <clinit> runs on class load, which is implicit and non-deterministic
            // across test execution order.
            if (changedInClass.contains("<clinit>")) {
                affectedClasses.add(classDep);
                continue;
            }

            // For other members: precise matching against the test's member deps.
            // <init> (constructor/instance initializer), methods, fields.
            for (String changedMember : changedInClass) {
                if (testMemberDeps.contains(classDep + "#" + changedMember)) {
                    affectedClasses.add(classDep);
                    break;
                }
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
        Set<String> larger  = smaller == testClassDeps ? changedClasses : testClassDeps;
        Set<String> overlap = new HashSet<>();
        for (String dep : smaller) {
            if (larger.contains(dep)) overlap.add(dep);
        }
        return overlap;
    }
}
