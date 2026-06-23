package me.bechberger.testorder.ops.detection;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import me.bechberger.testorder.ops.DetectDependenciesOperation;
import me.bechberger.testorder.ops.DetectDependenciesOperation.Config;
import me.bechberger.testorder.ops.DetectDependenciesOperation.Result;
import me.bechberger.testorder.ops.PluginLog;

/**
 * Integration test that runs detect-dependencies against a sample project with
 * known OD bugs (sample-od-bugs).
 *
 * <p>
 * Run directly: {@code java DetectDependenciesIT.java} or via Maven: mvn test
 * -Dtest=DetectDependenciesIT -Dtestorder.it=true
 */
public class DetectDependenciesIT {

	private static final Path SAMPLE_DIR = findSampleDir();
	private static final List<String> TEST_CLASSES = List.of("com.example.od.SetupTest", "com.example.od.VictimTest",
			"com.example.od.CounterTest", "com.example.od.IndependentTest");

	public static void main(String[] args) throws Exception {
		System.out.println("=== Detect-Dependencies Integration Test ===");
		System.out.println("Sample dir: " + SAMPLE_DIR);
		System.out.println();

		// Create a TestRunner that invokes mvn test on the sample project
		TestRunner runner = new SampleProjectRunner(SAMPLE_DIR);

		// Test with PFAST (exclusion-based — works even without order control)
		System.out.println("--- Algorithm: pfast ---");
		runDetection("pfast", runner);

		System.out.println();

		// Test with combined adaptive
		System.out.println("--- Algorithm: combined ---");
		runDetection("combined", runner);

		System.out.println("\n=== Done ===");
	}

	private static void runDetection(String algorithm, TestRunner runner) throws IOException {
		Path outputDir = SAMPLE_DIR.resolve("target/detection-" + algorithm);
		Files.createDirectories(outputDir);

		PluginLog log = new PluginLog() {
			@Override
			public void info(String msg) {
				System.out.println("  [INFO] " + msg);
			}
			@Override
			public void warn(String msg) {
				System.out.println("  [WARN] " + msg);
			}
			@Override
			public void debug(String msg) {
				/* quiet */ }
		};

		Config config = new Config(null, // no dependency index
				null, // no state file
				outputDir, algorithm, 60, // 60s time budget
				false, // don't stop on first
				42L, // fixed seed
				"sample-od-bugs", log);

		// We can't load from depMap/state, so we need to pass reference order.
		// The operation falls back to empty if neither exists.
		// Let's patch: provide a reference order via a custom approach.
		// Since the operation needs either state or depMap for reference order,
		// we'll create a minimal DependencyMap.

		// Actually, let's just test the runner directly first
		System.out.println("  Testing runner with reference order...");
		TestRunner.TestRunResult refResult = runner.run(TEST_CLASSES);
		System.out.println("  Reference run: passed=" + refResult.passedTests() + " failed=" + refResult.failedTests());

		// Now run with reversed order
		List<String> reversed = new ArrayList<>(TEST_CLASSES);
		Collections.reverse(reversed);
		TestRunner.TestRunResult revResult = runner.run(reversed);
		System.out.println("  Reversed run: passed=" + revResult.passedTests() + " failed=" + revResult.failedTests());

		// Try the full operation (will need depMap or state for reference order)
		// Create a simple dep map to provide reference order
		me.bechberger.testorder.DependencyMap depMap = new me.bechberger.testorder.DependencyMap();
		for (String tc : TEST_CLASSES) {
			depMap.put(tc, Set.of("com.example.od.GlobalRegistry"));
		}
		// Also add member deps to create conflict edges
		depMap.putMemberDeps("com.example.od.SetupTest",
				Set.of("com.example.od.GlobalRegistry#instance", "com.example.od.GlobalRegistry#counter",
						"com.example.od.GlobalRegistry#initialized", "com.example.od.GlobalRegistry#lastUser"));
		depMap.putMemberDeps("com.example.od.VictimTest", Set.of("com.example.od.GlobalRegistry#instance",
				"com.example.od.GlobalRegistry#initialized", "com.example.od.GlobalRegistry#lastUser"));
		depMap.putMemberDeps("com.example.od.CounterTest",
				Set.of("com.example.od.GlobalRegistry#instance", "com.example.od.GlobalRegistry#counter"));
		depMap.putMemberDeps("com.example.od.IndependentTest", Set.of("com.example.od.GlobalRegistry#instance"));

		// Save dep map temporarily
		Path depMapFile = outputDir.resolve("test-deps.lz4");
		depMap.save(depMapFile);

		Config configWithDeps = new Config(depMapFile, null, outputDir, algorithm, 60, false, 42L, "sample-od-bugs",
				log);

		try {
			Result result = DetectDependenciesOperation.run(configWithDeps, runner);
			System.out.println("  Results: " + result.results().size() + " findings");
			for (var finding : result.results()) {
				System.out.println("    - " + finding.type() + ": " + finding.victim() + " (confidence="
						+ String.format("%.0f%%", finding.confidence() * 100) + ")" + " chain="
						+ finding.dependencyChain());
			}
			if (result.reportPath() != null) {
				System.out.println("  Report: " + result.reportPath());
			}
		} catch (Exception e) {
			System.out.println("  ERROR: " + e.getMessage());
			e.printStackTrace(System.out);
		}
	}

