package me.bechberger.testorder.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;

/**
 * Drains all active IndexCollectorServer instances after the Maven session
 * ends, while the plugin classloader is still alive.
 * <p>
 * Without this, stopAndMerge() falls back to the JVM shutdown hook, which races
 * with Maven's classloader teardown and fails with NoClassDefFoundError on the
 * RoaringBitmap serialization path.
 * <p>
 * Also merges per-fork partial RunRecords (written by TelemetryListener when
 * build-session aggregation is enabled) into a single per-build RunRecord.
 * <p>
 * Registered as a Plexus component in META-INF/plexus/components.xml.
 */
public class CollectorLifecycleParticipant extends AbstractMavenLifecycleParticipant {

	@Override
	public void afterSessionEnd(MavenSession session) {
		drainCollectors();
		mergePartialRunRecords();
		restoreInstrumentedClasses();
	}

	private void drainCollectors() {
		Map<Path, me.bechberger.testorder.IndexCollectorServer> collectors = AbstractTestOrderMojo.activeCollectors;
		if (collectors.isEmpty()) {
			return;
		}
		List<Path> keys = new ArrayList<>(collectors.keySet());
		for (Path key : keys) {
			me.bechberger.testorder.IndexCollectorServer collector = collectors.remove(key);
			if (collector == null) {
				continue;
			}
			try {
				int merged = collector.stopAndMerge();
				if (merged > 0) {
					System.out.println(
							"[test-order] IndexCollectorServer merged " + merged + " test classes (session end)");
				}
			} catch (Exception | NoClassDefFoundError e) {
				System.err.println("[test-order] CollectorLifecycleParticipant: merge failed for " + key + ": " + e);
			}
		}
	}

	private void mergePartialRunRecords() {
		Map<String, AbstractTestOrderMojo.PendingAggregation> aggregations = AbstractTestOrderMojo.pendingAggregations;
		if (aggregations.isEmpty()) {
			return;
		}

		// Group by buildId (key format is "buildId|stateFilePath")
		// Multiple modules may share the same buildId but have different state files.
		// Process each (buildId, stateFile) pair separately.
		List<String> keys = new ArrayList<>(aggregations.keySet());
		for (String key : keys) {
			AbstractTestOrderMojo.PendingAggregation agg = aggregations.remove(key);
			if (agg == null) {
				continue;
			}
			String buildId = key.contains("|") ? key.substring(0, key.indexOf('|')) : key;
			try {
				boolean merged = me.bechberger.testorder.PartialRunAggregator.mergeAndApply(agg.pendingRunsDir(),
						buildId, agg.stateFile());
				if (merged) {
					System.out.println("[test-order] Aggregated per-fork run records into one RunRecord for build "
							+ buildId.substring(0, 8) + "...");
				}
			} catch (Exception e) {
				System.err.println("[test-order] CollectorLifecycleParticipant: partial run merge failed for "
						+ agg.stateFile() + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Restore class trees that were offline-instrumented during this session.
	 * Without this, a subsequent {@code mvn} invocation (without {@code clean})
	 * would re-run plugin executions like log4j2's
	 * {@code generate-plugin-descriptors} against instrumented bytecode and fail
	 * with {@code NoClassDefFoundError} on {@code UsageStore} — the annotation
	 * processor classpath does not include the test-order agent jar.
	 */
	private void restoreInstrumentedClasses() {
		java.util.Set<Path> backups = AbstractTestOrderMojo.pendingRestores;
		if (backups.isEmpty()) {
			return;
		}
		List<Path> snapshot = new ArrayList<>(backups);
		backups.clear();
		for (Path backup : snapshot) {
			try {
				me.bechberger.testorder.agent.OfflineInstrumentor.restore(backup);
			} catch (Exception | NoClassDefFoundError e) {
				System.err
						.println("[test-order] CollectorLifecycleParticipant: restore failed for " + backup + ": " + e);
			}
		}
	}
}
