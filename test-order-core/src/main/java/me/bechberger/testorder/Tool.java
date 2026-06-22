package me.bechberger.testorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.testorder.changes.ChangeDetector;
import me.bechberger.testorder.changes.FileHashStore;
import me.bechberger.testorder.changes.StructuralDiff;
import me.bechberger.testorder.ops.ExportJsonOperation;
import me.bechberger.testorder.ops.PluginLog;

/**
 * CLI tool for managing test-order dependency indexes and change detection.
 */
@Command(name = "test-order", mixinStandardHelpOptions = true, description = "Manage JUnit test ordering based on dependency telemetry", subcommands = {
		Tool.Aggregate.class, Tool.Affected.class, Tool.Stats.class, Tool.HashSnapshot.class, Tool.Changed.class,
		Tool.Run.class, Tool.Dump.class, Tool.ExportJson.class, Tool.Optimize.class, Tool.Select.class,
		Tool.StructDiff.class, Tool.Advise.class})
public class Tool implements Runnable {

	@Override
	public void run() {
		System.err.println("Usage: test-order <subcommand> [options]");
		System.err.println();
		System.err.println("Subcommands:");
		System.err.println("  aggregate     Aggregate .deps files into a dependency index");
		System.err.println("  affected      List test classes affected by changed classes");
		System.err.println("  stats         Print dependency index statistics");
		System.err.println("  hash-snapshot Scan source tree and save file hash snapshot");
		System.err.println("  changed       Detect changed source files");
		System.err.println("  run           Detect changes and print affected test classes");
		System.err.println("  dump          Dump a binary dependency index as human-readable text");
		System.err.println("  export-json   Export a binary dependency index as JSON");
		System.err.println("  optimize      Analyse run history and optimise scoring weights");
		System.err.println("  select        (deprecated — use 'affected') Select a fast subset of tests");
		System.err.println("  struct-diff   Structural diff of Java files (types, methods, fields)");
		System.err.println("  advise        Analyse per-method dependency overlap and suggest splits");
		System.err.println();
		System.err.println("Run 'test-order <subcommand> --help' for subcommand-specific options.");
		System.exit(1);
	}

	public static void main(String[] args) {
		String resolvedVersion = resolveVersion();
		System.exit(FemtoCli.builder().commandConfig(c -> c.version = resolvedVersion).run(new Tool(), args));
	}

	/**
	 * Reads the build version from the Maven-generated pom.properties bundled in
	 * the JAR. Falls back to "unknown" when running outside a packaged JAR (e.g.
	 * tests, IDE).
	 */
	static String resolveVersion() {
		try (var in = Tool.class.getResourceAsStream("/META-INF/maven/me.bechberger/test-order-core/pom.properties")) {
			if (in != null) {
				java.util.Properties props = new java.util.Properties();
				props.load(in);
				String v = props.getProperty("version");
				if (v != null && !v.isBlank())
					return v;
			}
		} catch (IOException ignored) {
		}
		return "unknown";
	}

	/**
	 * Splits a comma-separated (or semicolon-separated) class list, trimming
	 * whitespace and ignoring empty/duplicate entries.
	 */
	private static Set<String> splitClasses(String csv) {
		String sep = csv.contains(",") || !csv.contains(";") ? "," : ";";
		return Arrays.stream(csv.split(sep)).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
	}

	/**
	 * Checks that an index file exists; prints a helpful error and returns false if
	 * not.
	 */
	private static boolean checkIndexExists(Path indexFile) {
		if (!Files.exists(indexFile)) {
			System.err.println("Error: dependency index not found: " + indexFile);
			System.err.println("Run: mvn test -Dtestorder.mode=learn");
			return false;
		}
		return true;
	}

	/**
	 * True when {@code -Dtestorder.verbose=true} is set; surfaces stack traces in
	 * error paths.
	 */
	static boolean isVerbose() {
		return Boolean.parseBoolean(System.getProperty("testorder.verbose", "false"));
	}

