package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

/**
 * Shared metrics-generation workflow used by both Maven and Gradle plugins.
 * Collects index health, state-based test metrics, and change detection stats
 * into a {@link TestMetricsExport} object.
 */
public final class MetricsWorkflow {

	private MetricsWorkflow() {
	}

	/**
	 * Generates a metrics export from the given context.
	 *
	 * @param projectName
	 *            display name (e.g., artifactId or project.name)
	 * @param indexPath
	 *            path to the dependency index file
	 * @param statePath
	 *            path to the state file
	 * @param testClassesDir
	 *            path to compiled test classes (for stale-entry detection), may be
	 *            null
	 * @param ctx
	 *            plugin context (for change detection); may be null to skip change
	 *            detection
	 * @param noIndexHint
	 *            message to show when no index found (e.g., "Run mvn test
	 *            -Dtestorder.mode=learn")
	 * @param log
	 *            plugin logger
	 * @return the generated metrics export
	 */
	public static TestMetricsExport generate(String projectName, Path indexPath, Path statePath, Path testClassesDir,
			PluginContext ctx, String noIndexHint, PluginLog log) {

		TestMetricsExport.Builder builder = new TestMetricsExport.Builder(projectName, "report");

		// Index health metrics
		if (Files.exists(indexPath)) {
			try {
				DependencyMap map = DependencyMap.load(indexPath);
				long indexAge = (System.currentTimeMillis() - Files.getLastModifiedTime(indexPath).toMillis()) / 1000;
				builder.indexAge(indexAge);

				long totalEntries = map.size();
				long staleEntries = 0;
				if (testClassesDir != null && Files.isDirectory(testClassesDir)) {
					Set<String> liveTests = TestClassDiscovery.scanTestClasses(testClassesDir);
					for (String tc : map.testClasses()) {
						if (!liveTests.contains(tc)) {
							staleEntries++;
						}
					}
				}
				builder.indexHealth(totalEntries, staleEntries);
			} catch (IOException e) {
				builder.addRecommendation("Index is unreadable: " + e.getMessage() + ". Clean up and re-learn.");
			}
		} else {
			builder.addRecommendation("No dependency index found. " + noIndexHint);
		}

		// State-based metrics
		if (Files.exists(statePath)) {
			try {
				TestOrderState state = TestOrderState.load(statePath);
				int totalTests = state.getClassDurations().size();
				builder.testMetrics(totalTests, 0, 0);
			} catch (IOException e) {
				builder.addRecommendation("State file unreadable: " + e.getMessage());
			}
		}

		// Change detection metrics
		if (ctx != null) {
			try {
				Set<String> changedClasses = ChangeDetectionOps.detectChangedClasses(ctx.changeMode(),
						ctx.projectRoot(), ctx.sourceRoot(), ctx.hashFile(), ctx.changedClasses(), true, log);
				Set<String> changedTests = ChangeDetectionOps.detectChangedTestClasses(ctx.changeMode(),
						ctx.projectRoot(), ctx.testSourceRoot(), ctx.testHashFile(), true, log);
				builder.changesDetected(changedClasses.size(), changedTests.size(), 0);
			} catch (Exception e) {
				log.debug("[test-order] Change detection unavailable for metrics: " + e.getMessage());
			}
		}

		return builder.build();
	}

	/**
	 * Writes metrics JSON to the specified output file, creating parent directories
	 * as needed.
	 */
	public static void writeToFile(TestMetricsExport metrics, Path outputFile, PluginLog log) throws IOException {
		Path parent = outputFile.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.writeString(outputFile, metrics.toJson());
		log.info("[test-order] Metrics written to " + outputFile);
	}
}
