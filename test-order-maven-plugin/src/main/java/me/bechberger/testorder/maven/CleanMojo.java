package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import me.bechberger.testorder.ops.CleanOperation;

/**
 * Removes all test-order generated files (index, state, hashes, deps
 * directory).
 *
 * <p>
 * Usage: {@code mvn test-order:clean}
 */
@Mojo(name = "clean")
public class CleanMojo extends AbstractTestOrderMojo {

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		Path stateFilePath = ctx.resolveStateFile(stateFile);
		List<Path> files = List.of(ctx.resolveIndexFile(indexFile), stateFilePath,
				me.bechberger.testorder.PersistenceSupport.lockSibling(stateFilePath),
				ctx.resolveHashFile(hashFile), ctx.resolveTestHashFile(testHashFile),
				ctx.resolveMethodHashFile(methodHashFile));

		List<Path> dirs = new ArrayList<>(List.of(ctx.resolveDepsDir(depsDir)));

		// Also clean .test-order-precheck-* directories left behind by assessment/precheck
		Path baseDir = project.getBasedir().toPath();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, ".test-order-precheck-*")) {
			for (Path precheckDir : stream) {
				dirs.add(precheckDir);
			}
		} catch (IOException ignored) {
			// Directory listing failed — skip precheck cleanup
		}

		int deleted = CleanOperation.clean(files, dirs, pluginLog());
		if (deleted > 0) {
			getLog().info("[test-order] Cleaned " + deleted + " item(s)");
		}
	}
}
