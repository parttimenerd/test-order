package me.bechberger.testorder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;

/**
 * Coverage-closure support for test selection: computes the changed-element
 * universe (changed PRODUCTION classes only, with member-level precision via
 * {@link ChangedMembers} when available), the per-test coverage sets over that
 * universe, the greedy {@link SetCoverComputer} cover core, and the
 * sole-coverer map used to exempt tests from cache-skip.
 *
 * <p>
 * The universe is <em>production-only</em> by contract: changed test classes
 * never enter the universe and never cover themselves. They are already handled
 * by selection's changed-test path (scored as changed, capped by topN), and the
 * static call-graph expansion can pull test classes into
 * {@link ChangedMembers#changedClasses()} as callers of changed production code
 * — those entries are filtered out here. A test class may still be a
 * <em>coverer</em> of production elements (via its dependency edges); it just
 * cannot be a universe element.
 *
 * <p>
 * The universe is <em>class-granular</em> (changed production classes ∪ changed
 * production member classes); member-level precision enters through the
 * coverage sets: {@link StructuralChangeAnalyzer#computeOverlapClasses} matches
 * a test's {@code Class#member} dependencies against the specific changed
 * members, so a test only "covers" a changed class when it actually exercises a
 * changed member (MEMBER instrumentation), or at plain class level as fallback.
 * This mirrors the granularity already used by {@code TestScorer}'s set-cover
 * bonus.
 *
 * <p>
 * Contract (see notes/tia-contract.md): the closure only ever ADDS tests to a
 * selection; the cover core must survive topN capping and cache-skip; the
 * universe honors includePackages/module filtering because the effective
 * (module-filtered) dep map restricts both candidates and coverage sets.
 */
public final class CoverageClosure {

	/**
	 * Which universe elements the finally-selected subset exercises.
	 *
	 * @param totalElements
	 *            size of the universe (X/Y: the Y)
	 * @param coveredCount
	 *            universe elements covered by at least one selected test
	 * @param uncoveredElements
	 *            universe elements not covered by any selected test, in universe
	 *            order
	 */
	public record Status(int totalElements, int coveredCount, List<String> uncoveredElements) {
		/** True when every universe element is exercised by the selected subset. */
		public boolean allCovered() {
			return uncoveredElements.isEmpty();
		}
	}

	private static final Status EMPTY_STATUS = new Status(0, 0, List.of());

	/**
	 * Per-selection memo of the per-test overlap join
	 * ({@link StructuralChangeAnalyzer#computeOverlapClasses}) that is otherwise
	 * run TWICE per affected test in one {@code select()} call: once by
	 * {@link TestScorer}'s set-cover pipeline (over changed production classes ∪
	 * changed test classes) and once by {@link CoverageClosure#compute} (over the
	 * production-only candidate universe plus its top-level aliases).
	 *
	 * <p>
	 * The two pipelines' match sets deliberately differ (the scorer's includes
	 * changed test classes; the closure's is the production-only universe with
	 * nested-class aliases), so their results are NOT interchangeable. What IS
	 * shared is the raw join against the UNION of both match sets: the class-level
	 * overlap branches match each dependency independently and monotonically in the
	 * match set, so each consumer's exact result is recovered by filtering the
	 * memoized raw set with {@link DependencyMap#changedClassesContains} against
	 * its own match set. The member-level branch ignores the match set entirely, so
	 * its result is passed through unfiltered (it is already identical for every
	 * consumer).
	 *
	 * <p>
	 * The index is thread-confined ({@link #install}/{@link #clear}) and must live
	 * only within a single {@code select()} call — no cross-run caching. Consumers
	 * that find no installed index (show/diagnostic paths, other threads) keep
	 * their legacy direct computation, byte-for-byte.
	 */
	public static final class OverlapIndex {

		private static final ThreadLocal<OverlapIndex> CURRENT = new ThreadLocal<>();

