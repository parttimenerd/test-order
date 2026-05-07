package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ErrorCode;

/**
 * Diagnostic operation for validating test-order setup and detecting common
 * issues. Checks index, state, hash files, permissions, and change detection.
 */
public final class DiagnosticOperation {

	private DiagnosticOperation() {
	}

	/**
	 * Configuration for diagnostic checks.
	 */
	public record DiagnosticConfig(Path projectRoot, Path indexFile, Path stateFile, Path hashFile, Path testHashFile,
			Path methodHashFile, Path depsDir, Path testSourceRoot, String changeMode, PluginLog log) {
	}

	/**
	 * Diagnostic result with overall health status and individual check results.
	 */
	public record DiagnosticReport(int healthScore, String overallStatus, List<DiagnosticResult> results,
			Map<String, String> summary) {

		public boolean isHealthy() {
			return healthScore >= 80 && !hasErrors();
		}

		public boolean hasErrors() {
			return results.stream().anyMatch(r -> r.isError());
		}

		public boolean hasWarnings() {
			return results.stream().anyMatch(r -> r.isInformational() && !r.isSuccess());
		}
	}

	/**
	 * Run full diagnostic suite.
	 */
	public static DiagnosticReport diagnose(DiagnosticConfig config) {
		List<DiagnosticResult> results = new ArrayList<>();
		int score = 100;

		// Check 1: Index file
		DiagnosticResult indexCheck = checkIndexFile(config);
		results.add(indexCheck);
		if (indexCheck.isError())
			score -= 20;

		// Check 2: State file (if learning has happened)
		DiagnosticResult stateCheck = checkStateFile(config);
		results.add(stateCheck);
		if (stateCheck.isError())
			score -= 15;

		// Check 3: Hash files
		DiagnosticResult hashCheck = checkHashFiles(config);
		results.add(hashCheck);
		if (hashCheck.isError())
			score -= 10;

		// Check 4: Permissions
		DiagnosticResult permCheck = checkPermissions(config);
		results.add(permCheck);
		if (permCheck.isError())
			score -= 20;

		// Check 5: Change detection
		DiagnosticResult changeCheck = checkChangeDetection(config);
		results.add(changeCheck);
		if (changeCheck.isError())
			score -= 15;

		// Check 6: Deps directory
		DiagnosticResult depsCheck = checkDepsDirectory(config);
		// The agent's tryDirectMerge() writes the index directly without .deps files,
		// so missing .deps is normal when the index is already valid.
		if (indexCheck.isSuccess() && depsCheck.isInformational() && !depsCheck.isSuccess()) {
			depsCheck = DiagnosticResult.success("No .deps files needed (index was written directly by agent)");
		}
		results.add(depsCheck);
		if (depsCheck.isInformational() && !depsCheck.isSuccess() && !indexCheck.isSuccess())
			score -= 5;

		score = Math.max(0, Math.min(100, score));

		String status = determineStatus(score, results);
		Map<String, String> summary = buildSummary(config, results);

		return new DiagnosticReport(score, status, results, summary);
	}

