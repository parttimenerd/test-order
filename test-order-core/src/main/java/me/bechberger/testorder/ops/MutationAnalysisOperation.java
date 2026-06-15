package me.bechberger.testorder.ops;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.util.json.PrettyPrinter;

/**
 * Runs PIT mutation testing scoped to the classes covered by the dependency
 * index, computes per-test kill rates, updates the state file, and writes a
 * {@code test-mutation-results.json} report.
 * <p>
 * Designed for nightly / weekly CI runs. The kill rates stored in the state
 * file are picked up by {@link me.bechberger.testorder.TestScorer} on every
 * subsequent {@code select} / {@code order} run.
 */
public final class MutationAnalysisOperation {

	private MutationAnalysisOperation() {
	}

	/**
	 * Configuration for a mutation analysis run.
	 *
	 * @param indexFile
	 *            path to {@code test-dependencies.lz4}
	 * @param stateFile
	 *            path to {@code .test-order-state} (updated with kill rates after
	 *            the run)
	 * @param outputFile
	 *            destination for {@code test-mutation-results.json}
	 * @param projectRoot
	 *            project root — PIT resolves source and class directories relative
	 *            to this
	 * @param targetClasses
	 *            comma-separated glob for production classes to mutate, or
	 *            {@code null} to derive from the dependency index
	 * @param timeBudgetSeconds
	 *            maximum seconds to spend on mutation testing (0 = no limit)
	 * @param log
	 *            plugin log
	 * @param extraClasspath
	 *            additional classpath entries for the forked test minion (e.g.
	 *            Maven project test classpath), may be empty or {@code null}
	 */
	public record Config(Path indexFile, Path stateFile, Path outputFile, Path projectRoot, String targetClasses,
			int timeBudgetSeconds, PluginLog log, List<String> extraClasspath) {

		public Config(Path indexFile, Path stateFile, Path outputFile, Path projectRoot, String targetClasses,
				int timeBudgetSeconds, PluginLog log) {
			this(indexFile, stateFile, outputFile, projectRoot, targetClasses, timeBudgetSeconds, log, List.of());
		}
	}

	/**
	 * Result of a mutation analysis run.
	 *
	 * @param killRates
	 *            map from test-class FQCN to kill rate in [0.0, 1.0]
	 * @param totalMutants
	 *            total number of mutants generated
	 * @param totalKilled
	 *            total number of mutants killed by at least one test
	 * @param reportFile
	 *            path to the written JSON report
	 * @param durationMillis
	 *            elapsed wall-clock time
	 */
	public record Result(Map<String, Double> killRates, int totalMutants, int totalKilled, Path reportFile,
			long durationMillis) {
	}

