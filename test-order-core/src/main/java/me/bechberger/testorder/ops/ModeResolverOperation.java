package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Supplier;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

/**
 * Framework-agnostic mode resolution logic. Determines whether the plugin
 * should run in learn, order, optimize, or skip mode based on configuration,
 * index state, and auto-switching thresholds.
 * <p>
 * Eliminates duplication between Maven AutoMojo/PrepareMojo and Gradle
 * TestOrderPlugin.resolveMode().
 */
public final class ModeResolverOperation {

	private ModeResolverOperation() {
	}

	/** Input configuration for mode resolution. */
	public record ModeConfig(
			/** Requested mode string (auto, learn, order, optimize, skip, etc.) */
			String requestedMode,
			/** Path to the dependency index file. */
			Path indexPath,
			/** Path to the state file. */
			Path statePath,
			/**
			 * After this many non-learn runs, auto-switch to learn (0 = disabled).
			 */
			int autoLearnRunThreshold,
			/**
			 * Auto-switch to learn when changed-class count reaches this (0 = disabled).
			 */
			int autoLearnDiffThreshold,
			/**
			 * Lazy supplier for changed classes (only evaluated when autoLearnDiffThreshold
			 * > 0).
			 */
			Supplier<Set<String>> changedClassesSupplier,
			/**
			 * Optional callback to attempt CI index download before auto-detection. Called
			 * once — should download the index to {@code indexPath} if possible. May be
			 * {@code null} to skip CI download.
			 */
			Runnable ciDownloadCallback,
			/**
			 * Path to deps directory for auto-aggregation (null to skip auto-aggregation).
			 */
			Path depsDir,
			/**
			 * Path to compiled test classes directory for new-test detection (null to
			 * skip).
			 */
			Path testClassesDir,
			/**
			 * Path to test source root used when test classes are not compiled yet.
			 */
			Path testSourceRoot,
			/**
			 * Lazy supplier for changed test classes (only evaluated for new-test
			 * detection). May be {@code null} to skip.
			 */
			Supplier<Set<String>> changedTestsSupplier,
			/** Lazy supplier for dependency fingerprint (may be {@code null}). */
			Supplier<String> dependencyFingerprintSupplier,
			/** Logger. */
			PluginLog log) {

		/** Convenience constructor without new-test detection fields. */
		public ModeConfig(String requestedMode, Path indexPath, Path statePath, int autoLearnRunThreshold,
				int autoLearnDiffThreshold, Supplier<Set<String>> changedClassesSupplier, Runnable ciDownloadCallback,
				Path depsDir, PluginLog log) {
			this(requestedMode, indexPath, statePath, autoLearnRunThreshold, autoLearnDiffThreshold,
					changedClassesSupplier, ciDownloadCallback, depsDir, null, null, null, null, log);
		}
	}

	/** Result of mode resolution. */
	public record ModeDecision(
			/** Effective mode: "learn", "order", "optimize", or "skip". */
			String effectiveMode,
			/** Human-readable reason for the decision. */
			String reason,
			/** Whether state was modified (e.g. runsSinceLearn reset). */
			boolean stateModified) {
	}

	/**
	 * Validates and normalizes a mode string.
	 *
	 * @return the normalized mode (lowercase), or {@code null} for auto-detect
	 * @throws IllegalArgumentException
	 *             if the mode is invalid
	 */
	public static String normalizeMode(String mode) {
		if (mode == null || mode.isBlank()) {
			return null; // auto
		}
		String m = mode.trim().toLowerCase();
		return switch (m) {
			case "learn" -> "learn";
			case "order" -> "order";
			case "optimize" -> "optimize";
			case "skip", "off", "none" -> "skip";
			case "auto", "combined", "" -> null;
			default -> throw new IllegalArgumentException(
					"[test-order] Invalid mode '" + mode + "'. Valid values: auto, learn, order, optimize, skip");
		};
	}

