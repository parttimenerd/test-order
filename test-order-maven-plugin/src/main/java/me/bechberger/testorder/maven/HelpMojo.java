package me.bechberger.testorder.maven;

import java.util.Map;

import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestOrderState.WeightDef;
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

	/** Property name mapping for score weights (weight name → description). */
	private static final Map<String, String> SCORE_DESCRIPTIONS = Map.of(
			"newTest", "Weight for new/unseen tests",
			"changedTest", "Weight for changed test classes",
			"maxFailure", "Weight for recently failed tests",
			"speed", "Weight favouring fast tests",
			"speedPenalty", "Weight penalising slow tests",
			"depOverlap", "Weight for dependency overlap with changes",
			"changeComplexity", "Weight for source-change complexity",
			"staticFieldBonus", "Weight for changed static field overlap",
			"coverageBonus", "Weight for greedy set-cover bonus");

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
		sb.append("  tiered-select    Select tests into tier1/tier2/tier3 CI phases\n");
		sb.append("  run-tier         Run tier 2 or tier 3 from a previous tiered-select run\n");
		sb.append("  show-order       Display the current test priority order as a table\n");
		sb.append("  show-method-order  Display method-level priority order within each test class\n");
		sb.append("  dashboard        Generate an interactive HTML dashboard\n");
		sb.append("  serve            Generate dashboard and serve it via a local HTTP server\n");
		sb.append("  optimize         Optimise scoring weights via hill-climbing over run history\n");
		sb.append("  snapshot         Save source/test file hash snapshots for change detection\n");
		sb.append("  aggregate        Aggregate per-test .deps files into a single dependency index\n");
		sb.append("  dump             Dump the raw dependency index contents\n");
		sb.append("  export-json      Export dependency index as JSON\n");
		sb.append("  diagnose         Run diagnostic checks on plugin configuration and state\n");
		sb.append("  compact          Rebuild dependency index from .deps files (remove stale entries)\n");
		sb.append("  clean            Remove all test-order state, indexes, and hashes\n");
		sb.append("  download         Download dependency index from CI artifact store\n");
		sb.append("  coverage         Analyse dependency index and identify least-tested classes\n");
		sb.append("  metrics          Export test-order metrics as JSON for CI/CD dashboards\n");
		sb.append("  help             Display this help information\n");
		sb.append("\nCommon properties:\n");
		sb.append("  -Dtestorder.mode=<mode>          auto (default), learn, order, skip\n");
		sb.append("  -Dtestorder.changeMode=<mode>    uncommitted (default), since-last-run, since-last-commit, explicit\n");
		sb.append("  -Dtestorder.instrumentation.mode=<m>  FULL (default), METHOD_ENTRY, FULL_METHOD, FULL_MEMBER\n");
		sb.append(
				"  -Dtestorder.select.topN=<n>      Number of top-scored tests to select (default: -1, all affected)\n");
		sb.append("  -Dtestorder.select.randomM=<m>   Random fast tests for coverage diversity (default: 10)\n");
		sb.append(
				"  -Dtestorder.autoLearnRunThreshold=<n>  Force full learn every N runs in auto mode (default: 10)\n");
		sb.append(
				"  -Dtestorder.auto.optimizeEvery=<n>     Auto-optimise weights every N runs (default: 10)\n");
		sb.append(
				"  -Dtestorder.autoCompactEvery=<n>       Auto-compact index every N runs (default: 50)\n");
		sb.append(
				"  -Dtestorder.tiered.tier2Fraction=<f>  Tier-2 duration/count fraction in [0,1] (default: 0.5)\n");
		sb.append(
				"  -Dtestorder.tiered.weightByDuration=<b>  Tier-2 by duration budget (default: true)\n");
		sb.append(
				"  -Dtestorder.tiered.currentTier=<n>   Tier to run with run-tier (2 or 3)\n");
		sb.append(
				"  -Dtestorder.dashboard.serveSeconds=<n>  Stop 'serve' automatically after N seconds (default: 0)\n");
		sb.append("  -Dtestorder.includePackages=<p>   Additional package prefixes to instrument\n");
		sb.append("  -Dtestorder.skip=true             Skip the plugin entirely\n");
		sb.append("  -Dtestorder.debug=true            Verbose scoring output\n");
		sb.append("  -Dtestorder.failOnError=true      Fail build on diagnostic errors\n");
		sb.append("\nScore tuning (integer weights, override via -D):\n");
		for (WeightDef wd : TestOrderState.WEIGHT_DEFS) {
			String desc = SCORE_DESCRIPTIONS.getOrDefault(wd.name(), "Scoring weight");
			String prop = "testorder.score." + wd.name();
			sb.append(String.format("  %-38s %s (default: %d)\n", prop, desc, wd.defaultValue()));
		}
		sb.append("\nInvocation fallback:\n");
		sb.append("  If Maven cannot resolve the 'test-order' prefix in this project, use:\n");
		sb.append("  mvn me.bechberger:test-order-maven-plugin:<version>:auto test\n");
		sb.append("\nProperty precedence:\n");
		sb.append("  CLI system properties (-D) always override POM <configuration> values.\n");
		sb.append("  Example: -Dtestorder.mode=learn overrides <mode>order</mode> in your POM.\n");
		sb.append("\nTypical usage:\n");
		sb.append("  First run:   mvn clean test                    (auto-learns dependency index)\n");
		sb.append("  Next runs:   mvn clean test                    (auto-orders by priority)\n");
		sb.append("  CI fast:     mvn test-order:select test        (run only high-priority subset)\n");
		sb.append("  CI rest:     mvn test-order:run-remaining test (run deferred tests)\n");
		sb.append("  CI tiered:   mvn test-order:tiered-select test (run tier-1 first)\n");
		sb.append("               mvn test-order:run-tier test -Dtestorder.tiered.currentTier=2\n");
		sb.append("               mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3\n");
		sb.append("  Dashboard:   mvn test-order:dashboard          (generate HTML report)\n");
		sb.append("  View order:  mvn test-order:show-order         (print priority table)\n");
		sb.append("  Diagnose:    mvn test-order:diagnose           (check setup health)\n");

		getLog().info(sb.toString());
	}
}
