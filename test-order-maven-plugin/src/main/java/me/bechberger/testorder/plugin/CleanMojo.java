package me.bechberger.testorder.plugin;

import java.nio.file.Path;
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

		List<Path> files = List.of(ctx.resolveIndexFile(indexFile), ctx.resolveStateFile(stateFile),
				ctx.resolveHashFile(hashFile), ctx.resolveTestHashFile(testHashFile),
				ctx.resolveMethodHashFile(methodHashFile));

		List<Path> dirs = List.of(ctx.resolveDepsDir(depsDir));

		int deleted = CleanOperation.clean(files, dirs, pluginLog());
		if (deleted > 0) {
			getLog().info("[test-order] Cleaned " + deleted + " item(s)");
		}
	}
}
