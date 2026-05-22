package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import me.bechberger.testorder.agent.Agent;
import me.bechberger.testorder.agent.OfflineInstrumentor;
import me.bechberger.testorder.agent.runtime.ClassIdMapping;

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
	 * Instrumentation mode: CLASS (default), METHOD, or MEMBER.
	 */
	@Parameter(property = MavenPluginConfigKeys.INSTRUMENTATION_MODE, defaultValue = "CLASS")
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

		try {
			OfflineInstrumentor instrumentor = new OfflineInstrumentor(mode, includes, excludes);
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
