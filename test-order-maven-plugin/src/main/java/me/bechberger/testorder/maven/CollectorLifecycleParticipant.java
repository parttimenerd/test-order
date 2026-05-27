package me.bechberger.testorder.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

import me.bechberger.testorder.ops.ChangeDetectionOps;
import me.bechberger.testorder.ops.PluginLog;
import me.bechberger.testorder.ops.ReactorOrderOperation;
import me.bechberger.testorder.ops.ReactorOrderOperation.ModuleScore;
import me.bechberger.testorder.ops.ReactorOrderOperation.ReactorOrderInput;
import me.bechberger.testorder.ops.ReactorOrderOperation.ReactorOrderResult;

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

	private static final String SHARED_DIR_NAME = ".test-order";
	private static final String INDEX_FILE = "test-dependencies.lz4";
	private static final String STATE_FILE = "state.lz4";

	private static final String PROP_REORDER = "testorder.reactorReorder";
	private static final String PROP_TOPN = "testorder.reactorTopN";
	private static final String PROP_DRYRUN = "testorder.reactorReorder.dryRun";

	@Override
	public void afterProjectsRead(MavenSession session) {
		try {
			tryReorderReactor(session);
		} catch (Exception | NoClassDefFoundError e) {
			System.err.println("[test-order] reactor reorder failed: " + e);
		}
	}

	private void tryReorderReactor(MavenSession session) {
		if (session == null || session.getProjects() == null || session.getProjects().size() <= 1) {
			return;
		}
		if (!isReorderEnabled(session)) {
			return;
		}

		Path reactorRoot = session.getTopLevelProject().getBasedir().toPath();
		Path sharedDir = reactorRoot.resolve(SHARED_DIR_NAME);
		Path indexFile = sharedDir.resolve(INDEX_FILE);
		Path stateFile = sharedDir.resolve(STATE_FILE);

		if (!Files.exists(indexFile)) {
			System.out.println("[test-order] reactor reorder requested but no shared index at " + indexFile
					+ " — skipping. Run learn first.");
			return;
		}

		PluginLog log = new SystemOutPluginLog();
		Set<String> changedClasses = detectChangedClassesAcrossReactor(session, log);
		Set<String> changedTests = detectChangedTestClassesAcrossReactor(session, log);

		Map<String, Path> moduleTestDirs = new LinkedHashMap<>();
		Map<String, MavenProject> projectsById = new HashMap<>();
		for (MavenProject p : session.getProjects()) {
			if ("pom".equals(p.getPackaging())) {
				continue;
			}
			String dir = p.getBuild().getTestOutputDirectory();
			if (dir == null) {
				continue;
			}
			Path testClassesDir = Path.of(dir);
			String mid = ModuleIds.of(p);
			moduleTestDirs.put(mid, testClassesDir);
			projectsById.put(mid, p);
		}

		ReactorOrderResult result;
		try {
			ReactorOrderInput input = new ReactorOrderInput(indexFile, stateFile, changedClasses, changedTests,
					moduleTestDirs, null, 5, log);
			result = ReactorOrderOperation.compute(input);
		} catch (Exception e) {
			System.err.println("[test-order] reactor reorder: scoring failed: " + e);
			return;
		}

		Map<String, ModuleScore> scoreById = new HashMap<>();
		for (ModuleScore ms : result.moduleScores()) {
			scoreById.put(ms.moduleId(), ms);
		}

		Integer topN = parseIntProp(session, PROP_TOPN);
		ReactorReorderer.ReorderResult reorder = ReactorReorderer.reorder(session.getProjects(), scoreById, topN);

		System.out.println("[test-order] reactor reorder: " + reorder.activeModules() + " active module(s), "
				+ reorder.deferredModules() + " deferred (cumulative affected tests = " + reorder.cumulativeAffected()
				+ (topN != null ? ", topN=" + topN : "") + ")");

		boolean dryRun = boolProp(session, PROP_DRYRUN);
		if (dryRun) {
			System.out.println("[test-order] reactor reorder (dry-run): would reorder reactor; first 10 modules:");
			int i = 0;
			for (MavenProject p : reorder.ordered()) {
				if (i++ >= 10)
					break;
				ModuleScore s = scoreById.get(ModuleIds.of(p));
				int affected = s != null ? s.affectedTestCount() : 0;
				System.out.println("  " + p.getArtifactId() + " (affected=" + affected
						+ (reorder.deferred().contains(p) ? ", deferred" : "") + ")");
			}
			return;
		}

		session.setProjects(reorder.ordered());

		// Static skip on deferred modules (only when topN is set; without topN we
		// only reorder, we don't skip — every module still runs eventually).
		if (topN != null && topN > 0) {
			int skipped = 0;
			for (MavenProject p : reorder.deferred()) {
				p.getProperties().setProperty("skipTests", "true");
				skipped++;
			}
			if (skipped > 0) {
				System.out.println(
						"[test-order] reactor reorder: set skipTests=true on " + skipped + " deferred module(s)");
			}

			// Dynamic safety net: if scoring overestimated, stop adding new modules
			// once the cumulative test count crosses the threshold.
			ExecutionListener prev = session.getRequest().getExecutionListener();
			session.getRequest().setExecutionListener(new CumulativeTestCountListener(prev, topN));
		}
	}

	private boolean isReorderEnabled(MavenSession session) {
		return boolProp(session, PROP_REORDER);
	}

	private static boolean boolProp(MavenSession session, String key) {
		String v = session.getSystemProperties().getProperty(key);
		if (v == null)
			v = session.getUserProperties().getProperty(key);
		if (v == null)
			v = session.getTopLevelProject().getProperties().getProperty(key);
		return v != null && (v.isEmpty() || "true".equalsIgnoreCase(v));
	}

	private static Integer parseIntProp(MavenSession session, String key) {
		String v = session.getSystemProperties().getProperty(key);
		if (v == null)
			v = session.getUserProperties().getProperty(key);
		if (v == null)
			v = session.getTopLevelProject().getProperties().getProperty(key);
		if (v == null || v.isBlank())
			return null;
		try {
			return Integer.parseInt(v.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Set<String> detectChangedClassesAcrossReactor(MavenSession session, PluginLog log) {
		Set<String> result = new LinkedHashSet<>();
		Path gitRoot = session.getTopLevelProject().getBasedir().toPath();
		for (MavenProject p : session.getProjects()) {
			List<String> roots = p.getCompileSourceRoots();
			Path src = (roots != null && !roots.isEmpty())
					? Path.of(roots.get(0))
					: p.getBasedir().toPath().resolve("src/main/java");
			if (!Files.isDirectory(src))
				continue;
			try {
				result.addAll(
						ChangeDetectionOps.detectChangedClasses("uncommitted", gitRoot, src, null, null, true, log));
			} catch (Exception ignored) {
				// best-effort
			}
		}
		return result;
	}

	private static Set<String> detectChangedTestClassesAcrossReactor(MavenSession session, PluginLog log) {
		Set<String> result = new LinkedHashSet<>();
		Path gitRoot = session.getTopLevelProject().getBasedir().toPath();
		for (MavenProject p : session.getProjects()) {
			if ("pom".equals(p.getPackaging()))
				continue;
			List<String> roots = p.getTestCompileSourceRoots();
			Path testRoot = (roots != null && !roots.isEmpty())
					? Path.of(roots.get(0))
					: p.getBasedir().toPath().resolve("src/test/java");
			if (!Files.isDirectory(testRoot))
				continue;
			try {
				result.addAll(ChangeDetectionOps.detectChangedTestClasses("uncommitted", gitRoot, testRoot, null, null,
						true, log));
			} catch (Exception ignored) {
				// best-effort
			}
		}
		return result;
	}

	private static final class SystemOutPluginLog implements PluginLog {
		@Override
		public void info(String message) {
			System.out.println("[test-order] " + message);
		}

		@Override
		public void warn(String message) {
			System.out.println("[test-order] WARN " + message);
		}

		@Override
		public void debug(String message) {
			// suppress
		}
	}

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
