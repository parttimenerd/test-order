package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.ops.AggregateOperation;

/**
 * Aggregates individual {@code .deps} files from learn mode into a single
 * dependency index.
 */
@Mojo(name = "aggregate", aggregator = true)
public class AggregateMojo extends AbstractTestOrderMojo {

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		Path depsDirPath = ctx.resolveDepsDir(depsDir);
		if (!Files.isDirectory(depsDirPath)) {
			throw new MojoExecutionException("Deps directory does not exist: " + depsDirPath
					+ ". Run tests first with: mvn test-order:auto test");
		}

		try {
			AggregateOperation.aggregate(depsDirPath, resolveIndexPath(), pluginLog());
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to aggregate deps: " + e.getMessage(), e);
		}
	}
}
