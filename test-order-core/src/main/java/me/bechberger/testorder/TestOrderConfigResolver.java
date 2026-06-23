package me.bechberger.testorder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Shared configuration resolver for test-order runtime components.
 * <p>
 * Resolves values with precedence: system property &gt; classpath properties
 * file. Both the JUnit and TestNG plugins use this to avoid duplicating
 * resolution logic.
 */
public final class TestOrderConfigResolver {

	private static final String CONFIG_RESOURCE = "testorder-config.properties";

	private final ClassLoader classLoader;
	private Properties configProps;

	public TestOrderConfigResolver(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	/**
	 * Resolves a config value: system property first, then classpath properties.
	 */
	public String getConfig(String key) {
		String val = System.getProperty(key);
		if (val != null)
			return val;
		if (configProps == null) {
			configProps = new Properties();
			try (InputStream is = classLoader.getResourceAsStream(CONFIG_RESOURCE)) {
				if (is != null)
					configProps.load(is);
			} catch (IOException e) {
				TestOrderLogger.debug("Failed to load {}: {}", CONFIG_RESOURCE, e.getMessage());
			}
		}
		return configProps.getProperty(key);
	}

	public boolean getConfigBool(String key, boolean defaultValue) {
		String val = getConfig(key);
		if (val == null)
			return defaultValue;
		return "true".equalsIgnoreCase(val.trim());
	}

	public int getConfigInt(String key, int defaultValue) {
		String val = getConfig(key);
		if (val != null && !val.isBlank()) {
			try {
				return Integer.parseInt(val.trim());
			} catch (NumberFormatException ignored) {
			}
		}
		return defaultValue;
	}

	/**
	 * Index from weight name → WeightDef, built once from
	 * TestOrderState.WEIGHT_DEFS.
	 */
	private static final Map<String, TestOrderState.WeightDef> WEIGHT_DEF_BY_NAME;

	static {
		Map<String, TestOrderState.WeightDef> m = new java.util.LinkedHashMap<>();
		for (TestOrderState.WeightDef d : TestOrderState.WEIGHT_DEFS)
			m.put(d.name(), d);
		WEIGHT_DEF_BY_NAME = Map.copyOf(m);
	}

	/**
	 * Like {@link #getConfigInt} but clamps the resolved value to the optimizer
	 * range declared in {@link TestOrderState#WEIGHT_DEFS} for the given weight.
	 * The weight name is derived from the property key suffix after the last
	 * {@code '.'} (e.g., {@code "testorder.score.newTest"} → {@code "newTest"}). If
	 * no matching WeightDef is found the value is returned unclamped.
	 */
	private int getWeightInt(String key, int defaultValue) {
		int raw = getConfigInt(key, defaultValue);
		String name = key.substring(key.lastIndexOf('.') + 1);
		TestOrderState.WeightDef def = WEIGHT_DEF_BY_NAME.get(name);
		if (def == null)
			return raw;
		int clamped = Math.max(def.min(), Math.min(def.max(), raw));
		if (clamped != raw) {
			TestOrderLogger.warn("[test-order] Weight '{}' value {} is outside valid range [{}, {}]; clamping to {}",
					name, raw, def.min(), def.max(), clamped);
		}
		return clamped;
	}

	public double getConfigDouble(String key, double defaultValue) {
		String val = getConfig(key);
		if (val != null && !val.isBlank()) {
			try {
				return Double.parseDouble(val.trim());
			} catch (NumberFormatException ignored) {
			}
		}
		return defaultValue;
	}

	// ── Changed-class resolution ──────────────────────────────────────

	/**
	 * Resolves changed classes from the {@code testorder.changed.classes} property
	 * (CSV) and the {@code testorder.changed.classes.file} file (one per line).
	 */
	public Set<String> resolveChangedClasses() {
		Set<String> result = new LinkedHashSet<>();
		String explicit = getConfig(TestOrderConfig.CHANGED_CLASSES);
		if (explicit != null && !explicit.isBlank()) {
			String sep = explicit.contains(",") || !explicit.contains(";") ? "," : ";";
			for (String cls : explicit.split(sep)) {
				String trimmed = cls.trim();
				if (!trimmed.isEmpty())
					result.add(trimmed);
			}
		}
		String filePath = getConfig(TestOrderConfig.CHANGED_CLASSES_FILE);
		if (filePath != null && !filePath.isBlank()) {
			Path f = Path.of(filePath);
			if (Files.exists(f)) {
				try {
					Files.readAllLines(f).stream().map(String::trim).filter(s -> !s.isEmpty()).forEach(result::add);
				} catch (IOException e) {
					TestOrderLogger.error("Failed to read changed classes file: {}", e.getMessage());
				}
			} else {
				TestOrderLogger.warn("[test-order] Changed classes file not found (testorder.changed.classes.file={})",
						filePath);
			}
		}
		return result;
	}

	/**
	 * Resolves changed test classes from the {@code testorder.changed.test.classes}
	 * property (CSV).
	 */
	public Set<String> resolveChangedTestClasses() {
		Set<String> result = new LinkedHashSet<>();
		String explicit = getConfig(TestOrderConfig.CHANGED_TEST_CLASSES);
		if (explicit != null && !explicit.isBlank()) {
			String sep = explicit.contains(",") || !explicit.contains(";") ? "," : ";";
			for (String cls : explicit.split(sep)) {
				String trimmed = cls.trim();
				if (!trimmed.isEmpty())
					result.add(trimmed);
			}
		}
		return result;
	}

	/**
	 * Resolves changed methods from the {@code testorder.changed.methods} property
	 * (CSV of {@code className#methodName}).
	 */
	public Set<String> resolveChangedMethods() {
		Set<String> result = new LinkedHashSet<>();
		String explicit = getConfig(TestOrderConfig.CHANGED_METHODS);
		if (explicit != null && !explicit.isBlank()) {
			for (String key : explicit.split(",")) {
				String trimmed = key.trim();
				if (!trimmed.isEmpty())
					result.add(trimmed);
			}
		}
		return result;
	}

	// ── Weights resolution ────────────────────────────────────────────

	/**
	 * Resolves effective scoring weights: system properties override the given base
	 * weights (typically loaded from state/file).
	 */
	public TestOrderState.ScoringWeights resolveEffectiveWeights(TestOrderState.ScoringWeights base) {
		return new TestOrderState.ScoringWeights(getWeightInt(TestOrderConfig.SCORE_NEW_TEST, base.newTest()),
				getWeightInt(TestOrderConfig.SCORE_CHANGED_TEST, base.changedTest()),
				getWeightInt(TestOrderConfig.SCORE_MAX_FAILURE, base.maxFailure()),
				getWeightInt(TestOrderConfig.SCORE_SPEED, base.speed()),
				getWeightInt(TestOrderConfig.SCORE_SPEED_PENALTY, base.speedPenalty()),
				getWeightInt(TestOrderConfig.SCORE_DEP_OVERLAP, base.depOverlap()),
				getWeightInt(TestOrderConfig.SCORE_CHANGE_COMPLEXITY, base.changeComplexity()),
				getWeightInt(TestOrderConfig.SCORE_STATIC_FIELD_BONUS, base.staticFieldBonus()),
				getWeightInt(TestOrderConfig.SCORE_COVERAGE_BONUS, base.coverageBonus()),
				getWeightInt(TestOrderConfig.SCORE_KILL_RATE_BONUS, base.killRateBonus()),
				getWeightInt(TestOrderConfig.SCORE_PACKAGE_PROXIMITY_BONUS, base.packageProximityBonus()));
	}

	/**
	 * Loads base scoring weights from the weights file (if configured and exists),
	 * falling back to the given state's weights.
	 */
	public TestOrderState.ScoringWeights loadBaseWeights(TestOrderState state) {
		TestOrderState.ScoringWeights sw = state.weights();
		String weightsFilePath = getConfig(TestOrderConfig.WEIGHTS_FILE);
		if (weightsFilePath != null && !weightsFilePath.isEmpty()) {
			Path wf = Path.of(weightsFilePath);
			if (Files.exists(wf)) {
				try {
					sw = TestOrderState.ScoringWeights.loadFromFile(wf).weights();
				} catch (IOException e) {
					TestOrderLogger.error("Failed to load weights file: {}", e.getMessage());
				}
			} else {
				TestOrderLogger.warn("[test-order] Weights file not found: {} — using defaults. "
						+ "Check the path specified by -Dtestorder.weights.file.", wf.toAbsolutePath());
			}
		}
		return sw;
	}

	/**
	 * Resolves effective method scoring weights from system properties, falling
	 * back to the given state's method weights.
	 */
	public TestOrderState.MethodScoringWeights resolveMethodWeights(TestOrderState state) {
		TestOrderState.MethodScoringWeights mw = state.methodScoringWeights();
		return new TestOrderState.MethodScoringWeights(
				getConfigDouble(TestOrderConfig.METHOD_SCORE_FAILURE_RECENCY, mw.failureRecency()),
				getConfigDouble(TestOrderConfig.METHOD_SCORE_FAST, mw.fast()),
				getConfigDouble(TestOrderConfig.METHOD_SCORE_SLOW, mw.slow()),
				getConfigDouble(TestOrderConfig.METHOD_SCORE_DEP_OVERLAP, mw.depOverlap()),
				getConfigDouble(TestOrderConfig.METHOD_SCORE_NEW_METHOD, mw.newMethod()),
				getConfigDouble(TestOrderConfig.METHOD_SCORE_CHANGED_METHOD, mw.changedMethod()),
				getConfigDouble(TestOrderConfig.METHOD_SCORE_COVERAGE_BONUS, mw.coverageBonus()));
	}

	// ── Utility ───────────────────────────────────────────────────────

	/**
	 * Strips inner/nested class suffixes to get the top-level enclosing class name.
	 */
	public static String toTopLevelClassName(String className) {
		int dollar = className.indexOf('$');
		return dollar > 0 ? className.substring(0, dollar) : className;
	}
}
