package me.bechberger.testorder.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import me.bechberger.testorder.OptimizationDefaults;
import me.bechberger.testorder.TestOrderState;

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

		TestOrderState state;
		try {
			state = TestOrderState.load(statePath);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to load state file: " + e.getMessage(), e);
		}

		long withFailures = state.runs().stream().filter(r -> r.totalFailures() > 0).count();
		getLog().info("[test-order] Runs: " + state.runs().size() + " total, " + withFailures + " with failures");

		TestOrderState.ScoringWeights current = state.weights();
		getLog().info("[test-order] Current weights:  " + current.format());

		long startMs = System.currentTimeMillis();
		// Suppress JUL output from ScoringOptimizer — we log via Maven below
		java.util.logging.Logger julLogger = java.util.logging.Logger
				.getLogger("me.bechberger.testorder.TestOrderState");
		java.util.logging.Level previousLevel = julLogger.getLevel();
		julLogger.setLevel(java.util.logging.Level.OFF);
		TestOrderState.OptimizeResult optimized;
		try {
			optimized = state.optimize();
		} finally {
			julLogger.setLevel(previousLevel);
		}
		long elapsedMs = System.currentTimeMillis() - startMs;

		if (optimized == null) {
			getLog().warn(String.format("[test-order] Need at least %d runs with failures to optimise (have %d).",
					OptimizationDefaults.MIN_RUNS_FOR_OPTIMISATION, withFailures));
			return;
		}

		state.setWeights(optimized.weights());
		try {
			state.save(ctx.resolveStateFile(stateFile));
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to save optimised state: " + e.getMessage(), e);
		}

		getLog().info(String.format("[test-order] Optimised weights:  %s  (%.1fs)", optimized.weights().format(),
				elapsedMs / 1000.0));
		if (optimized.overfit()) {
			getLog().warn("[test-order] Overfitting detected — default weights used instead.");
		}
	}

}
