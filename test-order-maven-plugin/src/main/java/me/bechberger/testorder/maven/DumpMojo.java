package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.ops.DumpOperation;

/**
 * Dumps a binary dependency index as human-readable text format.
 * <p>
 * Usage: {@code mvn test-order:dump}
 */
@Mojo(name = "dump", aggregator = true)
public class DumpMojo extends AbstractTestOrderMojo {

	/** Output text file. If not set, writes to stdout. */
	@Parameter(property = MavenPluginConfigKeys.DUMP_OUTPUT)
	private String outputFile;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		Path idxPath = resolveIndexPath();
		if (!Files.exists(idxPath)) {
			throw new MojoExecutionException("Dependency index not found: " + idxPath);
		}

		try {
			if (outputFile != null && !outputFile.isBlank()) {
				DumpOperation.dump(idxPath, Path.of(outputFile), pluginLog());
			} else {
				// Print tip to stderr to avoid corrupting dump output on stdout
				System.err.println(
						"[test-order] Tip: use -Dtestorder.dump.output=<file> to write to a file, or run with -q to suppress Maven log messages from stdout");
				DumpOperation.dump(idxPath, System.out, pluginLog());
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to dump index", e);
		}
	}
}
