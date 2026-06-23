package me.bechberger.testorder.agent.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
 * <li><b>CLASS</b>: Records class usage via method/constructor entry
 * instrumentation plus foreign static-field access. Supports multiple test
 * classes per JVM (default fork mode). The JUnit listener calls
 * {@link #startTestClass}/{@link #endTestClass} via reflection to track
 * per-test-class dependencies.</li>
 * <li><b>METHOD</b>: Like CLASS, but additionally records per-test-method
 * dependencies. The JUnit listener calls
 * {@link #startTestMethod}/{@link #endTestMethod} around each test method.
 * Dependencies from setup/teardown ({@code @BeforeEach}, {@code @AfterEach},
 * etc.) are captured in class-level deps only; method-level deps contain only
 * what the test method itself touches.</li>
 * <li><b>MEMBER</b>: Like METHOD, but also records member-level dependencies
 * ({@code class#method}, {@code class#field}). Enables precise impact analysis:
 * a test that never calls the changed method won't be selected.</li>
 * </ul>
 * <p>
 * <b>Known limitation (C5/M23):</b> Dependency tracking uses a plain field (not
 * ThreadLocal) and assumes tests run sequentially on the test runner's thread.
 * Code that executes on a different thread — such as
 * {@code InvocationInterceptor} thread switching (e.g. Swing EDT),
 * {@code @Timeout(threadMode = SEPARATE_THREAD)}, or
 * {@code assertTimeoutPreemptively()} — will NOT have its class accesses
 * recorded. This is an inherent architectural constraint.
 * <p>
 * On JVM shutdown, merges recorded dependencies directly into the binary index
 * file (via reflection into DependencyMap on the system classpath). Falls back
 * to writing individual {@code .deps} text files if DependencyMap is not
 * available.
 */
public class UsageStore {

	private static final UsageStore INSTANCE = new UsageStore();

	/**
	 * Immutable snapshot pairing both trackers. A single volatile read of
	 * {@code activeState} gives the hot path an atomic view of (classTracker,
	 * methodTracker) without a second volatile read. The object is replaced
	 * atomically in startTestClass/endTestClass/startTestMethod/endTestMethod.
	 * <p>
	 * The {@code target} field points to whichever tracker should receive
	 * recordings right now (methodTracker if inside a test method, classTracker
	 * otherwise). This eliminates a branch on every hot-path call.
	 */
	static final class RecordingState {
		final BitsetTracker target; // record here — no branch needed
		final BitsetTracker classTracker; // for lifecycle/flush reference
		final BitsetTracker methodTracker; // null between test methods

		RecordingState(BitsetTracker classTracker, BitsetTracker methodTracker) {
			this.classTracker = classTracker;
			this.methodTracker = methodTracker;
			this.target = (methodTracker != null) ? methodTracker : classTracker;
		}
	}

	/**
	 * Combined hot-path state for METHOD/MEMBER modes. One volatile read replaces
	 * the former two-field approach (activeClassTracker + activeMethodTracker).
	 * Null means not recording.
	 */
	static volatile RecordingState activeState;

	/**
	 * Direct class-tracker reference for FULL mode's {@code recordClassOnly}.
	 * Avoids the extra pointer dereference through RecordingState. Null means not
	 * recording.
	 */
	static volatile BitsetTracker activeClassTracker;

	/**
	 * Legacy guard kept for backward compatibility (external code may read it).
	 * Maintained in sync with activeClassTracker != null.
	 */
	public static volatile boolean recording;

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

	// Volatile so that startTestClass/endTestClass/startTestMethod/endTestMethod
	// writes are visible to any thread that reads via recordUsageId or
	// recordMemberUsageId (e.g. JUnit 5 parallel, TestNG parallel, or application
	// worker threads). The static hot path (recordUsageIdFast et al.) uses
	// activeState instead; this field serves the instance-method path.
	private volatile ActiveTrackers activeTrackers = ActiveTrackers.IDLE;

	// Only used in lifecycle methods (not hot path).
	private volatile boolean methodLevelRecordingEnabled;

	private final ConcurrentHashMap<String, BitsetTracker> perTestTrackers = new ConcurrentHashMap<>(
			INITIAL_TEST_CLASS_CAPACITY);
	private final ConcurrentHashMap<String, BitsetTracker> perMethodTrackers = new ConcurrentHashMap<>(
			INITIAL_TEST_METHOD_CAPACITY);

	private UsageStore() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				flush();
			} catch (Throwable ignored) {
				// Classloader may be torn down during JVM shutdown — best effort.
			}
		}));
	}

	public static UsageStore getInstance() {
		return INSTANCE;
	}

	// ── Hot path recording (called by instrumented code) ─────────────
	//
	// Design:
	// - FULL mode: one volatile read of activeClassTracker (direct BitsetTracker
	// ref)
	// - METHOD/MEMBER: one volatile read of activeState (immutable pair),
	// giving both classTracker and methodTracker atomically without a second
	// volatile read. The methodTracker field is a plain read on an L1-cached
	// object — much cheaper than a volatile.
	//
	// Private methods skip class-ID recording entirely (the class is already
	// recorded by the non-private entry point that called them). They only
	// emit recordMemberUsageIdFast if member-level tracking is active.
	//
	// Method dispatch by mode and visibility:
	//
	// ┌────────────────┬────────────────────────┬───────────────────────────────┐
	// │ Mode │ Non-private method │ Private method │
	// ├────────────────┼────────────────────────┼───────────────────────────────┤
	// │ CLASS │ recordClassOnly │ (nothing emitted) │
	// │ METHOD │ recordMemberUsageIdFast │ recordMemberUsageIdFast │
	// │ MEMBER │ recordMemberUsageIdFast │ recordMemberUsageIdFast │
	// └────────────────┴────────────────────────┴───────────────────────────────┘
	//
	// Field access (GETSTATIC/PUTSTATIC to foreign class):
	// ┌────────────────┬─────────────────────────────────────────────────────────┐
	// │ Mode │ Call emitted │
	// ├────────────────┼─────────────────────────────────────────────────────────┤
	// │ CLASS │ recordClassOnly(classId) │
	// │ MEMBER │ recordMemberUsageIdFast(memberId) │
	// │ METHOD │ recordUsageIdFast(classId) │
	// └────────────────┴─────────────────────────────────────────────────────────┘

	/**
	 * CLASS mode, non-private methods: records class usage only. Also used for
	 * field access in CLASS mode (classId of the field owner).
	 * <p>
	 * Reads: activeClassTracker (1 volatile). No method-tracker overhead.
	 */
	public static void recordClassOnly(int classId) {
		BitsetTracker t = activeClassTracker; // single volatile read
		if (t == null)
			return;
		t.recordClass(classId);
	}

	/**
	 * METHOD/MEMBER: records classId to the active target tracker. Used for field
	 * access in METHOD mode (no memberId available).
	 * <p>
	 * Reads: activeState (1 volatile) + target (1 plain field). No branch.
	 */
	public static void recordUsageIdFast(int classId) {
		RecordingState s = activeState; // single volatile read
		if (s == null || s.target == null)
			return;
		s.target.recordClass(classId);
	}

	/**
	 * METHOD/MEMBER: records memberId to the active target tracker. Used for ALL
	 * method entries (private and non-private) and MEMBER field access. The classId
	 * is derived from the memberId at flush time via
	 * ClassIdMap.getClassIdForMember().
	 * <p>
	 * Reads: activeState (1 volatile) + target (1 plain field). No branch.
	 */
	public static void recordMemberUsageIdFast(int memberId) {
		RecordingState s = activeState;
		if (s == null || s.target == null) {
			return;
		}
		s.target.recordMember(memberId);
	}

	// ── Configuration (called by Agent via reflection) ────────────────

	/** Configure all settings at once (used by OfflineRuntimeBootstrap). */
	public void configure(String outputDir, String indexFile, boolean methodLevel, Runnable onFirstTestCallback) {
		this.outputDir = outputDir;
		this.indexFile = indexFile;
		this.methodLevelRecordingEnabled = methodLevel;
		this.onFirstTestClassCallback = onFirstTestCallback;
	}

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
	private final AtomicBoolean firstTestClassSeen = new AtomicBoolean();

	/** Register a callback to be invoked when the first test class starts. */
	public void setOnFirstTestClassCallback(Runnable callback) {
		this.onFirstTestClassCallback = callback;
	}

	// ── Test class lifecycle (called by TelemetryListener via reflection) ──

	/** Called when a test class starts execution. */
	public void startTestClass(String testClass) {
		if (firstTestClassSeen.compareAndSet(false, true)) {
			Runnable cb = onFirstTestClassCallback;
			onFirstTestClassCallback = null; // null out before invoking to avoid memory leak on throw
			if (cb != null) {
				cb.run();
			}
		}
		BitsetTracker tracker = perTestTrackers.computeIfAbsent(testClass, k -> new BitsetTracker());
		activeTrackers = new ActiveTrackers(testClass, tracker, null);
		// Publish activeState before activeClassTracker so that the METHOD/MEMBER
		// hot path activates first; the CLASS hot path activates last. This avoids
		// a window where activeClassTracker is visible but activeState is still null.
		activeState = new RecordingState(tracker, null);
		activeClassTracker = tracker;
		recording = true;
	}

	/** Called when a test class finishes execution. */
	public void endTestClass(String testClass) {
		if (activeTrackers.test != null && testClass.equals(activeTrackers.testClassName)) {
			// Clear plain field first so instance-method hot path stops recording,
			// then clear volatiles as a release fence for static hot-path callers.
			activeTrackers = ActiveTrackers.IDLE;
			activeState = null;
			activeClassTracker = null;
			recording = false;
		}
	}

	// ── Test method lifecycle (called by TelemetryListener via reflection,
	// METHOD/MEMBER mode) ──

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
		// Capture current class tracker before updating activeTrackers, so activeState
		// is built from a stable snapshot rather than re-reading the field after write.
		ActiveTrackers current = activeTrackers;
		ActiveTrackers updated = current.createMethodTracker(tracker);
		activeTrackers = updated;
		// Publish activeState after activeTrackers so the hot path always sees a
		// consistent (classTracker, methodTracker) pair from the same transition.
		activeState = new RecordingState(updated.test, tracker);
	}

	/** Called when a test method finishes execution. */
	public void endTestMethod() {
		if (!methodLevelRecordingEnabled) {
			return;
		}
		// Update activeTrackers first, then publish activeState — mirrors the ordering
		// in startTestClass/startTestMethod so the hot path never observes a state
		// where activeTrackers.methodTracker is non-null but activeState.methodTracker
		// is already null (or vice versa).
		ActiveTrackers updated = activeTrackers.createMethodTracker(null);
		activeTrackers = updated;
		activeState = new RecordingState(updated.test, null);
	}

	// ── Recording ─────────────────────────────────────────────────────

	/**
	 * Records class usage by pre-resolved integer ID (injected at instrumentation
	 * time). Safe under parallel JUnit: reads the volatile {@code activeState} once
	 * to get an atomic snapshot of (classTracker, methodTracker).
	 */
	public void recordUsageId(int classId) {
		RecordingState s = activeState; // single volatile read — safe across threads
		if (s == null)
			return;
		if (s.classTracker != null)
			s.classTracker.recordClass(classId);
		if (s.methodTracker != null)
			s.methodTracker.recordClass(classId);
	}

	/**
	 * Records member usage by pre-resolved integer ID (injected at instrumentation
	 * time). Safe under parallel JUnit: reads the volatile {@code activeState} once
	 * to get an atomic snapshot of (classTracker, methodTracker).
	 */
	public void recordMemberUsageId(int memberId) {
		RecordingState s = activeState; // single volatile read — safe across threads
		if (s == null)
			return;
		if (s.classTracker != null)
			s.classTracker.recordMember(memberId);
		if (s.methodTracker != null)
			s.methodTracker.recordMember(memberId);
	}

	// ── Flush on shutdown ─────────────────────────────────────────────

	private void flush() {
		if (perTestTrackers.isEmpty()) {
			return;
		}

		// Merge method-level deps into class-level trackers. During recording,
		// deps inside a test method only go to the methodTracker for performance.
		// We reconstruct the class-level view here (one-time O(n) merge).
		for (var entry : perMethodTrackers.entrySet()) {
			String methodKey = entry.getKey();
			int hashIdx = methodKey.indexOf('#');
			if (hashIdx < 0)
				continue;
			String testClass = methodKey.substring(0, hashIdx);
			BitsetTracker classTracker = perTestTrackers.get(testClass);
			if (classTracker != null) {
				classTracker.mergeFrom(entry.getValue());
			}
		}

		// Derive class bits from member bits (needed for both binary and string paths)
		for (var entry : perTestTrackers.entrySet()) {
			entry.getValue().deriveClassBitsFromMembers();
		}
		for (var entry : perMethodTrackers.entrySet()) {
			entry.getValue().deriveClassBitsFromMembers();
		}

		// Try binary protocol first (v2) — sends raw bitset data, no string conversion
		String collectorPort = System.getProperty("testorder.collector.port");
		if (collectorPort != null && !collectorPort.isEmpty()) {
			try {
				int port = Integer.parseInt(collectorPort);
				boolean sent = IndexCollectorClient.sendBinary(port, perTestTrackers, perMethodTrackers);
				if (sent) {
					AgentLogger.info("[flush] Sent binary deps to IndexCollectorServer on port " + port + " ("
							+ perTestTrackers.size() + " test classes)");
					return;
				}
			} catch (NumberFormatException e) {
				AgentLogger.info("[flush] Invalid collector port: " + collectorPort);
			}
			// Fall through to string-based approach on failure
			AgentLogger.warn("[flush] Binary socket send to port " + collectorPort
					+ " failed, falling back to string-based approach");
		}

		// String-based path: convert IDs to names (needed for .deps files or v1
		// fallback)
		Map<String, Set<String>> allDeps;
		Map<String, Set<String>> allMethodDeps;
		Map<String, Set<String>> allMemberDeps;
		Map<String, Set<String>> allMethodMemberDeps;
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

		// Try v1 string-based socket (e.g. if v2 is not available on server)
		if (collectorPort != null && !collectorPort.isEmpty()) {
			try {
				int port = Integer.parseInt(collectorPort);
				if (IndexCollectorClient.send(port, allDeps, allMethodDeps, allMemberDeps, allMethodMemberDeps)) {
					AgentLogger.log("[flush] Sent string deps to IndexCollectorServer on port " + port);
					return;
				}
			} catch (NumberFormatException e) {
				// already logged above
			}
			AgentLogger.warn("[flush] String-based socket send to port " + collectorPort
					+ " failed, falling back to file-based approach");
		}

		// Fallback: write .deps files when socket is unavailable (standalone agent
		// usage or server failure)
		if (outputDir != null && !outputDir.isEmpty()) {
			Path baseDir = Path.of(outputDir);
			try {
				Files.createDirectories(baseDir);
			} catch (IOException e) {
				AgentLogger.error("Failed to create output dir: " + outputDir, e);
				return;
			}
			for (var entry : allDeps.entrySet()) {
				writeDepsFile(baseDir, entry.getKey(), entry.getValue());
			}
			if (!allMethodDeps.isEmpty()) {
				writeMethodDepsFiles(baseDir, allMethodDeps);
			}
			if (!allMemberDeps.isEmpty()) {
				writeMemberDepsFiles(baseDir, allMemberDeps);
			}
			if (!allMethodMemberDeps.isEmpty()) {
				writeMethodMemberDepsFiles(baseDir, allMethodMemberDeps);
			}
			AgentLogger.log("[flush] Wrote incremental .deps files to " + outputDir);
		} else {
			AgentLogger.log("[flush] No collector port and no outputDir configured — deps lost");
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

	private void writeDepsFile(Path baseDir, String testClass, Set<String> deps) {
		Path outFile = baseDir.resolve(testClass + ".deps");
		try {
			Files.write(outFile, new ArrayList<>(deps));
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
			// className#methodName → className__methodName.mdeps (double underscore)
			String safeName = entry.getKey().replace("#", "__");
			Path outFile = baseDir.resolve(safeName + ".mdeps");
			try {
				List<String> lines = new ArrayList<>(entry.getValue().size() + 1);
				lines.add("# " + entry.getKey());
				lines.addAll(entry.getValue());
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
				Files.write(outFile, new ArrayList<>(entry.getValue()));
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
			String safeName = entry.getKey().replace("#", "__");
			Path outFile = baseDir.resolve(safeName + ".mmembers");
			try {
				List<String> lines = new ArrayList<>(entry.getValue().size() + 1);
				lines.add("# " + entry.getKey());
				lines.addAll(entry.getValue());
				Files.write(outFile, lines);
			} catch (IOException e) {
				AgentLogger.error("Failed to write mmembers file: " + outFile, e);
			}
		}
	}
}