		/**
		 * Installs the index for the current thread's selection run (a {@code null}
		 * argument is a no-op). Callers must {@link #clear()} in a {@code finally}
		 * block.
		 */
		public static void install(OverlapIndex index) {
			if (index != null) {
				CURRENT.set(index);
			}
		}

		/**
		 * The index installed for the current thread, or {@code null} outside a
		 * selection run.
		 */
		public static OverlapIndex current() {
			return CURRENT.get();
		}

		/**
		 * Clears the thread-confined index; every selection run calls this in
		 * {@code finally}.
		 */
		public static void clear() {
			CURRENT.remove();
		}

		/**
		 * Creates an index for one selection run, or {@code null} when there is no
		 * change signal (nothing to memoize — consumers keep their legacy direct path).
		 * The initial union match set is a SUPERSET of both consumers' match sets:
		 * changed production classes ∪ changed test classes (the scorer's effective
		 * match set) ∪ {@link ChangedMembers#changedClasses()};
		 * {@link CoverageClosure#compute} extends it with the closure's candidate query
		 * before any raw overlap is memoized.
		 */
		public static OverlapIndex forSelection(DependencyMap depMap, Set<String> changedClasses,
				Set<String> changedTestClasses, ChangedMembers changedMembers) {
			boolean hasSignal = (changedClasses != null && !changedClasses.isEmpty())
					|| (changedTestClasses != null && !changedTestClasses.isEmpty())
					|| (changedMembers != null && !changedMembers.changedClasses().isEmpty());
			if (!hasSignal) {
				return null;
			}
			Set<String> union = new LinkedHashSet<>();
			if (changedClasses != null) {
				union.addAll(changedClasses);
			}
			if (changedTestClasses != null) {
				union.addAll(changedTestClasses);
			}
			if (changedMembers != null) {
				union.addAll(changedMembers.changedClasses());
			}
			return new OverlapIndex(depMap, changedMembers, union);
		}

		private final DependencyMap depMap;
		private final ChangedMembers changedMembers;
		/**
		 * Union of both consumers' match sets; extended by compute() before first use.
		 */
		private Set<String> unionMatchSet;
		/** test class → raw overlap against the union match set (memoized). */
		private final Map<String, Set<String>> rawByTest = new HashMap<>();
		/**
		 * test class → true when the member-level branch applied (result is match-set
		 * independent).
		 */
		private final Map<String, Boolean> memberLevelByTest = new HashMap<>();
		/** Number of underlying computeOverlapClasses invocations (dedup evidence). */
		private long rawComputations;

		private OverlapIndex(DependencyMap depMap, ChangedMembers changedMembers, Set<String> unionMatchSet) {
			this.depMap = depMap;
			this.changedMembers = changedMembers;
			this.unionMatchSet = unionMatchSet;
		}

		/**
		 * Extends the union match set with the closure's candidate query. Must be
		 * called BEFORE the first memoized lookup: a raw set computed against a smaller
		 * union is not a superset of a consumer's result for a match set added later,
		 * and filtering it would silently drop deps.
		 */
		void extendMatchSet(Set<String> additional) {
			if (!rawByTest.isEmpty()) {
				throw new IllegalStateException(
						"OverlapIndex match set extended after the first memoized overlap computation — "
								+ "CoverageClosure.compute must run before any TestScorer scoring on this index");
			}
			unionMatchSet.addAll(additional);
		}