	/**
	 * Print a contextual error message for a Tool subcommand. Prefixes the
	 * subcommand name, includes the exception message, and points to a recovery
	 * hint. Stack trace is surfaced only when {@code -Dtestorder.verbose=true}.
	 */
	private static int reportError(String subcommand, String action, String hint, Throwable e) {
		System.err.println("[test-order " + subcommand + "] " + action + ": " + e.getMessage());
		if (hint != null && !hint.isBlank()) {
			System.err.println("Hint: " + hint);
		}
		if (isVerbose()) {
			e.printStackTrace(System.err);
		} else {
			System.err.println("Run with -Dtestorder.verbose=true for the full stack trace.");
		}
		return 1;
	}

	@Command(name = "aggregate", description = "Aggregate .deps files into a dependency index", mixinStandardHelpOptions = true)
	static class Aggregate implements Callable<Integer> {
		@Parameters(description = "Directory containing .deps files")
		Path depsDir;

		@Option(names = {"-o",
				"--output"}, description = "Output index file (default: ${DEFAULT-VALUE})", defaultValue = "test-dependencies.lz4")
		Path output;

		@Override
		public Integer call() {
			try {
				if (!Files.isDirectory(depsDir)) {
					System.err.println("Error: deps directory does not exist or is not a directory: " + depsDir);
					System.err.println("Run: mvn test -Dtestorder.mode=learn");
					return 1;
				}
				DependencyMap map = DependencyMap.aggregate(depsDir);
				if (map.size() == 0) {
					if (Files.exists(output)) {
						System.err.println("No .deps files found — refusing to overwrite existing index at " + output);
						System.err.println("If you intended to clear the index, delete " + output + " manually.");
						return 1;
					} else {
						System.err.println("No .deps files found — no index to write.");
						System.err.println("Run: mvn test -Dtestorder.mode=learn");
						return 1;
					}
				}
				map.save(output);
				System.out.printf("Aggregated %d test classes → %s%n", map.size(), output);
				return 0;
			} catch (IOException e) {
				return reportError("aggregate", "Failed to aggregate .deps files",
						"Check write permissions on the deps directory and output path.\nRun: mvn test-order:diagnose",
						e);
			}
		}
	}

	@Command(name = "affected", description = "List test classes affected by changed classes", mixinStandardHelpOptions = true)
	static class Affected implements Callable<Integer> {
		@Parameters(description = "Dependency index file")
		Path indexFile;

		@Option(names = {"-c", "--classes"}, required = true, description = "Comma-separated changed class FQCNs")
		String changedClasses;

		@Override
		public Integer call() {
			try {
				if (!checkIndexExists(indexFile))
					return 1;
				DependencyMap map = DependencyMap.load(indexFile);
				Set<String> changed = splitClasses(changedClasses);
				Set<String> affected = map.getAffectedTests(changed);
				if (affected.isEmpty()) {
					System.out.println("No affected test classes found.");
				} else {
					affected.stream().sorted().forEach(System.out::println);
				}
				return 0;
			} catch (IOException e) {
				return reportError("affected", "Failed to load index or compute affected tests",
						"The index may be missing or corrupted.\nRun: mvn test-order:diagnose\nRun: mvn test-order:clean\nRun: mvn test -Dtestorder.mode=learn",
						e);
			}
		}
	}

	@Command(name = "stats", description = "Print dependency index statistics", mixinStandardHelpOptions = true)
	static class Stats implements Callable<Integer> {
		@Parameters(description = "Dependency index file")
		Path indexFile;

		@Override
		public Integer call() {
			try {
				if (!checkIndexExists(indexFile))
					return 1;
				DependencyMap map = DependencyMap.load(indexFile);
				System.out.printf("Test classes:          %d%n", map.size());
				System.out.printf("Unique app classes:    %d%n", map.totalUniqueClasses());
				System.out.printf(Locale.US, "Avg deps per test:     %.1f%n", map.averageDeps());
				return 0;
			} catch (IOException e) {
				return reportError("stats", "Failed to load dependency index",
						"The file may be corrupted.\nRun: mvn test-order:diagnose\nRun: mvn test-order:clean\nRun: mvn test -Dtestorder.mode=learn",
						e);
			}
		}
	}

