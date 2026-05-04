package me.bechberger.testorder.ops.workflows;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.bechberger.testorder.AgentArgsBuilder;
import me.bechberger.testorder.PackageDetectorSupport;
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

		List<String> jvmArgs = List.of("-Xshare:off", "--enable-native-access=ALL-UNNAMED");

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
	}

	/**
	 * Aggregates .deps files into the binary dependency index.
	 *
	 * @return the aggregation result, or {@code null} if no deps directory exists
	 */
	public static AggregateOperation.Result aggregate(PluginContext ctx) throws java.io.IOException {
		if (ctx.depsDir() == null || !java.nio.file.Files.isDirectory(ctx.depsDir())) {
			return null;
		}
		return AggregateOperation.aggregate(ctx.depsDir(), ctx.indexFile(), ctx.log());
	}
}
