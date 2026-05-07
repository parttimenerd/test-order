package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.ops.OrdererConfigOperation;
import me.bechberger.testorder.ops.PluginContext;

/**
 * Order-mode workflow: detects changes, resolves weights, and builds the
 * PriorityClassOrderer configuration map.
 *
 * <p>
 * Plugins apply the returned config map as Surefire properties or Gradle system
 * properties.
 */
public final class OrderWorkflow {

	private OrderWorkflow() {
	}

	/** Result of order-mode setup. */
	public record OrderSetupResult(Map<String, String> configMap, Set<String> changedClasses, Set<String> changedTests,
			Set<String> changedMethods, TestOrderState.ScoringWeights weights) {
	}

	/**
	 * Performs full order-mode setup.
	 *
	 * @param ctx
	 *            plugin context
	 * @param state
	 *            loaded test-order state (may be a fresh instance)
	 * @return setup result for the plugin to apply
	 */
	public static OrderSetupResult setup(PluginContext ctx, TestOrderState state) throws IOException {
		ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		Map<String, String> configMap = OrdererConfigOperation.buildConfig(new OrdererConfigOperation.OrdererInput(
				ctx.indexFile().toAbsolutePath().toString(), ctx.stateFile().toAbsolutePath().toString(),
				ctx.weightsFile() != null ? ctx.weightsFile().toAbsolutePath().toString() : null, a.changedClasses(),
				a.changedTests(), a.changedMethods(), ctx.scoreOverrides(), ctx.methodOrderingEnabled(),
				ctx.springContextGrouping(), ctx.projectRoot().toAbsolutePath().toString(),
				ctx.sourceRoot() != null ? ctx.sourceRoot().toAbsolutePath().toString() : null, ctx.changeMode()));

		if (a.changedClasses().isEmpty() && a.changedTests().isEmpty()) {
			ctx.log().info("[test-order] No changed classes detected — running tests in default order.");
		} else {
			if (!a.changedClasses().isEmpty()) {
				ctx.log().info("[test-order] Detected " + a.changedClasses().size() + " changed source classes");
			}
			if (!a.changedTests().isEmpty()) {
				ctx.log().info("[test-order] Detected " + a.changedTests().size() + " changed test classes");
			}
		}

		return new OrderSetupResult(configMap, a.changedClasses(), a.changedTests(), a.changedMethods(), a.weights());
	}
}
