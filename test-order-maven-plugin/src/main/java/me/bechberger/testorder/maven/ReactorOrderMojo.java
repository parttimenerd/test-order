package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import me.bechberger.testorder.ops.ReactorOrderOperation;
import me.bechberger.testorder.ops.ReactorOrderOperation.ModuleScore;
import me.bechberger.testorder.ops.ReactorOrderOperation.ReactorOrderInput;
import me.bechberger.testorder.ops.ReactorOrderOperation.ReactorOrderResult;

/**
 * Computes the optimal module execution order for a multi-module reactor build
 * based on test urgency scores.
 *
 * <p>
 * In large multi-module projects, Maven executes modules in dependency-graph
 * order. Independent modules (at the same DAG depth) default to declaration
 * order in the POM. This goal analyzes which modules contain the
 * highest-priority tests (affected by recent changes, recently failed, etc.)
 * and recommends reordering so the most urgent modules run first.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * # Show recommended module order
 * mvn test-order:reactor-order
 *
 * # Get a -pl argument for running affected modules first
 * mvn test-order:reactor-order -Dtestorder.reactor.suggest=true
 * </pre>
 *
 * <p>
 * The output includes per-module urgency scores, affected test counts, and a
 * suggested {@code -pl} argument for running the most affected modules first.
 */
@Mojo(name = "reactor-order", aggregator = true)
public class ReactorOrderMojo extends AbstractTestOrderMojo {

	/**
	 * When true, outputs just the suggested {@code -pl} argument (machine-parseable
	 * for scripts).
	 */
	@Parameter(property = "testorder.reactor.suggest", defaultValue = "false")
	private boolean suggest;