	@Command(name = "hash-snapshot", description = "Scan source tree and save file hash snapshot", mixinStandardHelpOptions = true)
	static class HashSnapshot implements Callable<Integer> {
		@Option(names = {"-s",
				"--source-root"}, description = "Source root directory (default: ${DEFAULT-VALUE})", defaultValue = "src/main/java")
		Path sourceRoot;

		@Option(names = {"-o",
				"--output"}, description = "Hash file path (default: ${DEFAULT-VALUE})", defaultValue = ".test-order-hashes.lz4")
		Path hashFile;

		@Override
		public Integer call() {
			try {
				if (!Files.isDirectory(sourceRoot)) {
					System.err.println("Error: source root directory does not exist: " + sourceRoot);
					System.err.println("Use --source-root to specify the correct path.");
					return 1;
				}
				FileHashStore store = FileHashStore.scan(sourceRoot);
				store.save(hashFile);
				System.out.printf("Snapshot: %d files → %s%n", store.getHashes().size(), hashFile);
				return 0;
			} catch (IOException e) {
				return reportError("hash-snapshot", "Failed to scan source tree or write hash file",
						"Check read permissions on the source root and write permissions on the hash file's parent directory",
						e);
			}
		}
	}

	@Command(name = "changed", description = "Detect changed source files", mixinStandardHelpOptions = true)
	static class Changed implements Callable<Integer> {
		@Option(names = {"-m",
				"--mode"}, description = "Detection mode: since-last-run, since-last-commit, uncommitted, explicit (default: ${DEFAULT-VALUE})", defaultValue = "since-last-run")
		String modeStr;

		@Option(names = {"-p",
				"--project-root"}, description = "Project root directory (default: ${DEFAULT-VALUE})", defaultValue = ".")
		Path projectRoot;

		@Option(names = {"-s",
				"--source-root"}, description = "Source root (relative to project root, default: ${DEFAULT-VALUE})", defaultValue = "src/main/java")
		Path sourceRoot;

		@Option(names = {
				"--hash-file"}, description = "Hash file path (default: ${DEFAULT-VALUE})", defaultValue = ".test-order-hashes.lz4")
		Path hashFile;

		@Option(names = {"-c", "--classes"}, description = "Comma-separated class FQCNs (for EXPLICIT mode)")
		String classes;

		@Override
		public Integer call() {
			try {
				ChangeDetector.Mode mode = ChangeDetector.Mode.parse(modeStr);
				if (mode == ChangeDetector.Mode.EXPLICIT && (classes == null || classes.isBlank())) {
					System.err.println(
							"Warning: --mode=explicit requires --classes/-c to specify changed class FQCNs. No classes provided — returning empty result.");
				}
				Set<String> changed = ChangeDetector.detect(mode, projectRoot, sourceRoot, hashFile, classes);
				if (changed.isEmpty()) {
					System.out.println("No changes detected.");
				} else {
					changed.stream().sorted().forEach(System.out::println);
				}
				return 0;
			} catch (IllegalArgumentException e) {
				return reportError("changed", "Invalid change-detection mode",
						"Valid modes: since-last-run, since-last-commit, uncommitted, explicit", e);
			} catch (IOException e) {
				return reportError("changed", "Failed to detect changes",
						"Verify the project root, source root, and hash file paths; ensure the hash file is readable",
						e);
			}
		}
	}

	@Command(name = "run", description = "Detect changes and print affected test classes", mixinStandardHelpOptions = true)
	static class Run implements Callable<Integer> {
		@Parameters(description = "Dependency index file")
		Path indexFile;

		@Option(names = {"-m",
				"--mode"}, description = "Detection mode: since-last-run, since-last-commit, uncommitted, explicit (default: ${DEFAULT-VALUE})", defaultValue = "since-last-run")
		String modeStr;

		@Option(names = {"-p",
				"--project-root"}, description = "Project root (default: ${DEFAULT-VALUE})", defaultValue = ".")
		Path projectRoot;

