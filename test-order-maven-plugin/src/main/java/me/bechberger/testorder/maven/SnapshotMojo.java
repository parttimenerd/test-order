package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.changes.FileHashStore;
import me.bechberger.testorder.ops.HashSnapshotOperation;

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
		if ("pom".equals(project.getPackaging())) {
			getLog().debug("[test-order] Skipping snapshot — POM module.");
			return;
		}
		snapshot(resolveSourceRoot(), ctx.resolveHashFile(hashFile));
		snapshot(resolveTestSourceRoot(), ctx.resolveTestHashFile(testHashFile));

		// Also snapshot Kotlin source roots if they exist
		Path projectRoot = project.getBasedir().toPath();
		Path kotlinMain = projectRoot.resolve("src/main/kotlin");
		if (Files.isDirectory(kotlinMain)) {
			snapshot(kotlinMain, HashSnapshotOperation.kotlinHashFile(ctx.resolveHashFile(hashFile)));
		}
		Path kotlinTest = projectRoot.resolve("src/test/kotlin");
		if (Files.isDirectory(kotlinTest)) {
			snapshot(kotlinTest, HashSnapshotOperation.kotlinHashFile(ctx.resolveTestHashFile(testHashFile)));
		}
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
