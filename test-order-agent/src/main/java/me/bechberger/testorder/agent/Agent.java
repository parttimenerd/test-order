package me.bechberger.testorder.agent;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.jar.JarFile;

/**
 * Java agent entry point for test dependency recording. Instruments class
 * initializers/methods to track which classes are used during test execution.
 * <p>
 * Example agent string:
 * {@code outputDir=target/test-order-deps,includePackages=com.example;org.app,mode=FULL}
 */
public class Agent {

	public enum InstrumentationMode {
		/**
		 * Tracks which classes each test exercises. Records method/constructor entry
		 * and foreign static-field access. This is the default and cheapest mode — all
		 * dependency data is at per-test-class granularity.
		 */
		CLASS,
		/**
		 * Extends CLASS with per-test-method tracking. Each individual test method gets
		 * its own dependency set (setup/teardown excluded). Enables method-level
		 * ordering via dependency-overlap scoring. Moderate overhead (~68%).
		 */
		METHOD,
		/**
		 * Extends METHOD with member-level dependencies ({@code class#method},
		 * {@code class#field}). Enables precise impact analysis: a test that never
		 * calls the actually-changed method won't be selected. Highest overhead (~121%)
		 * and largest index, but best precision for affected-test selection.
		 */
		MEMBER;

		/**
		 * Parses a mode string. Case-insensitive. Accepts legacy names (FULL,
		 * FULL_METHOD, FULL_MEMBER) as aliases for CLASS, METHOD, MEMBER.
		 */
		public static InstrumentationMode fromString(String value) {
			return switch (value.toUpperCase()) {
				case "CLASS", "FULL" -> CLASS;
				case "METHOD", "FULL_METHOD", "METHOD_ENTRY" -> METHOD;
				case "MEMBER", "FULL_MEMBER" -> MEMBER;
				default -> throw new IllegalArgumentException(
						"Unknown instrumentation mode: '" + value + "'. Valid values: CLASS, METHOD, MEMBER");
			};
		}
	}

	private Path outputDir = Path.of("target/test-order-deps");
	private List<String> includePackages;
	private List<String> excludePackages;
	private IntelligentClassFilter.Strategy filterStrategy = IntelligentClassFilter.Strategy.SMART;
	private boolean skipTestClasses = true;
	private boolean useHeuristics = true;
	private boolean autoDetectPackages = true;
	private Path projectRoot = Path.of(System.getProperty("user.dir"));
	private InstrumentationMode mode = InstrumentationMode.CLASS;
	private Path indexFile = Path.of("test-dependencies.lz4");
	private Path verboseFile;
	private Path runtimeJarPath;

	// ── Option parsing ────────────────────────────────────────────────

	/**
	 * Fast direct parser for agent args. Supports both formats:
	 * <ul>
	 * <li>{@code key=value,key=value} (from Maven/Gradle plugin)</li>
	 * <li>{@code --key=value,--key=value} (from manual invocation)</li>
	 * </ul>
	 */
	public static Agent parse(String agentArgs) {
		Agent agent = new Agent();
		if (agentArgs == null || agentArgs.isBlank()) {
			return agent;
		}
		try {
			// Split on unescaped commas only; use \\, in an arg value to embed a literal
			// comma.
			for (String part : agentArgs.split("(?<!\\\\),")) {
				String kv = part.startsWith("--") ? part.substring(2) : part;
				int eq = kv.indexOf('=');
				if (eq < 0)
					continue;
				String key = kv.substring(0, eq);
				String value = kv.substring(eq + 1);
				// Unescape \, back to , in values (paths may contain commas escaped by the
				// builder)
				String unescaped = value.replace("\\,", ",");
				switch (key) {
					case "outputDir" -> agent.outputDir = Path.of(unescaped);
					case "includePackages" -> agent.includePackages = splitList(value);
					case "excludePackages" -> agent.excludePackages = splitList(value);
					case "filterStrategy" ->
						agent.filterStrategy = IntelligentClassFilter.Strategy.valueOf(value.toUpperCase());
					case "skipTestClasses" -> agent.skipTestClasses = Boolean.parseBoolean(value);
					case "useHeuristics" -> agent.useHeuristics = Boolean.parseBoolean(value);
					case "autoDetectPackages" -> agent.autoDetectPackages = Boolean.parseBoolean(value);
					case "projectRoot" -> agent.projectRoot = Path.of(unescaped);
					case "mode" -> agent.mode = InstrumentationMode.fromString(value);
					case "indexFile" -> agent.indexFile = Path.of(unescaped);
					case "verboseFile" -> agent.verboseFile = Path.of(unescaped);
					case "runtimeJarPath" -> agent.runtimeJarPath = Path.of(unescaped);
					default -> {
					} // ignore unknown keys for forward compatibility
				}
			}
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid agent arguments: " + agentArgs, e);
		}
		return agent;
	}

