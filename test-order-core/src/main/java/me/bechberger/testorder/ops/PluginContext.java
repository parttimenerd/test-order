package me.bechberger.testorder.ops;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Framework-agnostic context that captures all inputs a workflow needs.
 * Constructed by plugins (Maven/Gradle) from their framework-specific
 * configuration and passed to shared workflow classes.
 */
public final class PluginContext {

	// ── Path config ──────────────────────────────────────────────────
	private final Path projectRoot;
	private final Path sourceRoot;
	private final Path testSourceRoot;
	private final List<Path> additionalSourceRoots; // e.g. Kotlin
	private final Path testClassesDir;
	private final Path indexFile;
	private final Path stateFile;
	private final Path depsDir;
	private final Path hashFile;
	private final Path testHashFile;
	private final Path methodHashFile;

	// ── Change detection config ──────────────────────────────────────
	private final String changeMode;
	private final String changedClasses;
	private final String changedTestClasses;

	// ── Scoring config ───────────────────────────────────────────────
	private final Path weightsFile;
	private final Map<String, Integer> scoreOverrides;
	private final boolean methodOrderingEnabled;
	private final boolean springContextGrouping;

	// ── Selection config ─────────────────────────────────────────────
	private final int topN;
	private final int randomM;
	private final Long seed;
	private final Path selectedFile;
	private final Path remainingFile;

	// ── Learn config ─────────────────────────────────────────────────
	private final String instrumentationMode;
	private final String includePackages;
	private final boolean filterByGroupId;
	private final String groupId;
	private final Path verboseFile;

	// ── Auto-learn thresholds ────────────────────────────────────────
	private final int autoLearnRunThreshold;
	private final int autoLearnDiffThreshold;
	private final int optimizeEvery;

	// ── Dependency fingerprinting ────────────────────────────────────
	private final Supplier<String> dependencyFingerprintSupplier;

	// ── Display / metadata ───────────────────────────────────────────
	private final String projectName;
	private final String pluginVersion;

	// ── Logging ──────────────────────────────────────────────────────
	private final PluginLog log;

	private PluginContext(Builder b) {
		this.projectRoot = b.projectRoot;
		this.sourceRoot = b.sourceRoot;
		this.testSourceRoot = b.testSourceRoot;
		this.additionalSourceRoots = b.additionalSourceRoots != null ? List.copyOf(b.additionalSourceRoots) : List.of();
		this.testClassesDir = b.testClassesDir;
		this.indexFile = b.indexFile;
		this.stateFile = b.stateFile;
		this.depsDir = b.depsDir;
		this.hashFile = b.hashFile;
		this.testHashFile = b.testHashFile;
		this.methodHashFile = b.methodHashFile;
		this.changeMode = b.changeMode;
		this.changedClasses = b.changedClasses;
		this.changedTestClasses = b.changedTestClasses;
		this.weightsFile = b.weightsFile;
		this.scoreOverrides = b.scoreOverrides;
		this.methodOrderingEnabled = b.methodOrderingEnabled;
		this.springContextGrouping = b.springContextGrouping;
		this.topN = b.topN;
		this.randomM = b.randomM;
		this.seed = b.seed;
		this.selectedFile = b.selectedFile;
		this.remainingFile = b.remainingFile;
		this.instrumentationMode = b.instrumentationMode;
		this.includePackages = b.includePackages;
		this.filterByGroupId = b.filterByGroupId;
		this.groupId = b.groupId;
		this.verboseFile = b.verboseFile;
		this.autoLearnRunThreshold = b.autoLearnRunThreshold;
		this.autoLearnDiffThreshold = b.autoLearnDiffThreshold;
		this.optimizeEvery = b.optimizeEvery;
		this.dependencyFingerprintSupplier = b.dependencyFingerprintSupplier;
		this.projectName = b.projectName;
		this.pluginVersion = b.pluginVersion;
		this.log = b.log != null ? b.log : PluginLog.NOOP;
	}

	// ── Accessors ────────────────────────────────────────────────────