	/**
	 * Runs mutation analysis and returns the result.
	 *
	 * @param config
	 *            analysis configuration
	 * @return result with kill rates and report path
	 * @throws IOException
	 *             on I/O failure or if PIT classes are not on the classpath
	 */
	public static Result run(Config config) throws IOException {
		long start = System.currentTimeMillis();
		PluginLog log = config.log();

		if (!Files.exists(config.indexFile())) {
			throw new IOException("Dependency index not found: " + config.indexFile()
					+ ". Run learn mode first: mvn test -Dtestorder.mode=learn");
		}

		log.info("[test-order] Loading dependency index from " + config.indexFile());
		DependencyMap depMap = DependencyMap.load(config.indexFile());
		if (depMap.size() == 0) {
			throw new IOException("Dependency index is empty — learn mode must complete successfully first.");
		}

		// Derive the set of production classes to mutate from the dep graph
		Set<String> productionClasses = deriveProductionClasses(depMap, config.targetClasses());
		Set<String> testClasses = new LinkedHashSet<>(depMap.testClasses());

		log.info("[test-order] Mutation analysis: " + testClasses.size() + " test classes, " + productionClasses.size()
				+ " target production classes");

		// Resolve classpath entries from the project
		Path targetClasses = config.projectRoot().resolve("target/classes");
		Path targetTestClasses = config.projectRoot().resolve("target/test-classes");

		if (!Files.exists(targetClasses)) {
			throw new IOException(
					"Compiled classes directory not found: " + targetClasses + ". Run mvn compile test-compile first.");
		}

		// Build the target-class glob patterns for PIT
		String targetGlob = productionClasses.stream().map(c -> c.replace('$', '*')).collect(Collectors.joining(","));
		String testGlob = testClasses.stream().map(c -> c.replace('$', '*')).collect(Collectors.joining(","));

		// Resolve PIT report output directory
		Path pitReportDir = config.projectRoot().resolve("target/pit-reports");

		// Invoke PIT programmatically
		invokePit(config.projectRoot(), targetClasses, targetTestClasses, targetGlob, testGlob, pitReportDir,
				config.timeBudgetSeconds(), config.extraClasspath(), log);

		// Parse PIT XML report
		Path mutationsXml = findMutationsXml(pitReportDir);
		Map<String, MutationStats> statsByTest = parseMutationsXml(mutationsXml, testClasses);

		// Extract global totals from sentinel entry, then remove it
		MutationStats globalStats = statsByTest.remove("__total__");
		int totalMutants = globalStats != null ? globalStats.total : 0;
		int totalKilled = globalStats != null ? globalStats.killed : 0;

		// Per-test kill rate = fraction of ALL killed mutants that this test killed.
		// Tests that killed nothing get 0.0; the best test is 1.0 if it killed
		// everything.
		Map<String, Double> killRates = new LinkedHashMap<>();
		for (var entry : statsByTest.entrySet()) {
			MutationStats stats = entry.getValue();
			double rate = totalKilled > 0 ? (double) stats.killed / totalKilled : 0.0;
			killRates.put(entry.getKey(), rate);
		}

		// Update state file with kill rates
		if (Files.exists(config.stateFile())) {
			try {
				TestOrderState state = TestOrderState.load(config.stateFile());
				state.setKillRates(killRates);
				state.setMutationTotals(totalMutants, totalKilled);
				state.save(config.stateFile());
				log.info("[test-order] Kill rates saved to state file: " + config.stateFile());
			} catch (IOException e) {
				log.warn("[test-order] Failed to update state file with kill rates: " + e.getMessage());
			}
		} else {
			log.warn("[test-order] State file not found — kill rates will not be persisted for scoring: "
					+ config.stateFile());
		}

		// Write JSON report
		Path reportFile = writeReport(config.outputFile(), config.projectRoot(), killRates, statsByTest, totalMutants,
				totalKilled, log);

		long duration = System.currentTimeMillis() - start;
		log.info("[test-order] Mutation analysis complete in " + (duration / 1000) + "s — " + totalKilled + "/"
				+ totalMutants + " mutations killed");

		return new Result(Collections.unmodifiableMap(killRates), totalMutants, totalKilled, reportFile, duration);
	}

	// ── PIT invocation ────────────────────────────────────────────────────────

	/**
	 * Collects production class names from the dep graph, excluding test classes.
	 */
	static Set<String> deriveProductionClasses(DependencyMap depMap, String targetClassesOverride) {
		if (targetClassesOverride != null && !targetClassesOverride.isBlank()) {
			return new LinkedHashSet<>(Arrays.asList(targetClassesOverride.split(",")));
		}
		Set<String> testClasses = depMap.testClasses();
		Set<String> production = new LinkedHashSet<>();
		for (String testClass : testClasses) {
			for (String dep : depMap.get(testClass)) {
				if (!testClasses.contains(dep)) {
					production.add(dep);
				}
			}
		}
		return production;
	}

