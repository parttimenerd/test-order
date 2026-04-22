package me.bechberger.testorder.plugin;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.changes.FileHashStore;

/**
 * Scans the source tree and saves a compressed file hash snapshot for future
 * "since-last-run" change detection.
 */
@Mojo(name = "snapshot")
public class SnapshotMojo extends AbstractTestOrderMojo {

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		snapshot(ChangeDetectionHelper.resolveSourceRoot(project, sourceRoot), ctx.resolveHashFile(hashFile));
		snapshot(ChangeDetectionHelper.resolveTestSourceRoot(project, testSourceRoot),
				ctx.resolveTestHashFile(testHashFile));
	}

	private void snapshot(Path sourceRoot, Path outputFile) throws MojoExecutionException {
		if (!sourceRoot.toFile().isDirectory())
			return;
		try {
			FileHashStore store = FileHashStore.scan(sourceRoot);
			store.save(outputFile);
			getLog().info("[test-order] Snapshot: " + store.getHashes().size() + " files (" + sourceRoot + ") → "
					+ outputFile);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to save hash snapshot for " + sourceRoot, e);
		}
	}
}