		@Option(names = {"-s",
				"--source-root"}, description = "Source root (default: ${DEFAULT-VALUE})", defaultValue = "src/main/java")
		Path sourceRoot;

		@Option(names = {
				"--hash-file"}, description = "Hash file path (default: ${DEFAULT-VALUE})", defaultValue = ".test-order-hashes.lz4")
		Path hashFile;

		@Option(names = {"-c", "--classes"}, description = "Comma-separated class FQCNs (for EXPLICIT mode)")
		String classes;

		@Override
		public Integer call() {
			try {
				if (!checkIndexExists(indexFile))
					return 1;
				ChangeDetector.Mode mode = ChangeDetector.Mode.parse(modeStr);
				if (mode == ChangeDetector.Mode.EXPLICIT && (classes == null || classes.isBlank())) {
					System.err.println(
							"Warning: --mode=explicit requires --classes/-c to specify changed class FQCNs. No classes provided — returning empty result.");
				}
				Set<String> changed = ChangeDetector.detect(mode, projectRoot, sourceRoot, hashFile, classes);
				if (changed.isEmpty()) {
					System.out.println("No changes detected → all tests run in default order.");
					return 0;
				}
				System.out.println("Changed classes:");
				changed.forEach(c -> System.out.println("  " + c));

				DependencyMap map = DependencyMap.load(indexFile);
				Set<String> affected = map.getAffectedTests(changed);
				if (affected.isEmpty()) {
					System.out.println("No affected test classes found.");
				} else {
					System.out.println("Affected test classes (run first):");
					affected.stream().sorted().forEach(t -> System.out.println("  " + t));
				}
				return 0;
			} catch (IllegalArgumentException e) {
				return reportError("run", "Invalid change-detection mode",
						"Valid modes: since-last-run, since-last-commit, uncommitted, explicit", e);
			} catch (IOException e) {
				return reportError("run", "Failed to detect changes or load index",
						"Verify the index, project root, source root, and hash file paths", e);
			}
		}
	}

	@Command(name = "dump", description = "Dump a binary dependency index (data format v1) as human-readable text", mixinStandardHelpOptions = true)
	static class Dump implements Callable<Integer> {
		@Parameters(description = "Dependency index file (binary data format v1)")
		Path indexFile;

		@Option(names = {"-o", "--output"}, description = "Output file (default: stdout)")
		Path output;

		@Override
		public Integer call() {
			try {
				if (!checkIndexExists(indexFile))
					return 1;
				DependencyMap map = DependencyMap.load(indexFile);
				if (map.size() == 0) {
					System.err.println("Dependency index is empty: " + indexFile);
					System.err.println("Run: mvn test -Dtestorder.mode=learn");
					return 1;
				}
				if (output != null) {
					map.saveText(output);
					System.out.printf("Dumped %d test classes → %s%n", map.size(), output);
				} else {
					// write to stdout, sorted for reproducible output
					map.testClasses().stream().sorted().forEach(tc -> {
						System.out.print(tc);
						System.out.print('\t');
						System.out.println(String.join(",", map.get(tc).stream().sorted().toList()));
					});
				}
				return 0;
			} catch (IOException e) {
				return reportError("dump", "Failed to read or write dependency index",
						"Check the index path is readable and the output path is writable", e);
			}
		}
	}

	@Command(name = "export-json", description = "Export a binary dependency index (and history) as JSON", mixinStandardHelpOptions = true)
	static class ExportJson implements Callable<Integer> {
		@Parameters(description = "Dependency index file (binary data format v1)")
		Path indexFile;

		@Option(names = {"-o", "--output"}, description = "Output JSON file (default: stdout)")
		Path output;

		@Option(names = {"-s", "--state"}, description = "State file for history data (default: .test-order/state.lz4)")
		Path stateFile;

