package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import me.bechberger.testorder.ExplainEntry;
import me.bechberger.testorder.OrderReportPrinter;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.workflows.ShowOrderWorkflow;
import me.bechberger.testorder.ops.workflows.ShowOrderWorkflow.ShowOrderResult;

/**
 * Prints a detailed per-test score breakdown for the given changed-class set.
 * <p>
 * Usage:
 *
 * <pre>
 * # Explain top-10 tests for the given changed class:
 * mvn test-order:explain -Dtestorder.changed.classes=com.example.Foo
 *
 * # Explain a specific test:
 * mvn test-order:explain \
 *     -Dtestorder.changed.classes=com.example.Foo \
 *     -Dtestorder.explain.test=com.example.FooTest
 * </pre>
 */
@Mojo(name = "explain", defaultPhase = org.apache.maven.plugins.annotations.LifecyclePhase.VALIDATE, aggregator = true, requiresProject = true, threadSafe = true)
public class ExplainMojo extends AbstractTestOrderMojo {

	/**
	 * Fully-qualified name of the single test to explain. If omitted, show top-N.
	 */
	@Parameter(property = "testorder.explain.test")
	private String explainTest;

	/**
	 * How many top-scored tests to explain when {@code testorder.explain.test} is
	 * not set.
	 */
	@Parameter(property = "testorder.explain.topN", defaultValue = "10")
	private int explainTopN;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip) {
			getLog().info("[test-order] Skipping explain (skip=true)");
			return;
		}

		Path idxPath = resolveIndexPath();
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}

		PluginContext pctx = buildPluginContext();

		try {
			ShowOrderResult result = ShowOrderWorkflow.compute(pctx);

			if (result.ranked().isEmpty()) {
				getLog().warn("[test-order] No tests found in dependency index. Run learn first.");
				return;
			}

			if (explainTest != null && !explainTest.isBlank()) {
				// Find rank of the requested test
				int rank = 1;
				for (OrderReportPrinter.RankedTest rt : result.ranked()) {
					if (rt.name().equals(explainTest)) {
						break;
					}
					rank++;
				}
				if (rank > result.ranked().size()) {
					getLog().warn("[test-order] Test '" + explainTest + "' not found in ranked list."
							+ " It may not be in the dependency index or test output directory.");
					return;
				}
				ExplainEntry entry = result.scorer().explain(explainTest, rank);
				getLog().info("\n" + entry.format());
			} else {
				// Print top-N explains
				List<OrderReportPrinter.RankedTest> topN = result.ranked().stream().limit(explainTopN).toList();
				for (int i = 0; i < topN.size(); i++) {
					ExplainEntry entry = result.scorer().explain(topN.get(i).name(), i + 1);
					getLog().info("\n" + entry.format());
				}
			}
		} catch (IOException e) {
			throw new MojoExecutionException("explain failed: " + e.getMessage(), e);
		}
	}
}
