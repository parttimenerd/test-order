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

		Path projectDir = project.getBasedir().toPath();
		Path indexTarget = resolveIndexPath();

		getLog().info("[test-order] Downloading CI dependency index to " + indexTarget);

		Optional<Path> result = CiDepDownloadManager.downloadIfConfigured(projectDir, indexTarget);

		if (result.isPresent()) {
			getLog().info("[test-order] CI index written to " + result.get());
		} else {
			throw new MojoExecutionException(
					"CI download failed. Ensure .test-order/download-config.yml exists with your CI provider settings.\n"
							+ "  Example config (GitHub Actions):\n"
							+ "    provider: github\n"
							+ "    repo: owner/repo\n"
							+ "    artifact: test-order-index\n"
							+ "  Required env vars: GITHUB_TOKEN (or GITLAB_TOKEN for GitLab).\n"
							+ "  See docs/CI.md for full configuration reference.");
		}
	}
}
