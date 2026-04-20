package me.bechberger.testorder;

import me.bechberger.testorder.changes.ChangeComplexity;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.changes.StructuralDiff;
import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * JUnit ClassOrderer that prioritizes test classes based on a weighted score.
 * <p>
 * Scores are computed from five weighted components (new test bonus, changed test
 * bonus, failure recency, speed bonus, dependency overlap).  Weights are loaded
 * from the {@code .test-order-state} file written by the Maven plugin; system
 * properties / classpath properties override individual weights.
 * <p>
 * Among tests with equal scores, a greedy Jaccard-diversity selection maximises
 * the breadth of covered dependencies, so that tests exercising different parts
 * of the codebase run before redundant ones.
 * <p>
 * Configuration via system properties (or {@code testorder-config.properties} on
 * the classpath):
 * <ul>
 *     <li>{@code testorder.index.path} — path to the {@code test-dependencies.lz4}</li>
 *     <li>{@code testorder.state.path} — path to the {@code .test-order-state} file</li>
 *     <li>{@code testorder.changed.classes} — comma-separated changed class FQCNs</li>
 *     <li>{@code testorder.changed.classes.file} — file with one changed class per line</li>
 *     <li>{@code testorder.changed.test.classes} — comma-separated changed test class FQCNs</li>
 *     <li>{@code testorder.score.*} — individual weight overrides</li>
 * </ul>
 */
public class PriorityClassOrderer implements ClassOrderer {

    private static final String CONFIG_RESOURCE = "testorder-config.properties";

    /** Lazily loaded config properties from classpath resource. */
    private Properties configProps;

