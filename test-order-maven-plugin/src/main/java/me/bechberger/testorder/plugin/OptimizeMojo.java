package me.bechberger.testorder.plugin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.ops.OptimizeOperation;
import net.jpountz.lz4.LZ4BlockInputStream;

/**
 * Optimises scoring weights by hill-climbing over recorded run history and
 * saves the result back into the state file.
 * <p>
 * Usage: {@code mvn test-order:optimize}
 */
@Mojo(name = "optimize", defaultPhase = LifecyclePhase.VALIDATE)
public class OptimizeMojo extends AbstractTestOrderMojo {

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;
		Path statePath = ctx.resolveStateFile(stateFile);
		if (!Files.exists(statePath)) {
			throw new MojoExecutionException(
					"State file not found: " + statePath + ". Run some test-order test runs first.");
		}

		validateStateFileFormat(statePath);

		try {
			OptimizeOperation.Result result = OptimizeOperation.run(statePath, msg -> getLog().info(msg));
			if (result != null && result.overfit()) {
				getLog().warn("[test-order] Overfitting detected — default weights used instead.");
			}
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to optimise: " + e.getMessage(), e);
		}
	}

	private void validateStateFileFormat(Path statePath) throws MojoExecutionException {
		try {
			byte[] raw = Files.readAllBytes(statePath);
			if (raw.length == 0) {
				return;
			}
			String json;
			if (raw[0] == '{' || raw[0] == ' ' || raw[0] == '\n' || raw[0] == '\r' || raw[0] == '\t') {
				json = new String(raw, StandardCharsets.UTF_8).strip();
			} else {
				try (var in = new LZ4BlockInputStream(new ByteArrayInputStream(raw))) {
					json = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
				}
			}
			if (json.isEmpty() || !json.startsWith("{")) {
				throw new IOException("content is not valid state data");
			}
		} catch (Exception e) {
			throw new MojoExecutionException("Failed to load state file: " + statePath + ". " + e.getMessage(), e);
		}
	}

}
