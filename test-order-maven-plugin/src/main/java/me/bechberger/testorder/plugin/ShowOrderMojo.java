package me.bechberger.testorder.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.changes.ChangeComplexity;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer;
import me.bechberger.testorder.changes.StructuralChangeAnalyzer.ChangedMembers;
import me.bechberger.testorder.changes.StructuralDiff;

/**
 * Displays the computed test execution order without running any tests.
 * <p>
 * Usage: {@code mvn test-order:show-order}
 */
@Mojo(name = "show-order", defaultPhase = LifecyclePhase.VALIDATE)
public class ShowOrderMojo extends AbstractTestOrderMojo {

	/** Score bonus for new test classes not in the dependency index */
	@Parameter(property = MavenPluginConfigKeys.SCORE_NEW_TEST)
	private Integer scoreNewTest;

	/** Score bonus for test classes whose source was modified */
	@Parameter(property = MavenPluginConfigKeys.SCORE_CHANGED_TEST)
	private Integer scoreChangedTest;

	/** Maximum score bonus from failure frequency */
	@Parameter(property = MavenPluginConfigKeys.SCORE_MAX_FAILURE)
	private Integer scoreMaxFailure;

	/** Score bonus for tests with below-median duration */
	@Parameter(property = MavenPluginConfigKeys.SCORE_SPEED)
	private Integer scoreSpeed;

	/** Score penalty for tests with above-median duration */
	@Parameter(property = MavenPluginConfigKeys.SCORE_SPEED_PENALTY)
	private Integer scoreSpeedPenalty;

	/** Max score from dependency overlap (ratio-based) */
	@Parameter(property = MavenPluginConfigKeys.SCORE_DEP_OVERLAP)
	private Integer scoreDepOverlap;

	/** Max score from complexity-weighted dependency overlap */
	@Parameter(property = MavenPluginConfigKeys.SCORE_CHANGE_COMPLEXITY)
	private Integer scoreChangeComplexity;

	/** Optional fixed bonus when a test overlaps changed static field members */
	@Parameter(property = MavenPluginConfigKeys.SCORE_STATIC_FIELD_BONUS)
	private Integer scoreStaticFieldBonus;

	/** Set-cover coverage bonus weight (0 = disabled, uses depOverlap instead) */
	@Parameter(property = MavenPluginConfigKeys.SCORE_COVERAGE_BONUS)
	private Integer scoreCoverageBonus;

	/**
	 * show-order allows explicit mode with no changedClasses (means "no changes").
	 */
	@Override
	protected void validateParameters() throws MojoExecutionException {
		ParameterValidator validator = new ParameterValidator(getLog());
		validator.validateChangeMode(changeMode);
		// Skip validateExplicitModeRequirements — empty changedClasses is valid for
		// show-order
		if (weightsFile != null && !weightsFile.isBlank()) {
			validator.validateFilePath(weightsFile, "weightsFile");
		}
	}

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		Path idxPath = resolveIndexPath();

