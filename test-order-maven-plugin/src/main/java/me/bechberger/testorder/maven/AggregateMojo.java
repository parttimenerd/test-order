package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.IndexCollectorServer;
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
		Path indexPath = resolveIndexPath();
		// Always check for fallback payload file — written by IndexCollectorServer when
		// the Maven JVM shuts down before stopAndMerge can complete. Process
		// unconditionally: even if index exists, the fallback carries data from the
		// most
		// recent learn run that failed to merge.
		try {
			if (IndexCollectorServer.processFallbackFile(indexPath)) {
				getLog().info("[test-order] Processed collector fallback — merged into index at " + indexPath);
			}
		} catch (IOException e) {
			getLog().warn("[test-order] Failed to process collector fallback: " + e.getMessage());
		}
		Path depsDirPath = ctx.resolveDepsDir(depsDir);
		if (!Files.isDirectory(depsDirPath)) {
			if (Files.exists(indexPath)) {
				getLog().info("[test-order] No deps directory — index already written by collector at " + indexPath);
				return;
			}
			throw new MojoExecutionException("No dependency data found: neither the index file ("
					+ indexPath + ") nor the .deps directory (" + depsDirPath + ") exists."
					+ " Run learn mode first: mvn test -Dtestorder.mode=learn"
					+ "\n  For more details: mvn test-order:diagnose");
		}

		try {
			AggregateOperation.aggregate(depsDirPath, indexPath, pluginLog());
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to aggregate deps: " + e.getMessage(), e);
		}
	}
}