	private static List<String> splitList(String value) {
		if (value == null || value.isEmpty())
			return Collections.emptyList();
		List<String> result = new ArrayList<>();
		for (String s : value.split(";")) {
			if (!s.isEmpty())
				result.add(s);
		}
		return result;
	}

	public Path getOutputDir() {
		return outputDir;
	}

	public List<String> getIncludePackages() {
		return includePackages == null ? Collections.emptyList() : includePackages;
	}

	public List<String> getExcludePackages() {
		return excludePackages == null ? Collections.emptyList() : excludePackages;
	}

	public IntelligentClassFilter.Strategy getFilterStrategy() {
		return filterStrategy;
	}

	public boolean isSkipTestClasses() {
		return skipTestClasses;
	}

	public boolean isUseHeuristics() {
		return useHeuristics;
	}

	public boolean isAutoDetectPackages() {
		return autoDetectPackages;
	}

	public Path getProjectRoot() {
		return projectRoot;
	}

	public InstrumentationMode getMode() {
		return mode;
	}

	public Path getIndexFile() {
		return indexFile;
	}

	public Path getVerboseFile() {
		return verboseFile;
	}

	public Path getRuntimeJarPath() {
		return runtimeJarPath;
	}

	// ── Agent entry point ─────────────────────────────────────────────

	public static void premain(String agentArgs, Instrumentation inst) {
		Agent options = Agent.parse(agentArgs);

		// ensure output directory exists
		Path outputDir = options.getOutputDir();
		try {
			Files.createDirectories(outputDir);
		} catch (IOException e) {
			throw new RuntimeException("Failed to create output directory: " + outputDir, e);
		}

		// system properties for TelemetryListener (on system classpath, not accessible
		// via UsageStore)
		System.setProperty("testorder.learn", "true");
		System.setProperty("testorder.instrumentation.mode", options.getMode().name());

		// selective learn: load uncertain-classes file if present
		Set<String> uncertainClasses = loadUncertainClasses();

		// extract and add runtime jar to bootstrap classpath
		try {
			appendRuntimeJarToBootstrap(inst, Agent.class.getClassLoader(), options.getRuntimeJarPath());
		} catch (IOException e) {
			throw new RuntimeException("Failed to add runtime jar to bootstrap classpath", e);
		}

		// configure AgentLogger if verbose file is set (must be before UsageStore
		// config so it can log)
		if (options.getVerboseFile() != null) {
			try {
				configureAgentLogger(options.getVerboseFile(), null);
			} catch (ReflectiveOperationException e) {
				System.err.println("[test-order] Failed to configure AgentLogger: " + e.getMessage());
			}
		}

		// configure UsageStore via reflection (now on bootstrap classpath)
		AsmClassTransformer transformer = new AsmClassTransformer(options, uncertainClasses);
		try {
			Class<?> usageStoreClass = Class.forName("me.bechberger.testorder.agent.runtime.UsageStore", true, null);
			Object instance = usageStoreClass.getMethod("getInstance").invoke(null);
			boolean methodLevel = options.getMode() == InstrumentationMode.METHOD
					|| options.getMode() == InstrumentationMode.MEMBER;
			usageStoreClass.getMethod("configure", String.class, String.class, boolean.class, Runnable.class).invoke(
					instance, outputDir.toAbsolutePath().toString(), options.getIndexFile().toAbsolutePath().toString(),
					methodLevel, (Runnable) transformer::releaseTransformationCaches);
		} catch (ReflectiveOperationException e) {
			throw new RuntimeException("Failed to configure UsageStore", e);
		}

		inst.addTransformer(transformer, true);
	}

	static void configureAgentLogger(Path verboseFile, ClassLoader classLoader) throws ReflectiveOperationException {
		Class<?> loggerClass = Class.forName("me.bechberger.testorder.agent.runtime.AgentLogger", true, classLoader);
		loggerClass.getMethod("setVerboseFile", String.class).invoke(null, verboseFile.toAbsolutePath().toString());
	}