    @Override
    public void orderClasses(ClassOrdererContext context) {
        String indexPath = getConfig(TestOrderConfig.INDEX_PATH);
        if (indexPath == null || indexPath.isEmpty()) {
            return;
        }
        Path idx = Path.of(indexPath);
        if (!Files.exists(idx)) {
            return;
        }

        DependencyMap depMap;
        try {
            depMap = DependencyMap.load(idx);
        } catch (IOException e) {
            TestOrderLogger.error("Failed to load dependency index: {}", e.getMessage());
            return;
        }

        // load unified state (weights, durations, failure history)
        String statePath = getConfig(TestOrderConfig.STATE_PATH);
        TestOrderState state;
        try {
            state = (statePath != null && !statePath.isEmpty() && Files.exists(Path.of(statePath)))
                    ? TestOrderState.load(Path.of(statePath))
                    : new TestOrderState();
        } catch (IOException e) {
            TestOrderLogger.error("Failed to load state: {}", e.getMessage());
            state = new TestOrderState();
        }

        Set<String> changedClasses = resolveChangedClasses();
        Set<String> changedTestClasses = resolveChangedTestClasses();
        boolean debug = getConfigBool(TestOrderConfig.DEBUG, false);

        // resolve scoring weights: system property > weights file > state file > defaults
        TestOrderState.ScoringWeights sw = state.weights();
        String weightsFilePath = getConfig(TestOrderConfig.WEIGHTS_FILE);
        if (weightsFilePath != null && !weightsFilePath.isEmpty()) {
            Path wf = Path.of(weightsFilePath);
            if (Files.exists(wf)) {
                try {
                    sw = TestOrderState.ScoringWeights.loadFromFile(wf).weights();
                } catch (IOException e) {
                    TestOrderLogger.error("Failed to load weights file: {}", e.getMessage());
                }
            }
        }
        int newTestBonus = getConfigInt("testorder.score.newTest", sw.newTest());
        int changedTestBonus = getConfigInt("testorder.score.changedTest", sw.changedTest());
        int maxFailureBonus = getConfigInt("testorder.score.maxFailure", sw.maxFailure());
        int speedBonus = getConfigInt("testorder.score.speed", sw.speed());
        int speedPenalty = getConfigInt("testorder.score.speedPenalty", sw.speedPenalty());
        int depOverlapWeight = getConfigInt("testorder.score.depOverlap", sw.depOverlap());
        int changeComplexityWeight = getConfigInt("testorder.score.changeComplexity", sw.changeComplexity());
        int staticFieldBonus = getConfigInt("testorder.score.staticFieldBonus", sw.staticFieldBonus());
        int coverageBonus = getConfigInt("testorder.score.coverageBonus", sw.coverageBonus());
        TestOrderState.ScoringWeights effectiveWeights = new TestOrderState.ScoringWeights(
                newTestBonus, changedTestBonus, maxFailureBonus, speedBonus, speedPenalty,
            depOverlapWeight, changeComplexityWeight, staticFieldBonus, coverageBonus);

        // set up run-quality tracking
        if (statePath != null && !statePath.isEmpty()) {
            TestOrderState.setStatePath(statePath);
        }

        List<? extends ClassDescriptor> descriptors = context.getClassDescriptors();
        List<String> testClassNames = descriptors.stream()
                .map(this::getTopLevelClassName).toList();

        // Compute structural change analysis for precise member-level scoring
        ChangedMembers changedMembers = null;
        List<StructuralDiff.FileDiff> structuralDiffs = null;
        boolean structuralEnabled = getConfigBool(TestOrderConfig.STRUCTURAL_DIFF_ENABLED, true);
        String projectRootStr = getConfig(TestOrderConfig.PROJECT_ROOT);
        if (structuralEnabled && projectRootStr != null && !projectRootStr.isEmpty()) {
            try {
                Path projectRoot = Path.of(projectRootStr);
                String changeMode = getConfig(TestOrderConfig.CHANGE_MODE);
                StructuralChangeAnalyzer.AnalysisResult analysis;
                if ("since-last-commit".equals(changeMode) || "SINCE_LAST_COMMIT".equals(changeMode)) {
                    analysis = StructuralChangeAnalyzer.analyzeSinceLastCommitFull(projectRoot);
                } else {
                    analysis = StructuralChangeAnalyzer.analyzeUncommittedFull(projectRoot);
                }
                changedMembers = analysis.changedMembers();
                structuralDiffs = analysis.diffs();
                if (debug) {
                    TestOrderLogger.debug("[structural] {} classes with structural changes, {} changed members",
                            changedMembers.changedClasses().size(),
                            changedMembers.changedMemberKeys().size());
                }
            } catch (IOException e) {
                TestOrderLogger.debug("[structural] Failed to compute structural analysis: {}", e.getMessage());
            }
        }

        // Compute change complexity for changed source files
        Map<String, Double> changeComplexityMap = Map.of();
        if (!changedClasses.isEmpty() && projectRootStr != null && !projectRootStr.isEmpty()) {
            Path projectRoot = Path.of(projectRootStr);
            String srcRoot = getConfig(TestOrderConfig.SOURCE_ROOT);
            List<Path> sourceRoots = new ArrayList<>();
            if (srcRoot != null && !srcRoot.isBlank()) {
                sourceRoots.add(Path.of(srcRoot));
            } else {
                // fallback: standard Maven layout
                sourceRoots.add(projectRoot.resolve("src/main/java"));
                sourceRoots.add(projectRoot.resolve("src/main/kotlin"));
            }
            changeComplexityMap = ChangeComplexity.compute(changedClasses, sourceRoots, changedMembers, structuralDiffs);
        }
        // Also try pre-computed complexity from config properties
        if (changeComplexityMap.isEmpty()) {
            changeComplexityMap = ChangeComplexity.deserialise(
                    getConfig(TestOrderConfig.CHANGE_COMPLEXITY));
        }

        TestOrderLogger.info("[test-order] change detection mode={} changedClasses={} changedTests={}",
                getConfig(TestOrderConfig.CHANGE_MODE), changedClasses.size(), changedTestClasses.size());
        if (debug) {
            TestOrderLogger.debug("[test-order] changed classes: {}", changedClasses);
            TestOrderLogger.debug("[test-order] changed test classes: {}", changedTestClasses);
        }

        TestScorer scorer = new TestScorer.Builder(effectiveWeights, depMap, state,
                changedClasses, changedTestClasses)
                .testClassNames(testClassNames)
                .changedMembers(changedMembers)
                .changeComplexity(changeComplexityMap)
                .build();

        // score each test class
        Map<ClassDescriptor, Integer> scores = new HashMap<>();
        for (ClassDescriptor desc : descriptors) {
            String testClassName = getTopLevelClassName(desc);
            TestScorer.ScoreResult result = scorer.score(testClassName);
            scores.put(desc, result.score());

            // record breakdown for run history
            if (statePath != null && !statePath.isEmpty()) {
                TestOrderState.recordBreakdown(testClassName,
                        new TestOrderState.ScoreBreakdown(result.score(), result.isNew(), result.isChanged(),
                                result.depOverlap(), result.depTotal(), result.failScore(), result.isFast(),
                                result.isSlow(), result.complexityOverlap(),
                                result.speedRatio(), result.hasStaticFieldOverlap()));
            }

            if (debug) {
                long dur = state.getDuration(testClassName, -1);
                TestOrderLogger.debug("{} score={} (deps={}, fail={}, new={}, changed={}, fast={}, slow={}, dur={})",
                        testClassName, result.score(), result.depOverlap(),
                        result.failScore(), result.isNew(), result.isChanged(), result.isFast(), result.isSlow(),
                        dur >= 0 ? dur + "ms" : "?");
            }
        }

        // order: group by score descending, within each group use Jaccard diversity
        orderByScoreAndDiversity(descriptors, scores, depMap, state,
                getConfigBool(TestOrderConfig.SPRING_CONTEXT_GROUPING, false));

        // Set up method-level ordering
        boolean methodOrderingEnabled = getConfigBool(TestOrderConfig.METHOD_ORDER_ENABLED, false);
        if (methodOrderingEnabled) {
            // Load method-level weights
            double methodFailureRecency = getConfigDouble("testorder.method.score.failureRecency",
                    state.methodScoringWeights().failureRecency());
            double methodFast = getConfigDouble("testorder.method.score.fast",
                    state.methodScoringWeights().fast());
            double methodSlow = getConfigDouble("testorder.method.score.slow",
                    state.methodScoringWeights().slow());
            double methodDepOverlap = getConfigDouble("testorder.method.score.depOverlap",
                    state.methodScoringWeights().depOverlap());
            double methodNewMethod = getConfigDouble("testorder.method.score.newMethod",
                    state.methodScoringWeights().newMethod());
            double methodChangedMethod = getConfigDouble("testorder.method.score.changedMethod",
                    state.methodScoringWeights().changedMethod());
            double methodCoverageBonus = getConfigDouble("testorder.method.score.coverageBonus",
                    state.methodScoringWeights().coverageBonus());
            TestOrderState.MethodScoringWeights methodWeights = new TestOrderState.MethodScoringWeights(
                    methodFailureRecency, methodFast, methodSlow, methodDepOverlap,
                    methodNewMethod, methodChangedMethod, methodCoverageBonus);

            // Resolve changed methods
            Set<String> changedMethods = resolveChangedMethods();

            // Inject state, weights, and dependency map for method ordering
            PriorityMethodOrderer.setPendingState(state, methodWeights,
                    true, depMap, changedClasses, changedMethods);
            TestOrderLogger.debug("[method-order] enabled with weights: failureRecency={}, fast={}, slow={}, " +
                    "depOverlap={}, newMethod={}, changedMethod={}, coverageBonus={}",
                    methodFailureRecency, methodFast,
                    methodSlow, methodDepOverlap, methodNewMethod, methodChangedMethod,
                    methodCoverageBonus);
        }

        if (debug) {
            TestOrderLogger.debug("Final order:");
            for (int i = 0; i < descriptors.size(); i++) {
                TestOrderLogger.debug("  {}. {} (score={})",
                        i + 1, descriptors.get(i).getTestClass().getName(),
                        scores.getOrDefault(descriptors.get(i), 0));
            }
        }
    }