	/**
	 * TestRunner that invokes Maven on the sample-od-bugs project.
	 */
	static class SampleProjectRunner implements TestRunner {
		private final Path projectDir;
		private final Path reportDir;

		SampleProjectRunner(Path projectDir) {
			this.projectDir = projectDir;
			this.reportDir = projectDir.resolve("target/surefire-reports");
		}

		@Override
		public TestRunResult run(List<String> testOrder) {
			if (testOrder.isEmpty()) {
				return new TestRunResult(List.of(), Set.of(), Set.of());
			}

			// Handle wildcard: run all tests without -Dtest filter
			boolean runAll = testOrder.size() == 1 && "*".equals(testOrder.get(0));

			// Build test list from FQCNs → simple names for -Dtest
			String testList = runAll
					? null
					: testOrder.stream()
							.map(fqcn -> fqcn.contains(".") ? fqcn.substring(fqcn.lastIndexOf('.') + 1) : fqcn)
							.collect(Collectors.joining(","));

			// Trace: System.out.println(" [RUNNER] Running: " + (runAll ? "*" : testList));

			try {
				// Clean previous reports
				if (Files.exists(reportDir)) {
					try (var files = Files.list(reportDir)) {
						files.filter(p -> p.toString().endsWith(".xml")).forEach(p -> {
							try {
								Files.deleteIfExists(p);
							} catch (IOException ignored) {
							}
						});
					}
				}

				List<String> command = new ArrayList<>(
						List.of("mvn", "test", "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false",
								"--batch-mode", "--quiet", "-f", projectDir.toString()));
				if (!runAll) {
					command.add(2, "-Dtest=" + testList);
				}
				ProcessBuilder pb = new ProcessBuilder(command);
				pb.directory(projectDir.toFile());
				pb.redirectErrorStream(true);

				Process proc = pb.start();
				try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
					while (reader.readLine() != null) {
						/* discard */ }
				}
				proc.waitFor();

				return parseResults(runAll ? List.of() : testOrder);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return new TestRunResult(testOrder, Set.of(), new HashSet<>(testOrder));
			} catch (IOException e) {
				System.err.println("  Runner error: " + e.getMessage());
				return new TestRunResult(testOrder, Set.of(), new HashSet<>(testOrder));
			}
		}

		private TestRunResult parseResults(List<String> executionOrder) {
			Set<String> passed = new HashSet<>();
			Set<String> failed = new HashSet<>();

			if (!Files.exists(reportDir)) {
				return new TestRunResult(executionOrder, Set.of(), new HashSet<>(executionOrder));
			}

			try (var files = Files.list(reportDir)) {
				files.filter(p -> p.getFileName().toString().startsWith("TEST-")
						&& p.getFileName().toString().endsWith(".xml")).forEach(report -> {
							try {
								String content = Files.readString(report);
								String name = extractAttr(content, "name");
								if (name == null)
									return;

								// Map back to FQCN
								String fqcn = findFqcn(executionOrder, name);

								int failCount = parseInt(extractAttr(content, "failures"))
										+ parseInt(extractAttr(content, "errors"));
								if (failCount > 0) {
									failed.add(fqcn != null ? fqcn : name);
								} else {
									passed.add(fqcn != null ? fqcn : name);
								}
							} catch (IOException ignored) {
							}
						});
			} catch (IOException e) {
				System.err.println("  Could not read reports: " + e.getMessage());
			}

			// Anything not reported is assumed passed (skipped)
			for (String test : executionOrder) {
				if (!passed.contains(test) && !failed.contains(test)) {
					passed.add(test);
				}
			}

			return new TestRunResult(executionOrder, passed, failed);
		}

		private String findFqcn(List<String> order, String simpleName) {
			for (String fqcn : order) {
				if (fqcn.endsWith("." + simpleName) || fqcn.equals(simpleName)) {
					return fqcn;
				}
			}
			return null;
		}

		private static String extractAttr(String xml, String attr) {
			String prefix = attr + "=\"";
			int start = xml.indexOf(prefix);
			if (start < 0)
				return null;
			start += prefix.length();
			int end = xml.indexOf('"', start);
			if (end < 0)
				return null;
			return xml.substring(start, end);
		}

		private static int parseInt(String s) {
			if (s == null || s.isEmpty())
				return 0;
			try {
				return Integer.parseInt(s);
			} catch (NumberFormatException e) {
				return 0;
			}
		}
	}

	private static Path findSampleDir() {
		Path cwd = Path.of("").toAbsolutePath();
		Path candidate = cwd.resolve("samples/sample-od-bugs");
		if (Files.exists(candidate))
			return candidate;
		candidate = cwd.getParent().resolve("samples/sample-od-bugs");
		if (Files.exists(candidate))
			return candidate;
		throw new IllegalStateException("Could not locate samples/sample-od-bugs relative to " + cwd
				+ ". Run this test from the project root or the test-order-maven-plugin module.");
	}
}