	private static void invokePit(Path projectRoot, Path classesDir, Path testClassesDir, String targetGlob,
			String testGlob, Path reportDir, int timeBudgetSeconds, List<String> extraClasspath, PluginLog log)
			throws IOException {
		// Verify PIT is available on the classpath
		try {
			Class.forName("org.pitest.mutationtest.tooling.EntryPoint");
		} catch (ClassNotFoundException e) {
			throw new IOException(
					"PIT (pitest-entry) is not on the classpath. " + "Add the pitest-entry dependency to your build.",
					e);
		}

		try {
			// Collect full classpath for PIT (classes + test-classes + all jars)
			List<String> classpath = buildClasspath(projectRoot, classesDir, testClassesDir, extraClasspath);

			// Redirect PIT's stdout/stderr to suppress verbose output
			PrintStream originalOut = System.out;
			PrintStream originalErr = System.err;
			try (PrintStream devNull = new PrintStream(OutputStream.nullOutputStream())) {
				System.setOut(devNull);
				System.setErr(devNull);
				runPitEntryPoint(classpath, classesDir, testClassesDir, targetGlob, testGlob, reportDir,
						timeBudgetSeconds, projectRoot);
			} finally {
				System.setOut(originalOut);
				System.setErr(originalErr);
			}
		} catch (IOException ioe) {
			throw ioe;
		} catch (Exception e) {
			throw new IOException("PIT mutation analysis failed: " + e.getMessage(), e);
		}

		log.info("[test-order] PIT analysis complete, report at " + reportDir);
	}

	@SuppressWarnings("unchecked")
	private static void runPitEntryPoint(List<String> classpath, Path classesDir, Path testClassesDir,
			String targetGlob, String testGlob, Path reportDir, int timeBudgetSeconds, Path projectRoot)
			throws Exception {
		Class<?> entryPointClass = Class.forName("org.pitest.mutationtest.tooling.EntryPoint");
		Class<?> reportOptionsClass = Class.forName("org.pitest.mutationtest.config.ReportOptions");
		Class<?> pluginServicesClass = Class.forName("org.pitest.mutationtest.config.PluginServices");
		Class<?> verbosityClass = Class.forName("org.pitest.util.Verbosity");
		Class<?> testGroupConfigClass = Class.forName("org.pitest.testapi.TestGroupConfig");

		Object reportOptions = reportOptionsClass.getDeclaredConstructor().newInstance();

		// groupConfig: required in PIT 1.25+, must not be null
		Object emptyGroupConfig = testGroupConfigClass.getDeclaredConstructor().newInstance();
		reportOptionsClass.getMethod("setGroupConfig", testGroupConfigClass).invoke(reportOptions, emptyGroupConfig);

		// targetClasses: Collection<String> globs
		reportOptionsClass.getMethod("setTargetClasses", Collection.class).invoke(reportOptions,
				globToCollection(targetGlob));

		// targetTests: Collection<Predicate<String>> — wrap each glob as a prefix
		// predicate
		List<java.util.function.Predicate<String>> testPredicates = globToCollection(testGlob).stream()
				.map(g -> (java.util.function.Predicate<String>) s -> s.startsWith(g.replace("*", "")))
				.collect(Collectors.toList());
		reportOptionsClass.getMethod("setTargetTests", Collection.class).invoke(reportOptions, testPredicates);

		// sourceDirs: Collection<Path>
		Path srcMain = projectRoot.resolve("src/main/java");
		List<Path> sourceDirs = Files.isDirectory(srcMain) ? List.of(srcMain) : List.of();
		reportOptionsClass.getMethod("setSourceDirs", Collection.class).invoke(reportOptions, sourceDirs);

		// classpath
		reportOptionsClass.getMethod("setClassPathElements", Collection.class).invoke(reportOptions, classpath);

		// reportDir
		reportOptionsClass.getMethod("setReportDir", String.class).invoke(reportOptions,
				reportDir.toAbsolutePath().toString());

		// no timestamped subdirs — write directly to reportDir
		reportOptionsClass.getMethod("setShouldCreateTimestampedReports", boolean.class).invoke(reportOptions, false);

		// outputFormats: XML only
		reportOptionsClass.getMethod("addOutputFormats", Collection.class).invoke(reportOptions, List.of("XML"));

		// verbosity: QUIET for production runs
		Object quiet = verbosityClass.getField("QUIET").get(null);
		reportOptionsClass.getMethod("setVerbosity", verbosityClass).invoke(reportOptions, quiet);

		// don't fail when no mutations found
		reportOptionsClass.getMethod("setFailWhenNoMutations", boolean.class).invoke(reportOptions, false);

		// skip pre-existing test failures — some tests may be flaky/env-dependent
		reportOptionsClass.getMethod("setSkipFailingTests", boolean.class).invoke(reportOptions, true);

		// timeBudget: use timeout constant (ms)
		if (timeBudgetSeconds > 0) {
			reportOptionsClass.getMethod("setTimeoutConstant", long.class).invoke(reportOptions,
					(long) timeBudgetSeconds * 1000);
		}

		// Forward JVM args from the parent process to the forked minion so that
		// module-system flags (--add-opens etc.) and GraalVM options are inherited.
		List<String> inheritedArgs = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
				.stream()
				.filter(a -> a.startsWith("--add-opens") || a.startsWith("--add-exports") || a.startsWith("--add-reads")
						|| a.startsWith("--enable-native-access") || a.startsWith("-XX:") || a.startsWith("-Djava."))
				.collect(Collectors.toList());
		if (!inheritedArgs.isEmpty()) {
			reportOptionsClass.getMethod("addChildJVMArgs", List.class).invoke(reportOptions, inheritedArgs);
		}

		// Build PluginServices
		Object pluginServices = pluginServicesClass.getMethod("makeForContextLoader").invoke(null);

		// Run via EntryPoint
		Object entryPoint = entryPointClass.getDeclaredConstructor().newInstance();
		entryPointClass.getMethod("execute", java.io.File.class, reportOptionsClass, pluginServicesClass, Map.class)
				.invoke(entryPoint, projectRoot.toFile(), reportOptions, pluginServices, new java.util.HashMap<>());
	}