	/**
	 * Resolves the effective mode. Implements the full auto-detection and
	 * threshold-based switching logic shared between Maven and Gradle plugins.
	 */
	public static ModeDecision resolve(ModeConfig config) {
		PluginLog log = config.log();

		// 1. Normalize explicit mode
		String explicit = normalizeMode(config.requestedMode());
		if (explicit != null) {
			if ("order".equals(explicit) && !Files.exists(config.indexPath())) {
				log.warn("[test-order] mode=order but no dependency index found at " + config.indexPath()
						+ ". Run 'mvn test -Dtestorder.mode=learn' first to build the index.");
				return new ModeDecision("skip", "No dependency index found and mode is 'order'", false);
			}
			return new ModeDecision(explicit, "Explicit mode: " + explicit, false);
		}

		// 2. Auto-detect: try CI download
		if (config.ciDownloadCallback() != null) {
			try {
				config.ciDownloadCallback().run();
			} catch (Exception e) {
				log.warn("[test-order] CI download callback failed (ignored): " + e.getMessage());
			}
		}

		// 3. Auto-aggregation from .deps files
		if (!Files.exists(config.indexPath()) && config.depsDir() != null && Files.isDirectory(config.depsDir())) {
			try {
				AggregateOperation.Result agg = AggregateOperation.aggregate(config.depsDir(), config.indexPath(), log);
				if (agg.written()) {
					log.debug(StructuredLog.autoAggregation(agg.depsFileCount(), agg.testClassCount(), java.util.Map
							.of("index_path", config.indexPath().toString(), "deps_dir", config.depsDir().toString())));
				}
			} catch (IOException e) {
				log.warn("[test-order] Auto-aggregation failed: " + e.getMessage());
			}
		}

		// 4. No index → learn (unless there are no test classes at all)
		if (!Files.exists(config.indexPath())) {
			boolean compiledTestsPresent = config.testClassesDir() != null && Files.isDirectory(config.testClassesDir())
					&& !TestClassDiscovery.scanTestClasses(config.testClassesDir()).isEmpty();
			boolean sourceTestsPresent = TestClassDiscovery.hasTestSources(config.testSourceRoot());
			if (!compiledTestsPresent && !sourceTestsPresent) {
				return new ModeDecision("skip", "No test classes found — skipping", false);
			}
			return new ModeDecision("learn", "No index file found — auto-selecting learn mode", false);
		}

		// 5. Empty/corrupted index check
		DependencyMap depMap = null;
		try {
			depMap = DependencyMap.load(config.indexPath());
			if (depMap.size() == 0) {
				log.warn("[test-order] Dependency index contains no tests — continuing in order mode");
			}
		} catch (IOException e) {
			log.warn("[test-order] Failed to load index for validation: " + e.getMessage());
			// Proceed — let downstream handle the load error
		}

		// 5b. New-test-class detection (only in auto mode)
		if (depMap != null && config.testClassesDir() != null && Files.isDirectory(config.testClassesDir())) {
			Set<String> newTests = TestClassDiscovery.findNewTestClasses(depMap, config.testClassesDir(), log);
			if (!newTests.isEmpty() && config.changedTestsSupplier() != null) {
				// Only treat changed test sources as "new" to avoid repeatedly flagging
				// old/non-runnable compiled test classes.
				Set<String> changedTests = config.changedTestsSupplier().get();
				newTests.retainAll(changedTests);
			}
			if (!newTests.isEmpty()) {
				String names = newTests.stream().sorted().limit(5).reduce((a, b) -> a + ", " + b).orElse("");
				if (newTests.size() > 5)
					names += " (... " + (newTests.size() - 5) + " more)";
				log.info("[test-order] New test class(es) detected: " + names
						+ " — switching to learn mode automatically.");
				return new ModeDecision("learn", "New test classes detected: " + names, false);
			}
		}

		// 6. Dependency fingerprint comparison (detect pom.xml/build.gradle changes)
		if (config.dependencyFingerprintSupplier() != null) {
			String currentFingerprint = config.dependencyFingerprintSupplier().get();
			if (currentFingerprint != null) {
				TestOrderState fpState = null;
				if (Files.exists(config.statePath())) {
					try {
						fpState = TestOrderState.load(config.statePath());
					} catch (IOException e) {
						log.warn("[test-order] Could not load state for fingerprint check: " + e.getMessage());
					}
				}
				if (fpState != null) {
					String storedFingerprint = fpState.dependencyFingerprint();
					log.debug("[test-order] Fingerprint check: stored=" + storedFingerprint + " current="
							+ currentFingerprint);
					if (storedFingerprint == null) {
						// First time — record fingerprint without triggering learn
						fpState.setDependencyFingerprint(currentFingerprint);
						saveState(fpState, config.statePath(), log);
					} else if (!storedFingerprint.equals(currentFingerprint)) {
						// Fingerprint changed — trigger learn
						log.info("[test-order] Dependency change detected — switching to learn mode automatically.");
						fpState.setDependencyFingerprint(currentFingerprint);
						fpState.resetRunsSinceLearn();
						boolean saved = saveState(fpState, config.statePath(), log);
						return new ModeDecision("learn", "Dependency change detected (fingerprint changed)", saved);
					}
				}
			}
		}

		// 7. Threshold-based auto-switching
		if (config.autoLearnRunThreshold() > 0 || config.autoLearnDiffThreshold() > 0) {
			TestOrderState state = null;
			if (Files.exists(config.statePath())) {
				try {
					state = TestOrderState.load(config.statePath());
				} catch (IOException e) {
					log.warn("[test-order] Could not load state for threshold check: " + e.getMessage());
				}
			}

			if (state != null) {
				// Run-count threshold
				if (config.autoLearnRunThreshold() > 0 && state.runsSinceLearn() >= config.autoLearnRunThreshold()) {
					log.info("[test-order] Run count since last learn (" + state.runsSinceLearn()
							+ ") reached threshold (" + config.autoLearnRunThreshold()
							+ ") — switching to learn mode automatically.");
					state.resetRunsSinceLearn();
					boolean saved = saveState(state, config.statePath(), log);
					return new ModeDecision("learn",
							"Run count threshold reached (" + config.autoLearnRunThreshold() + ")", saved);
				}

				// Diff threshold
				if (config.autoLearnDiffThreshold() > 0 && config.changedClassesSupplier() != null) {
					Set<String> changedNow = config.changedClassesSupplier().get();
					if (changedNow.size() >= config.autoLearnDiffThreshold()) {
						log.info("[test-order] Changed-class count (" + changedNow.size() + ") reached threshold ("
								+ config.autoLearnDiffThreshold() + ") — switching to learn mode automatically.");
						state.resetRunsSinceLearn();
						boolean saved = saveState(state, config.statePath(), log);
						return new ModeDecision("learn",
								"Changed-class count threshold reached (" + config.autoLearnDiffThreshold() + ")",
								saved);
					}
				}
			}
		}

		log.debug(StructuredLog.modeDecision("order", "Index exists",
				java.util.Map.of("index_path", config.indexPath().toString(), "requested_mode",
						config.requestedMode() == null ? "" : config.requestedMode())));
		return new ModeDecision("order", "Index exists — using order mode", false);
	}

	private static boolean saveState(TestOrderState state, Path statePath, PluginLog log) {
		try {
			state.save(statePath);
			return true;
		} catch (IOException e) {
			log.warn("[test-order] Could not save state after threshold reset: " + e.getMessage());
			return false;
		}
	}
}