	/**
	 * Reads the uncertain-classes file (if configured via
	 * {@code testorder.learn.uncertainClassesFile}) and returns the set of FQCNs to
	 * instrument. Returns {@code null} when the property is absent or the file
	 * cannot be read — callers treat {@code null} as "instrument everything".
	 */
	static Set<String> loadUncertainClasses() {
		String filePath = System.getProperty("testorder.learn.uncertainClassesFile");
		if (filePath == null || filePath.isBlank()) {
			return null;
		}
		Path p = Path.of(filePath);
		if (!Files.isRegularFile(p)) {
			return null;
		}
		try {
			Set<String> result = new HashSet<>();
			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					String trimmed = line.trim();
					if (!trimmed.isEmpty()) {
						result.add(trimmed);
					}
				}
			}
			return Collections.unmodifiableSet(result);
		} catch (IOException e) {
			System.err.println("[test-order] Could not read uncertain-classes file: " + e.getMessage());
			return null;
		}
	}

	static Path appendRuntimeJarToBootstrap(Instrumentation inst, ClassLoader resourceLoader, Path preExtractedJar)
			throws IOException {
		Path jarPath;
		if (preExtractedJar != null && Files.exists(preExtractedJar) && Files.size(preExtractedJar) > 0) {
			jarPath = preExtractedJar;
		} else {
			jarPath = extractRuntimeJar(resourceLoader);
		}
		// appendToBootstrapClassLoaderSearch takes ownership of the JarFile (the JVM
		// keeps it open for the lifetime of the class loader), so we intentionally do
		// not close it here — wrapping it in try-with-resources would close it
		// prematurely.
		inst.appendToBootstrapClassLoaderSearch(new JarFile(jarPath.toFile()));
		return jarPath;
	}

	// Keep no-arg overload for tests
	static Path appendRuntimeJarToBootstrap(Instrumentation inst, ClassLoader resourceLoader) throws IOException {
		return appendRuntimeJarToBootstrap(inst, resourceLoader, null);
	}

	static Path extractRuntimeJar(ClassLoader resourceLoader) throws IOException {
		// Use the agent jar's own location + size as a fast cache key, avoiding
		// the need to read and hash the full runtime jar on every fork.
		Path cacheDir = Paths.get(System.getProperty("java.io.tmpdir"));
		String cacheKey = computeCacheKey(resourceLoader);
		Path cachedJar = cacheDir.resolve("test-order-runtime-" + cacheKey + ".jar");

		if (Files.exists(cachedJar) && Files.size(cachedJar) > 0) {
			// TOCTOU note: there is an inherent race between this size check and the
			// subsequent read — a concurrent process could replace the file between the
			// two operations. The risk is low because (a) the cache key embeds the agent
			// jar's size+mtime so a legitimate replacement is extremely unlikely in a
			// single build, and (b) the JVM will raise a ZipException when it opens a
			// corrupt jar, which surfaces the problem clearly.
			return cachedJar.toAbsolutePath();
		}

		// Cache miss — extract the runtime jar
		try (InputStream in = resourceLoader.getResourceAsStream("test-order-runtime.jar")) {
			if (in == null) {
				throw new RuntimeException("Could not find test-order-runtime.jar");
			}
			byte[] jarContent = in.readAllBytes();

			// Atomic write: write to temp file then rename to prevent parallel forks
			// from reading a partially-written jar ("zip file is empty" errors).
			// Create with owner-only permissions so no other OS user can replace the
			// file between our existence check and the JVM loading it.
			Path tempFile;
			try {
				tempFile = Files.createTempFile(cacheDir, "test-order-runtime-", ".tmp",
						PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
			} catch (UnsupportedOperationException ignored) {
				// Non-POSIX filesystem (Windows) — fall back to default permissions
				tempFile = Files.createTempFile(cacheDir, "test-order-runtime-", ".tmp");
			}
			try {
				Files.write(tempFile, jarContent);
				Files.move(tempFile, cachedJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				// Best-effort: restrict permissions on the final cached file too
				try {
					Files.setPosixFilePermissions(cachedJar, PosixFilePermissions.fromString("r--------"));
				} catch (UnsupportedOperationException | IOException ignored) {
				}
			} catch (IOException e) {
				// Another fork may have beaten us to it — that's fine
				Files.deleteIfExists(tempFile);
				if (Files.exists(cachedJar) && Files.size(cachedJar) > 0) {
					return cachedJar.toAbsolutePath();
				}
				throw e;
			}
			return cachedJar.toAbsolutePath();
		}
	}

	private static String computeCacheKey(ClassLoader resourceLoader) {
		// Derive cache key from the agent jar's identity (location + size + mtime)
		// so we never re-read the embedded resource when the cached file already exists
		try {
			java.net.URL agentUrl = resourceLoader.getResource("test-order-runtime.jar");
			if (agentUrl != null) {
				String path = agentUrl.getPath();
				// URL is like "file:/path/to/agent.jar!/test-order-runtime.jar"
				int bangIdx = path.indexOf('!');
				if (bangIdx > 0) {
					String jarPath = path.startsWith("file:") ? path.substring(5, bangIdx) : path.substring(0, bangIdx);
					Path agentJarPath = Paths.get(jarPath);
					if (Files.exists(agentJarPath)) {
						long size = Files.size(agentJarPath);
						long mtime = Files.getLastModifiedTime(agentJarPath).toMillis();
						return Long.toHexString(size) + "-" + Long.toHexString(mtime);
					}
				}
			}
		} catch (Exception ignored) {
			// Fall through to content-hash approach
		}
		// Fallback: read content and hash (first fork pays this cost, subsequent forks
		// use the cached file)
		try (InputStream in = resourceLoader.getResourceAsStream("test-order-runtime.jar")) {
			if (in != null) {
				return Integer.toHexString(java.util.Arrays.hashCode(in.readAllBytes()));
			}
		} catch (IOException ignored) {
		}
		return "unknown";
	}
}