		/**
		 * The raw per-test overlap set against the union match set, computed ONCE per
		 * test and memoized. The member-deps guard mirrors TestScorer's
		 * ({@code hasMemberDeps()}): for computeOverlapClasses the guard difference
		 * against CoverageClosure's {@code hasClassMemberDeps()} is immaterial — with
		 * method-only member data both a null and an empty set take the class-level
		 * branch.
		 *
		 * <p>
		 * The returned set is memoized and shared — callers must not mutate it.
		 */
		private Set<String> rawOverlap(String test) {
			return rawByTest.computeIfAbsent(test, t -> {
				Set<String> deps = depMap.get(t);
				if (deps == null) {
					deps = Set.of();
				}
				Set<String> memberDeps = depMap.hasMemberDeps() ? depMap.getMemberDeps(t) : null;
				rawComputations++;
				Set<String> raw = StructuralChangeAnalyzer.computeOverlapClasses(deps, memberDeps, changedMembers,
						unionMatchSet);
				// Same dispatch as computeOverlapClasses: the member-level branch ran when
				// member change data exists AND the test has member deps — its result
				// never consults the match set, so no per-consumer filtering may apply.
				boolean memberLevel = changedMembers != null && !changedMembers.changedClasses().isEmpty()
						&& memberDeps != null && !memberDeps.isEmpty();
				memberLevelByTest.put(t, memberLevel);
				return raw;
			});
		}

		/**
		 * The exact overlap set the calling consumer would get from
		 * {@code computeOverlapClasses(deps, memberDeps, changedMembers, matchSet)}:
		 * the memoized raw set filtered per dependency with
		 * {@link DependencyMap#changedClassesContains} (the class-level branches match
		 * each dep independently), or the raw set itself when the member-level branch
		 * applied (match-set independent result).
		 */
		public Set<String> overlapFor(String test, Set<String> matchSet) {
			Set<String> raw = rawOverlap(test);
			if (raw.isEmpty()) {
				return Set.of();
			}
			if (Boolean.TRUE.equals(memberLevelByTest.get(test))) {
				return raw;
			}
			Set<String> filtered = new LinkedHashSet<>();
			for (String dep : raw) {
				if (DependencyMap.changedClassesContains(matchSet, dep)) {
					filtered.add(dep);
				}
			}
			return filtered;
		}

		/**
		 * Number of underlying {@link StructuralChangeAnalyzer#computeOverlapClasses}
		 * invocations performed by this index — the dedup evidence: one per DISTINCT
		 * candidate test instead of one per candidate per consumer pipeline.
		 */
		public long rawComputationCount() {
			return rawComputations;
		}
	}

	private final Set<String> universe;
	/** test class → universe elements it covers (non-empty entries only). */
	private final Map<String, Set<String>> coverage;
	/** Greedy minimal-ish cover core in pick order. */
	private final List<String> coverCore;
	/** Tests that are the ONLY coverer of at least one universe element. */
	private final Set<String> soleCovererTests;
	/**
	 * Member-level universe but class-level coverage sets (MEMBER instrumentation
	 * off).
	 */
	private final boolean memberPrecisionUnavailable;

	private CoverageClosure(Set<String> universe, Map<String, Set<String>> coverage,
			boolean memberPrecisionUnavailable) {
		this.universe = universe;
		this.coverage = coverage;
		if (universe.isEmpty()) {
			this.coverCore = List.of();
			this.soleCovererTests = Set.of();
		} else {
			SetCoverComputer.Result<String> result = new SetCoverComputer<>(coverage, universe).compute();
			this.coverCore = List.copyOf(result.order());
			// element → coverers; a test is a "sole coverer" when it is the only
			// candidate covering some universe element. Sole coverers are always part
			// of the cover core (the greedy cover cannot avoid them), but the explicit
			// set documents the cache-skip exemption as a hard invariant.
			Map<String, List<String>> coverersByElement = new LinkedHashMap<>();
			for (var e : coverage.entrySet()) {
				for (String element : e.getValue()) {
					coverersByElement.computeIfAbsent(element, ignored -> new ArrayList<>()).add(e.getKey());
				}
			}
			Set<String> sole = new LinkedHashSet<>();
			for (var e : coverersByElement.entrySet()) {
				if (e.getValue().size() == 1) {
					sole.add(e.getValue().get(0));
				}
			}
			this.soleCovererTests = Set.copyOf(sole);
		}
		this.memberPrecisionUnavailable = memberPrecisionUnavailable;
	}

