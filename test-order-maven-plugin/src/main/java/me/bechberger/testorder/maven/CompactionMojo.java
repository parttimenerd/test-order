package me.bechberger.testorder.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

import me.bechberger.testorder.ops.IndexCompactionOperation;

/**
 * Maven goal to rebuild the test-order index from all .deps files. Useful for
 * cleaning stale entries or fixing a corrupted index.
 *
 * Usage: mvn test-order:compact
 */
@Mojo(name = "compact", defaultPhase = LifecyclePhase.VALIDATE, threadSafe = true, aggregator = true, requiresProject = true)
public class CompactionMojo extends AbstractTestOrderMojo {

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip) {
			return;
		}

		try {
			getLog().info("");
			getLog().info("═══════════════════════════════════════════════════════════");
			getLog().info("[test-order] Compacting Index from .deps Files");
			getLog().info("═══════════════════════════════════════════════════════════");
			getLog().info("");

			IndexCompactionOperation.CompactionResult result = IndexCompactionOperation.compact(
					ctx.resolveDepsDir(depsDir), ctx.resolveIndexFile(indexFile), MavenPluginLog.wrap(getLog()));

			getLog().info("");
			getLog().info("Status: " + result.description());
			if (result.hasChanges()) {
				getLog().info("  Added:   " + result.addedTests() + " test classes");
				getLog().info("  Removed: " + result.removedTests() + " test classes");
			}
			if (result.newIndexSize() > 0) {
				getLog().info("  Index Size: " + result.newIndexSize() + " bytes");
			}
			getLog().info("");
			getLog().info("═══════════════════════════════════════════════════════════");

		} catch (Exception e) {
			throw new MojoExecutionException("[test-order] Failed to compact index: " + e.getMessage(), e);
		}
	}
}
