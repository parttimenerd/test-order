package me.bechberger.testorder.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Produces a framework-agnostic configuration map for the JUnit
 * PriorityClassOrderer. Maven writes this to a {@code .properties} file; Gradle
 * injects it as system properties on the test task.
 */
public final class OrdererConfigOperation {

	private OrdererConfigOperation() {
	}

	/** Input configuration for orderer config generation. */
	public record OrdererInput(String indexPath, String statePath, String weightsFile, Set<String> changedClasses,
			Set<String> changedTests, Set<String> changedMethods, Map<String, Integer> scoreOverrides,
			boolean methodOrderingEnabled, boolean springContextGrouping, String projectRoot, String sourceRoot,
			String changeMode) {
	}

	/** Canonical property keys used by PriorityClassOrderer. */
	public static final String KEY_INDEX_PATH = "testorder.index.path";
	public static final String KEY_STATE_PATH = "testorder.state.path";
	public static final String KEY_WEIGHTS_FILE = "testorder.weights.file";
	public static final String KEY_CHANGED_CLASSES = "testorder.changed.classes";
	public static final String KEY_CHANGED_TEST_CLASSES = "testorder.changed.test.classes";
	public static final String KEY_CHANGED_METHODS = "testorder.changed.methods";
	public static final String KEY_METHOD_ORDER_ENABLED = "testorder.methodOrder.enabled";
	public static final String KEY_SPRING_CONTEXT_GROUPING = "testorder.score.springContextGrouping";
	public static final String KEY_PROJECT_ROOT = "testorder.project.root";
	public static final String KEY_SOURCE_ROOT = "testorder.source.root";
	public static final String KEY_CHANGE_MODE = "testorder.changeMode";
	public static final String KEY_CHANGE_DETECTION_LOGGED = "testorder.changeDetection.logged";

	/**
	 * Builds the complete config map from the input. The returned map contains only
	 * entries with non-null, non-blank values. Both Maven (file write) and Gradle
	 * (system properties) can consume this directly.
	 */
	public static Map<String, String> buildConfig(OrdererInput input) {
		if (input.indexPath() == null || input.indexPath().isBlank()) {
			throw new IllegalStateException("[test-order] Cannot build orderer config: indexPath is null."
					+ "\nRun: mvn test -Dtestorder.mode=learn");
		}
		Map<String, String> config = new LinkedHashMap<>();

		putIfPresent(config, KEY_INDEX_PATH, input.indexPath());
		putIfPresent(config, KEY_STATE_PATH, input.statePath());
		putIfPresent(config, KEY_WEIGHTS_FILE, input.weightsFile());

		if (input.changedClasses() != null && !input.changedClasses().isEmpty()) {
			config.put(KEY_CHANGED_CLASSES, String.join(",", input.changedClasses()));
		}
		if (input.changedTests() != null && !input.changedTests().isEmpty()) {
			config.put(KEY_CHANGED_TEST_CLASSES, String.join(",", input.changedTests()));
		}
		if (input.changedMethods() != null && !input.changedMethods().isEmpty()) {
			config.put(KEY_CHANGED_METHODS, String.join(",", input.changedMethods()));
		}

		if (input.scoreOverrides() != null) {
			for (var e : input.scoreOverrides().entrySet()) {
				config.put("testorder.score." + e.getKey(), String.valueOf(e.getValue()));
			}
		}

		if (input.methodOrderingEnabled()) {
			config.put(KEY_METHOD_ORDER_ENABLED, "true");
		}
		if (input.springContextGrouping()) {
			config.put(KEY_SPRING_CONTEXT_GROUPING, "true");
		}

		putIfPresent(config, KEY_PROJECT_ROOT, input.projectRoot());
		putIfPresent(config, KEY_SOURCE_ROOT, input.sourceRoot());
		putIfPresent(config, KEY_CHANGE_MODE, input.changeMode());
		// R17-1: Signal to PriorityClassOrderer that the mojo already logged
		// change-detection info, preventing duplicate output with forkCount>1.
		config.put(KEY_CHANGE_DETECTION_LOGGED, "true");

		return config;
	}

	private static void putIfPresent(Map<String, String> map, String key, String value) {
		if (value != null && !value.isBlank()) {
			map.put(key, value);
		}
	}
}