	/**
	 * Computes the closure view for one selection run.
	 *
	 * @param depMap
	 *            the effective (module/includePackages-filtered) dependency map
	 * @param changedClasses
	 *            class-level changed production classes (may be empty; defensive
	 *            test-class entries are filtered out)
	 * @param changedTestClasses
	 *            changed test classes (changed ∪ new from the caller). Accepted for
	 *            call-site compatibility but deliberately NOT part of the universe:
	 *            a changed test class is already handled by selection's
	 *            changed-test path (scored as changed, capped by topN) and must not
	 *            force-include itself via the cover core or count as an element
	 *            needing coverage.
	 * @param changedMembers
	 *            member-level change analysis, or {@code null} for the
	 *            since-last-run class-level fallback. Note: the static call-graph
	 *            expansion can add test classes to
	 *            {@link ChangedMembers#changedClasses()} (test bytecode is scanned
	 *            for callers of changed production code) — those entries are
	 *            filtered out below.
	 */
	public static CoverageClosure compute(DependencyMap depMap, Set<String> changedClasses,
			Set<String> changedTestClasses, ChangedMembers changedMembers) {
		return compute(depMap, changedClasses, changedTestClasses, changedMembers, null);
	}

	/**
	 * Computes the closure view for one selection run, reusing the per-test overlap
	 * join already memoized in {@code sharedOverlaps} (see {@link OverlapIndex}).
	 * The inputs and the resulting universe/candidates are identical to
	 * {@link #compute(DependencyMap, Set, Set, ChangedMembers)} — only WHERE the
	 * per-test join runs changes, so existing {@code compute} callers are
	 * unaffected.
	 *
	 * @param sharedOverlaps
	 *            the per-selection overlap index installed by the selection
	 *            orchestration, or {@code null} to compute the join directly
	 *            (legacy path)
	 */
	public static CoverageClosure compute(DependencyMap depMap, Set<String> changedClasses,
			Set<String> changedTestClasses, ChangedMembers changedMembers, OverlapIndex sharedOverlaps) {
		Set<String> knownTests = depMap.testClasses();
		Set<String> universe = new LinkedHashSet<>();
		if (changedClasses != null) {
			for (String c : changedClasses) {
				if (!isTestClassName(c, knownTests)) {
					universe.add(c);
				}
			}
		}
		if (changedMembers != null) {
			// LOAD-BEARING: changedMembers.changedClasses() contains not only the
			// structurally changed classes but also every class the static call-graph
			// expansion merely REACHED (callers of changed code, up to the configured
			// depth). Those caller classes did NOT change; letting them into the
			// universe violates the contract universe ("changed classes ∪ changed
			// member keys", notes/tia-contract.md) and inflates the cover core:
			// unchanged code would have to be "covered" (diluting the topN budget),
			// its sole dependents would be falsely exempt from cache-skip, and the CI
			// "changed elements covered X/Y" line would count unchanged classes.
			// When a class-level changed set exists it is authoritative (git/explicit
			// source semantics) — intersect. When it is empty the structural member
			// data is the only change signal (tiered/structural-only paths) and is
			// taken as-is. The containment check is nested-aware: structural and
			// bytecode-derived FQCNs can be nested binary names (com.Outer$Helper)
			// while the class-level set carries the top-level (com.Outer), because
			// git-level detection derives class names from file paths.
			boolean intersectWithChangedClasses = changedClasses != null && !changedClasses.isEmpty();
			for (String c : changedMembers.changedClasses()) {
				if (isTestClassName(c, knownTests)) {
					continue;
				}
				if (intersectWithChangedClasses && !DependencyMap.changedClassesContains(changedClasses, c)) {
					continue;
				}
				universe.add(c);
			}
		}
		Set<String> indexedChangedTests = new LinkedHashSet<>();
		if (changedTestClasses != null) {
			for (String t : changedTestClasses) {
				if (knownTests.contains(t)) {
					indexedChangedTests.add(t);
				}
			}
		}
		if (universe.isEmpty()) {
			return new CoverageClosure(Set.of(), Map.of(), false);
		}

		// LOAD-BEARING: candidate discovery AND class-level overlap matching must
		// see the TOP-LEVEL aliases of universe elements. Nested-class deps are
		// indexed under both their binary name and their top-level class
		// (DependencyMap#buildInvertedIndex), so a test that only depends on
		// com.Outer is indexed under com.Outer — querying the exact binary name
		// com.Outer$Helper would find no candidates, its sole coverer could never
		// be force-included, and the element would be falsely reported uncovered
		// (spurious WARN or coverage.enforce build failure). The same alias set is
		// passed to computeOverlapClasses as the class-level match set:
		// changedClassesContains only strips "$" from the DEP side, so a recorded
		// dep com.Outer can never match the nested element directly — matching the
		// top-level alias and resolving the raw dep back to the element via
		// resolveToUniverse's top-level equivalence is what makes that branch
		// reachable.
		Set<String> candidateQuery = new LinkedHashSet<>(universe);
		for (String element : universe) {
			int dollar = element.indexOf('$');
			if (dollar > 0) {
				candidateQuery.add(element.substring(0, dollar));
			}
		}

		// Candidates: tests reachable via the inverted index (handles the
		// nested-class dep aliasing), plus indexed changed test classes — a changed
		// test can still COVER production elements (via dep/member edges), it just
		// never enters the universe and never covers itself.
		Set<String> candidates = new LinkedHashSet<>(depMap.getAffectedTests(candidateQuery));
		candidates.addAll(indexedChangedTests);

		// Only per-test-CLASS member data feeds getMemberDeps (DependencyMap reads
		// memberDepsMap exclusively); an index with only per-test-METHOD member data
		// degrades to class-level coverage sets just like an index without any
		// member data.
		boolean hasClassMemberDeps = depMap.hasClassMemberDeps();
		if (sharedOverlaps != null) {
			// The raw join is memoized against the union of BOTH consumers' match
			// sets; the closure's candidate query must join that union before the
			// first memoized lookup (see OverlapIndex#extendMatchSet).
			sharedOverlaps.extendMatchSet(candidateQuery);
		}
		Map<String, Set<String>> coverage = new LinkedHashMap<>();
		for (String test : candidates) {
			Set<String> raw;
			if (sharedOverlaps != null) {
				raw = sharedOverlaps.overlapFor(test, candidateQuery);
			} else {
				Set<String> deps = depMap.get(test);
				Set<String> memberDeps = hasClassMemberDeps ? depMap.getMemberDeps(test) : null;
				raw = StructuralChangeAnalyzer.computeOverlapClasses(deps, memberDeps, changedMembers, candidateQuery);
			}
			Set<String> covered = new LinkedHashSet<>();
			for (String dep : raw) {
				covered.addAll(resolveToUniverse(dep, universe, knownTests));
			}
			if (!covered.isEmpty()) {
				coverage.put(test, covered);
			}
		}

		// Precision notice data: the universe was derived from member-level change
		// data, but the index has no per-test-CLASS MEMBER instrumentation, so
		// coverage sets degrade to class-level overlap (computeOverlapClasses
		// fallback branch). Per-test-METHOD-only member data does NOT help here:
		// getMemberDeps reads the class-level map only, so the coverage sets are
		// class-level and the notice must fire (a method-only index used to
		// suppress it because hasMemberDeps() was true).
		boolean memberPrecisionUnavailable = changedMembers != null && !changedMembers.changedMemberKeys().isEmpty()
				&& !hasClassMemberDeps;
		// keep universe order for deterministic uncovered-element reporting
		return new CoverageClosure(Collections.unmodifiableSet(universe), coverage, memberPrecisionUnavailable);
	}

