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
		if ("pom".equals(project.getPackaging())) {
			getLog().debug("[test-order] Skipping clean — POM module.");
			return;
		}

		Path stateFilePath = ctx.resolveStateFile(stateFile);
		List<Path> files = List.of(ctx.resolveIndexFile(indexFile), stateFilePath,
				me.bechberger.testorder.PersistenceSupport.lockSibling(stateFilePath), ctx.resolveHashFile(hashFile),
				ctx.resolveTestHashFile(testHashFile), ctx.resolveMethodHashFile(methodHashFile));

		List<Path> dirs = new ArrayList<>(List.of(ctx.resolveDepsDir(depsDir)));

		// Also clean pending-runs directory (partial-run fallback files that accumulate
		// when shutdown hooks complete after JVM exit — see B19/B20)
		Path pendingRunsDir = ctx.resolveBaseDir().resolve("pending-runs");
		if (Files.isDirectory(pendingRunsDir)) {
			dirs.add(pendingRunsDir);
		}

		// Also clean detection directory left behind by detect-dependencies runs
		Path detectionDir = ctx.resolveBaseDir().resolve("detection");
		if (Files.isDirectory(detectionDir)) {
			dirs.add(detectionDir);
		}

		// Also clean .test-order-precheck-* directories left behind by
		// assessment/precheck
		Path baseDir = project.getBasedir().toPath();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(baseDir, ".test-order-precheck-*")) {
			for (Path precheckDir : stream) {
				dirs.add(precheckDir);
			}
		} catch (IOException ignored) {
			// Directory listing failed — skip precheck cleanup
		}

		// Clean any remaining .lock files in .test-order/ directory
		List<Path> allFiles = new ArrayList<>(files);
		Path testOrderDir = ctx.resolveBaseDir();
		if (Files.isDirectory(testOrderDir)) {
			try (DirectoryStream<Path> lockStream = Files.newDirectoryStream(testOrderDir, "*.lock")) {
				for (Path lockFile : lockStream) {
					allFiles.add(lockFile);
				}
			} catch (IOException ignored) {
			}
		}

		int deleted = CleanOperation.clean(allFiles, dirs, pluginLog());
		if (deleted > 0) {
			getLog().info("[test-order] Cleaned " + deleted + " item(s)");
		}
	}
}
