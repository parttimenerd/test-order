package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import me.bechberger.testorder.AgentArgsBuilder;
import me.bechberger.testorder.PackageDetectorSupport;
import me.bechberger.testorder.changes.BytecodeHashStore;
import me.bechberger.testorder.changes.ChangeDetectionSupport;
import me.bechberger.testorder.changes.ChangeDetector;
import me.bechberger.testorder.changes.SelectiveLearnSupport;
import me.bechberger.testorder.changes.UncertainClassesStore;
import me.bechberger.testorder.ops.AggregateOperation;
import me.bechberger.testorder.ops.ChangeDetectionOps;
import me.bechberger.testorder.ops.HashSnapshotOperation;
import me.bechberger.testorder.ops.PluginContext;

/**
 * Shared learn-mode workflow: resolves include packages, builds agent args,
 * snapshots hashes, and aggregates dependency files.
 * <p>
 * Plugins call this and then apply the result to their framework-specific test
 * runner (Surefire argLine / Gradle jvmArgs).
 */
public final class LearnWorkflow {

	private LearnWorkflow() {
	}

	/**
	 * Result of learn-mode setup. Contains everything plugins need to configure
	 * their test runner for instrumentation.
	 */
	public record LearnSetupResult(
			/** The resolved agent arguments string (for {@code -javaagent:jar=args}). */
			String agentArgs,
			/** System properties to set on the forked test JVM. */
			Map<String, String> systemProperties,
			/** Additional JVM args (e.g. {@code -Xshare:off}). */
			List<String> jvmArgs,
			/** The resolved effective include-packages string (may be null). */
			String effectiveIncludePackages) {
	}

	/**
	 * Prepares learn-mode configuration. Does not modify any framework state —
	 * returns a result that the plugin applies.
	 *
	 * @param ctx
	 *            plugin context with all paths and config
	 * @param includeIndexInArgs
	 *            whether to pass the index file path to the agent
	 * @return setup result for the plugin to apply
	 */
	public static LearnSetupResult setup(PluginContext ctx, boolean includeIndexInArgs) {
		String effectiveInclude = ctx.includePackages();
		if (effectiveInclude == null || effectiveInclude.isBlank()) {
			effectiveInclude = PackageDetectorSupport.resolveIncludePackages(ctx.sourceRoot(), null,
					ctx.filterByGroupId(), ctx.groupId());
		}

		String agentArgs = AgentArgsBuilder.buildArgs(ctx.depsDir(), ctx.instrumentationMode(),
				includeIndexInArgs ? ctx.indexFile() : null, effectiveInclude,
				ctx.verboseFile() != null ? ctx.verboseFile().toString() : null);

		Map<String, String> sysProps = new LinkedHashMap<>();
		sysProps.put("testorder.learn", "true");
		sysProps.put("testorder.instrumentation.mode", ctx.instrumentationMode().toUpperCase());
		sysProps.put("testorder.state.path", ctx.stateFile().toAbsolutePath().toString());

		if (ctx.selectiveLearn() && ctx.sourceRoot() != null && ctx.classesDir() != null && ctx.depsDir() != null) {
			// Skip selective instrumentation on first learn run (no index yet) — there is
			// no previous dependency data to prune against, so full instrumentation is
			// required to build a useful index.
			boolean indexExists = ctx.indexFile() != null && java.nio.file.Files.exists(ctx.indexFile());
			// Use resolveMode so that changeMode=auto correctly picks SINCE_LAST_RUN vs
			// SINCE_LAST_COMMIT based on whether a hash snapshot already exists.
			ChangeDetector.Mode changeMode;
			try {
				changeMode = ChangeDetectionSupport.resolveMode(ctx.changeMode(), ctx.hashFile());
			} catch (IOException e) {
				ctx.log().warn("[test-order] IOException resolving change mode, falling back to UNCOMMITTED: "
						+ e.getMessage());
				changeMode = ChangeDetector.Mode.UNCOMMITTED;
			}
			// Use repoRoot (git root) if available so cross-module changes are detected;
			// fall back to module projectRoot so the diff is scoped to this module.
			Path changeRoot = ctx.repoRoot() != null ? ctx.repoRoot() : ctx.projectRoot();
			SelectiveLearnSupport.StaticAnalysisData saData = indexExists
					? SelectiveLearnSupport.computeStaticAnalysisData(changeRoot, ctx.classesDir(), changeMode)
					: null;
			Set<String> uncertain = saData != null ? saData.uncertainClasses() : null;
			if (uncertain != null) {
				// Namespace the file by module ID to avoid races in multi-module reactor
				// builds where each module runs learn in parallel.
				String moduleId = ctx.currentModuleId();
				String fname = (moduleId == null || moduleId.isBlank())
						? "uncertain-classes.txt"
						: "uncertain-classes-" + moduleId.replaceAll("[^a-zA-Z0-9._-]", "_") + ".txt";
				Path uncertainFile = ctx.depsDir().resolve(fname);
				try {
					UncertainClassesStore.save(uncertainFile, uncertain);
					me.bechberger.testorder.changes.StaticAnalysisDataStore.save(
							me.bechberger.testorder.changes.StaticAnalysisDataStore.sidecarPath(uncertainFile), saData);
					sysProps.put("testorder.learn.uncertainClassesFile", uncertainFile.toAbsolutePath().toString());
					if (uncertain.isEmpty()) {
						ctx.log().info("[test-order] Selective learn: no source changes detected; "
								+ "no classes will be instrumented this run");
					} else {
						ctx.log().info("[test-order] Selective learn: " + uncertain.size()
								+ " uncertain class(es) will be instrumented");
					}
				} catch (IOException e) {
					ctx.log().warn(
							"[test-order] Selective learn: failed to write uncertain-classes file: " + e.getMessage());
				}
			} else {
				ctx.log().info(
						"[test-order] Selective learn: no existing index — using full instrumentation for initial run");
			}
		}

		List<String> jvmArgs = List.of("--enable-native-access=ALL-UNNAMED");

		if (effectiveInclude != null) {
			ctx.log().info("[test-order] Instrumentation packages: " + effectiveInclude);
		}
		ctx.log().info("[test-order] Learn mode (" + ctx.instrumentationMode().toUpperCase() + "): attaching agent");

		return new LearnSetupResult(agentArgs, sysProps, jvmArgs, effectiveInclude);
	}