	private static DiagnosticResult checkIndexFile(DiagnosticConfig config) {
		try {
			if (!Files.exists(config.indexFile())) {
				return DiagnosticResult.info(ErrorCode.INDEX_NOT_FOUND,
						"No dependency index found at " + config.indexFile().toAbsolutePath());
			}

			long size = Files.size(config.indexFile());
			if (size < 100) {
				return DiagnosticResult.error(ErrorCode.INDEX_EMPTY,
						"Index file is suspiciously small (" + size + " bytes)",
						List.of("Run tests in learn mode to collect test dependencies",
								"Then run testOrderAggregate to merge .deps files into index"));
			}

			// Try to load it
			try {
				DependencyMap map = DependencyMap.load(config.indexFile());
				long indexAge = (System.currentTimeMillis() - Files.getLastModifiedTime(config.indexFile()).toMillis())
						/ 1000;
				Map<String, String> ctx = new HashMap<>();
				ctx.put("index_size_bytes", String.valueOf(size));
				ctx.put("index_age_seconds", String.valueOf(indexAge));
				ctx.put("test_class_count", String.valueOf(map.size()));

				if (indexAge > 86400) { // 24 hours
					return DiagnosticResult.info(ErrorCode.INDEX_NEEDS_REBUILD,
							"Index is " + (indexAge / 3600) + " hours old",
							List.of("Consider re-running learn mode to pick up recent changes",
									"Or verify that your build hasn't changed significantly"));
				}

				return DiagnosticResult.success("Index file is valid (" + map.size() + " test classes)");
			} catch (IOException e) {
				return DiagnosticResult.error(ErrorCode.INDEX_CORRUPTED, "Index file is corrupted: " + e.getMessage(),
						List.of("Recovery steps:", "  1. Clean up: mvn test-order:clean (or Gradle: testOrderClean)",
								"  2. If .deps files exist: mvn test-order:compact (or Gradle: testOrderCompact)",
								"  3. Otherwise re-learn: mvn test -Dtestorder.mode=learn",
								"  4. Verify fix: mvn test-order:diagnose",
								"Possible causes: disk full during write, incompatible version, or file is not LZ4"));
			}
		} catch (IOException e) {
			return DiagnosticResult.error(ErrorCode.INDEX_READ_ERROR, "Failed to read index file: " + e.getMessage(),
					List.of("Check file permissions and disk space"));
		}
	}

	private static DiagnosticResult checkStateFile(DiagnosticConfig config) {
		try {
			if (!Files.exists(config.stateFile())) {
				return DiagnosticResult.info(ErrorCode.STATE_NOT_FOUND,
						"No state file found (expected after first test run)");
			}

			long size = Files.size(config.stateFile());
			try {
				// Try to load it
				me.bechberger.testorder.TestOrderState.load(config.stateFile());
				return DiagnosticResult.success("State file is valid (" + size + " bytes)");
			} catch (IOException e) {
				return DiagnosticResult.error(ErrorCode.STATE_CORRUPTED, "State file is corrupted: " + e.getMessage(),
						List.of("Recovery steps:", "  1. Delete state file: rm .test-order/state.lz4",
								"  2. Run tests normally — state will be recreated with fresh data",
								"  3. Scoring weights and duration history will be lost (rebuilt over ~5 runs)",
								"Possible causes: disk full, unexpected termination, or concurrent write"));
			}
		} catch (IOException e) {
			return DiagnosticResult.error(ErrorCode.STATE_READ_ERROR, "Failed to read state file: " + e.getMessage(),
					List.of("Check file permissions"));
		}
	}

	private static DiagnosticResult checkHashFiles(DiagnosticConfig config) {
		List<String> missing = new ArrayList<>();
		if (!Files.exists(config.hashFile()))
			missing.add("source");
		if (!Files.exists(config.testHashFile()))
			missing.add("test");

		if (!missing.isEmpty()) {
			return DiagnosticResult.info(ErrorCode.HASH_FILE_STALE,
					"Hash snapshot files missing: " + String.join(", ", missing),
					List.of("Hash snapshots track file changes between runs (since-last-run mode)",
							"They are created automatically during learn mode runs",
							"To create manually: mvn test-order:snapshot (or Gradle: testOrderSnapshot)",
							"If using git-based change detection, these files are optional"));
		}

		return DiagnosticResult.success("Hash files are present and up-to-date");
	}