	private static List<String> buildClasspath(Path projectRoot, Path classesDir, Path testClassesDir,
			List<String> extraClasspath) throws IOException {
		List<String> cp = new ArrayList<>();
		cp.add(classesDir.toAbsolutePath().toString());
		cp.add(testClassesDir.toAbsolutePath().toString());

		// Project test dependencies passed explicitly by the build tool (most reliable)
		if (extraClasspath != null) {
			cp.addAll(extraClasspath);

			// PIT's minion needs junit-platform-launcher to initialise
			// JUnit5TestUnitFinder.
			// Maven doesn't declare it as a direct test dependency so it may not appear in
			// extraClasspath — derive it from the junit-platform-engine jar path (same
			// version, adjacent directory) and add it if not already present.
			addJunitPlatformLauncher(extraClasspath, cp);
		}

		// Add jars from target/dependency (if Maven copied them there)
		Path depsDir = projectRoot.resolve("target/dependency");
		if (Files.isDirectory(depsDir)) {
			try (var stream = Files.walk(depsDir, 1)) {
				stream.filter(p -> p.toString().endsWith(".jar")).map(p -> p.toAbsolutePath().toString())
						.forEach(cp::add);
			}
		}

		// Add pitest jars from the current JVM classpath.
		// Only pitest jars — JUnit jars must come from the project's own test classpath
		// (via extraClasspath) to avoid version conflicts between test-order's JUnit
		// 6.x
		// and the project's JUnit 5.x dependencies.
		String jvmClasspath = System.getProperty("java.class.path", "");
		if (!jvmClasspath.isBlank()) {
			for (String entry : jvmClasspath.split(java.io.File.pathSeparator)) {
				String name = entry.contains(java.io.File.separator)
						? entry.substring(entry.lastIndexOf(java.io.File.separator) + 1)
						: entry;
				if (name.startsWith("pitest")) {
					cp.add(entry);
				}
			}
		}

		// Also scan classloader URL hierarchy — Maven plugin class-realms do not appear
		// on java.class.path but their JARs must be visible so PIT can read
		// HotSwapAgent.class bytes when building the agent JAR. Apply same filter.
		addClassloaderUrls(MutationAnalysisOperation.class.getClassLoader(), cp);

		return cp;
	}

