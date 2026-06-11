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

	public enum BuildSystem {
		MAVEN, GRADLE;

		/** Command to run tests in learn mode. */
		public String learnCommand() {
			return this == GRADLE ? "./gradlew test -Dtestorder.mode=learn" : "mvn test -Dtestorder.mode=learn";
		}

		/** Command to show test order. */
		public String showCommand() {
			return this == GRADLE ? "./gradlew testOrderShow" : "mvn test-order:show";
		}

		/** Command to show the dashboard. */
		public String dashboardCommand() {
			return this == GRADLE ? "./gradlew testOrderDashboard" : "mvn test-order:dashboard";
		}

		/** Prefix for plugin-specific goals/tasks. */
		public String pluginPrefix() {
			return this == GRADLE ? "./gradlew testOrder" : "mvn test-order:";
		}
	}

	// ── Path config ──────────────────────────────────────────────────
	private final Path projectRoot;
	/**
	 * Git repository root — may span multiple modules; null means use projectRoot.
	 */
	private final Path repoRoot;
	private final Path sourceRoot;
	private final Path testSourceRoot;
	private final List<Path> additionalSourceRoots; // e.g. Kotlin
	private final Path testClassesDir;
	private final Path classesDir;
	private final Path indexFile;
	private final Path stateFile;
	private final Path depsDir;
	private final Path hashFile;
	private final Path testHashFile;
	private final Path methodHashFile;
	private final Path bytecodeHashFile;

	// ── Change detection config ──────────────────────────────────────
	private final String changeMode;
	private final String changedClasses;
	private final String changedTestClasses;

	// ── Scoring config ───────────────────────────────────────────────
	private final Path weightsFile;
	private final Map<String, Integer> scoreOverrides;
	private final boolean methodOrderingEnabled;
	private final boolean springContextGrouping;
	private final boolean staticAnalysisEnabled;
	private final int staticAnalysisDepth;
	private final boolean bytecodeChangeDetectionEnabled;
	private final boolean bytecodeAugmentDependencyMapEnabled;

	// ── Selection config ─────────────────────────────────────────────
	private final int topN;
	private final int randomM;
	private final boolean selectiveLearn;
	private final boolean alwaysLearn;
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
	/**
	 * Owning module id of the project this context belongs to (e.g.
	 * "groupId:artifactId"). When non-null, multi-module-aware operations filter
	 * the dependency index to tests recorded as belonging to this module before
	 * selection. Null in single-module builds or when callers don't supply it.
	 */
	private final String currentModuleId;

	// ── Logging / build system ───────────────────────────────────────
	private final PluginLog log;
	private final BuildSystem buildSystem;

	private PluginContext(Builder b) {
		this.projectRoot = b.projectRoot;
		this.repoRoot = b.repoRoot;
		this.sourceRoot = b.sourceRoot;
		this.testSourceRoot = b.testSourceRoot;
		this.additionalSourceRoots = b.additionalSourceRoots != null ? List.copyOf(b.additionalSourceRoots) : List.of();
		this.testClassesDir = b.testClassesDir;
		this.classesDir = b.classesDir;
		this.indexFile = b.indexFile;
		this.stateFile = b.stateFile;
		this.depsDir = b.depsDir;
		this.hashFile = b.hashFile;
		this.testHashFile = b.testHashFile;
		this.methodHashFile = b.methodHashFile;
		this.bytecodeHashFile = b.bytecodeHashFile;
		this.changeMode = b.changeMode;
		this.changedClasses = b.changedClasses;
		this.changedTestClasses = b.changedTestClasses;
		this.weightsFile = b.weightsFile;
		this.scoreOverrides = b.scoreOverrides;
		this.methodOrderingEnabled = b.methodOrderingEnabled;
		this.springContextGrouping = b.springContextGrouping;
		this.staticAnalysisEnabled = b.staticAnalysisEnabled;
		this.staticAnalysisDepth = b.staticAnalysisDepth;
		this.bytecodeChangeDetectionEnabled = b.bytecodeChangeDetectionEnabled;
		this.bytecodeAugmentDependencyMapEnabled = b.bytecodeAugmentDependencyMapEnabled;
		this.topN = b.topN;
		this.randomM = b.randomM;
		this.selectiveLearn = b.selectiveLearn;
		this.alwaysLearn = b.alwaysLearn;
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
		this.currentModuleId = b.currentModuleId;
		this.log = b.log != null ? b.log : PluginLog.NOOP;
		this.buildSystem = b.buildSystem != null ? b.buildSystem : BuildSystem.MAVEN;
	}

	// ── Accessors ────────────────────────────────────────────────────

	public Path projectRoot() {
		return projectRoot;
	}

	/**
	 * Git repository root — spans all modules; null if not set (use projectRoot).
	 */
	public Path repoRoot() {
		return repoRoot;
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
	public Path classesDir() {
		return classesDir;
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
	public Path bytecodeHashFile() {
		return bytecodeHashFile;
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
	public boolean staticAnalysisEnabled() {
		return staticAnalysisEnabled;
	}
	public int staticAnalysisDepth() {
		return staticAnalysisDepth;
	}
	public boolean bytecodeChangeDetectionEnabled() {
		return bytecodeChangeDetectionEnabled;
	}
	public boolean bytecodeAugmentDependencyMapEnabled() {
		return bytecodeAugmentDependencyMapEnabled;
	}
	public int topN() {
		return topN;
	}

	public int randomM() {
		return randomM;
	}

	public boolean selectiveLearn() {
		return selectiveLearn;
	}
	public boolean alwaysLearn() {
		return alwaysLearn;
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
	public String currentModuleId() {
		return currentModuleId;
	}
	public PluginLog log() {
		return log;
	}
	public BuildSystem buildSystem() {
		return buildSystem;
	}
	/**
	 * Convenience: command to run tests in learn mode for the active build system.
	 */
	public String learnCommand() {
		return buildSystem.learnCommand();
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
		private Path repoRoot;
		private Path sourceRoot;
		private Path testSourceRoot;
		private List<Path> additionalSourceRoots;
		private Path testClassesDir;
		private Path classesDir;
		private Path indexFile;
		private Path stateFile;
		private Path depsDir;
		private Path hashFile;
		private Path testHashFile;
		private Path methodHashFile;
		private Path bytecodeHashFile;
		private String changeMode;
		private String changedClasses;
		private String changedTestClasses;
		private Path weightsFile;
		private Map<String, Integer> scoreOverrides;
		private boolean methodOrderingEnabled;
		private boolean springContextGrouping;
		private boolean staticAnalysisEnabled = true;
		private int staticAnalysisDepth = 2;
		private boolean bytecodeChangeDetectionEnabled = true;
		private boolean bytecodeAugmentDependencyMapEnabled = true;
		private int topN = -1;
		private int randomM = 10;
		private boolean selectiveLearn = false;
		private boolean alwaysLearn = false;
		private Long seed;
		private Path selectedFile;
		private Path remainingFile;
		private String instrumentationMode = "MEMBER";
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
		private String currentModuleId;
		private PluginLog log;
		private BuildSystem buildSystem;

		private Builder() {
		}

		public Builder projectRoot(Path v) {
			this.projectRoot = v;
			return this;
		}

		public Builder repoRoot(Path v) {
			this.repoRoot = v;
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
		public Builder classesDir(Path v) {
			this.classesDir = v;
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
		public Builder bytecodeHashFile(Path v) {
			this.bytecodeHashFile = v;
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
		public Builder staticAnalysisEnabled(boolean v) {
			this.staticAnalysisEnabled = v;
			return this;
		}
		public Builder staticAnalysisDepth(int v) {
			this.staticAnalysisDepth = v;
			return this;
		}
		public Builder bytecodeChangeDetectionEnabled(boolean v) {
			this.bytecodeChangeDetectionEnabled = v;
			return this;
		}
		public Builder bytecodeAugmentDependencyMapEnabled(boolean v) {
			this.bytecodeAugmentDependencyMapEnabled = v;
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

		public Builder selectiveLearn(boolean v) {
			this.selectiveLearn = v;
			return this;
		}
		public Builder alwaysLearn(boolean v) {
			this.alwaysLearn = v;
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
		public Builder currentModuleId(String v) {
			this.currentModuleId = v;
			return this;
		}
		public Builder log(PluginLog v) {
			this.log = v;
			return this;
		}
		public Builder buildSystem(BuildSystem v) {
			this.buildSystem = v;
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
