package me.bechberger.testorder.maven;

import java.nio.file.Path;
import java.util.Optional;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

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
 */
@Mojo(name = "download", requiresProject = true)
public class DownloadMojo extends AbstractTestOrderMojo {

	/** Track whether download already ran in this reactor build. */
	private static volatile boolean downloadedInReactor = false;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip) {
			getLog().info("[test-order] download skipped (testorder.skip=true)");
			return;
		}
		if ("pom".equals(project.getPackaging())) {
			getLog().debug("[test-order] Skipping download — POM module.");
			return;
		}

		// Only download once per reactor build — the index is shared
		if (downloadedInReactor) {
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
			downloadedInReactor = true;
			getLog().info("[test-order] CI index written to " + result.get());
		} else {
			throw new MojoExecutionException("CI download failed: could not retrieve artifact from your CI provider.\n"
					+ "  Check:\n" + "    • Is GITHUB_TOKEN / GITLAB_TOKEN set in the environment?\n"
					+ "    • Does the workflow/artifact exist? (run `gh run list` to verify)\n"
					+ "    • Is the config in .test-order/download-config.yml correct?\n"
					+ "  Run with -X for debug logging.");
		}
	}
}