	/**
	 * True when {@code className} is a test class of the effective dep map or an
	 * inner class of one (nested {@code Outer$Inner} form). The dep map's keys are
	 * test classes, so this is the reliable in-module classification — the same
	 * rule {@code CoverageOperation} uses to exclude test classes from coverage
	 * analysis.
	 */
	private static boolean isTestClassName(String className, Set<String> testClassNames) {
		if (testClassNames.contains(className)) {
			return true;
		}
		int dollar = className.indexOf('$');
		return dollar > 0 && testClassNames.contains(className.substring(0, dollar));
	}

	/**
	 * Maps a test-side dependency name (possibly a nested class, e.g.
	 * {@code com.Foo$Bar}) to the universe element(s) it belongs to, or an empty
	 * set when it matches no universe element. A dep covers its exact universe
	 * element, its top-level element, and — the top-level equivalence that makes
	 * nested universe elements coverable at all — every universe element nested
	 * under the dep's top-level class: a test depending on {@code com.Outer}
	 * exercises {@code com.Outer$Helper} at class granularity, mirroring the
	 * reverse direction {@code DependencyMap#buildInvertedIndex} already applies
	 * when it indexes nested deps under their top-level class.
	 */
	private static Set<String> resolveToUniverse(String depName, Set<String> universe, Set<String> knownTests) {
		// computeOverlapClasses can return test-class names (via
		// changedMembers.changedClasses(), which the static call-graph expansion
		// fills with test classes too); they can never be universe elements, so
		// skip the O(|universe|) fallback scan for them — guaranteed miss.
		if (isTestClassName(depName, knownTests)) {
			return Set.of();
		}
		Set<String> result = null;
		if (universe.contains(depName)) {
			result = new LinkedHashSet<>();
			result.add(depName);
		}
		int dollar = depName.indexOf('$');
		String top = dollar > 0 ? depName.substring(0, dollar) : depName;
		if (dollar > 0 && universe.contains(top)) {
			result = result == null ? new LinkedHashSet<>() : result;
			result.add(top);
		}
		for (String element : universe) {
			// universe element is a nested class of the dep's top-level (nested FQCN
			// reported as changed while tests depend on the top-level class); the
			// top-level-prefix comparison avoids a Set.of allocation per element
			if (element.length() > top.length() && element.charAt(top.length()) == '$' && element.startsWith(top)) {
				result = result == null ? new LinkedHashSet<>() : result;
				result.add(element);
			}
		}
		return result == null ? Set.of() : result;
	}

