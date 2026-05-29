package me.bechberger.testorder.maven;

import java.util.Map;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestOrderState.WeightDef;

/**
 * Displays help information about the test-order plugin goals and common
 * configuration properties.
 * <p>
 * Usage: {@code mvn test-order:help}
 */
@Mojo(name = "help", requiresProject = false)
public class HelpMojo extends AbstractMojo {

	/** Property name mapping for score weights (weight name → description). */
	private static final Map<String, String> SCORE_DESCRIPTIONS = Map.ofEntries(
			Map.entry("newTest", "Weight for new/unseen tests"),
			Map.entry("changedTest", "Weight for changed test classes"),
			Map.entry("maxFailure", "Weight for recently failed tests"),
			Map.entry("speed", "Weight favouring fast tests"),
			Map.entry("speedPenalty", "Weight penalising slow tests"),
			Map.entry("depOverlap", "Weight for dependency overlap with changes"),
			Map.entry("changeComplexity", "Weight for source-change complexity"),
			Map.entry("staticFieldBonus", "Weight for changed static field overlap"),
			Map.entry("coverageBonus", "Weight for greedy set-cover bonus"),
			Map.entry("killRateBonus", "Bonus scaled by mutation kill rate (requires analyze-mutations data)"));

	@Override
	public void execute() throws MojoExecutionException {
		StringBuilder sb = new StringBuilder();
		sb.append("\ntest-order-maven-plugin — Test prioritisation and selection for Maven\n\n");
		sb.append("Goals:\n");
		sb.append("  prepare          Configure Surefire for learn or order mode (bound to process-test-classes)\n");
		sb.append("  auto             Auto local dev mode: learn if needed, then run selected subset\n");
		sb.append("  learn            Explicitly run in learn mode: instrument tests and build dependency index\n");
		sb.append("  instrument       Offline instrumentation: transform classes at build time (no agent needed)\n");
		sb.append("  select           Select a fast CI subset (top-n + random diverse fast tests)\n");
		sb.append("  run-remaining    Run the tests deferred by a previous select invocation\n");
		sb.append("  tiered-select    Select tests into tier1/tier2/tier3 CI phases\n");
		sb.append("  run-tier         Run tier 2 or tier 3 from a previous tiered-select run\n");
		sb.append("  show             Unified view: class order, method order, and ML health\n");
		sb.append("  show-order       (deprecated) Use 'show' instead\n");
		sb.append("  show-method-order  (deprecated) Use 'show' instead\n");
		sb.append("  analyze          (deprecated) Use 'show -Dtestorder.show.ml=true' instead\n");
		sb.append("  explain          Explain why a specific test class is ranked where it is\n");
		sb.append("  show-static-analysis  Show static call-graph analysis details (verbose output)\n");
		sb.append("  analyze-mutations  Run mutation testing and record kill-rate data for scoring\n");
		sb.append("  reactor-order    Compute optimal module execution order for multi-module builds\n");
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
		sb.append("  detect-dependencies  Detect order-dependent tests via reordering strategies\n");
		sb.append("  download         Download dependency index from CI artifact store\n");
		sb.append("  coverage         Analyse dependency index and identify least-tested classes\n");
		sb.append("  metrics          Export test-order metrics as JSON for CI/CD dashboards\n");
		sb.append("  help             Display this help information\n");
		sb.append("\nCommon properties:\n");
		sb.append("  -Dtestorder.mode=<mode>          auto (default), learn, order, skip\n");
		sb.append(
				"  -Dtestorder.changeMode=<mode>    uncommitted (default), since-last-run, since-last-commit, explicit\n");
		sb.append("  -Dtestorder.instrumentation.mode=<m>  CLASS (default), METHOD, MEMBER\n");
		sb.append(
				"  -Dtestorder.select.topN=<n>      Number of top-scored tests to select (default: -1, all affected)\n");
		sb.append("  -Dtestorder.select.randomM=<m>   Random fast tests for coverage diversity (default: 10)\n");
		sb.append(
				"  -Dtestorder.autoLearnRunThreshold=<n>  Force full learn every N runs in auto mode (default: 10)\n");
		sb.append("  -Dtestorder.auto.optimizeEvery=<n>     Auto-optimise weights every N runs (default: 10)\n");
		sb.append("  -Dtestorder.autoCompactEvery=<n>       Auto-compact index every N runs (default: 50)\n");
		sb.append("  -Dtestorder.tiered.tier2Fraction=<f>  Tier-2 duration/count fraction in [0,1] (default: 0.5)\n");
		sb.append("  -Dtestorder.tiered.weightByDuration=<b>  Tier-2 by duration budget (default: true)\n");
		sb.append("  -Dtestorder.tiered.currentTier=<n>   Tier to run with run-tier (2 or 3)\n");
		sb.append(
				"  -Dtestorder.dashboard.serveSeconds=<n>  Stop 'serve' automatically after N seconds (default: 0)\n");
		sb.append(
				"  -Dtestorder.dashboard.port=<n>       Port for 'serve'/'dashboard' goals (default: 0 = auto-select)\n");
		sb.append("  -Dtestorder.includePackages=<p>   Additional package prefixes to instrument\n");
		sb.append("  -Dtestorder.skip=true             Skip the plugin entirely\n");
		sb.append("  -Dtestorder.debug=true            Verbose scoring output\n");
		sb.append("  -Dtestorder.failOnError=true      Fail build on diagnostic errors\n");
		sb.append("\nDetection properties (detect-dependencies goal):\n");
		sb.append("  -Dtestorder.detect.algorithm=<a>    combined (default), reverse, random, history,\n");
		sb.append("                                      pfast, iterative, bounded, tuscan\n");
		sb.append("  -Dtestorder.detect.timeBudget=<s>   Time budget in seconds (default: 300, 0=unlimited)\n");
		sb.append("  -Dtestorder.detect.stopOnFirst=<b>  Stop after first finding (default: false)\n");
		sb.append("  -Dtestorder.detect.seed=<n>         Random seed for reproducibility (default: 42)\n");
		sb.append("  -Dtestorder.detect.failOnDetection=<b>  Fail build if ODs found (default: false)\n");
		sb.append("\nScore tuning (integer weights, override via -D):\n");
		for (WeightDef wd : TestOrderState.WEIGHT_DEFS) {
			String desc = SCORE_DESCRIPTIONS.getOrDefault(wd.name(), "Scoring weight");
			String prop = "testorder.score." + wd.name();
			sb.append(String.format("  %-38s %s (default: %d)\n", prop, desc, wd.defaultValue()));
		}
		sb.append("\nPlugin prefix resolution (#1):\n");
		sb.append("  If Maven cannot resolve the 'test-order' prefix, either:\n");
		sb.append("  a) Add to ~/.m2/settings.xml:\n");
		sb.append("       <pluginGroups><pluginGroup>me.bechberger</pluginGroup></pluginGroups>\n");
		sb.append("  b) Or use fully-qualified invocation:\n");
		sb.append("       mvn me.bechberger:test-order-maven-plugin:<version>:<goal> test\n");
		sb.append("  c) Or declare the plugin in <build><plugins> section of your POM.\n");
		sb.append("\nAlgorithm recommendation (#25):\n");
		sb.append("  combined (default) — Best general-purpose: tries reverse, random, and history\n");
		sb.append("                       strategies adaptively. Use this unless you have a reason not to.\n");
		sb.append("  reverse            — Cheapest (1 run). Good for quick smoke-checks.\n");
		sb.append("  random             — Random permutations. Good with generous time budgets.\n");
		sb.append("  history            — Uses prior run data to target suspicious tests.\n");
		sb.append("  pfast              — Probabilistic approach (Pradet-style). Good for large suites.\n");
		sb.append("  iterative          — Exhaustive pairwise iteration. Thorough but slow.\n");
		sb.append("  bounded            — Random with bounded number of runs.\n");
		sb.append("  tuscan             — Covering-array based. Good for systematic coverage.\n");
		sb.append("\nProperty precedence:\n");
		sb.append("  CLI system properties (-D) always override POM <configuration> values.\n");
		sb.append("  Example: -Dtestorder.mode=learn overrides <mode>order</mode> in your POM.\n");
		sb.append("\nTypical usage:\n");
		sb.append("  Quick start: mvn clean test                    (auto-learns dependency index)\n");
		sb.append("  Next runs:   mvn clean test                    (auto-orders by priority)\n");
		sb.append("  CI fast:     mvn test-order:select test        (run only high-priority subset)\n");
		sb.append("  CI rest:     mvn test-order:run-remaining test (run deferred tests)\n");
		sb.append("  CI tiered:   mvn test-order:tiered-select test (run tier-1 first)\n");
		sb.append("               mvn test-order:run-tier test -Dtestorder.tiered.currentTier=2\n");
		sb.append("               mvn test-order:run-tier test -Dtestorder.tiered.currentTier=3\n");
		sb.append("  Dashboard:   mvn test-order:dashboard          (generate HTML report)\n");
		sb.append("  Show report: mvn test-order:show               (unified class/method/health view)\n");
		sb.append("  Diagnose:    mvn test-order:diagnose           (check setup health)\n");
		sb.append("  Detect OD:  mvn test-order:detect-dependencies  (find order-dependent tests)\n");
		sb.append("  Coverage:    mvn test-order:coverage           (writes to target/coverage-reports/)\n");
		sb.append("  Metrics:     mvn test-order:metrics            (writes to target/test-order-metrics.json)\n");
		sb.append("  Mutations:   mvn test-order:analyze-mutations   (record kill rates; enable killRateBonus)\n");
		sb.append(
				"  Explain:     mvn test-order:explain -Dtestorder.explain.test=com.example.MyTest  (score explanation)\n");

		getLog().info(sb.toString());
	}
}
