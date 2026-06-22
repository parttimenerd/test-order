package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.ops.ExportJsonOperation;

/**
 * Exports the binary dependency index ({@code test-dependencies.lz4}) as JSON,
 * including history from the state file if available.
 * <p>
 * Usage: {@code mvn test-order:export-json} or
 * {@code mvn test-order:export-json -Dtestorder.exportJson.output=deps.json}
 */
@Mojo(name = "export-json", aggregator = true)
public class ExportJsonMojo extends AbstractTestOrderMojo {

	/** Output JSON file. If not set, writes to stdout. */
	@Parameter(property = MavenPluginConfigKeys.EXPORT_JSON_OUTPUT)
	private String outputFile;

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		Path idxPath = resolveIndexPath();
		if (!Files.exists(idxPath)) {
			throw new MojoExecutionException("Dependency index not found at " + idxPath + "."
					+ "\nRun: mvn test -Dtestorder.mode=learn" + "\nRun: mvn test-order:diagnose");
		}

		Path statePath = ctx.resolveStateFile(stateFile);
		if (!Files.exists(statePath)) {
			statePath = null;
		}

		try {
			if (outputFile != null && !outputFile.isBlank()) {
				ExportJsonOperation.export(idxPath, statePath, Path.of(outputFile), pluginLog());
			} else {
				ExportJsonOperation.export(idxPath, statePath, System.out, pluginLog());
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to export index as JSON", e);
		}
	}
}