    /**
     * Groups tests by score (descending), then within each group uses
     * greedy Jaccard-distance selection to maximise dependency diversity.
     * Within a Jaccard tie, shorter duration wins.
     */
    private void orderByScoreAndDiversity(List<? extends ClassDescriptor> descriptors,
                                          Map<ClassDescriptor, Integer> scores,
                                          DependencyMap depMap,
                                          TestOrderState state,
                                          boolean springContextGrouping) {
        // pre-compute top-level names and dep sets (avoid repeated reflection + map lookups)
        Map<ClassDescriptor, String> nameCache = new HashMap<>(descriptors.size());
        Map<ClassDescriptor, Set<String>> depsCache = new HashMap<>(descriptors.size());
        Map<ClassDescriptor, String> springContextCache = new HashMap<>(descriptors.size());
        for (ClassDescriptor d : descriptors) {
            String name = getTopLevelClassName(d);
            nameCache.put(d, name);
            depsCache.put(d, depMap.get(name));
            if (springContextGrouping) {
                springContextCache.put(d, TestScorer.springContextKey(d.getTestClass()));
            }
        }

        // group descriptors by score
        TreeMap<Integer, List<ClassDescriptor>> groups = new TreeMap<>(Comparator.reverseOrder());
        for (ClassDescriptor d : descriptors) {
            groups.computeIfAbsent(scores.getOrDefault(d, 0), k -> new ArrayList<>()).add(d);
        }

        // greedy diversity selection across all groups (higher score groups first)
        List<ClassDescriptor> result = new ArrayList<>(descriptors.size());
        Set<String> coveredDeps = new HashSet<>();
        String activeSpringContext = null;

        for (var entry : groups.entrySet()) {
            List<ClassDescriptor> group = new ArrayList<>(entry.getValue());
            while (!group.isEmpty()) {
                int bestIdx = -1;
                double bestDistance = -1;
                boolean bestMatchesSpringContext = false;
                long bestDuration = Long.MAX_VALUE;
                String bestName = null;

                for (int i = 0; i < group.size(); i++) {
                    ClassDescriptor desc = group.get(i);
                    Set<String> deps = depsCache.get(desc);
                    double distance = TestSelector.jaccardDistance(deps, coveredDeps);
                    long dur = state.getDuration(nameCache.get(desc), Long.MAX_VALUE);
                    String name = nameCache.get(desc);
                    boolean matchesSpringContext = springContextGrouping
                            && activeSpringContext != null
                            && Objects.equals(activeSpringContext, springContextCache.get(desc));

                    if (distance > bestDistance
                            || (distance == bestDistance && matchesSpringContext && !bestMatchesSpringContext)
                            || (distance == bestDistance && matchesSpringContext == bestMatchesSpringContext
                                && dur < bestDuration)
                            || (distance == bestDistance && matchesSpringContext == bestMatchesSpringContext
                                && dur == bestDuration && bestName != null && name.compareTo(bestName) < 0)) {
                        bestIdx = i;
                        bestDistance = distance;
                        bestMatchesSpringContext = matchesSpringContext;
                        bestDuration = dur;
                        bestName = name;
                    }
                }

                // O(1) swap-to-end removal instead of O(N) ArrayList.remove
                ClassDescriptor best = group.get(bestIdx);
                int last = group.size() - 1;
                if (bestIdx != last) {
                    group.set(bestIdx, group.get(last));
                }
                group.remove(last);
                result.add(best);
                coveredDeps.addAll(depsCache.get(best));
                if (springContextGrouping) {
                    activeSpringContext = springContextCache.get(best);
                }
            }
        }

        // replace contents in-place (descriptors is a mutable list)
        @SuppressWarnings("unchecked")
        List<ClassDescriptor> mutable = (List<ClassDescriptor>) descriptors;
        mutable.clear();
        mutable.addAll(result);
    }

