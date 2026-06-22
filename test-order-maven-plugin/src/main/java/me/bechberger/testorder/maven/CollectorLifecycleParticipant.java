package me.bechberger.testorder.maven;

import java.io.IOException;
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
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;

import me.bechberger.testorder.SourceRootScanner;
import me.bechberger.testorder.agent.runtime.ClassIdMap;
import me.bechberger.testorder.agent.runtime.ClassIdMapping;
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
	private static final String PROP_SKIP_INACTIVE = "testorder.skipInactiveModules";

	@Override
	public void afterProjectsRead(MavenSession session) {
		// Signal to mojos that the lifecycle extension is active so they can use
		// the aggregated partial-run path (pending-runs/*.part merged at session end).
		if (session != null) {
			session.getUserProperties().setProperty("testorder.extensionActive", "true");
		}
		try {
			restoreLeftoverInstrumentation(session);
		} catch (Exception | NoClassDefFoundError e) {
			System.err.println("[test-order] startup restore failed: " + e);
		}
		try {
			installRuntimeRealmInjector(session);
		} catch (Exception | NoClassDefFoundError e) {
			System.err.println("[test-order] runtime-realm injector install failed: " + e);
		}
		try {
			prepareReactorClassIdMap(session);
		} catch (Exception | NoClassDefFoundError e) {
			System.err.println("[test-order] reactor class-id map pre-pass failed: " + e);
		}
		try {
			ensurePrepareGoalBound(session);
		} catch (Exception | NoClassDefFoundError e) {
			System.err.println("[test-order] prepare-goal binding failed: " + e);
		}
		try {
			disableValidatePhasePlugins(session);
		} catch (Exception | NoClassDefFoundError e) {
			System.err.println("[test-order] disableValidatePlugins failed: " + e);
		}
		try {
			tryReorderReactor(session);
		} catch (Exception | NoClassDefFoundError e) {
			System.err.println("[test-order] reactor reorder failed: " + e);
		}
	}

	/**
	 * Installs an {@link ExecutionListener} that imports
	 * {@code me.bechberger.testorder.agent.runtime} into every plugin's
	 * {@code ClassRealm} just before that plugin executes.
	 * <p>
	 * Prevents {@code NoClassDefFoundError: UsageStore} when other plugins (e.g.
	 * {@code openapi-generator-maven-plugin}, log4j2's plugin-descriptor generator)
	 * load instrumented bytecode in the same Maven JVM. Their realms normally only
	 * import {@code maven.api} and can't see our agent runtime.
	 * <p>
	 * Importing rather than copying URLs ensures every realm sees the same
	 * {@code UsageStore} class, so the static maps are shared across the session.
	 */
	private void installRuntimeRealmInjector(MavenSession session) {
		if (session == null) {
			return;
		}
		ExecutionListener prev = session.getRequest().getExecutionListener();
		// Use the participant's own classloader (the test-order extension realm) as
		// the source — it has UsageStore via the test-order-agent dependency.
		ClassLoader extensionRealm = getClass().getClassLoader();
		RuntimeRealmInjector injector = new RuntimeRealmInjector(prev, extensionRealm);
		session.getRequest().setExecutionListener(injector);
	}

	/**
	 * Pre-allocates a single reactor-wide class-id map covering every {@code .java}
	 * file across every module's compile + test source roots. Ensures cross-module
	 * edges are recorded with correct IDs: a classId baked into module-A's
	 * instrumented bytecode means the same FQN when module-B's fork records it.
	 * <p>
	 * Without this, each module's prepare assigns IDs in isolation; module-A's
	 * {@code Library} might be ID 5 while module-B's {@code Service} is ALSO ID 5
	 * in module-B's map, so {@code ServiceTest -> Library} edges get
	 * mis-attributed.
	 * <p>
	 * Per-module {@code prepare} mojos load this file under a file lock, register
	 * any classes the source scan missed (inner classes, generated types) past the
	 * current max ID, and save the (possibly grown) map back.
	 */
	// Package-private for unit testing.
	void prepareReactorClassIdMap(MavenSession session) {
		if (session == null || session.getProjects() == null || session.getProjects().isEmpty()) {
			return;
		}
		MavenProject top = session.getTopLevelProject();
		if (top == null || top.getBasedir() == null) {
			// Degenerate session (e.g. unit-test fixtures with no top-level project).
			// Without a stable reactor root we can't pick a single shared map path —
			// skip rather than fall back to a per-cwd path that other modules wouldn't see.
			return;
		}
		// Reactor root = multiModuleProjectDirectory when set, BUT only when the user
		// actually invoked Maven from that directory (executionRootDirectory == mmDir).
		// Without this discriminator, a standalone sub-project that happens to be
		// nested inside an unrelated Maven tree (with a .mvn/ marker higher up) would
		// write its class-id-map to the outer project's .test-order/ directory.
		// This mirrors the same guard in ReactorContext (line 62).
		Path reactorRoot;
		try {
			reactorRoot = ReactorContext.resolveReactorRoot(session, top).root;
		} catch (RuntimeException e) {
			reactorRoot = top.getBasedir().toPath().normalize();
		}
		Path sharedDir = reactorRoot.resolve(SHARED_DIR_NAME);
		Path mappingFile = sharedDir.resolve("class-id-map.bin");

		ClassIdMap map = ClassIdMap.createForBenchmark();

		// Pre-seed from any existing reactor map so IDs survive across runs (a class
		// removed between builds would otherwise reshuffle IDs of everything after it).
		if (Files.exists(mappingFile)) {
			try {
				ClassIdMapping existing = ClassIdMapping.load(mappingFile);
				map.bulkLoadClasses(existing.toClassMap());
				if (existing.memberCount() > 0) {
					map.bulkLoadMembers(existing.toMemberMap());
				}
			} catch (IOException e) {
				System.err.println("[test-order] reactor class-id map: could not load existing " + mappingFile
						+ " — starting fresh: " + e.getMessage());
			}
		}

		int registered = 0;
		Set<String> allFqns = new LinkedHashSet<>();
		for (MavenProject project : session.getProjects()) {
			if (project == null) {
				continue;
			}
			List<String> compileRoots = project.getCompileSourceRoots();
			if (compileRoots != null) {
				for (String root : compileRoots) {
					if (root == null || root.isBlank()) {
						continue;
					}
					allFqns.addAll(SourceRootScanner.scanFqns(Path.of(root)));
				}
			}
			List<String> testRoots = project.getTestCompileSourceRoots();
			if (testRoots != null) {
				for (String root : testRoots) {
					if (root == null || root.isBlank()) {
						continue;
					}
					allFqns.addAll(SourceRootScanner.scanFqns(Path.of(root)));
				}
			}
		}
		for (String fqn : allFqns) {
			int id = map.getOrRegisterClass(fqn);
			if (id >= 0) {
				registered++;
			}
		}

		if (registered == 0 && !Files.exists(mappingFile)) {
			// No sources scanned and no prior file — nothing to write.
			return;
		}

		try {
			Files.createDirectories(sharedDir);
			ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(map, map.getNextClassId(), map.getNextMemberId());
			mapping.save(mappingFile);
			System.err.println("[test-order] Reactor class-id map: pre-allocated " + registered + " class IDs across "
					+ session.getProjects().size() + " module(s) → " + mappingFile);
		} catch (IOException e) {
			System.err.println("[test-order] reactor class-id map: save failed: " + e.getMessage());
		}
	}

	/**
	 * Walks every project's {@code target/.test-order/classes-backup*} directory
	 * and restores any leftover {@code .instrumented} markers from a previously
	 * crashed/aborted session. Without this, instrumented bytecode from a failed
	 * build would persist on disk and break subsequent {@code mvn test} runs that
	 * don't recompile (since other plugins would hit {@code NoClassDefFoundError}
	 * on {@code UsageStore} call-sites).
	 */
	private void restoreLeftoverInstrumentation(MavenSession session) {
		if (session == null || session.getProjects() == null) {
			return;
		}
		int restored = 0;
		for (MavenProject project : session.getProjects()) {
			if (project == null || project.getBuild() == null) {
				continue;
			}
			Path targetDir = Path.of(project.getBuild().getDirectory());
			Path backupRoot = targetDir.resolve(".test-order").resolve("classes-backup");
			Path backupTest = targetDir.resolve(".test-order").resolve("classes-backup-test");
			for (Path backup : new Path[]{backupRoot, backupTest}) {
				try {
					if (Files.exists(backup.resolve(".instrumented"))) {
						boolean ok = me.bechberger.testorder.ClassBackupRestorer.restore(backup);
						if (ok) {
							restored++;
							System.err.println("[test-order] Restored leftover instrumentation: " + backup);
						}
					}
				} catch (Exception e) {
					System.err.println(
							"[test-order] Failed to restore leftover backup " + backup + ": " + e.getMessage());
				}
			}
		}
		if (restored > 0) {
			System.err.println(
					"[test-order] Recovered from previous crashed session: restored " + restored + " backup(s)");
		}
	}

	/**
	 * Programmatically binds {@code test-order:prepare} to
	 * {@code process-test-classes} for every project in the reactor that does not
	 * already declare the plugin.
	 * <p>
	 * This lets users invoke {@code mvn clean test} (a single lifecycle phase) and
	 * have instrumentation run between {@code testCompile} and {@code test},
	 * without the Maven double-lifecycle bug that occurs when {@code prepare} is
	 * invoked as a CLI goal alongside two lifecycle phases (e.g.,
	 * {@code mvn clean process-test-classes
	 * me.bechberger:test-order-maven-plugin:prepare test}). In that pathological
	 * form, Maven re-runs the default lifecycle from {@code validate} when
	 * computing the {@code test} phase, recompiling sources after instrumentation
	 * has overwritten the bytecode.
	 */
	private void ensurePrepareGoalBound(MavenSession session) {
		if (session == null || session.getProjects() == null) {
			return;
		}
		for (MavenProject project : session.getProjects()) {
			if (project == null || project.getModel() == null || project.getBuild() == null) {
				continue;
			}
			if ("pom".equals(project.getPackaging())) {
				continue;
			}
			if (hasTestOrderPlugin(project)) {
				continue;
			}
			Plugin plugin = new Plugin();
			plugin.setGroupId("me.bechberger");
			plugin.setArtifactId("test-order-maven-plugin");
			// Version intentionally omitted: matches the plugin already loaded in this
			// session (the same one that registered this lifecycle participant). Maven
			// resolves it from the realm hosting the participant.
			PluginExecution exec = new PluginExecution();
			exec.setId("test-order-prepare-injected");
			exec.setPhase("process-test-classes");
			exec.getGoals().add("prepare");
			plugin.getExecutions().add(exec);
			project.getBuild().getPlugins().add(plugin);
		}
	}

	private static boolean hasTestOrderPlugin(MavenProject project) {
		if (project.getBuildPlugins() == null) {
			return false;
		}
		for (Plugin p : project.getBuildPlugins()) {
			if ("me.bechberger".equals(p.getGroupId()) && "test-order-maven-plugin".equals(p.getArtifactId())) {
				return true;
			}
		}
		return false;
	}

	private static final String PROP_DISABLE_VALIDATE = "testorder.disableValidatePlugins";

	/**
	 * When {@code -Dtestorder.disableValidatePlugins=true} is set, moves executions
	 * of known validate-phase-only plugins (e.g. xml-maven-plugin,
	 * spring-javaformat-maven-plugin) to phase "none" so they don't block the
	 * build. Some of these plugins have no generic {@code skip} property, making
	 * this the only way to bypass them for repos with pre-existing format
	 * violations (e.g. netty).
	 */
	private void disableValidatePhasePlugins(MavenSession session) {
		if (session == null) {
			return;
		}
		String prop = session.getUserProperties().getProperty(PROP_DISABLE_VALIDATE,
				session.getSystemProperties().getProperty(PROP_DISABLE_VALIDATE, "false"));
		if (!"true".equalsIgnoreCase(prop)) {
			return;
		}
		Set<String> knownBlockers = Set.of("xml-maven-plugin", "spring-javaformat-maven-plugin");
		for (MavenProject project : session.getProjects()) {
			if (project == null || project.getBuild() == null) {
				continue;
			}
			for (Plugin p : project.getBuildPlugins()) {
				if (!knownBlockers.contains(p.getArtifactId())) {
					continue;
				}
				for (PluginExecution exec : p.getExecutions()) {
					if ("validate".equals(exec.getPhase())) {
						exec.setPhase("none");
						System.err.println("[test-order] Disabled " + p.getArtifactId() + ":" + exec.getId()
								+ " validate-phase execution (testorder.disableValidatePlugins=true)");
					}
				}
			}
		}
	}

	private void tryReorderReactor(MavenSession session) {
		if (session == null || session.getProjects() == null || session.getProjects().size() <= 1) {
			return;
		}
		if (!isReorderEnabled(session)) {
			return;
		}

		MavenProject top = session.getTopLevelProject();
		if (top == null || top.getBasedir() == null) {
			return;
		}
		Path reactorRoot = top.getBasedir().toPath();
		Path sharedDir = reactorRoot.resolve(SHARED_DIR_NAME);
		Path indexFile = sharedDir.resolve(INDEX_FILE);
		Path stateFile = sharedDir.resolve(STATE_FILE);

		if (!Files.exists(indexFile)) {
			boolean explicitlyRequested = readProp(session, PROP_REORDER) != null;
			if (explicitlyRequested) {
				System.err.println("[test-order] reactor reorder requested but no shared index at " + indexFile
						+ " — skipping.\nRun: mvn test -Dtestorder.mode=learn");
			} else {
				System.err.println("[test-order] reactor reorder skipped: no learn data at " + indexFile
						+ ".\nRun: mvn test-order:learn test");
			}
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

		System.err.println("[test-order] reactor reorder: " + reorder.activeModules() + " active module(s), "
				+ reorder.deferredModules() + " deferred (cumulative affected tests = " + reorder.cumulativeAffected()
				+ (topN != null ? ", topN=" + topN : "") + ")");

		boolean dryRun = boolProp(session, PROP_DRYRUN);
		if (dryRun) {
			System.err.println("[test-order] reactor reorder (dry-run): would reorder reactor; first 10 modules:");
			int i = 0;
			for (MavenProject p : reorder.ordered()) {
				if (i++ >= 10)
					break;
				ModuleScore s = scoreById.get(ModuleIds.of(p));
				int affected = s != null ? s.affectedTestCount() : 0;
				System.err.println("  " + p.getArtifactId() + " (affected=" + affected
						+ (reorder.deferred().contains(p) ? ", deferred" : "") + ")");
			}
			return;
		}

		session.setProjects(reorder.ordered());

		// Skip the entire build (compile, enforcer, formatter, etc.) for modules that
		// neither have affected tests NOR are transitively required by any such module.
		// Only active when the 'affected' goal is present (default-on) or when
		// testorder.skipInactiveModules=true is set explicitly.
		if (skipInactiveModulesEnabled(session) && reorder.activeModules() > 0) {
			Set<MavenProject> required = ReactorReorderer.transitiveRequired(reorder.active(), session.getProjects(),
					reorder.projectsById());
			int fullySkipped = 0;
			for (MavenProject p : session.getProjects()) {
				if ("pom".equals(p.getPackaging())) {
					continue;
				}
				if (!required.contains(p)) {
					ModuleScore ms = scoreById.get(ModuleIds.of(p));
					if (ms == null || ms.totalTestCount() == 0) {
						// No score, or the index doesn't cover this module's tests (newly added
						// module, or test-classes not compiled this run). We have no evidence
						// the module is unaffected — must not suppress.
						continue;
					}
					p.getProperties().setProperty("maven.main.skip", "true");
					p.getProperties().setProperty("maven.test.skip", "true");
					p.getProperties().setProperty("skipTests", "true");
					p.getProperties().setProperty("enforcer.skip", "true");
					p.getProperties().setProperty("skipFormatting", "true");
					fullySkipped++;
				}
			}
			if (fullySkipped > 0) {
				System.err.println("[test-order] reactor skip: set maven.main.skip+skipTests on " + fullySkipped
						+ " module(s) with no affected tests and not transitively required by any active module"
						+ " (set -Dtestorder.skipInactiveModules=false to opt out)");
			}
		}

		// Print the chosen ranking so the reorder is auditable and reversible.
		// Only the top 10 active modules are listed to keep CI logs readable.
		// The listing follows the reactor's DAG-respecting execution order, so a
		// module with more affected tests may appear later if it depends on a
		// module with fewer affected. To make the priority signal explicit, we
		// also print the top-N most-affected modules separately above the
		// build-order list.
		if (reorder.activeModules() > 0) {
			boolean explicitlyRequested = readProp(session, PROP_REORDER) != null;
			String optOutHint = explicitlyRequested ? "" : " (set -Dtestorder.reactorReorder=false to opt out)";

			// Top-N most-affected modules (priority view, ignoring DAG)
			List<ModuleScore> byPriority = new ArrayList<>();
			for (MavenProject p : reorder.ordered()) {
				ModuleScore s = scoreById.get(ModuleIds.of(p));
				if (s != null && s.affectedTestCount() > 0) {
					byPriority.add(s);
				}
			}
			byPriority.sort((a, b) -> {
				int c = Integer.compare(b.affectedTestCount(), a.affectedTestCount());
				if (c != 0)
					return c;
				c = Long.compare(b.sumTestScores(), a.sumTestScores());
				if (c != 0)
					return c;
				return Integer.compare(b.maxTestScore(), a.maxTestScore());
			});
			int topToShow = Math.min(3, byPriority.size());
			if (topToShow > 0) {
				System.err.println("[test-order] highest-priority modules (by affected test count)" + optOutHint + ":");
				for (int i = 0; i < topToShow; i++) {
					ModuleScore s = byPriority.get(i);
					System.err.println(String.format("  %2d.  %-50s  affected=%d  sum=%d", i + 1,
							me.bechberger.testorder.ops.workflows.ShowWorkflow.shortenModuleId(s.moduleId()),
							s.affectedTestCount(), s.sumTestScores()));
				}
			}

			System.err.println("[test-order] reactor build order (DAG-respecting; priority breaks ties):");
			int shown = 0;
			for (MavenProject p : reorder.ordered()) {
				ModuleScore s = scoreById.get(ModuleIds.of(p));
				if (s == null || s.affectedTestCount() == 0) {
					continue;
				}
				if (shown++ >= 10) {
					System.err.println("  … " + (reorder.activeModules() - 10) + " more active module(s)");
					break;
				}
				System.err.println(String.format("  %2d.  %-50s  affected=%d  sum=%d", shown, p.getArtifactId(),
						s.affectedTestCount(), s.sumTestScores()));
			}
			if (reorder.deferredModules() > 0) {
				System.err.println("[test-order] " + reorder.deferredModules()
						+ " module(s) with no affected tests run last in declaration order.");
			}
		}

		// Static skip on deferred modules (only when topN is set; without topN we
		// only reorder, we don't skip — every module still runs eventually).
		// Only skip modules that had real affectedTestCount > 0 (budget-capped), not
		// modules with no index data or zero affected tests — those must always run.
		if (topN != null && topN > 0) {
			int skipped = 0;
			for (MavenProject p : reorder.deferred()) {
				ModuleScore ms = scoreById.get(ModuleIds.of(p));
				if (ms == null || ms.affectedTestCount() == 0) {
					// No data or no affected tests: skip count exceeded budget but this
					// module's tests are unknown — don't suppress them.
					continue;
				}
				p.getProperties().setProperty("skipTests", "true");
				skipped++;
			}
			if (skipped > 0) {
				System.err.println(
						"[test-order] reactor reorder: set skipTests=true on " + skipped + " deferred module(s)");
			}

			// Dynamic safety net: if scoring overestimated, stop adding new modules
			// once the cumulative test count crosses the threshold.
			ExecutionListener prev = session.getRequest().getExecutionListener();
			session.getRequest().setExecutionListener(new CumulativeTestCountListener(prev, topN));
		}
	}

	private boolean skipInactiveModulesEnabled(MavenSession session) {
		String explicit = readProp(session, PROP_SKIP_INACTIVE);
		if (explicit != null) {
			return explicit.isEmpty() || "true".equalsIgnoreCase(explicit);
		}
		// Default-on when the 'affected' goal is present (same condition as reorder).
		return goalsTriggerReorder(session);
	}

	// Package-private for unit testing.
	boolean isReorderEnabled(MavenSession session) {
		// Explicit override (true or false) always wins.
		String explicit = readProp(session, PROP_REORDER);
		if (explicit != null) {
			return explicit.isEmpty() || "true".equalsIgnoreCase(explicit);
		}
		// Default-on when an affected/auto/run-tier goal is in the requested goals.
		// Bare `mvn test` and other lifecycle invocations keep declaration order.
		return goalsTriggerReorder(session);
	}

	/**
	 * Returns true when any of the session's CLI goals is one that benefits from
	 * module reordering: {@code test-order:affected}, {@code test-order:auto},
	 * {@code test-order:run-tier}, or {@code test-order:run-tier1/2/3}.
	 *
	 * <p>
	 * Goals invoked transitively via lifecycle bindings (POM {@code <execution>})
	 * are not seen here; users wiring those should set
	 * {@code testorder.reactorReorder=true} explicitly.
	 */
	// Package-private for unit testing.
	static boolean goalsTriggerReorder(MavenSession session) {
		if (session == null || session.getGoals() == null) {
			return false;
		}
		for (String goal : session.getGoals()) {
			if (goal == null) {
				continue;
			}
			int colon = goal.lastIndexOf(':');
			String name = colon >= 0 ? goal.substring(colon + 1) : goal;
			if (name.equals("affected") || name.equals("auto") || name.startsWith("run-tier")) {
				return true;
			}
		}
		return false;
	}

	private static boolean boolProp(MavenSession session, String key) {
		String v = readProp(session, key);
		return v != null && (v.isEmpty() || "true".equalsIgnoreCase(v));
	}

	/**
	 * Reads a property in the canonical precedence order: user properties (-D
	 * flags) → system properties → top-level project properties. Returns null when
	 * the key is unset everywhere, distinguishing "unset" from "set to
	 * empty/false". Callers that only need a boolean should use {@link #boolProp}.
	 */
	private static String readProp(MavenSession session, String key) {
		if (session == null) {
			return null;
		}
		String v = session.getUserProperties().getProperty(key);
		if (v == null) {
			v = session.getSystemProperties().getProperty(key);
		}
		if (v == null) {
			MavenProject top = session.getTopLevelProject();
			if (top != null) {
				v = top.getProperties().getProperty(key);
			}
		}
		return v;
	}

	private static Integer parseIntProp(MavenSession session, String key) {
		// User properties (-D flags) take precedence over system properties and pom.xml
		String v = session.getUserProperties().getProperty(key);
		if (v == null)
			v = session.getSystemProperties().getProperty(key);
		if (v == null) {
			MavenProject top = session.getTopLevelProject();
			if (top != null)
				v = top.getProperties().getProperty(key);
		}
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
		MavenProject top = session.getTopLevelProject();
		if (top == null)
			return result;
		Path gitRoot = top.getBasedir().toPath();
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
		MavenProject top = session.getTopLevelProject();
		if (top == null)
			return result;
		Path gitRoot = top.getBasedir().toPath();
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
			System.err.println("[test-order] " + message);
		}

		@Override
		public void warn(String message) {
			System.err.println("[test-order] WARN " + message);
		}

		@Override
		public void debug(String message) {
			// suppress
		}
	}

	@Override
	public void afterSessionEnd(MavenSession session) {
		try {
			drainCollectors(session);
		} finally {
			try {
				mergePartialRunRecords();
			} catch (Exception e) {
				System.err.println("[test-order] Failed to merge partial run records: " + e.getMessage());
			}
			try {
				appendMLHistories();
			} catch (Exception e) {
				System.err.println("[test-order] Failed to append ML history: " + e.getMessage());
			}
			// Always restore instrumented bytecode — leaving it in place corrupts the
			// compiled classes directory even if earlier steps threw.
			restoreInstrumentedClasses(session);
		}
	}

	private void drainCollectors(MavenSession session) {
		// Primary path: session-property bridge (works across classloader realms).
		// AbstractTestOrderMojo.startCollector() stores "port:indexFilePath" entries
		// in session user properties so this participant can find and drain them even
		// though the extension and plugin classloaders each have separate static maps.
		String activeEntry = session != null
				? session.getUserProperties().getProperty(AbstractTestOrderMojo.SESSION_ACTIVE_COLLECTORS_KEY, "")
				: "";
		if (!activeEntry.isBlank()) {
			for (String entry : activeEntry.split("\\|")) {
				if (entry.isBlank()) {
					continue;
				}
				int colon = entry.indexOf(':');
				if (colon <= 0) {
					continue;
				}
				try {
					int port = Integer.parseInt(entry.substring(0, colon));
					java.nio.file.Path indexFile = java.nio.file.Path.of(entry.substring(colon + 1)).normalize();
					// Try to drain via the local (possibly shared) static map first.
					me.bechberger.testorder.IndexCollectorServer collector = AbstractTestOrderMojo.activeCollectors
							.remove(indexFile);
					if (collector != null) {
						try {
							int merged = collector.stopAndMerge();
							if (merged > 0) {
								System.err.println("[test-order] IndexCollectorServer merged " + merged
										+ " test classes (session end)");
							}
							continue;
						} catch (Exception | NoClassDefFoundError e) {
							System.err.println("[test-order] CollectorLifecycleParticipant: merge failed for "
									+ indexFile + ": " + e);
						}
					}
					// Cross-realm case: server is in the plugin classloader which is a
					// different realm. Use the JVM-global registry (System.getProperties()
					// key) to find the actual server instance.
					me.bechberger.testorder.IndexCollectorServer.drainByPort(port, indexFile);
				} catch (Exception e) {
					System.err.println("[test-order] CollectorLifecycleParticipant: drain entry error: " + e);
				}
			}
			// Remove the session key only after all modules have been drained, so that
			// parallel module builds that registered after we started draining are not
			// lost.
			session.getUserProperties().remove(AbstractTestOrderMojo.SESSION_ACTIVE_COLLECTORS_KEY);
			// Fall through: drain any remaining static-map entries not covered by session
			// properties (e.g. a module that registered before the session property was
			// written, or a cross-realm registration that didn't match any entry above).
		}

		// Legacy fallback: same-realm static map (works when not using extensions
		// or when the mojos happen to share our classloader). Also catches any
		// leftovers after the session-property path above removes matched entries.
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
					System.err.println(
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
					System.err.println("[test-order] Aggregated per-fork run records into one RunRecord for build "
							+ (buildId.length() > 8 ? buildId.substring(0, 8) + "..." : buildId));
				}
			} catch (Exception e) {
				System.err.println("[test-order] CollectorLifecycleParticipant: partial run merge failed for "
						+ agg.stateFile() + ": " + e.getMessage());
			}
		}
	}

	/**
	 * For each registered {@link AbstractTestOrderMojo.PendingMLHistory}, reads the
	 * most recent {@code RunRecord} from the state file and appends it as an
	 * {@link me.bechberger.testorder.ml.MLRunRecord} to {@code history.lz4}. This
	 * builds up training data for future ML predictions.
	 */
	private void appendMLHistories() {
		java.util.List<AbstractTestOrderMojo.PendingMLHistory> pending = AbstractTestOrderMojo.pendingMLHistories;
		if (pending.isEmpty()) {
			return;
		}
		java.util.List<AbstractTestOrderMojo.PendingMLHistory> drained = new java.util.ArrayList<>(pending);
		pending.clear();

		for (AbstractTestOrderMojo.PendingMLHistory mlh : drained) {
			try {
				if (!java.nio.file.Files.exists(mlh.stateFile())) {
					continue;
				}
				me.bechberger.testorder.TestOrderState state = me.bechberger.testorder.TestOrderState
						.load(mlh.stateFile());
				java.util.List<me.bechberger.testorder.TestOrderState.RunRecord> runs = state.runs();
				if (runs.isEmpty()) {
					continue;
				}
				me.bechberger.testorder.TestOrderState.RunRecord last = runs.get(runs.size() - 1);
				java.util.List<me.bechberger.testorder.ml.MLTestOutcome> outcomes = last.outcomes().stream()
						.map(o -> new me.bechberger.testorder.ml.MLTestOutcome(o.testClass(), o.failed(), 0L, // duration
																												// not
																												// tracked
																												// in
																												// RunRecord
								null))
						.toList();
				me.bechberger.testorder.ml.MLRunRecord mlRun = new me.bechberger.testorder.ml.MLRunRecord(
						last.timestamp(), mlh.changedClasses(), mlh.changedTestClasses(), last.totalTests(),
						last.totalFailures(), outcomes);
				java.nio.file.Files.createDirectories(mlh.historyFile().getParent());
				me.bechberger.testorder.ml.MLHistoryPersistence.append(mlh.historyFile(), mlRun, 200);
				System.err.println("[test-order][ml] Appended run record to ML history: " + last.totalTests()
						+ " tests, " + last.totalFailures() + " failures -> " + mlh.historyFile());
			} catch (Exception e) {
				System.err.println("[test-order][ml] Failed to append ML history for " + mlh.historyFile() + ": "
						+ e.getMessage());
			}
		}
	}

	/**
	 * Without this, a subsequent {@code mvn} invocation (without {@code clean})
	 * would re-run plugin executions like log4j2's
	 * {@code generate-plugin-descriptors} against instrumented bytecode and fail
	 * with {@code NoClassDefFoundError} on {@code UsageStore} — the annotation
	 * processor classpath does not include the test-order agent jar.
	 */
	private void restoreInstrumentedClasses(MavenSession session) {
		// ClassBackupRestorer (in test-order-core, same classloader realm as mojos)
		// tracks all registered backups and performs the restore with pure JDK I/O.
		me.bechberger.testorder.ClassBackupRestorer.restoreAll();

		// Also drain any paths registered only via session user properties
		// (cross-classloader-realm bridge for edge cases).
		String sessionPaths = session != null
				? session.getUserProperties().getProperty("testorder.pendingRestores", "")
				: "";
		if (!sessionPaths.isBlank()) {
			session.getUserProperties().remove("testorder.pendingRestores");
			for (String p : sessionPaths.split("\\|")) {
				if (!p.isBlank()) {
					try {
						me.bechberger.testorder.ClassBackupRestorer.restore(Path.of(p.trim()));
					} catch (Exception e) {
						System.err.println("[test-order] CollectorLifecycleParticipant: restore failed for " + p + ": "
								+ e.getMessage());
					}
				}
			}
		}
	}
}