	/**
	 * Adds junit-platform-launcher to the classpath if it isn't already there.
	 * PIT's JUnit 5 plugin needs LauncherFactory (from junit-platform-launcher) but
	 * Maven projects often don't declare it as a direct test dependency. We derive
	 * its path from the junit-platform-engine jar (same version, sibling
	 * directory).
	 */
	private static void addJunitPlatformLauncher(List<String> extraClasspath, List<String> cp) {
		boolean alreadyPresent = cp.stream().anyMatch(e -> e.contains("junit-platform-launcher"));
		if (alreadyPresent) {
			return;
		}
		// Find junit-platform-engine in the extraClasspath to derive the version
		for (String entry : extraClasspath) {
			Path p = Path.of(entry);
			String name = p.getFileName().toString();
			if (name.startsWith("junit-platform-engine-") && name.endsWith(".jar")) {
				// Extract version: "junit-platform-engine-1.11.4.jar" → "1.11.4"
				String version = name.substring("junit-platform-engine-".length(), name.length() - ".jar".length());
				// Sibling dir:
				// .../junit-platform-launcher/<version>/junit-platform-launcher-<version>.jar
				Path launcherJar = p.getParent().getParent().getParent().resolve("junit-platform-launcher")
						.resolve(version).resolve("junit-platform-launcher-" + version + ".jar");
				if (Files.exists(launcherJar)) {
					cp.add(launcherJar.toAbsolutePath().toString());
					return;
				}
			}
		}
	}

	private static void addIfPitOrJunit(String path, List<String> cp) {
		String name = path.contains(java.io.File.separator)
				? path.substring(path.lastIndexOf(java.io.File.separator) + 1)
				: path;
		// Only add pitest jars from the plugin classloader — JUnit jars
		// (junit-platform-*,
		// junit-jupiter-*) must come from the project's own test classpath to avoid
		// version conflicts between test-order's JUnit 6.x and the project's JUnit 5.x.
		if (name.startsWith("pitest")) {
			cp.add(new java.io.File(path).getAbsolutePath());
		}
	}

	private static void addClassloaderUrls(ClassLoader cl, List<String> cp) {
		if (cl == null)
			return;
		if (cl instanceof java.net.URLClassLoader ucl) {
			for (java.net.URL url : ucl.getURLs()) {
				if ("file".equals(url.getProtocol())) {
					addIfPitOrJunit(url.getFile(), cp);
				}
			}
		} else {
			// Plexus/Maven class-realm exposes URLs via getURLs() reflectively
			try {
				java.lang.reflect.Method getUrls = cl.getClass().getMethod("getURLs");
				java.net.URL[] urls = (java.net.URL[]) getUrls.invoke(cl);
				for (java.net.URL url : urls) {
					if ("file".equals(url.getProtocol())) {
						addIfPitOrJunit(url.getFile(), cp);
					}
				}
			} catch (Exception ignored) {
				// not a URL classloader, skip
			}
		}
		addClassloaderUrls(cl.getParent(), cp);
	}

	private static Collection<String> globToCollection(String glob) {
		if (glob == null || glob.isBlank())
			return List.of();
		return Arrays.asList(glob.split(","));
	}

	// ── Report parsing ────────────────────────────────────────────────────────

	private static Path findMutationsXml(Path pitReportDir) throws IOException {
		if (!Files.isDirectory(pitReportDir)) {
			throw new IOException("PIT report directory not found: " + pitReportDir
					+ ". The mutation analysis may have failed — check for errors above.");
		}
		// PIT writes to <reportDir>/<timestamp>/mutations.xml
		try (var stream = Files.walk(pitReportDir, 2)) {
			return stream.filter(p -> p.getFileName().toString().equals("mutations.xml"))
					.max(Comparator.comparingLong(p -> {
						try {
							return Files.getLastModifiedTime(p).toMillis();
						} catch (IOException e) {
							return 0L;
						}
					})).orElseThrow(() -> new IOException("mutations.xml not found under " + pitReportDir));
		}
	}