	/**
	 * Number of top tests to show per module in the detailed output.
	 */
	@Parameter(property = "testorder.reactor.topN", defaultValue = "5")
	private int topN;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		Path idxPath = resolveIndexPath();
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}

		// Collect test-classes directories from all reactor modules
		Map<String, Path> moduleTestDirs = new LinkedHashMap<>();
		Map<String, String> moduleIdToRelativePath = new LinkedHashMap<>();

		List<MavenProject> projects = session.getProjects();
		for (MavenProject p : projects) {
			if ("pom".equals(p.getPackaging())) {
				continue;
			}
			String testOutputDir = p.getBuild().getTestOutputDirectory();
			if (testOutputDir == null) {
				continue;
			}
			Path testClassesDir = Path.of(testOutputDir);
			if (!Files.isDirectory(testClassesDir)) {
				continue;
			}
			String gid = p.getGroupId();
			String moduleId = (gid == null || gid.isEmpty()) ? p.getArtifactId() : gid + ":" + p.getArtifactId();
			moduleTestDirs.put(moduleId, testClassesDir);

			// Compute relative path from reactor root for -pl suggestion
			Path reactorRoot = session.getTopLevelProject().getBasedir().toPath();
			Path moduleDir = p.getBasedir().toPath();
			String relativePath = reactorRoot.relativize(moduleDir).toString();
			if (relativePath.isEmpty()) {
				relativePath = ".";
			}
			moduleIdToRelativePath.put(moduleId, relativePath);
		}

		if (moduleTestDirs.isEmpty()) {
			getLog().info("[test-order] No modules with compiled test classes found.");
			return;
		}

		// Detect changed classes across ALL reactor modules.
		// For an aggregator mojo on the root POM, we must scan all module source
		// roots — not just the root project's (which typically has no sources).
		Set<String> changed = detectChangedClassesAllModules();
		Set<String> changedTests = detectChangedTestClassesAllModules();

		ReactorOrderInput input = new ReactorOrderInput(idxPath, ctx.resolveStateFile(stateFile), changed, changedTests,
				moduleTestDirs, null, topN, pluginLog());

		ReactorOrderResult result;
		try {
			result = ReactorOrderOperation.compute(input);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to compute reactor order", e);
		}

		List<ModuleScore> sorted = result.moduleScores().stream().sorted().toList();

		if (suggest) {
			// Machine-readable output: just the -pl argument
			List<String> affectedPaths = sorted.stream().filter(m -> m.affectedTestCount() > 0)
					.map(m -> moduleIdToRelativePath.getOrDefault(m.moduleId(), m.moduleId())).toList();
			if (affectedPaths.isEmpty()) {
				getLog().info("[test-order] No affected modules — all tests have score 0.");
			} else {
				System.out.println("-pl " + String.join(",", affectedPaths));
			}
			return;
		}

		// Detailed human-readable output
		getLog().info("");
		getLog().info("╔══════════════════════════════════════════════════════════════╗");
		getLog().info("║         test-order: Reactor Module Priority                 ║");
		getLog().info("╠══════════════════════════════════════════════════════════════╣");
		getLog().info(String.format("║  Changed classes: %-40d ║", changed.size()));
		getLog().info(String.format("║  Changed tests:   %-40d ║", changedTests.size()));
		getLog().info(String.format("║  Modules:         %-40d ║", moduleTestDirs.size()));
		getLog().info("╚══════════════════════════════════════════════════════════════╝");
		getLog().info("");

		int rank = 1;
		for (ModuleScore ms : sorted) {
			String relativePath = moduleIdToRelativePath.getOrDefault(ms.moduleId(), ms.moduleId());
			String affected = ms.affectedTestCount() > 0
					? ms.affectedTestCount() + "/" + ms.totalTestCount() + " affected"
					: "no affected tests";
			getLog().info(String.format("  #%d  %-40s  max=%3d  sum=%5d  (%s)", rank, relativePath, ms.maxTestScore(),
					ms.sumTestScores(), affected));
			for (String top : ms.topTests()) {
				getLog().info("       → " + top);
			}
			rank++;
		}

		// Print suggested -pl for affected modules
		List<ModuleScore> affected = result.affectedModules();
		if (!affected.isEmpty() && affected.size() < sorted.size()) {
			getLog().info("");
			getLog().info("[test-order] Suggested fast-feedback command (affected modules first):");
			List<String> paths = affected.stream()
					.map(m -> moduleIdToRelativePath.getOrDefault(m.moduleId(), m.moduleId())).toList();
			getLog().info("  mvn test -pl " + String.join(",", paths) + " -am");
			getLog().info("");
			List<String> remainingPaths = sorted.stream().filter(m -> m.affectedTestCount() == 0)
					.map(m -> moduleIdToRelativePath.getOrDefault(m.moduleId(), m.moduleId())).toList();
			if (!remainingPaths.isEmpty()) {
				getLog().info("[test-order] Then run remaining modules:");
				getLog().info("  mvn test -pl " + String.join(",", remainingPaths));
			}
		} else if (affected.isEmpty()) {
			getLog().info("");
			getLog().info("[test-order] No modules have affected tests — full test run recommended.");
		}
	}

	/**
	 * Detects changed production classes across ALL reactor modules. Unlike the
	 * per-module {@code detectChangedClasses()}, this scans every module's source
	 * root so that an aggregator mojo on the root POM (which has no sources of its
	 * own) still finds changes in submodules.
	 */
	private Set<String> detectChangedClassesAllModules() {
		// Force git-based detection (uncommitted) since we don't have per-module hash
		// files in this aggregator context. The 'auto' and 'since-last-run' modes
		// require hash files which are per-module.
		String effectiveMode = ("auto".equalsIgnoreCase(changeMode) || "since-last-run".equalsIgnoreCase(changeMode))
				? "uncommitted"
				: changeMode;
		Set<String> result = new LinkedHashSet<>();
		for (MavenProject p : session.getProjects()) {
			if ("pom".equals(p.getPackaging()))
				continue;
			List<String> roots = p.getCompileSourceRoots();
			Path srcRoot = (roots != null && !roots.isEmpty())
					? Path.of(roots.get(0))
					: p.getBasedir().toPath().resolve("src/main/java");
			if (!Files.isDirectory(srcRoot))
				continue;
			// Pass null for changedClasses to avoid short-circuiting git detection.
			// ChangeDetectionSupport.detectChangedClasses immediately returns explicit
			// classes when non-null, skipping git diff entirely.
			Set<String> detected = me.bechberger.testorder.ops.ChangeDetectionOps.detectChangedClasses(effectiveMode,
					ctx.gitRoot(), srcRoot, null, null, true, pluginLog());
			result.addAll(detected);
		}
		// Merge explicitly specified changed classes (from -Dtestorder.changed.classes)
		if (changedClasses != null && !changedClasses.isBlank()) {
			for (String cls : changedClasses.split(",")) {
				String trimmed = cls.trim();
				if (!trimmed.isEmpty()) {
					result.add(trimmed);
				}
			}
		}
		return result;
	}

	/**
	 * Detects changed test classes across ALL reactor modules.
	 */
	private Set<String> detectChangedTestClassesAllModules() {
		String effectiveMode = ("auto".equalsIgnoreCase(changeMode) || "since-last-run".equalsIgnoreCase(changeMode))
				? "uncommitted"
				: changeMode;
		Set<String> result = new LinkedHashSet<>();
		for (MavenProject p : session.getProjects()) {
			if ("pom".equals(p.getPackaging()))
				continue;
			List<String> roots = p.getTestCompileSourceRoots();
			Path testRoot = (roots != null && !roots.isEmpty())
					? Path.of(roots.get(0))
					: p.getBasedir().toPath().resolve("src/test/java");
			if (!Files.isDirectory(testRoot))
				continue;
			// Pass null for explicitChangedTestClasses to avoid per-iteration merging.
			Set<String> detected = me.bechberger.testorder.ops.ChangeDetectionOps
					.detectChangedTestClasses(effectiveMode, ctx.gitRoot(), testRoot, null, null, true, pluginLog());
			result.addAll(detected);
		}
		// Merge explicitly specified changed test classes
		if (changedTestClasses != null && !changedTestClasses.isBlank()) {
			for (String cls : changedTestClasses.split(",")) {
				String trimmed = cls.trim();
				if (!trimmed.isEmpty()) {
					result.add(trimmed);
				}
			}
		}
		return result;
	}
}
