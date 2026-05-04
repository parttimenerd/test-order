package me.bechberger.testorder.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Displays help information about the test-order plugin goals and common
 * configuration properties.
 * <p>
 * Usage: {@code mvn test-order:help}
 */
@Mojo(name = "help", requiresProject = false)
public class HelpMojo extends AbstractMojo {

	@Override
	public void execute() throws MojoExecutionException {
		StringBuilder sb = new StringBuilder();
		sb.append("\ntest-order-maven-plugin — Test prioritisation and selection for Maven\n\n");
		sb.append("Goals:\n");
		sb.append("  prepare          Configure Surefire for learn or order mode (bound to process-test-classes)\n");
		sb.append("  auto             Auto local dev mode: learn if needed, then run selected subset\n");
		sb.append("  learn            Explicitly run in learn mode: instrument tests and build dependency index\n");
		sb.append("  select           Select a fast CI subset (top-n + random diverse fast tests)\n");
		sb.append("  run-remaining    Run the tests deferred by a previous select invocation\n");
		sb.append("  show-order       Display the current test priority order as a table\n");
		sb.append("  dashboard        Generate an interactive HTML dashboard\n");
		sb.append("  serve            Generate dashboard and serve it via a local HTTP server\n");
		sb.append("  optimize         Optimise scoring weights via hill-climbing over run history\n");
		sb.append("  snapshot         Save source/test file hash snapshots for change detection\n");
		sb.append("  aggregate        Aggregate per-test .deps files into a single dependency index\n");
		sb.append("  dump             Dump the raw dependency index contents\n");
		sb.append("  help             Display this help information\n");
		sb.append("\nCommon properties:\n");
		sb.append("  -Dtestorder.mode=<mode>          auto (default), learn, order, skip\n");
		sb.append(
				"  -Dtestorder.select.topN=<n>      Number of top-scored tests to select (default: -1, all affected)\n");
		sb.append("  -Dtestorder.select.randomM=<m>   Random fast tests for coverage diversity (default: 10)\n");
		sb.append(
				"  -Dtestorder.autoLearnRunThreshold=<n>  Force full learn every N runs in auto mode (default: 10)\n");
		sb.append("  -Dtestorder.includePackages=<p>   Additional package prefixes to instrument\n");
		sb.append("  -Dtestorder.skip=true             Skip the plugin entirely\n");
		sb.append("\nTypical usage:\n");
		sb.append("  First run:   mvn clean test                    (auto-learns dependency index)\n");
		sb.append("  Next runs:   mvn clean test                    (auto-orders by priority)\n");
		sb.append("  CI fast:     mvn test-order:select test        (run only high-priority subset)\n");
		sb.append("  CI rest:     mvn test-order:run-remaining test (run deferred tests)\n");
		sb.append("  Dashboard:   mvn test-order:dashboard          (generate HTML report)\n");
		sb.append("  View order:  mvn test-order:show-order         (print priority table)\n");

		getLog().info(sb.toString());
	}
}