	static Map<String, MutationStats> parseMutationsXml(Path xmlFile, Set<String> knownTestClasses) throws IOException {
		Map<String, MutationStats> result = new LinkedHashMap<>();
		// Initialise all known test classes so they appear in the output even if they
		// killed no mutants.
		for (String tc : knownTestClasses) {
			result.put(tc, new MutationStats());
		}
		int totalMutantsGlobal = 0;
		try {
			Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile.toFile());
			NodeList mutations = doc.getElementsByTagName("mutation");
			for (int i = 0; i < mutations.getLength(); i++) {
				totalMutantsGlobal++;
				Element mutation = (Element) mutations.item(i);
				boolean detected = Boolean.parseBoolean(mutation.getAttribute("detected"));
				String killingTest = getChildText(mutation, "killingTest");

				// PIT JUnit 5 format: "com.example.FooTest.[engine:junit-jupiter]/..."
				// Strip engine/class/method descriptor, keep only the class name.
				String testClass = null;
				if (killingTest != null && !killingTest.isBlank()) {
					int slash = killingTest.indexOf('/');
					testClass = slash >= 0 ? killingTest.substring(0, slash) : killingTest;
					// Strip JUnit-5 engine suffix: "FooTest.[engine:junit-jupiter]" → "FooTest"
					int dotEngine = testClass.indexOf(".[");
					if (dotEngine >= 0) {
						testClass = testClass.substring(0, dotEngine);
					}
					// Normalise inner-class separator: PIT emits "Outer$Inner" which already
					// matches the dep-map format — no replacement needed.
					// Match against known test classes (fallback for package mismatches)
					if (!knownTestClasses.contains(testClass)) {
						testClass = matchKnownClass(testClass, knownTestClasses);
					}
				}

				if (detected && testClass != null) {
					result.computeIfAbsent(testClass, k -> new MutationStats()).killed++;
				}
				// Survived mutants: do NOT distribute across all test classes.
				// Each mutant is counted once in the global total (handled in run()).
			}
		} catch (Exception e) {
			throw new IOException("Failed to parse PIT mutations report: " + e.getMessage(), e);
		}
		result.put("__total__",
				new MutationStats(totalMutantsGlobal, result.values().stream().mapToInt(s -> s.killed).sum()));
		return result;
	}

	private static String getChildText(Element parent, String tagName) {
		NodeList nodes = parent.getElementsByTagName(tagName);
		if (nodes.getLength() == 0)
			return null;
		return nodes.item(0).getTextContent();
	}

	static String matchKnownClass(String candidate, Set<String> known) {
		// Try simple suffix match (handles package prefix differences)
		for (String k : known) {
			if (k.endsWith(candidate) || candidate.endsWith(k)) {
				return k;
			}
		}
		return candidate; // keep original if no match
	}

	static final class MutationStats {
		int total = 0;
		int killed = 0;

		MutationStats() {
		}

		MutationStats(int total, int killed) {
			this.total = total;
			this.killed = killed;
		}
	}

	// ── JSON report ───────────────────────────────────────────────────────────

	private static Path writeReport(Path outputFile, Path projectRoot, Map<String, Double> killRates,
			Map<String, MutationStats> statsByTest, int totalMutants, int totalKilled, PluginLog log)
			throws IOException {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)));
		root.put("projectRoot", projectRoot.toAbsolutePath().toString());
		root.put("totalMutants", totalMutants);
		root.put("totalKilled", totalKilled);
		root.put("overallKillRate", totalMutants > 0 ? (double) totalKilled / totalMutants : 0.0);

		List<Object> testList = new ArrayList<>();
		for (var entry : killRates.entrySet()) {
			MutationStats stats = statsByTest.get(entry.getKey());
			Map<String, Object> tc = new LinkedHashMap<>();
			tc.put("testClass", entry.getKey());
			tc.put("mutationsKilledByTest", stats != null ? stats.killed : 0);
			tc.put("killRate", entry.getValue());
			testList.add(tc);
		}
		// Sort descending by kill rate for readability
		testList.sort((a, b) -> {
			double ra = (Double) ((Map<?, ?>) a).get("killRate");
			double rb = (Double) ((Map<?, ?>) b).get("killRate");
			return Double.compare(rb, ra);
		});
		root.put("testClasses", testList);

		Path parent = outputFile.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temp = me.bechberger.testorder.PersistenceSupport.temporarySibling(outputFile);
		Files.writeString(temp, PrettyPrinter.prettyPrint(root), StandardCharsets.UTF_8);
		me.bechberger.testorder.PersistenceSupport.moveIntoPlace(temp, outputFile);
		log.info("[test-order] Mutation report written to " + outputFile);
		return outputFile;
	}
}