		@Override
		public Integer call() {
			try {
				if (!checkIndexExists(indexFile))
					return 1;
				PluginLog log = new PluginLog() {
					@Override
					public void info(String message) {
						System.err.println(message);
					}

					@Override
					public void warn(String message) {
						System.err.println(message);
					}

					@Override
					public void debug(String message) {
						// keep CLI output clean
					}
				};
				Path resolvedState = stateFile;
				if (resolvedState == null) {
					// The state file is always written to .test-order/state.lz4 relative to cwd
					Path candidate = Path.of(".test-order/state.lz4");
					if (java.nio.file.Files.exists(candidate)) {
						resolvedState = candidate;
					}
				}
				if (output != null) {
					ExportJsonOperation.export(indexFile, resolvedState, output, log);
					if (java.nio.file.Files.exists(output)) {
						System.out.printf("Exported dependency index as JSON → %s%n", output);
					}
				} else {
					ExportJsonOperation.export(indexFile, resolvedState, System.out, log);
				}
				return 0;
			} catch (IOException e) {
				return reportError("export-json", "Failed to export index/state as JSON",
						"Check the index, state, and output paths are accessible", e);
			}
		}
	}

	@Command(name = "optimize", description = "Analyse run history and optimise scoring weights", mixinStandardHelpOptions = true)
	static class Optimize implements Callable<Integer> {
		@Parameters(description = "State file (.test-order-state)", defaultValue = ".test-order-state")
		Path stateFile;

		@Override
		public Integer call() {
			try {
				if (!Files.exists(stateFile)) {
					System.err.println("Error: state file not found: " + stateFile);
					System.err.println("Run tests in order mode first to generate state data.");
					return 1;
				}
				TestOrderState state = TestOrderState.load(stateFile);
				java.util.List<TestOrderState.RunRecord> runs = state.runs();
				if (runs.isEmpty()) {
					System.out.println("No run history found.");
					return 0;
				}

				long withFailures = runs.stream().filter(r -> r.totalFailures() > 0).count();
				System.out.printf("Runs: %d total, %d with failures%n", runs.size(), withFailures);
				System.out.println();

				System.out.println("Run history (most recent last):");
				for (TestOrderState.RunRecord r : runs) {
					String date = java.time.Instant.ofEpochMilli(r.timestamp()).atZone(java.time.ZoneId.systemDefault())
							.toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
					if (r.totalFailures() > 0) {
						System.out.printf(Locale.US, "  %s  tests=%d  failures=%d  first-fail@%d  APFD=%.1f%%%n", date,
								r.totalTests(), r.totalFailures(), r.firstFailurePosition() + 1, r.apfd() * 100);
					} else {
						System.out.printf("  %s  tests=%d  all passed%n", date, r.totalTests());
					}
				}
				System.out.println();

				System.out.printf("Current weights:  %s%n", state.weights().format());
				System.out.println();

				TestOrderState.OptimizeResult opt = state.optimize();
				if (opt == null) {
					System.out.printf("Need at least %d runs with failures to optimise (have %d).%n",
							OptimizationDefaults.MIN_RUNS_FOR_OPTIMISATION, withFailures);
					return 0;
				}

				// save optimised weights back to the state file
				state.setWeights(opt.weights());
				state.save(stateFile);

				System.out.printf("Optimised weights saved:  %s%n", opt.weights().format());
				System.out.printf(Locale.US, "  Training APFDc:    %.1f%%%n", opt.trainScore() * 100);
				System.out.printf(Locale.US, "  Validation APFDc:  %.1f%%%n", opt.validationScore() * 100);
				System.out.printf("  Folds:             %d%n", opt.folds());
				if (opt.overfit()) {
					System.out.println("  WARNING: Overfitting detected — default weights used instead.");
				}
				return 0;
			} catch (IOException e) {
				return reportError("optimize", "Failed to load state or save optimised weights",
						"Check the state file path is readable and writable; need at least a few learn runs of history",
						e);
			}
		}

	}

	@Command(name = "select", description = "DEPRECATED — use 'affected' instead. Select a fast subset of tests (new + top-n + m diverse fast tests)", mixinStandardHelpOptions = true)
	static class Select implements Callable<Integer> {
		@Parameters(description = "Dependency index file")
		Path indexFile;

