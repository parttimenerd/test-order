package me.bechberger.testorder.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.DependencyMap;

/**
 * Aggregates individual {@code .deps} files from learn mode into a single
 * dependency index.
 */
@Mojo(name = "aggregate")
public class AggregateMojo extends AbstractTestOrderMojo {

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		Path depsDirPath = ctx.resolveDepsDir(depsDir);
		if (!Files.isDirectory(depsDirPath)) {
			throw new MojoExecutionException("Deps directory does not exist: " + depsDirPath
					+ ". Run tests first with: mvn test-order:combined test");
		}

		try {
			DependencyMap map = DependencyMap.aggregate(depsDirPath);
			Path output = resolveIndexPath();
			if (map.size() == 0) {
				if (Files.exists(output)) {
					getLog().warn(
							"[test-order] No .deps files found — refusing to overwrite existing index at " + output);
					getLog().warn("[test-order] If you intended to clear the index, delete " + output + " manually.");
				} else {
					getLog().warn("[test-order] No .deps files found — no index to write.");
					getLog().warn("[test-order] Run tests first with: mvn test-order:combined test");
				}
				return;
			}
			map.save(output);
			getLog().info("[test-order] Aggregated " + map.size() + " test classes → " + output);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to aggregate deps", e);
		}
	}
}