    private String getTopLevelClassName(ClassDescriptor descriptor) {
        Class<?> clazz = descriptor.getTestClass();
        while (clazz.getEnclosingClass() != null) {
            clazz = clazz.getEnclosingClass();
        }
        return clazz.getName();
    }

    private Set<String> resolveChangedClasses() {
        Set<String> result = new LinkedHashSet<>();
        String explicit = getConfig(TestOrderConfig.CHANGED_CLASSES);
        if (explicit != null && !explicit.isBlank()) {
            for (String cls : explicit.split(",")) {
                String trimmed = cls.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
        }
        String filePath = getConfig(TestOrderConfig.CHANGED_CLASSES_FILE);
        if (filePath != null && !filePath.isBlank()) {
            Path f = Path.of(filePath);
            if (Files.exists(f)) {
                try {
                    Files.readAllLines(f).stream()
                            .map(String::trim).filter(s -> !s.isEmpty())
                            .forEach(result::add);
                } catch (IOException e) {
                    TestOrderLogger.error("Failed to read changed classes file: {}", e.getMessage());
                }
            }
        }
        return result;
    }

    private Set<String> resolveChangedTestClasses() {
        Set<String> result = new LinkedHashSet<>();
        String explicit = getConfig(TestOrderConfig.CHANGED_TEST_CLASSES);
        if (explicit != null && !explicit.isBlank()) {
            for (String cls : explicit.split(",")) {
                String trimmed = cls.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
        }
        return result;
    }

    private Set<String> resolveChangedMethods() {
        Set<String> result = new LinkedHashSet<>();
        String explicit = getConfig(TestOrderConfig.CHANGED_METHODS);
        if (explicit != null && !explicit.isBlank()) {
            for (String key : explicit.split(",")) {
                String trimmed = key.trim();
                if (!trimmed.isEmpty()) result.add(trimmed);
            }
        }
        return result;
    }

    private String getConfig(String key) {
        String val = System.getProperty(key);
        if (val != null) return val;
        if (configProps == null) {
            configProps = new Properties();
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(CONFIG_RESOURCE)) {
                if (is != null) configProps.load(is);
            } catch (IOException e) {
                TestOrderLogger.debug("Failed to load {}: {}", CONFIG_RESOURCE, e.getMessage());
            }
        }
        return configProps.getProperty(key);
    }

    private boolean getConfigBool(String key, boolean defaultValue) {
        String val = getConfig(key);
        if (val == null) return defaultValue;
        return "true".equalsIgnoreCase(val.trim());
    }

    private int getConfigInt(String key, int defaultValue) {
        String val = getConfig(key);
        if (val != null && !val.isBlank()) {
            try { return Integer.parseInt(val.trim()); }
            catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    private double getConfigDouble(String key, double defaultValue) {
        String val = getConfig(key);
        if (val != null && !val.isBlank()) {
            try { return Double.parseDouble(val.trim()); }
            catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