	public Path projectRoot() {
		return projectRoot;
	}
	public Path sourceRoot() {
		return sourceRoot;
	}
	public Path testSourceRoot() {
		return testSourceRoot;
	}
	public List<Path> additionalSourceRoots() {
		return additionalSourceRoots;
	}
	public Path testClassesDir() {
		return testClassesDir;
	}
	public Path indexFile() {
		return indexFile;
	}
	public Path stateFile() {
		return stateFile;
	}
	public Path depsDir() {
		return depsDir;
	}
	public Path hashFile() {
		return hashFile;
	}
	public Path testHashFile() {
		return testHashFile;
	}
	public Path methodHashFile() {
		return methodHashFile;
	}
	public String changeMode() {
		return changeMode;
	}
	public String changedClasses() {
		return changedClasses;
	}
	public String changedTestClasses() {
		return changedTestClasses;
	}
	public Path weightsFile() {
		return weightsFile;
	}
	public Map<String, Integer> scoreOverrides() {
		return scoreOverrides;
	}
	public boolean methodOrderingEnabled() {
		return methodOrderingEnabled;
	}
	public boolean springContextGrouping() {
		return springContextGrouping;
	}
	public int topN() {
		return topN;
	}
	public int randomM() {
		return randomM;
	}
	public Long seed() {
		return seed;
	}
	public Path selectedFile() {
		return selectedFile;
	}
	public Path remainingFile() {
		return remainingFile;
	}
	public String instrumentationMode() {
		return instrumentationMode;
	}
	public String includePackages() {
		return includePackages;
	}
	public boolean filterByGroupId() {
		return filterByGroupId;
	}
	public String groupId() {
		return groupId;
	}
	public Path verboseFile() {
		return verboseFile;
	}
	public int autoLearnRunThreshold() {
		return autoLearnRunThreshold;
	}
	public int autoLearnDiffThreshold() {
		return autoLearnDiffThreshold;
	}
	public int optimizeEvery() {
		return optimizeEvery;
	}
	public Supplier<String> dependencyFingerprintSupplier() {
		return dependencyFingerprintSupplier;
	}
	public String projectName() {
		return projectName;
	}
	public String pluginVersion() {
		return pluginVersion;
	}
	public PluginLog log() {
		return log;
	}