		@Option(names = {
				"--state"}, description = "State file (default: ${DEFAULT-VALUE})", defaultValue = ".test-order-state")
		Path stateFile;

		@Option(names = {
				"--top-n"}, description = "Number of top-scored tests (default: ${DEFAULT-VALUE})", defaultValue = "20")
		int topN;

		@Option(names = {
				"--random-m"}, description = "Number of random diverse fast tests (default: ${DEFAULT-VALUE})", defaultValue = "10")
		int randomM;

		@Option(names = {"--seed"}, description = "Random seed for reproducibility")
		Long seed;

		@Option(names = {"-c", "--changed"}, description = "Comma-separated changed class FQCNs")
		String changedClasses;

		@Option(names = {"--changed-tests"}, description = "Comma-separated changed test class FQCNs")
		String changedTestClasses;

		@Option(names = {
				"--selected-file"}, description = "Output file for selected tests", defaultValue = "test-order-selected.txt")
		Path selectedFile;

		@Option(names = {
				"--remaining-file"}, description = "Output file for remaining tests", defaultValue = "test-order-remaining.txt")
		Path remainingFile;

		@Override
		public Integer call() {
			System.err
					.println("Warning: 'select' is deprecated — use 'mvn test-order:affected test' in Maven instead.");
			if (topN < -1) {
				System.err.println("Error: --top-n must be >= -1 (got " + topN + "). Use -1 for all affected tests.");
				return 1;
			}
			if (randomM < 0) {
				System.err.println("Error: --random-m must be >= 0 (got " + randomM + ").");
				return 1;
			}
			if (topN == 0) {
				System.err.println(
						"Warning: --top-n=0 selects no top-scored tests. New tests and @AlwaysRun tests are still included. "
								+ "Use --top-n=-1 to select all change-affected tests.");
			}
			try {
				if (!java.nio.file.Files.exists(indexFile)) {
					System.err.println("Error: dependency index not found: " + indexFile);
					System.err.println("Run: mvn test -Dtestorder.mode=learn");
					return 1;
				}
				DependencyMap depMap = DependencyMap.load(indexFile);
				Path effectiveStateFile = stateFile != null ? stateFile : Path.of(".test-order-state");
				Path effectiveSelectedFile = selectedFile != null ? selectedFile : Path.of("test-order-selected.txt");
				Path effectiveRemainingFile = remainingFile != null
						? remainingFile
						: Path.of("test-order-remaining.txt");
				TestOrderState state = java.nio.file.Files.exists(effectiveStateFile)
						? TestOrderState.load(effectiveStateFile)
						: new TestOrderState();

				java.util.Set<String> changed = changedClasses != null
						? splitClasses(changedClasses)
						: java.util.Set.of();
				java.util.Set<String> changedTests = changedTestClasses != null
						? splitClasses(changedTestClasses)
						: java.util.Set.of();

				TestSelector.Selection sel = new TestSelector(depMap, state, changed, changedTests, state.weights(),
						new TestSelector.Config(topN, randomM, seed)).select();

				TestSelector.writeTestList(sel.selected(), effectiveSelectedFile);
				TestSelector.writeTestList(sel.remaining(), effectiveRemainingFile);

				System.out.printf("Selected %d tests → %s%n", sel.selected().size(), effectiveSelectedFile);
				System.out.printf("Remaining %d tests → %s%n", sel.remaining().size(), effectiveRemainingFile);
				for (String tc : sel.selected()) {
					System.out.println("  + " + tc);
				}
				return 0;
			} catch (IOException e) {
				return reportError("select", "Failed to load index/state or write test lists",
						"Run: mvn test -Dtestorder.mode=learn", e);
			}
		}
	}

	@Command(name = "struct-diff", description = "Structural diff of Java files (types, methods, fields)", mixinStandardHelpOptions = true)
	static class StructDiff implements Callable<Integer> {

		@Option(names = {"-p",
				"--project-root"}, description = "Project root directory (default: ${DEFAULT-VALUE})", defaultValue = ".")
		Path projectRoot;

		@Option(names = {
				"--ref"}, description = "Git ref to diff against (default: ${DEFAULT-VALUE})", defaultValue = "HEAD")
		String gitRef;