	/**
	 * Snapshots source and test file hashes for future SINCE_LAST_RUN change
	 * detection.
	 */
	public static void snapshotHashes(PluginContext ctx) {
		HashSnapshotOperation.snapshot(ctx.sourceRoot(), ctx.hashFile(), ctx.testSourceRoot(), ctx.testHashFile(),
				(label, path) -> ctx.log().info("[test-order] Saved " + label + " hash snapshot: " + path),
				(label, msg) -> ctx.log().warn("[test-order] Failed to save " + label + " hash snapshot: " + msg));

		if (ctx.methodOrderingEnabled() && ctx.methodHashFile() != null) {
			ChangeDetectionOps.snapshotMethodHashes(ctx.testSourceRoot(), ctx.methodHashFile(), ctx.log());
		}

		// Seed the bytecode hash baseline so the next change-detecting run (or
		// diagnostic show-static-analysis) can compute method-level differences
		// instead of falling back to class-level. Without this, the first SA run
		// after learn sees no baseline and seeds only `<class>` markers.
		if (ctx.bytecodeChangeDetectionEnabled() && ctx.classesDir() != null && ctx.bytecodeHashFile() != null
				&& java.nio.file.Files.isDirectory(ctx.classesDir())) {
			try {
				BytecodeHashStore curr = BytecodeHashStore.scan(ctx.classesDir());
				curr.save(ctx.bytecodeHashFile());
				ctx.log().info("[test-order] Saved bytecode hash snapshot: " + ctx.bytecodeHashFile());
			} catch (IOException e) {
				ctx.log().warn("[test-order] Failed to save bytecode hash snapshot: " + e.getMessage());
			}
		}
	}

	/**
	 * Aggregates .deps files into the binary dependency index.
	 *
	 * <p>
	 * When {@link me.bechberger.testorder.ops.PluginContext#selectiveLearn()} is
	 * {@code true}, uses incremental (merge) semantics: the existing index is
	 * loaded and only the newly-recorded test deps are unioned in, preserving
	 * dependency data for tests that were not re-instrumented this run. When
	 * {@code false}, the full .deps directory replaces the index.
	 *
	 * @return the aggregation result, or {@code null} if no deps directory exists
	 */
	public static AggregateOperation.Result aggregate(PluginContext ctx) throws java.io.IOException {
		if (ctx.depsDir() == null || !java.nio.file.Files.isDirectory(ctx.depsDir())) {
			return null;
		}
		return AggregateOperation.aggregate(ctx.depsDir(), ctx.indexFile(), ctx.log(), ctx.selectiveLearn());
	}
}