	/** True when there is no universe (no change signal) — closure is inert. */
	public boolean isEmpty() {
		return universe.isEmpty();
	}

	/** The changed-element universe (unmodifiable, universe order). */
	public Set<String> universe() {
		return universe;
	}

	/**
	 * The greedy cover core in pick order: the minimal-ish test set covering the
	 * whole universe. Tests in this set must be force-included BEFORE topN cap
	 * counting and are exempt from cache-skip.
	 */
	public List<String> coverCore() {
		return coverCore;
	}

	/**
	 * Tests that are the sole coverer of at least one universe element. These are
	 * exempt from cache-skip: dropping one would leave a changed element without
	 * any exercising test.
	 */
	public Set<String> soleCovererTests() {
		return soleCovererTests;
	}

	/** Member-level universe but only class-level coverage sets available. */
	public boolean memberPrecisionUnavailable() {
		return memberPrecisionUnavailable;
	}

	/**
	 * Computes which universe elements the given (final) test selection exercises.
	 *
	 * @param selectedTests
	 *            the finally selected test classes (post cap, post cache-skip)
	 */
	public Status status(Collection<String> selectedTests) {
		if (universe.isEmpty()) {
			return EMPTY_STATUS;
		}
		Set<String> covered = new LinkedHashSet<>();
		for (String test : selectedTests) {
			covered.addAll(coverage.getOrDefault(test, Set.of()));
		}
		List<String> uncovered = new ArrayList<>();
		for (String element : universe) {
			if (!covered.contains(element)) {
				uncovered.add(element);
			}
		}
		return new Status(universe.size(), universe.size() - uncovered.size(), List.copyOf(uncovered));
	}
}
