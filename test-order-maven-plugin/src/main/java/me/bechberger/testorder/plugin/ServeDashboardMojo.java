package me.bechberger.testorder.plugin;

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
@Mojo(name = "serve", requiresProject = true)
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

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		Path htmlPath = resolveOutputPath();
		String regenerateMode = regenerate == null ? "auto" : regenerate.trim().toLowerCase(Locale.ROOT);

		boolean shouldGenerate = switch (regenerateMode) {
			case "true", "yes", "always" -> true;
			case "false", "no", "never" -> false;
			default -> !Files.exists(htmlPath); // "auto"
		};

		if (shouldGenerate) {
			getLog().info("[test-order] Generating dashboard before serving…");
			super.execute();
		} else if (!Files.exists(htmlPath)) {
			throw new MojoExecutionException("[test-order] Dashboard not found at " + htmlPath
					+ " — run 'mvn test-order:dashboard' first, or set " + MavenPluginConfigKeys.DASHBOARD_REGENERATE
					+ "=auto");
		}

		try {
			boundPort = DashboardServerOperation.start(htmlPath, ctx.resolveStateFile(stateFile), port, pluginLog(),
					p -> this.boundPort = p);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to bind HTTP server on port " + port, e);
		}
	}

	/** Bound port after the server starts; 0 until then. Accessible for testing. */
	public volatile int boundPort = 0;
}