		// auto-aggregate if needed
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}

		DependencyMap depMap;
		try {
			depMap = DependencyMap.load(idxPath);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to load dependency index", e);
		}
		warnIfNoDeps(depMap);

		Set<String> changed = detectChangedClasses();
		Set<String> changedTests = detectChangedTestClasses();
		warnUnknownChangedClasses(changed, depMap);
		TestOrderState state = loadState();

		TestOrderState.ScoringWeights sw = resolveWeights(state);

		// Apply system property overrides (if any)
		sw = new TestOrderState.ScoringWeights(scoreNewTest != null ? scoreNewTest : sw.newTest(),
				scoreChangedTest != null ? scoreChangedTest : sw.changedTest(),
				scoreMaxFailure != null ? scoreMaxFailure : sw.maxFailure(),
				scoreSpeed != null ? scoreSpeed : sw.speed(),
				scoreSpeedPenalty != null ? scoreSpeedPenalty : sw.speedPenalty(),
				scoreDepOverlap != null ? scoreDepOverlap : sw.depOverlap(),
				scoreChangeComplexity != null ? scoreChangeComplexity : sw.changeComplexity(),
				scoreStaticFieldBonus != null ? scoreStaticFieldBonus : sw.staticFieldBonus(),
				scoreCoverageBonus != null ? scoreCoverageBonus : sw.coverageBonus());

		// warn about negative weights
		new ParameterValidator(getLog()).warnNegativeWeights(Map.of("newTest", sw.newTest(), "changedTest",
				sw.changedTest(), "maxFailure", sw.maxFailure(), "speed", sw.speed(), "speedPenalty", sw.speedPenalty(),
				"depOverlap", sw.depOverlap(), "changeComplexity", sw.changeComplexity(), "staticFieldBonus",
				sw.staticFieldBonus(), "coverageBonus", sw.coverageBonus()));

		ChangedMembers changedMembers = null;
		List<StructuralDiff.FileDiff> structuralDiffs = null;
		if (!changed.isEmpty() && !"false".equals(System.getProperty(MavenPluginConfigKeys.STRUCTURAL_DIFF_ENABLED))) {
			try {
				Path projectRoot = project.getBasedir().toPath().toAbsolutePath();
				StructuralChangeAnalyzer.AnalysisResult analysis;
				String structuralDiffMode = resolveStructuralDiffMode();
				if ("since-last-commit".equals(structuralDiffMode)) {
					analysis = StructuralChangeAnalyzer.analyzeSinceLastCommitFull(projectRoot);
				} else if ("uncommitted".equals(structuralDiffMode)) {
					analysis = StructuralChangeAnalyzer.analyzeUncommittedFull(projectRoot);
				} else {
					analysis = null;
				}
				if (analysis != null) {
					changedMembers = analysis.changedMembers();
					structuralDiffs = analysis.diffs();
				}
			} catch (IOException e) {
				getLog().debug("[test-order] Failed to compute structural diff: " + e.getMessage());
			}
		}

		Map<String, Double> changeComplexityMap = Map.of();
		if (!changed.isEmpty()) {
			List<Path> sourceRoots = resolveSourceRoots();
			if (!sourceRoots.isEmpty()) {
				changeComplexityMap = ChangeComplexity.compute(changed, sourceRoots, changedMembers, structuralDiffs);
			}
		}

		// collect all known test classes + any changed test classes not yet in the
		// index
		Set<String> allTests = new LinkedHashSet<>(depMap.testClasses());
		allTests.addAll(changedTests);

		// discover compiled test classes from target/test-classes (catches new classes
		// not yet in index)
		Path testClassesDir = Path.of(project.getBuild().getTestOutputDirectory());
		if (Files.isDirectory(testClassesDir)) {
			try (var walk = Files.walk(testClassesDir)) {
				walk.filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$"))
						.filter(ShowOrderMojo::looksLikeTestClass).forEach(p -> {
							String relative = testClassesDir.relativize(p).toString();
							String fqcn = relative.replace('/', '.').replace('\\', '.').replaceAll("\\.class$", "");
							allTests.add(fqcn);
						});
			} catch (IOException e) {
				getLog().debug("[test-order] Could not scan test-classes: " + e.getMessage());
			}
		}

		TestScorer scorer = new TestScorer.Builder(sw, depMap, state, changed, changedTests)
				.testClassNames(depMap.testClasses()).changedMembers(changedMembers)
				.changeComplexity(changeComplexityMap).build();

		// score each test class
		record TestScore(String name, int score, int depOverlap, double failScore, boolean isNew, boolean isChanged,
				boolean isFast, boolean isSlow, long duration) {
		}

		List<TestScore> scored = new ArrayList<>();
		for (String testClass : allTests) {
			TestScorer.ScoreResult result = scorer.score(testClass);
			long dur = state.getDuration(testClass, -1);
			scored.add(new TestScore(testClass, result.score(), result.depOverlap(), result.failScore(), result.isNew(),
					result.isChanged(), result.isFast(), result.isSlow(), dur));
		}

		if (scored.isEmpty()) {
			getLog().info("[test-order] No test classes found in dependency index or test output.");
			getLog().info("[test-order] Build tests or run learn mode first: mvn test -D" + MavenPluginConfigKeys.MODE
					+ "=learn");
			return;
		}

		// sort same as PriorityClassOrderer
		scored.sort(Comparator.<TestScore, Integer>comparing(TestScore::score).reversed()
				.thenComparingLong(s -> s.duration() >= 0 ? s.duration() : Long.MAX_VALUE)
				.thenComparing(TestScore::name));

		// print results — use System.out so the table is visible even with Maven's -q
		// flag
		System.out.println();
		if (!changed.isEmpty()) {
			System.out.println("Changed classes: " + changed);
		}
		if (!changedTests.isEmpty()) {
			System.out.println("Changed test classes: " + changedTests);
		}
		System.out.println();

		// determine column widths
		int maxName = "Test Class".length();
		for (TestScore s : scored) {
			String shortName = shortenName(s.name());
			if (shortName.length() > maxName)
				maxName = shortName.length();
		}

		String fmt = "  %-4s %-" + maxName + "s %6s %5s %5s %8s %8s%n";
		System.out.printf(fmt, "#", "Test Class", "Score", "Deps", "Fail", "Changed", "Duration");
		System.out.printf(fmt, "—", "—".repeat(maxName), "—".repeat(6), "—".repeat(5), "—".repeat(5), "—".repeat(8),
				"—".repeat(8));
		for (int i = 0; i < scored.size(); i++) {
			TestScore s = scored.get(i);
			System.out.printf(fmt, (i + 1) + ".", shortenName(s.name()), s.score(),
					s.depOverlap() > 0 ? s.depOverlap() : "",
					s.failScore() > 0 ? String.format("%.1f", s.failScore()) : "", s.isChanged() ? "yes" : "",
					s.duration() >= 0 ? s.duration() + "ms" : "");
		}
		System.out.println();
	}

	/**
	 * Resolves the effective change mode for structural diffing. Since-last-run and
	 * explicit modes have no reliable structural-diff baseline, so structural
	 * complexity is disabled for those modes rather than using a mismatched git
	 * comparison.
	 */
	protected String resolveStructuralDiffMode() {
		if (changeMode == null || changeMode.isBlank()) {
			return null;
		}
		return switch (changeMode) {
			case "since-last-commit" -> "since-last-commit";
			case "uncommitted" -> "uncommitted";
			case "explicit", "since-last-run" -> null;
			case "auto" -> {
				if (changedClasses != null && !changedClasses.isBlank()) {
					yield null;
				}
				yield Files.exists(ctx.resolveHashFile(hashFile)) ? null : "since-last-commit";
			}
			default -> null;
		};
	}

	private String shortenName(String fqcn) {
		int lastDot = fqcn.lastIndexOf('.');
		if (lastDot < 0)
			return fqcn;
		String pkg = fqcn.substring(0, lastDot);
		String cls = fqcn.substring(lastDot + 1);
		String[] parts = pkg.split("\\.");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length - 1; i++) {
			sb.append(parts[i].charAt(0)).append('.');
		}
		sb.append(parts[parts.length - 1]).append('.').append(cls);
		return sb.toString();
	}

	private List<Path> resolveSourceRoots() {
		LinkedHashSet<Path> roots = new LinkedHashSet<>();
		roots.add(ChangeDetectionHelper.resolveSourceRoot(project, sourceRoot));

		Path projectRoot = project.getBasedir().toPath().toAbsolutePath();
		Path kotlinRoot = projectRoot.resolve("src/main/kotlin");
		if (Files.isDirectory(kotlinRoot)) {
			roots.add(kotlinRoot);
		}

		return roots.stream().filter(Objects::nonNull).filter(Files::isDirectory).toList();
	}

	/**
	 * Checks whether a compiled {@code .class} file looks like a JUnit test class
	 * by scanning the constant pool for JUnit test annotation descriptors. Filters
	 * out utility classes, test applications, and other non-test classes that
	 * happen to live in {@code src/test/java}.
	 */
	private static boolean looksLikeTestClass(Path classFile) {
		try {
			byte[] bytes = Files.readAllBytes(classFile);
			String content = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
			return content.contains("Lorg/junit/jupiter/api/Test;")
					|| content.contains("Lorg/junit/jupiter/api/TestFactory;")
					|| content.contains("Lorg/junit/jupiter/api/RepeatedTest;")
					|| content.contains("Lorg/junit/jupiter/params/ParameterizedTest;")
					|| content.contains("Lorg/junit/Test;");
		} catch (IOException e) {
			return false;
		}
	}
}