	/**
	 * Returns all source roots (primary + additional) as a list.
	 */
	public List<Path> allSourceRoots() {
		if (additionalSourceRoots.isEmpty()) {
			return sourceRoot != null ? List.of(sourceRoot) : List.of();
		}
		var roots = new java.util.ArrayList<Path>();
		if (sourceRoot != null)
			roots.add(sourceRoot);
		roots.addAll(additionalSourceRoots);
		return List.copyOf(roots);
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {
		private Path projectRoot;
		private Path sourceRoot;
		private Path testSourceRoot;
		private List<Path> additionalSourceRoots;
		private Path testClassesDir;
		private Path indexFile;
		private Path stateFile;
		private Path depsDir;
		private Path hashFile;
		private Path testHashFile;
		private Path methodHashFile;
		private String changeMode;
		private String changedClasses;
		private String changedTestClasses;
		private Path weightsFile;
		private Map<String, Integer> scoreOverrides;
		private boolean methodOrderingEnabled;
		private boolean springContextGrouping;
		private int topN = -1;
		private int randomM = 10;
		private Long seed;
		private Path selectedFile;
		private Path remainingFile;
		private String instrumentationMode = "CLASS";
		private String includePackages;
		private boolean filterByGroupId = true;
		private String groupId;
		private Path verboseFile;
		private int autoLearnRunThreshold = 10;
		private int autoLearnDiffThreshold;
		private int optimizeEvery = 10;
		private Supplier<String> dependencyFingerprintSupplier;
		private String projectName;
		private String pluginVersion;
		private PluginLog log;

		private Builder() {
		}

		public Builder projectRoot(Path v) {
			this.projectRoot = v;
			return this;
		}
		public Builder sourceRoot(Path v) {
			this.sourceRoot = v;
			return this;
		}
		public Builder testSourceRoot(Path v) {
			this.testSourceRoot = v;
			return this;
		}
		public Builder additionalSourceRoots(List<Path> v) {
			this.additionalSourceRoots = v;
			return this;
		}
		public Builder testClassesDir(Path v) {
			this.testClassesDir = v;
			return this;
		}
		public Builder indexFile(Path v) {
			this.indexFile = v;
			return this;
		}
		public Builder stateFile(Path v) {
			this.stateFile = v;
			return this;
		}
		public Builder depsDir(Path v) {
			this.depsDir = v;
			return this;
		}
		public Builder hashFile(Path v) {
			this.hashFile = v;
			return this;
		}
		public Builder testHashFile(Path v) {
			this.testHashFile = v;
			return this;
		}
		public Builder methodHashFile(Path v) {
			this.methodHashFile = v;
			return this;
		}
		public Builder changeMode(String v) {
			this.changeMode = v;
			return this;
		}
		public Builder changedClasses(String v) {
			this.changedClasses = v;
			return this;
		}
		public Builder changedTestClasses(String v) {
			this.changedTestClasses = v;
			return this;
		}
		public Builder weightsFile(Path v) {
			this.weightsFile = v;
			return this;
		}
		public Builder scoreOverrides(Map<String, Integer> v) {
			this.scoreOverrides = v;
			return this;
		}
		public Builder methodOrderingEnabled(boolean v) {
			this.methodOrderingEnabled = v;
			return this;
		}
		public Builder springContextGrouping(boolean v) {
			this.springContextGrouping = v;
			return this;
		}
		public Builder topN(int v) {
			this.topN = v;
			return this;
		}
		public Builder randomM(int v) {
			this.randomM = v;
			return this;
		}
		public Builder seed(Long v) {
			this.seed = v;
			return this;
		}
		public Builder selectedFile(Path v) {
			this.selectedFile = v;
			return this;
		}
		public Builder remainingFile(Path v) {
			this.remainingFile = v;
			return this;
		}
		public Builder instrumentationMode(String v) {
			this.instrumentationMode = v;
			return this;
		}
		public Builder includePackages(String v) {
			this.includePackages = v;
			return this;
		}
		public Builder filterByGroupId(boolean v) {
			this.filterByGroupId = v;
			return this;
		}
		public Builder groupId(String v) {
			this.groupId = v;
			return this;
		}
		public Builder verboseFile(Path v) {
			this.verboseFile = v;
			return this;
		}
		public Builder autoLearnRunThreshold(int v) {
			this.autoLearnRunThreshold = v;
			return this;
		}
		public Builder autoLearnDiffThreshold(int v) {
			this.autoLearnDiffThreshold = v;
			return this;
		}
		public Builder optimizeEvery(int v) {
			this.optimizeEvery = v;
			return this;
		}
		public Builder dependencyFingerprintSupplier(Supplier<String> v) {
			this.dependencyFingerprintSupplier = v;
			return this;
		}
		public Builder projectName(String v) {
			this.projectName = v;
			return this;
		}
		public Builder pluginVersion(String v) {
			this.pluginVersion = v;
			return this;
		}
		public Builder log(PluginLog v) {
			this.log = v;
			return this;
		}

		public PluginContext build() {
			if (topN < -1)
				throw new IllegalArgumentException("[test-order] selectTopN cannot be less than -1: " + topN);
			if (randomM < 0)
				throw new IllegalArgumentException("[test-order] selectRandomM cannot be negative: " + randomM);
			if (optimizeEvery < 0)
				throw new IllegalArgumentException("[test-order] optimizeEvery cannot be negative: " + optimizeEvery);
			if (autoLearnRunThreshold < 0)
				throw new IllegalArgumentException(
						"[test-order] autoLearnRunThreshold cannot be negative: " + autoLearnRunThreshold);
			return new PluginContext(this);
		}
	}
}
