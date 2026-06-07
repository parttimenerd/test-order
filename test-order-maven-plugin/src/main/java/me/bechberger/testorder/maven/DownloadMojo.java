package me.bechberger.testorder.maven;

import java.nio.file.Path;
import java.util.Optional;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import me.bechberger.testorder.ci.CiDepDownloadManager;

/**
 * Downloads the test-order dependency index from a CI system (GitHub Actions,
 * GitLab CI, or a generic HTTP endpoint) as configured in
 * {@code .test-order/download-config.yml}.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * mvn test-order:download
 * </pre>
 *
 * <p>
 * With {@code -DfallbackToLearn=true}, download failure is not fatal — the
 * subsequent {@code test} run is automatically redirected into learn mode so a
 * local index is built instead.
 */
@Mojo(name = "download", requiresProject = true)
public class DownloadMojo extends AbstractTestOrderMojo {

	/**
	 * Session-scoped key to track whether the download already ran in this reactor
	 * build. Stored in session user properties (not a static field) so it is
	 * automatically scoped to the current Maven session — avoids the flag
	 * persisting across builds when using the Maven daemon (mvnd).
	 */
	private static final String SESSION_DOWNLOADED_KEY = "testorder.internal.downloadedInReactor";

	/**
	 * When {@code true}, a failed download (no config, no artifact, no token) is
	 * not fatal. The plugin sets {@code testorder.mode=learn} so the next
	 * {@code test} run builds a local index instead.
	 */
	@Parameter(property = "testorder.download.fallbackToLearn", defaultValue = "false")
	private boolean fallbackToLearn;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		if ("pom".equals(project.getPackaging())) {
			getLog().debug("[test-order] Skipping download — POM module.");
			return;
		}

		// Only download once per reactor build — the index is shared.
		// Use session user properties (not a static field) so the flag is scoped
		// to this Maven session and does not persist across mvnd daemon builds.
		if (session != null && session.getUserProperties() != null
				&& "true".equals(session.getUserProperties().getProperty(SESSION_DOWNLOADED_KEY))) {
			getLog().debug("[test-order] Download already completed for this reactor build.");
			return;
		}

		// Use reactor root for config lookup (config lives at project root, not
		// per-module)
		Path reactorRoot = session.getTopLevelProject() != null
				? session.getTopLevelProject().getBasedir().toPath()
				: project.getBasedir().toPath();
		Path indexTarget = resolveIndexPath();

		if (!me.bechberger.testorder.ci.CiConfigParser.configExistsIn(reactorRoot)) {
			if (fallbackToLearn) {
				getLog().info("[test-order] No download-config.yml found — falling back to local learn pass.");
				setLearnFallback();
				return;
			}
			throw new MojoExecutionException("CI download failed: .test-order/download-config.yml not found.\n"
					+ "  Create it with your CI provider settings, e.g. (GitHub Actions):\n" + "    ci:\n"
					+ "      github:\n" + "        owner: your-org\n" + "        repo: your-repo\n"
					+ "        workflow: ci.yml\n" + "        artifact-name: test-order-deps\n"
					+ "  Required env vars: GITHUB_TOKEN (or GITLAB_TOKEN for GitLab).\n"
					+ "  See docs/CI.md for full configuration reference.");
		}

		getLog().info("[test-order] Downloading CI dependency index to " + indexTarget);

		Optional<Path> result = CiDepDownloadManager.downloadIfConfigured(reactorRoot, indexTarget);

		if (result.isPresent()) {
			if (session != null && session.getUserProperties() != null) {
				session.getUserProperties().setProperty(SESSION_DOWNLOADED_KEY, "true");
			}
			getLog().info("[test-order] CI index written to " + result.get());
		} else {
			if (fallbackToLearn) {
				getLog().info("[test-order] CI download failed — falling back to local learn pass.");
				setLearnFallback();
				return;
			}
			throw new MojoExecutionException("CI download failed: could not retrieve artifact from your CI provider.\n"
					+ "  Check:\n" + "    • Is GITHUB_TOKEN / GITLAB_TOKEN set in the environment?\n"
					+ "    • Does the workflow/artifact exist? (run `gh run list` to verify)\n"
					+ "    • Is the config in .test-order/download-config.yml correct?\n"
					+ "  Run with -X for debug logging.");
		}
	}

	private void setLearnFallback() {
		if (session != null && session.getUserProperties() != null) {
			session.getUserProperties().setProperty(MavenPluginConfigKeys.MODE, "learn");
		}
	}
}