		@Option(names = {"--since-last-commit"}, description = "Include changes from the last commit (HEAD~1..HEAD)")
		boolean sinceLastCommit;

		@Parameters(description = "Specific Java files to diff (default: all changed files)")
		List<Path> files;

		@Override
		public Integer call() {
			try {
				Path root = projectRoot.toAbsolutePath().normalize();

				if (files != null && !files.isEmpty()) {
					// Diff specific files against git ref
					List<StructuralDiff.FileDiff> diffs = new java.util.ArrayList<>();
					for (Path f : files) {
						Path absFile = f.toAbsolutePath().normalize();
						if (!Files.isRegularFile(absFile) || !absFile.toString().endsWith(".java")) {
							System.err.println("Skipping non-Java file: " + f);
							continue;
						}
						StructuralDiff.FileDiff diff = StructuralDiff.diffAgainstGit(absFile, root, gitRef);
						if (diff.hasChanges())
							diffs.add(diff);
					}
					System.out.print(StructuralDiff.formatReport(diffs));
				} else if (sinceLastCommit) {
					List<StructuralDiff.FileDiff> diffs = StructuralDiff.diffSinceLastCommit(root);
					System.out.print(StructuralDiff.formatReport(diffs));
				} else {
					List<StructuralDiff.FileDiff> diffs = StructuralDiff.diffUncommitted(root, gitRef);
					System.out.print(StructuralDiff.formatReport(diffs));
				}
				return 0;
			} catch (IOException e) {
				return reportError("struct-diff", "Failed to compute structural diff",
						"Verify the git repository root is correct and the working tree is accessible", e);
			}
		}
	}

	@Command(name = "advise", description = "Analyse per-method dependency overlap and suggest test classes to split", mixinStandardHelpOptions = true)
	static class Advise implements Callable<Integer> {

		@Parameters(description = "Dependency index file")
		Path indexFile;

		@Option(names = {"--threshold"}, description = "Similarity threshold (default: ${DEFAULT-VALUE}); "
				+ "classes whose avg pairwise Jaccard similarity is below this are flagged", defaultValue = "0.3")
		double threshold;

		@Option(names = {"--verbose", "-v"}, description = "Print per-class details including suggested split groups")
		boolean verbose;

		@Override
		public Integer call() {
			if (threshold < 0.0 || threshold > 1.0) {
				System.err.println("Error: --threshold must be in [0.0, 1.0] (got " + threshold + ").");
				return 1;
			}
			try {
				if (!checkIndexExists(indexFile))
					return 1;
				DependencyMap depMap = DependencyMap.load(indexFile);

				if (!depMap.hasMethodDeps()) {
					System.out.println("No per-method dependency data found in index.");
					System.out.println(
							"Ensure your test run includes the test-order agent with method-level coverage tracking.");
					return 0;
				}

				List<TestSplitAdvice> advice = TestSplitAdvisor.analyze(depMap, threshold);

				if (advice.isEmpty()) {
					System.out.printf(Locale.US,
							"No split candidates found (threshold=%.2f). All classes have sufficiently cohesive methods.%n",
							threshold);
					return 0;
				}

				System.out.printf(Locale.US, "Found %d split candidate%s (threshold=%.2f):%n%n", advice.size(),
						advice.size() == 1 ? "" : "s", threshold);

				for (TestSplitAdvice a : advice) {
					System.out.println("  " + a.summary());
					if (verbose) {
						System.out.println("  reason: " + a.reason());
						for (int i = 0; i < a.suggestedGroups().size(); i++) {
							System.out.printf("    group %d: %s%n", i + 1, a.suggestedGroups().get(i));
						}
						System.out.println();
					}
				}
				return 0;
			} catch (IOException e) {
				return reportError("advise", "Failed to load index or compute method-level advice",
						"Per-method advice requires method-level instrumentation.\nRun: mvn test -Dtestorder.instrumentationMode=METHOD -Dtestorder.mode=learn",
						e);
			}
		}
	}
}
