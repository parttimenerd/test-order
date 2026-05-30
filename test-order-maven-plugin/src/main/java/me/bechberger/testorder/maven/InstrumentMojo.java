package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import me.bechberger.testorder.agent.Agent;
import me.bechberger.testorder.agent.OfflineInstrumentor;
import me.bechberger.testorder.agent.runtime.ClassIdMapping;
import me.bechberger.testorder.changes.SelectiveLearnSupport;

/**
 * Instruments compiled application classes at build time (offline mode).
 * <p>
 * This goal transforms {@code target/classes} in-place, inserting the same
 * usage-recording bytecode that the Java agent would inject at class-load time.
 * The advantage is that forked test JVMs load pre-instrumented classes directly
 * — no agent overhead, no per-fork transformation cost.
 * <p>
 * Produces a class-id mapping file at
 * {@code target/.test-order/class-id-map.bin} which is loaded by the test
 * runtime to configure UsageStore.
 * <p>
 * Usage:
 *
 * <pre>
 * mvn compile test-order:instrument test -Dtestorder.mode=learn -Dtestorder.instrumentation=offline
 * </pre>
 */
@Mojo(name = "instrument", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class InstrumentMojo extends AbstractTestOrderMojo {

	/**
	 * Comma-separated package prefixes to instrument (dot-separated). If empty,
	 * auto-detection from source directories is used.
	 */
	@Parameter(property = MavenPluginConfigKeys.INCLUDE_PACKAGES)
	private String includePackages;

	/**
	 * Instrumentation mode: MEMBER (default), CLASS, or METHOD.
	 */
	@Parameter(property = MavenPluginConfigKeys.INSTRUMENTATION_MODE, defaultValue = "MEMBER")
	private String instrumentationMode;

	/**
	 * When true (default) and no source packages are detected, fall back to the
	 * project groupId as an instrumentation filter.
	 */
	@Parameter(property = MavenPluginConfigKeys.FILTER_BY_GROUP_ID, defaultValue = "true")
	private boolean filterByGroupId;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		if ("pom".equals(project.getPackaging())) {
			getLog().debug("[test-order] Skipping instrument — POM module.");
			return;
		}

		Path classesDir = Path.of(project.getBuild().getOutputDirectory());
		if (!Files.isDirectory(classesDir)) {
			getLog().warn("[test-order] No classes directory at " + classesDir + " — run 'compile' first.");
			return;
		}

		// Resolve effective packages
		String effectiveInclude = resolveIncludePackages(includePackages, filterByGroupId, project, getLog());
		List<String> includes = parsePackageList(effectiveInclude);
		List<String> excludes = List.of(); // could be made configurable later

		// Parse instrumentation mode
		Agent.InstrumentationMode mode;
		try {
			mode = Agent.InstrumentationMode.fromString(instrumentationMode);
		} catch (IllegalArgumentException e) {
			throw new MojoExecutionException("Invalid instrumentationMode: " + instrumentationMode);
		}

		getLog().info("[test-order] Offline instrumentation (" + mode + "): " + classesDir);
		getLog().info("[test-order] Packages: " + (includes.isEmpty() ? "(all)" : String.join(", ", includes)));

		// Selective learn: compute uncertain classes if enabled, but only when an
		// existing index is present. On the first learn run there is no index to
		// prune against, so fall back to full instrumentation.
		Set<String> uncertainClasses = null;
		me.bechberger.testorder.changes.SelectiveLearnSupport.StaticAnalysisData saData = null;
		if (selectiveLearn) {
			Path idxPath = ctx != null ? ctx.resolveIndexFile(indexFile) : Path.of(indexFile);
			boolean indexExists = java.nio.file.Files.exists(idxPath);
			if (indexExists) {
				me.bechberger.testorder.changes.ChangeDetector.Mode changeDetectorMode;
				try {
					Path hf = ctx != null ? ctx.resolveHashFile(hashFile) : null;
					changeDetectorMode = me.bechberger.testorder.changes.ChangeDetectionSupport.resolveMode(changeMode,
							hf);
				} catch (IOException e) {
					changeDetectorMode = me.bechberger.testorder.changes.ChangeDetector.Mode.UNCOMMITTED;
				}
				// Use git root (reactor root in multi-module) so cross-module changes are
				// detected
				Path projectRoot = ctx != null ? ctx.gitRoot() : project.getBasedir().toPath();
				saData = SelectiveLearnSupport.computeStaticAnalysisData(projectRoot, classesDir, changeDetectorMode);
				uncertainClasses = saData != null ? saData.uncertainClasses() : null;
				if (uncertainClasses != null && !uncertainClasses.isEmpty()) {
					getLog().info("[test-order] Selective instrument: " + uncertainClasses.size()
							+ " uncertain class(es) will be instrumented");
				} else if (uncertainClasses != null) {
					getLog().info(
							"[test-order] Selective instrument: no source changes detected; skipping instrumentation");
				}
			} else {
				getLog().info(
						"[test-order] Selective instrument: no existing index — using full instrumentation for initial run");
			}
		}

		// Write uncertain-classes.txt for dashboard Static Analysis tab
		if (uncertainClasses != null) {
			String mid = computeCurrentModuleId();
			String fname = (mid == null || mid.isBlank())
					? "uncertain-classes.txt"
					: "uncertain-classes-" + mid.replaceAll("[^a-zA-Z0-9._-]", "_") + ".txt";
			try {
				Path depsDirPath = ctx != null ? ctx.resolveDepsDir(depsDir) : Path.of(depsDir);
				Path uncertainFile = depsDirPath.resolve(fname);
				me.bechberger.testorder.changes.UncertainClassesStore.save(uncertainFile, uncertainClasses);
				if (saData != null) {
					me.bechberger.testorder.changes.StaticAnalysisDataStore.save(
							me.bechberger.testorder.changes.StaticAnalysisDataStore.sidecarPath(uncertainFile), saData);
				}
			} catch (IOException e2) {
				getLog().debug("[test-order] Could not write uncertain-classes file: " + e2.getMessage());
			}
		}

		try {
			OfflineInstrumentor instrumentor = new OfflineInstrumentor(mode, includes, excludes, uncertainClasses);
			ClassIdMapping mapping = instrumentor.instrument(classesDir);

			// Write mapping file
			Path mappingDir = Path.of(project.getBuild().getDirectory(), ".test-order");
			Path mappingFile = mappingDir.resolve("class-id-map.bin");
			mapping.save(mappingFile);

			getLog().info("[test-order] Instrumented " + instrumentor.getTransformedCount() + " classes" + " (skipped "
					+ instrumentor.getSkippedCount() + ")" + ", mapping: " + mappingFile);
		} catch (IOException e) {
			throw new MojoExecutionException("Offline instrumentation failed", e);
		}
	}

	private static List<String> parsePackageList(String packages) {
		if (packages == null || packages.isBlank())
			return List.of();
		List<String> result = new ArrayList<>();
		for (String pkg : packages.split(",")) {
			String trimmed = pkg.trim();
			if (!trimmed.isEmpty()) {
				result.add(trimmed);
			}
		}
		return result;
	}
}
