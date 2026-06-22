package me.bechberger.testorder.maven;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.LZ4Support;
import me.bechberger.testorder.ops.OptimizeOperation;

/**
 * Optimises scoring weights by hill-climbing over recorded run history and
 * saves the result back into the state file.
 * <p>
 * Usage: {@code mvn test-order:optimize}
 */
@Mojo(name = "optimize", defaultPhase = LifecyclePhase.VALIDATE, aggregator = true)
public class OptimizeMojo extends AbstractTestOrderMojo {

	@Override
	public void execute() throws MojoExecutionException {
		initContext();
		if (skip)
			return;

		// Use the shared state file at the reactor root (not per-module paths).
		Path primaryState = ctx.resolveStateFile(stateFile);
		if (!Files.exists(primaryState)) {
			throw new MojoExecutionException(
					"No state file found at " + primaryState + "\nRun: mvn test -Dtestorder.mode=learn");
		}
		java.util.List<Path> statePaths = java.util.List.of(primaryState);

		int optimized = 0;
		for (Path statePath : statePaths) {
			validateStateFileFormat(statePath);
			try {
				getLog().info("[test-order] Optimising weights from: " + statePath);
				OptimizeOperation.Result result = OptimizeOperation.run(statePath, msg -> getLog().info(msg));
				if (result == null) {
					getLog().info("[test-order] Skipped " + statePath
							+ " — insufficient failure history for meaningful optimization (need >= "
							+ me.bechberger.testorder.OptimizationDefaults.MIN_RUNS_FOR_OPTIMISATION
							+ " failure runs).");
				} else if (result.overfit()) {
					getLog().warn("[test-order] Overfitting detected for " + statePath + " — default weights used.");
				} else {
					getLog().info("[test-order] Weights optimised successfully.");
					optimized++;
				}
			} catch (IOException e) {
				getLog().warn("[test-order] Failed to optimise " + statePath + ": " + e.getMessage());
			}
		}

		if (optimized == 0) {
			getLog().warn("[test-order] No state files were optimised — insufficient failure data in all state files.");
			getLog().warn("Run: mvn test -Dtestorder.mode=learn");
			return;
		}
		getLog().info("[test-order] Optimised " + optimized + " state file(s).");
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
				try (var in = LZ4Support.blockInputStream(new ByteArrayInputStream(raw))) {
					json = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
				}
			}
			if (json.isEmpty() || !json.startsWith("{")) {
				throw new IOException("content is not valid JSON state data (expected '{' prefix)");
			}
		} catch (Exception e) {
			throw new MojoExecutionException("Failed to load state file: " + statePath + ". " + e.getMessage()
					+ "\nRun: rm " + statePath + "\nRun: mvn test -Dtestorder.mode=learn", e);
		}
	}

}
