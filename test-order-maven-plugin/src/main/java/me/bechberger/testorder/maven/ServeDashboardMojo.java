package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import me.bechberger.testorder.ops.DashboardServerOperation;

/**
 * Serves the test-order dashboard via a local HTTP server and opens it in the
 * browser.
 *
 * <p>
 * If the dashboard HTML has not been generated yet (or
 * {@code regenerate=true}), the dashboard is (re-)generated first. The server
 * runs until the Maven build is interrupted (Ctrl+C).
 *
 * <p>
 * Usage: {@code mvn test-order:serve}
 *
 * <p>
 * Custom port: {@code mvn test-order:serve -Dtestorder.dashboard.port=8080}
 */
@Mojo(name = "serve", requiresProject = true, aggregator = true)
public class ServeDashboardMojo extends DashboardMojo {

	/**
	 * TCP port for the embedded HTTP server. {@code 0} (default) picks a free
	 * ephemeral port automatically.
	 */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_PORT, defaultValue = "0")
	private int port;

	/**
	 * Controls when the dashboard HTML is regenerated before serving:
	 * <ul>
	 * <li>{@code auto} (default) — regenerate only if the file does not exist</li>
	 * <li>{@code true} — always regenerate</li>
	 * <li>{@code false} — never regenerate; fail if the file is missing</li>
	 * </ul>
	 */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_REGENERATE, defaultValue = "auto")
	private String regenerate;

	/**
	 * Optional bounded server lifetime in seconds. {@code 0} (default) keeps the
	 * server running until interrupted. Useful for CI and scripted checks that
	 * should not wait indefinitely.
	 */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_SERVE_SECONDS, defaultValue = "0")
	private int serveSeconds;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		// Support testorder.serve.port alias for testorder.dashboard.port (R13-4)
		if (port == 0 && session != null && session.getUserProperties() != null) {
			String servePort = session.getUserProperties().getProperty(MavenPluginConfigKeys.SERVE_PORT_ALIAS);
			if (servePort != null && !servePort.isBlank()) {
				try {
					port = Integer.parseInt(servePort.trim());
					getLog().info("[test-order] Using " + MavenPluginConfigKeys.SERVE_PORT_ALIAS + "=" + port
							+ " (alias for " + MavenPluginConfigKeys.DASHBOARD_PORT + ")");
				} catch (NumberFormatException e) {
					throw new MojoExecutionException("[test-order] Invalid " + MavenPluginConfigKeys.SERVE_PORT_ALIAS
							+ " value '" + servePort + "' — must be a number");
				}
			}
		}
		if (serveSeconds < 0) {
			throw new MojoExecutionException(
					"[test-order] " + MavenPluginConfigKeys.DASHBOARD_SERVE_SECONDS + " must be >= 0");
		}

		// Warn early if serveSeconds=0 will block the build indefinitely
		if (serveSeconds == 0 && session != null && session.getGoals() != null && session.getGoals().size() > 1) {
			getLog().warn("[test-order] WARNING: serveSeconds=0 blocks the build — "
					+ "subsequent goals will NOT execute while the server is running. "
					+ "Use -Dtestorder.dashboard.serveSeconds=30 for bounded serving.");
		}

		Path htmlPath = resolveOutputPath();
		String regenerateMode = regenerate == null ? "auto" : regenerate.trim().toLowerCase(Locale.ROOT);

		boolean shouldGenerate = switch (regenerateMode) {
			case "true", "yes", "always" -> true;
			case "false", "no", "never" -> false;
			case "auto" -> !Files.exists(htmlPath);
			default -> {
				getLog().warn("[test-order] Unrecognized " + MavenPluginConfigKeys.DASHBOARD_REGENERATE + " value '"
						+ regenerate + "' — valid values: auto, true, false. Falling back to 'auto'.");
				yield !Files.exists(htmlPath);
			}
		};

		if (shouldGenerate) {
			getLog().info("[test-order] Generating dashboard before serving…");
			// Suppress the file:// browser open from the parent — we open the http:// URL
			// instead.
			boolean savedOpenBrowser = openBrowser;
			openBrowser = false;
			try {
				super.execute();
			} finally {
				openBrowser = savedOpenBrowser;
			}
		} else if (!Files.exists(htmlPath)) {
			throw new MojoExecutionException("[test-order] Dashboard not found at " + htmlPath
					+ " — run 'mvn test-order:dashboard' first, or set " + MavenPluginConfigKeys.DASHBOARD_REGENERATE
					+ "=auto");
		}

		try {
			if (serveSeconds == 0) {
				getLog().info("[test-order] Server running indefinitely. Press Ctrl+C to stop.");
			}
			boundPort = DashboardServerOperation.start(htmlPath, ctx.resolveStateFile(stateFile), port, pluginLog(),
					p -> this.boundPort = p, serveSeconds, openBrowser);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to bind HTTP server on port " + port, e);
		}
	}

	/** Bound port after the server starts; 0 until then. Accessible for testing. */
	public volatile int boundPort = 0;
}
