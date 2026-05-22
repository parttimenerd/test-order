package me.bechberger.testorder.agent.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Bootstraps the UsageStore runtime from a pre-built class-id mapping file
 * (produced by offline instrumentation). Called by TelemetryListener when
 * offline mode is detected (system property {@code testorder.offline.mapping}
 * is set).
 * <p>
 * This replaces the Java agent's {@code premain()} initialization path: instead
 * of the agent configuring UsageStore via reflection during class loading, the
 * mapping and configuration are loaded from disk at test startup.
 */
public final class OfflineRuntimeBootstrap {

	private static volatile boolean initialized;

	private OfflineRuntimeBootstrap() {
	}

	/**
	 * Initialize UsageStore from an offline class-id mapping file.
	 *
	 * @param mappingFile
	 *            path to the class-id-map.bin produced by OfflineInstrumentor
	 * @param outputDir
	 *            directory for .deps file output (fallback)
	 * @param indexFile
	 *            path to the binary dependency index
	 * @param methodLevel
	 *            whether to enable per-method recording (METHOD/MEMBER modes)
	 */
	public static synchronized void init(Path mappingFile, String outputDir, String indexFile, boolean methodLevel) {
		if (initialized)
			return;

		try {
			ClassIdMapping mapping = ClassIdMapping.load(mappingFile);

			// Load class mappings into ClassIdMap singleton
			ClassIdMap classIdMap = ClassIdMap.getInstance();
			classIdMap.bulkLoadClasses(mapping.toClassMap());

			if (mapping.memberCount() > 0) {
				classIdMap.bulkLoadMembers(mapping.toMemberMap());
			}

			// Configure UsageStore
			UsageStore store = UsageStore.getInstance();
			store.configure(outputDir, indexFile, methodLevel, null);

			initialized = true;
			AgentLogger.log("[OfflineRuntimeBootstrap] Initialized with " + mapping.classCount() + " classes, "
					+ mapping.memberCount() + " members");
		} catch (IOException e) {
			AgentLogger.log("[OfflineRuntimeBootstrap] ERROR: Failed to load mapping from " + mappingFile + ": "
					+ e.getMessage());
			throw new RuntimeException("Failed to initialize offline instrumentation runtime", e);
		}
	}

	/**
	 * Returns true if the offline runtime has been initialized.
	 */
	public static boolean isInitialized() {
		return initialized;
	}

	/**
	 * Reset state (for testing only).
	 */
	static void resetForTesting() {
		initialized = false;
	}
}