	private static DiagnosticResult checkPermissions(DiagnosticConfig config) {
		Path testOrderDir = config.indexFile().getParent();
		try {
			if (!Files.exists(testOrderDir)) {
				return DiagnosticResult.info(ErrorCode.PERMISSION_DENIED, ".test-order directory does not exist yet",
						List.of("This is normal before the first test run",
								"The directory will be created automatically on the first learn run"));
			}

			if (!Files.isWritable(testOrderDir)) {
				return DiagnosticResult.error(ErrorCode.PERMISSION_DENIED,
						"Cannot write to .test-order directory: " + testOrderDir,
						List.of("Recovery steps:", "  1. Fix permissions: chmod 755 " + testOrderDir,
								"  2. Or change ownership: chown $USER " + testOrderDir,
								"  3. On CI: ensure the build user owns the workspace checkout",
								"  4. In containers: verify the working directory is mounted writable"));
			}

			return DiagnosticResult.success(".test-order directory is writable");
		} catch (Exception e) {
			return DiagnosticResult.error(ErrorCode.PERMISSION_DENIED, "Permission check failed: " + e.getMessage(),
					List.of("Check .test-order directory ownership and permissions"));
		}
	}

	private static DiagnosticResult checkChangeDetection(DiagnosticConfig config) {
		try {
			// Validate change mode
			me.bechberger.testorder.changes.ChangeDetectionSupport.normalizeMode(config.changeMode());

			// Check if source root exists
			if (!Files.exists(config.testSourceRoot())) {
				return DiagnosticResult.info(ErrorCode.TEST_SOURCE_ROOT_ABSENT,
						"Test source root not found: " + config.testSourceRoot(),
						List.of("Verify your test source directory structure", "Default: src/test/java",
								"This is expected for POM-only parent modules"));
			}

			return DiagnosticResult.success("Change detection mode '" + config.changeMode() + "' is valid");
		} catch (IOException e) {
			return DiagnosticResult.error(ErrorCode.CHANGE_MODE_INVALID,
					"Invalid change detection mode: " + config.changeMode(),
					List.of("Valid modes: auto, since-last-run, since-last-commit, uncommitted, explicit",
							"Set via -Dtestorder.changeMode=<mode>"));
		}
	}

	private static DiagnosticResult checkDepsDirectory(DiagnosticConfig config) {
		try {
			if (!Files.exists(config.depsDir())) {
				return DiagnosticResult.info(ErrorCode.DEPS_NOT_FOUND, "No .deps directory found");
			}

			long fileCount;
			try (var files = Files.list(config.depsDir())) {
				fileCount = files.filter(p -> p.getFileName().toString().endsWith(".deps")).count();
			}

			if (fileCount == 0) {
				return DiagnosticResult.info(ErrorCode.DEPS_NOT_FOUND, ".deps directory exists but is empty",
						List.of("Run tests in learn mode to populate .deps files"));
			}

			return DiagnosticResult.success(".deps directory contains " + fileCount + " dependency files");
		} catch (IOException e) {
			return DiagnosticResult.error(ErrorCode.INDEX_READ_ERROR,
					"Failed to check .deps directory: " + e.getMessage(), List.of("Check directory permissions"));
		}
	}

	private static String determineStatus(int score, List<DiagnosticResult> results) {
		boolean hasErrors = results.stream().anyMatch(DiagnosticResult::isError);
		if (hasErrors) {
			return score >= 70 ? "ISSUES ⚠" : "CRITICAL ✗";
		}
		if (score >= 90) {
			return "HEALTHY ✓";
		} else if (score >= 70) {
			return "WARNINGS ⚠";
		} else if (score >= 50) {
			return "ISSUES ⚠";
		} else {
			return "CRITICAL ✗";
		}
	}

	private static Map<String, String> buildSummary(DiagnosticConfig config, List<DiagnosticResult> results) {
		Map<String, String> summary = new HashMap<>();
		long errors = results.stream().filter(DiagnosticResult::isError).count();
		long warnings = results.stream().filter(r -> r.isInformational() && !r.isSuccess()).count();

		summary.put("total_checks", String.valueOf(results.size()));
		summary.put("errors", String.valueOf(errors));
		summary.put("warnings", String.valueOf(warnings));
		summary.put("project_root", config.projectRoot().toAbsolutePath().toString());

		return summary;
	}
}
