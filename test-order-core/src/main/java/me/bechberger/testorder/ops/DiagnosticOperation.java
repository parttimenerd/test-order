package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
			Path methodHashFile, Path depsDir, Path testSourceRoot, String changeMode, Path testClassesDir,
			PluginLog log) {

		/** Backward-compatible constructor without testClassesDir. */
		public DiagnosticConfig(Path projectRoot, Path indexFile, Path stateFile, Path hashFile, Path testHashFile,
				Path methodHashFile, Path depsDir, Path testSourceRoot, String changeMode, PluginLog log) {
			this(projectRoot, indexFile, stateFile, hashFile, testHashFile, methodHashFile, depsDir, testSourceRoot,
					changeMode, null, log);
		}
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
		// Also skip the penalty when no index exists yet (fresh project — expected
		// state).
		if (indexCheck.isSuccess() && depsCheck.isInformational() && !depsCheck.isSuccess()) {
			depsCheck = DiagnosticResult.success("No .deps files needed (index was written directly by agent)");
		}
		results.add(depsCheck);
		boolean freshProject = indexCheck.code() == ErrorCode.NOT_INITIALIZED_INDEX;
		if (!freshProject && depsCheck.isInformational() && !depsCheck.isSuccess() && !indexCheck.isSuccess())
			score -= 5;

		// Check 7: Pending collector fallback payloads
		DiagnosticResult fallbackCheck = checkFallbackPayload(config);
		if (fallbackCheck != null) {
			results.add(fallbackCheck);
		}

		score = Math.max(0, Math.min(100, score));

		String status = determineStatus(score, results);
		Map<String, String> summary = buildSummary(config, results);

		return new DiagnosticReport(score, status, results, summary);
	}

	private static DiagnosticResult checkIndexFile(DiagnosticConfig config) {
		try {
			if (!Files.exists(config.indexFile())) {
				return DiagnosticResult.info(ErrorCode.NOT_INITIALIZED_INDEX,
						"No dependency index found — this is expected before the first learn run",
						List.of("Run learn mode to build the index: mvn test -Dtestorder.mode=learn",
								"The index is created automatically at the end of a learn run"));
			}

			long size = Files.size(config.indexFile());
			if (size < 100) {
				return DiagnosticResult.error(ErrorCode.INDEX_EMPTY,
						"Index file is suspiciously small (" + size + " bytes)",
						List.of("Run tests in learn mode to collect test dependencies: mvn test -Dtestorder.mode=learn",
								"Then run 'mvn test-order:aggregate' to merge .deps files into the index"));
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
							List.of("Consider re-running learn mode to pick up recent changes: mvn test -Dtestorder.mode=learn",
									"Or verify that your build hasn't changed significantly"));
				}

				// Check index coverage vs actual compiled test classes (B11)
				int indexedCount = map.size();
				if (indexedCount == 0) {
					return DiagnosticResult.error(ErrorCode.INDEX_EMPTY,
							"Index has 0 test classes — learn mode ran but recorded no dependencies",
							List.of("Most likely cause: the package filter excludes all your test classes.",
									"Fix: add the package filter explicitly:",
									"  mvn test -Dtestorder.mode=learn -Dtestorder.includePackages=com.yourcompany",
									"Or check that your source packages match the project groupId (used as fallback filter)",
									"Run 'mvn test-order:diagnose' for further analysis"));
				}
				int actualCount = countTestClasses(config.testClassesDir());
				if (actualCount > 0 && indexedCount < actualCount / 10) {
					return DiagnosticResult.error(ErrorCode.INDEX_EMPTY,
							"Index covers only " + indexedCount + " of ~" + actualCount
									+ " test classes (<10%) — learn mode likely did not complete successfully",
							List.of("Re-run learn mode: mvn test -Dtestorder.mode=learn",
									"If using reuseForks=false, ensure each forked JVM can write to .test-order/",
									"If your source packages don't match the project's groupId, set the filter explicitly:",
									"  -Dtestorder.includePackages=com.yourcompany",
									"Check for errors in the previous learn run output"));
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
				return DiagnosticResult.info(ErrorCode.NOT_INITIALIZED_STATE,
						"No state file found — this is expected before the first learn run",
						List.of("Run learn mode to start collecting test history: mvn test -Dtestorder.mode=learn"));
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
		// methodHashFile is only created when method-level ordering is enabled;
		// do not report it as missing for users who have not enabled that feature.

		if (!missing.isEmpty()) {
			return DiagnosticResult.info(ErrorCode.HASH_FILE_STALE,
					"Hash snapshot files missing: " + String.join(", ", missing),
					List.of("Hash snapshots track file changes between runs (since-last-run mode)",
							"They are created automatically during learn mode runs",
							"To create manually: mvn test-order:snapshot (or Gradle: testOrderSnapshot)",
							"If using git-based change detection, these files are optional"));
		}

		return DiagnosticResult.success("Hash files are present");
	}

	private static DiagnosticResult checkPermissions(DiagnosticConfig config) {
		Path testOrderDir = config.indexFile().getParent();
		try {
			if (!Files.exists(testOrderDir)) {
				return DiagnosticResult.info(ErrorCode.NOT_INITIALIZED_DIR,
						".test-order directory does not exist yet — this is expected before the first learn run",
						List.of("The directory will be created automatically on the first learn run"));
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
			if (config.testSourceRoot() == null || !Files.exists(config.testSourceRoot())) {
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

	private static DiagnosticResult checkFallbackPayload(DiagnosticConfig config) {
		Path fallbackFile = config.indexFile().resolveSibling(config.indexFile().getFileName() + ".collector-fallback");
		if (!Files.exists(fallbackFile)) {
			return null;
		}
		return DiagnosticResult.info(me.bechberger.testorder.ErrorCode.FALLBACK_PAYLOAD_PENDING,
				"Unprocessed fallback payload from a previous learn run (\"Wrote fallback payloads\" in log)",
				List.of(
						"Most likely cause: <extensions>true</extensions> is missing from the plugin declaration.",
						"Fix: add <extensions>true</extensions> to the test-order-maven-plugin block in pom.xml:",
						"  <plugin>",
						"    <groupId>me.bechberger</groupId>",
						"    <artifactId>test-order-maven-plugin</artifactId>",
						"    <extensions>true</extensions>   ← add this line",
						"    ...",
						"  </plugin>",
						"Without it, the index is written one build late (results are still correct but noisy).",
						"The pending payload at " + fallbackFile.toAbsolutePath() + " will be merged on the next run."));
	}

	/**
	 * Counts top-level .class files in the test output directory as a proxy for
	 * test class count.
	 */
	private static int countTestClasses(Path testClassesDir) {
		if (testClassesDir == null || !Files.isDirectory(testClassesDir))
			return 0;
		try (Stream<Path> walk = Files.walk(testClassesDir)) {
			return (int) walk.filter(p -> {
				String name = p.getFileName().toString();
				// Count only top-level test classes (no $ inner classes)
				return name.endsWith(".class") && !name.contains("$");
			}).count();
		} catch (IOException e) {
			return 0;
		}
	}

	private static DiagnosticResult checkDepsDirectory(DiagnosticConfig config) {
		try {
			if (!Files.exists(config.depsDir())) {
				return DiagnosticResult.info(ErrorCode.DEPS_NOT_FOUND, "No .deps directory found",
						List.of("Run learn mode to collect per-test dependency data: mvn test -Dtestorder.mode=learn",
								"The .deps directory is created automatically on the first learn run"));
			}

			long fileCount;
			try (var files = Files.list(config.depsDir())) {
				fileCount = files.filter(p -> p.getFileName().toString().endsWith(".deps")).count();
			}

			if (fileCount == 0) {
				return DiagnosticResult.info(ErrorCode.DEPS_NOT_FOUND, ".deps directory exists but is empty",
						List.of("Run tests in learn mode to populate .deps files: mvn test -Dtestorder.mode=learn"));
			}

			return DiagnosticResult.success(".deps directory contains " + fileCount + " dependency files");
		} catch (IOException e) {
			return DiagnosticResult.error(ErrorCode.INDEX_READ_ERROR,
					"Failed to check .deps directory: " + e.getMessage(), List.of("Check directory permissions"));
		}
	}

	private static String determineStatus(int score, List<DiagnosticResult> results) {
		boolean hasErrors = results.stream().anyMatch(DiagnosticResult::isError);
		boolean freshProject = results.stream().anyMatch(r -> r.code() == ErrorCode.NOT_INITIALIZED_INDEX);
		if (freshProject && !hasErrors) {
			return "NOT SET UP (run learn mode first)";
		}
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
