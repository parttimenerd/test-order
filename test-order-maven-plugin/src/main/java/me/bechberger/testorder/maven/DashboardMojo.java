package me.bechberger.testorder.maven;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.dashboard.DashboardResources;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.WarnOnce;
import me.bechberger.testorder.ops.workflows.DashboardWorkflow;

/**
 * Generates a self-contained HTML dashboard visualising the test-order scoring,
 * dependency graph, run history, and coverage data.
 *
 * <p>
 * Usage: {@code mvn test-order:dashboard}
 *
 * <p>
 * Output: {@code target/test-order-dashboard/index.html}
 */
@Mojo(name = "dashboard", defaultPhase = LifecyclePhase.VALIDATE, aggregator = true)
public class DashboardMojo extends AbstractTestOrderMojo {

	/** Output HTML file path. */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_OUTPUT, defaultValue = "${project.build.directory}/test-order-dashboard/index.html")
	protected String dashboardOutput;

	/** If true, attempt to open the generated dashboard in the default browser. */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_OPEN, defaultValue = "false")
	protected boolean openBrowser;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		Path idxPath = resolveIndexPath();
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
		}

		try {
			me.bechberger.testorder.DependencyMap depMap = me.bechberger.testorder.DependencyMap.load(idxPath);
			if (depMap.testClasses().isEmpty()) {
				getLog().info("[test-order] No dependency index found — run `mvn test` "
						+ "(auto-detects learn mode) or `mvn -Dtestorder.mode=learn test` first.");
				return;
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to read dependency index at " + idxPath, e);
		}

		PluginContext pctx = buildPluginContextBuilder().pluginVersion(getPluginVersion()).build();

		String template;
		try {
			template = DashboardResources.assembleTemplate();
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to load dashboard template", e);
		}

		Path outPath = Path.of(dashboardOutput);
		try {
			Path outParent = outPath.toAbsolutePath().getParent();
			if (outParent != null) {
				Files.createDirectories(outParent);
			}

			// Compute ML health data if available
			java.util.Map<String, Double> mlPredictions = null;
			me.bechberger.testorder.ml.TestHealthReport healthReport = null;
			Path mlHistoryDir = resolveMLHistoryDir();
			me.bechberger.testorder.ml.MLHealthLoader.LoadResult mlResult = me.bechberger.testorder.ml.MLHealthLoader
					.load(mlHistoryDir);
			if (mlResult.hasData()) {
				healthReport = mlResult.healthReport();
				// Generate predictions via Tribuo
				Path idxPathForML = resolveIndexPath();
				if (Files.exists(idxPathForML)) {
					try {
						me.bechberger.testorder.DependencyMap depMap = me.bechberger.testorder.DependencyMap
								.load(idxPathForML);
						Set<String> testClasses = new HashSet<>(depMap.testClasses());
						if (!testClasses.isEmpty()) {
							Path historyFile = mlHistoryDir.resolve("history.lz4");
							mlPredictions = me.bechberger.testorder.maven.ml.TestFailurePredictor
									.trainAndPredict(historyFile, depMap, Set.of(), Set.of(), testClasses);
						}
					} catch (Exception e) {
						WarnOnce.warn(MavenPluginLog.wrap(getLog()), "ml-train-failure",
								"[test-order] ML predictions unavailable: " + e.getMessage()
										+ " — rerun with -Dtestorder.verbose=true for stack traces.");
						if (Boolean.getBoolean("testorder.verbose")) {
							e.printStackTrace();
						}
					}
				}
			}

			new DashboardWorkflow(pctx, template, outPath.toAbsolutePath().getParent(), mlPredictions, healthReport)
					.generate(outPath);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to write dashboard to " + outPath, e);
		}

		if (openBrowser) {
			tryOpenBrowser(outPath.toAbsolutePath().toUri());
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private String getPluginVersion() {
		try (InputStream is = getClass()
				.getResourceAsStream("/META-INF/maven/me.bechberger/test-order-maven-plugin/pom.properties")) {
			if (is != null) {
				Properties props = new Properties();
				props.load(is);
				return props.getProperty("version", "unknown");
			}
		} catch (IOException e) {
			getLog().debug("[test-order] Could not read plugin version: " + e.getMessage());
		}
		return "unknown";
	}

	protected void tryOpenBrowser(URI uri) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(uri);
			}
		} catch (Exception e) {
			getLog().debug("[test-order] dashboard: could not open browser: " + e.getMessage());
		}
	}

	/** Returns the resolved output path for the generated dashboard HTML. */
	protected Path resolveOutputPath() {
		return Path.of(dashboardOutput);
	}
}
