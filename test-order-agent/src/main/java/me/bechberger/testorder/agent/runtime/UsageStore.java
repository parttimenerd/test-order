package me.bechberger.testorder.agent.runtime;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton that records which application classes are used during test runs.
 * Lives on the bootstrap classpath so it's visible to all classloaders.
 * <p>
 * Uses atomic bitsets for lock-free tracking: each test/method gets a
 * BitsetTracker that records class IDs (not String names), eliminating
 * synchronization overhead.
 * <p>
 * Supports three instrumentation modes:
 * <ul>
 * <li><b>METHOD_ENTRY</b>: Records class usage via method/constructor entry
 * instrumentation. Supports multiple test classes per JVM (default fork mode).
 * The JUnit listener calls {@link #startTestClass}/{@link #endTestClass} via
 * reflection to track per-test-class dependencies.</li>
 * <li><b>FULL</b>: Like METHOD_ENTRY, but the agent also instruments field
 * accesses to foreign classes. Uses the same per-test-class tracking
 * mechanism.</li>
 * <li><b>FULL_METHOD</b>: Like FULL, but additionally records per-test-method
 * dependencies. The JUnit listener calls
 * {@link #startTestMethod}/{@link #endTestMethod} around each test method.
 * Dependencies from setup/teardown ({@code @BeforeEach}, {@code @AfterEach},
 * etc.) are captured in class-level deps only; method-level deps contain only
 * what the test method itself touches.</li>
 * </ul>
 * <p>
 * <b>Known limitation (C5/M23):</b> Dependency tracking uses a plain field (not ThreadLocal)
 * and assumes tests run sequentially on the test runner's thread. Code that executes on
 * a different thread — such as {@code InvocationInterceptor} thread switching (e.g. Swing EDT),
 * {@code @Timeout(threadMode = SEPARATE_THREAD)}, or {@code assertTimeoutPreemptively()} —
 * will NOT have its class accesses recorded. This is an inherent architectural constraint.
 * <p>
 * On JVM shutdown, merges recorded dependencies directly into the binary index
 * file (via reflection into DependencyMap on the system classpath). Falls back
 * to writing individual {@code .deps} text files if DependencyMap is not
 * available.
 */
public class UsageStore {

	private static final UsageStore INSTANCE = new UsageStore();

	// Configuration (set by Agent via reflection)
	private volatile String outputDir;
	private volatile String indexFile;

	// Typical projects have up to ~200 test classes; 256 avoids any rehash below
	// that.
	private static final int INITIAL_TEST_CLASS_CAPACITY = 256;
	// A project with 200 test classes often has 2000+ methods; 2048 covers most
	// without resize.
	private static final int INITIAL_TEST_METHOD_CAPACITY = 2048;

	/**
	 * Combines both active trackers into a single reference so the hot path pays
	 * exactly one volatile read instead of three (currentTestTracker +
	 * methodLevelRecordingEnabled + currentMethodTracker).
	 */
	private static final class ActiveTrackers {
		static final ActiveTrackers IDLE = new ActiveTrackers(null, null, null);
		final String testClassName;
		final BitsetTracker test; // null when no test class is active
		final BitsetTracker method; // null when outside a method window
		ActiveTrackers(String testClassName, BitsetTracker test, BitsetTracker method) {
			this.testClassName = testClassName;
			this.test = test;
			this.method = method;
		}

		ActiveTrackers createMethodTracker(BitsetTracker methodTracker) {
			return new ActiveTrackers(this.testClassName, this.test, methodTracker);
		}
	}

	// Plain field — tests never execute in parallel within the same JVM
	// process, so no ThreadLocal or volatile is needed. Using a plain field
	// gives the hot path exactly one field read instead of a ThreadLocal lookup.
	private ActiveTrackers activeTrackers = ActiveTrackers.IDLE;

	// Only used in lifecycle methods (not hot path).
	private volatile boolean methodLevelRecordingEnabled;

	private final ConcurrentHashMap<String, BitsetTracker> perTestTrackers = new ConcurrentHashMap<>(
			INITIAL_TEST_CLASS_CAPACITY);
	private final ConcurrentHashMap<String, BitsetTracker> perMethodTrackers = new ConcurrentHashMap<>(
			INITIAL_TEST_METHOD_CAPACITY);

	private UsageStore() {
		Runtime.getRuntime().addShutdownHook(new Thread(this::flush));
	}

	public static UsageStore getInstance() {
		return INSTANCE;
	}

	// ── Hot path recording (called by instrumented code via reflection)
	// ───────────────

	public static void recordUsageIdFast(int classId) {
		INSTANCE.recordUsageId(classId);
	}

	public static void recordMemberUsageIdFast(int memberId) {
		INSTANCE.recordMemberUsageId(memberId);
	}

	// ── Configuration (called by Agent via reflection) ────────────────

	/** Set the output directory for fallback .deps file writing. */
	public void setOutputDir(String dir) {
		this.outputDir = dir;
	}

	/** Set the binary index file path for direct merge on shutdown. */
	public void setIndexFile(String path) {
		this.indexFile = path;
	}

	/** Enable or disable per-method recording work in the hot path. */
	public void setMethodLevelRecordingEnabled(boolean enabled) {
		this.methodLevelRecordingEnabled = enabled;
	}

	/**
	 * Callback invoked once when the first test class starts, to release
	 * transformation-phase caches and reclaim memory.
	 */
	private volatile Runnable onFirstTestClassCallback;
	private volatile boolean firstTestClassSeen;

	/** Register a callback to be invoked when the first test class starts. */
	public void setOnFirstTestClassCallback(Runnable callback) {
		this.onFirstTestClassCallback = callback;
	}

	// ── Test class lifecycle (called by TelemetryListener via reflection) ──

	/** Called when a test class starts execution. */
	public void startTestClass(String testClass) {
		if (!firstTestClassSeen) {
			firstTestClassSeen = true;
			Runnable cb = onFirstTestClassCallback;
			if (cb != null) {
				cb.run();
				onFirstTestClassCallback = null;
			}
		}
		BitsetTracker tracker = perTestTrackers.computeIfAbsent(testClass, k -> new BitsetTracker());
		activeTrackers = new ActiveTrackers(testClass, tracker, null);
	}

	/** Called when a test class finishes execution. */
	public void endTestClass(String testClass) {
		if (activeTrackers.test != null && testClass.equals(activeTrackers.testClassName)) {
			activeTrackers = ActiveTrackers.IDLE;
		}
	}

	// ── Test method lifecycle (called by TelemetryListener via reflection,
	// FULL_METHOD mode) ──

	/**
	 * Called when a test method starts execution. Deps recorded during the method
	 * go to both the class-level set and this method-specific set. Setup/teardown
	 * deps are NOT captured here since they execute outside this window.
	 */
	public void startTestMethod(String testClass, String methodName) {
		if (!methodLevelRecordingEnabled) {
			return;
		}
		String methodKey = testClass + "#" + methodName;
		BitsetTracker tracker = perMethodTrackers.computeIfAbsent(methodKey, k -> new BitsetTracker());
		activeTrackers = activeTrackers.createMethodTracker(tracker);
	}

	/** Called when a test method finishes execution. */
	public void endTestMethod() {
		if (!methodLevelRecordingEnabled) {
			return;
		}
		activeTrackers = activeTrackers.createMethodTracker(null);
	}

	// ── Recording ─────────────────────────────────────────────────────

	/**
	 * Records class usage by pre-resolved integer ID (injected at instrumentation
	 * time). Hot path: one volatile read of {@code active}, then two plain field
	 * reads.
	 */
	public void recordUsageId(int classId) {
		BitsetTracker t = activeTrackers.test;
		if (t != null)
			t.recordClass(classId);
		BitsetTracker m = activeTrackers.method;
		if (m != null)
			m.recordClass(classId);
	}

	/**
	 * Records member usage by pre-resolved integer ID (injected at instrumentation
	 * time). Hot path: one volatile read of {@code active}, then two plain field
	 * reads.
	 */
	public void recordMemberUsageId(int memberId) {
		BitsetTracker t = activeTrackers.test;
		if (t != null)
			t.recordMember(memberId);
		BitsetTracker m = activeTrackers.method;
		if (m != null)
			m.recordMember(memberId);
	}

	// ── Flush on shutdown ─────────────────────────────────────────────

	private void flush() {
		Map<String, Set<String>> allDeps;
		Map<String, Set<String>> allMethodDeps;
		Map<String, Set<String>> allMemberDeps;
		Map<String, Set<String>> allMethodMemberDeps;
		// Single-pass collection: iterate each tracker map once, calling toClassNames()
		// and toMemberNames() just once per BitsetTracker instead of twice.
		{
			allDeps = new HashMap<>(perTestTrackers.size());
			allMemberDeps = new HashMap<>(perTestTrackers.size());
			for (var entry : perTestTrackers.entrySet()) {
				BitsetTracker bt = entry.getValue();
				allDeps.put(entry.getKey(), bt.toClassNames());
				Set<String> members = bt.toMemberNames();
				if (!members.isEmpty()) {
					allMemberDeps.put(entry.getKey(), members);
				}
			}
			allMethodDeps = new HashMap<>(perMethodTrackers.size());
			allMethodMemberDeps = new HashMap<>(perMethodTrackers.size());
			for (var entry : perMethodTrackers.entrySet()) {
				BitsetTracker bt = entry.getValue();
				allMethodDeps.put(entry.getKey(), bt.toClassNames());
				Set<String> members = bt.toMemberNames();
				if (!members.isEmpty()) {
					allMethodMemberDeps.put(entry.getKey(), members);
				}
			}
		}
		AgentLogger.log("[flush] " + allDeps.size() + " test classes, " + allMethodDeps.size() + " test methods" + ", "
				+ allMemberDeps.size() + " member-dep entries");
		for (var e : allDeps.entrySet()) {
			AgentLogger.log("[flush]   " + e.getKey() + " → " + e.getValue().size() + " deps");
		}
		AgentLogger.log("[flush] indexFile=" + indexFile + ", outputDir=" + outputDir);
		if (allDeps.isEmpty()) {
			return;
		}

		// Performance optimization: prefer writing incremental .deps files over
		// synchronized index merges.
		// When outputDir is set, always write .deps files first (safe for multi-fork).
		// If indexFile is also set, attempt direct merge afterwards so the index is
		// immediately available
		// (e.g. AutoMojo first-run learn mode).
		if (outputDir != null && !outputDir.isEmpty()) {
			// Ensure output directory exists once (not per file)
			Path baseDir = Path.of(outputDir);
			try {
				Files.createDirectories(baseDir);
			} catch (IOException e) {
				AgentLogger.error("Failed to create output dir: " + outputDir, e);
				return;
			}
			// Write incremental .deps files
			for (var entry : allDeps.entrySet()) {
				writeDepsFile(baseDir, entry.getKey(), entry.getValue());
			}
			// Write per-method .mdeps files
			if (!allMethodDeps.isEmpty()) {
				writeMethodDepsFiles(baseDir, allMethodDeps);
			}
			// Write class-level member deps for FULL_MEMBER mode
			if (!allMemberDeps.isEmpty()) {
				writeMemberDepsFiles(baseDir, allMemberDeps);
			}
			// Write method-level member deps for FULL_MEMBER mode
			if (!allMethodMemberDeps.isEmpty()) {
				writeMethodMemberDepsFiles(baseDir, allMethodMemberDeps);
			}
			AgentLogger.log("[flush] Wrote incremental .deps files to " + outputDir);
			// Also try direct merge when indexFile is set, so the index is available
			// immediately.
			// Failure is non-fatal — .deps files are already written and can be aggregated
			// later.
			if (indexFile != null && !indexFile.isEmpty()) {
				if (tryDirectMerge(allDeps, allMethodDeps, allMemberDeps, allMethodMemberDeps)) {
					AgentLogger.log("[flush] Also merged directly into " + indexFile);
					AgentLogger.info("Written dependency index with " + allDeps.size()
							+ " entries to " + indexFile);
				}
			}
			return;
		}

		// Fallback: only use direct merge when no outputDir is configured (edge case)
		if (indexFile != null && !indexFile.isEmpty()) {
			if (tryDirectMerge(allDeps, allMethodDeps, allMemberDeps, allMethodMemberDeps)) {
				AgentLogger.info("Written dependency index with " + allDeps.size()
						+ " entries to " + indexFile);
			}
		}
	}

	// Individual collect methods kept for test reflection access; each delegates to
	// a
	// single-pass helper so that flush() itself doesn't call them (it uses inline
	// collection).

	private Map<String, Set<String>> collectDeps() {
		Map<String, Set<String>> snapshot = new HashMap<>(perTestTrackers.size());
		for (var entry : perTestTrackers.entrySet()) {
			snapshot.put(entry.getKey(), entry.getValue().toClassNames());
		}
		return snapshot;
	}

	private Map<String, Set<String>> collectMethodDeps() {
		Map<String, Set<String>> snapshot = new HashMap<>(perMethodTrackers.size());
		for (var entry : perMethodTrackers.entrySet()) {
			snapshot.put(entry.getKey(), entry.getValue().toClassNames());
		}
		return snapshot;
	}

	private Map<String, Set<String>> collectMemberDeps() {
		Map<String, Set<String>> snapshot = new HashMap<>(perTestTrackers.size());
		for (var entry : perTestTrackers.entrySet()) {
			Set<String> members = entry.getValue().toMemberNames();
			if (!members.isEmpty()) {
				snapshot.put(entry.getKey(), members);
			}
		}
		return snapshot;
	}

	private Map<String, Set<String>> collectMethodMemberDeps() {
		Map<String, Set<String>> snapshot = new HashMap<>(perMethodTrackers.size());
		for (var entry : perMethodTrackers.entrySet()) {
			Set<String> members = entry.getValue().toMemberNames();
			if (!members.isEmpty()) {
				snapshot.put(entry.getKey(), members);
			}
		}
		return snapshot;
	}

	/**
	 * Attempts to merge deps directly into the binary index file using
	 * DependencyMap from the system classpath via reflection.
	 *
	 * @return true if successful, false if DependencyMap is not available
	 */
	private boolean tryDirectMerge(Map<String, Set<String>> deps, Map<String, Set<String>> methodDeps,
			Map<String, Set<String>> memberDeps, Map<String, Set<String>> methodMemberDeps) {
		try {
			Class<?> depMapClass = Class.forName("me.bechberger.testorder.DependencyMap", true,
					ClassLoader.getSystemClassLoader());
			// Try 5-arg signature first (with member deps)
			try {
				Method mergeMethod = depMapClass.getMethod("mergeFromAgent", Path.class, Map.class, Map.class,
						Map.class, Map.class);
				mergeMethod.invoke(null, Path.of(indexFile), deps, methodDeps, memberDeps, methodMemberDeps);
				return true;
			} catch (NoSuchMethodException ignore) {
				// fall through to 3-arg
			}
			// Try 3-arg signature (without member deps)
			try {
				Method mergeMethod = depMapClass.getMethod("mergeFromAgent", Path.class, Map.class, Map.class);
				mergeMethod.invoke(null, Path.of(indexFile), deps, methodDeps);
				return true;
			} catch (NoSuchMethodException ignore) {
				// fall through to 2-arg
			}
			// Try 2-arg signature (oldest)
			Method mergeMethod = depMapClass.getMethod("mergeFromAgent", Path.class, Map.class);
			mergeMethod.invoke(null, Path.of(indexFile), deps);
			return true;
		} catch (Exception e) {
			AgentLogger.error("Direct index merge failed, falling back to .deps files", e);
			return false;
		}
	}

	private void writeDepsFile(Path baseDir, String testClass, Set<String> deps) {
		Path outFile = baseDir.resolve(testClass + ".deps");
		try {
			Files.write(outFile, deps.stream().sorted().toList());
		} catch (IOException e) {
			AgentLogger.error("Failed to write deps file: " + outFile, e);
		}
	}

	/**
	 * Writes per-method deps as .mdeps files. Each file is named
	 * className#methodName.mdeps (with '#' replaced by '__' to be filesystem safe)
	 * and contains one dep FQCN per line.
	 */
	private void writeMethodDepsFiles(Path baseDir, Map<String, Set<String>> methodDeps) {
		for (var entry : methodDeps.entrySet()) {
			// className#methodName → className__methodName.mdeps
			String safeName = entry.getKey().replace('#', '_');
			Path outFile = baseDir.resolve(safeName + ".mdeps");
			try {
				List<String> lines = new ArrayList<>(entry.getValue().size() + 1);
				lines.add("# " + entry.getKey());
				entry.getValue().stream().sorted().forEach(lines::add);
				Files.write(outFile, lines);
			} catch (IOException e) {
				AgentLogger.error("Failed to write mdeps file: " + outFile, e);
			}
		}
	}

	/**
	 * Writes class-level member deps as .members files. File name:
	 * testClass.members, content: one member key per line (depClass#member).
	 */
	private void writeMemberDepsFiles(Path baseDir, Map<String, Set<String>> memberDeps) {
		for (var entry : memberDeps.entrySet()) {
			Path outFile = baseDir.resolve(entry.getKey() + ".members");
			try {
				Files.write(outFile, entry.getValue().stream().sorted().toList());
			} catch (IOException e) {
				AgentLogger.error("Failed to write members file: " + outFile, e);
			}
		}
	}

	/**
	 * Writes method-level member deps as .mmembers files. First line stores method
	 * key as '# class#method', remaining lines are member keys.
	 */
	private void writeMethodMemberDepsFiles(Path baseDir, Map<String, Set<String>> methodMemberDeps) {
		for (var entry : methodMemberDeps.entrySet()) {
			String safeName = entry.getKey().replace('#', '_');
			Path outFile = baseDir.resolve(safeName + ".mmembers");
			try {
				List<String> lines = new ArrayList<>(entry.getValue().size() + 1);
				lines.add("# " + entry.getKey());
				entry.getValue().stream().sorted().forEach(lines::add);
				Files.write(outFile, lines);
			} catch (IOException e) {
				AgentLogger.error("Failed to write mmembers file: " + outFile, e);
			}
		}
	}
}
