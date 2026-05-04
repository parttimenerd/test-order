package me.bechberger.testorder.plugin;

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
@Mojo(name = "dashboard", defaultPhase = LifecyclePhase.VALIDATE)
public class DashboardMojo extends ShowOrderMojo {

	/** Output HTML file path. */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_OUTPUT, defaultValue = "${project.build.directory}/test-order-dashboard/index.html")
	protected String dashboardOutput;

	/** If true, attempt to open the generated dashboard in the default browser. */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_OPEN, defaultValue = "false")
	private boolean openBrowser;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		Path idxPath = resolveIndexPath();
		if (!Files.exists(idxPath)) {
			autoAggregateOrFail(idxPath);
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
			Files.createDirectories(outPath.getParent());
			new DashboardWorkflow(pctx, template, outPath.getParent()).generate(outPath);
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
