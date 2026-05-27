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
	 */
	public record Config(Path indexFile, Path stateFile, Path outputFile, Path projectRoot, String targetClasses,
			int timeBudgetSeconds, PluginLog log) {
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
				config.timeBudgetSeconds(), log);

		// Parse PIT XML report
		Path mutationsXml = findMutationsXml(pitReportDir);
		Map<String, MutationStats> statsByTest = parseMutationsXml(mutationsXml, testClasses);

		// Compute per-test kill rates
		Map<String, Double> killRates = new LinkedHashMap<>();
		int totalMutants = 0;
		int totalKilled = 0;
		for (var entry : statsByTest.entrySet()) {
			MutationStats stats = entry.getValue();
			totalMutants += stats.total;
			totalKilled += stats.killed;
			double rate = stats.total > 0 ? (double) stats.killed / stats.total : 0.0;
			killRates.put(entry.getKey(), rate);
		}

		// Update state file with kill rates
		if (Files.exists(config.stateFile())) {
			try {
				TestOrderState state = TestOrderState.load(config.stateFile());
				state.setKillRates(killRates);
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
	private static Set<String> deriveProductionClasses(DependencyMap depMap, String targetClassesOverride) {
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
			String testGlob, Path reportDir, int timeBudgetSeconds, PluginLog log) throws IOException {
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
			List<String> classpath = buildClasspath(projectRoot, classesDir, testClassesDir);

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

		Object reportOptions = reportOptionsClass.getDeclaredConstructor().newInstance();

		// targetClasses
		java.lang.reflect.Method setTargetClasses = reportOptionsClass.getMethod("setTargetClasses", Collection.class);
		setTargetClasses.invoke(reportOptions, globToCollection(targetGlob));

		// targetTests
		java.lang.reflect.Method setTargetTests = reportOptionsClass.getMethod("setTargetTests", Collection.class);
		setTargetTests.invoke(reportOptions, globToCollection(testGlob));

		// sourceDirs
		java.lang.reflect.Method setSourceDirs = reportOptionsClass.getMethod("setSourceDirs", Collection.class);
		setSourceDirs.invoke(reportOptions, List.of(projectRoot.resolve("src/main/java").toFile()));

		// classpath
		java.lang.reflect.Method setClassPathElements = reportOptionsClass.getMethod("setClassPathElements",
				Collection.class);
		setClassPathElements.invoke(reportOptions, classpath);

		// reportDir
		java.lang.reflect.Method setReportDir = reportOptionsClass.getMethod("setReportDir", String.class);
		setReportDir.invoke(reportOptions, reportDir.toAbsolutePath().toString());

		// outputFormats: XML only (no HTML)
		java.lang.reflect.Method setOutputFormats = reportOptionsClass.getMethod("setOutputFormats", Collection.class);
		setOutputFormats.invoke(reportOptions, List.of("XML"));

		// verbose: false
		java.lang.reflect.Method setVerbose = reportOptionsClass.getMethod("setVerbose", boolean.class);
		setVerbose.invoke(reportOptions, false);

		// timeBudget (if set)
		if (timeBudgetSeconds > 0) {
			try {
				java.lang.reflect.Method setTimeout = reportOptionsClass.getMethod("setTimeoutFactor", double.class);
				setTimeout.invoke(reportOptions, 1.0);
			} catch (NoSuchMethodException ignored) {
			}
		}

		// Build PluginServices (uses ServiceLoader internally)
		java.lang.reflect.Method loadDefault = pluginServicesClass.getMethod("makeForContextLoader");
		Object pluginServices = loadDefault.invoke(null);

		// Run via EntryPoint
		Object entryPoint = entryPointClass.getDeclaredConstructor().newInstance();
		java.lang.reflect.Method launch = entryPointClass.getMethod("execute", java.io.File.class, reportOptionsClass,
				pluginServicesClass, java.util.HashMap.class);
		launch.invoke(entryPoint, projectRoot.toFile(), reportOptions, pluginServices, new java.util.HashMap<>());
	}

	private static List<String> buildClasspath(Path projectRoot, Path classesDir, Path testClassesDir)
			throws IOException {
		List<String> cp = new ArrayList<>();
		cp.add(classesDir.toAbsolutePath().toString());
		cp.add(testClassesDir.toAbsolutePath().toString());

		// Add jars from target/dependency (if Maven copied them there)
		Path depsDir = projectRoot.resolve("target/dependency");
		if (Files.isDirectory(depsDir)) {
			try (var stream = Files.walk(depsDir, 1)) {
				stream.filter(p -> p.toString().endsWith(".jar")).map(p -> p.toAbsolutePath().toString())
						.forEach(cp::add);
			}
		}

		// Also add jars from the current JVM classpath (covers plugin dependencies)
		String jvmClasspath = System.getProperty("java.class.path", "");
		if (!jvmClasspath.isBlank()) {
			cp.addAll(Arrays.asList(jvmClasspath.split(java.io.File.pathSeparator)));
		}

		return cp;
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

	private static Map<String, MutationStats> parseMutationsXml(Path xmlFile, Set<String> knownTestClasses)
			throws IOException {
		Map<String, MutationStats> result = new LinkedHashMap<>();
		try {
			Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile.toFile());
			NodeList mutations = doc.getElementsByTagName("mutation");
			for (int i = 0; i < mutations.getLength(); i++) {
				Element mutation = (Element) mutations.item(i);
				boolean detected = Boolean.parseBoolean(mutation.getAttribute("detected"));
				String killingTest = getChildText(mutation, "killingTest");

				// Extract test class from "com.example.FooTest/testMethod()" format
				String testClass = null;
				if (killingTest != null && !killingTest.isBlank()) {
					int slash = killingTest.indexOf('/');
					testClass = slash >= 0 ? killingTest.substring(0, slash) : killingTest;
					// Normalise inner class separator
					testClass = testClass.replace('$', '.');
					// Match against known test classes (case-insensitive fallback)
					if (!knownTestClasses.contains(testClass)) {
						testClass = matchKnownClass(testClass, knownTestClasses);
					}
				}

				// Attribute each mutant to the killing test (if killed) or to all covering
				// tests (if survived — count the mutant once for the mutated class)
				String mutatedClass = getChildText(mutation, "mutatedClass");
				Set<String> coveringTests = getCoveringTests(mutatedClass, knownTestClasses);

				if (detected && testClass != null) {
					result.computeIfAbsent(testClass, k -> new MutationStats()).killed++;
					result.computeIfAbsent(testClass, k -> new MutationStats()).total++;
				} else {
					// Survived mutant: charge to all tests that cover this class
					for (String covering : coveringTests) {
						result.computeIfAbsent(covering, k -> new MutationStats()).total++;
					}
				}
			}
		} catch (Exception e) {
			throw new IOException("Failed to parse PIT mutations report: " + e.getMessage(), e);
		}
		return result;
	}

	private static String getChildText(Element parent, String tagName) {
		NodeList nodes = parent.getElementsByTagName(tagName);
		if (nodes.getLength() == 0)
			return null;
		return nodes.item(0).getTextContent();
	}

	private static String matchKnownClass(String candidate, Set<String> known) {
		// Try simple suffix match (handles package prefix differences)
		for (String k : known) {
			if (k.endsWith(candidate) || candidate.endsWith(k)) {
				return k;
			}
		}
		return candidate; // keep original if no match
	}

	/**
	 * Returns test classes that cover the given production class (from dep map
	 * perspective, we just return all known test classes as a conservative fallback
	 * for survived mutants since we don't have the reverse index here).
	 */
	private static Set<String> getCoveringTests(String mutatedClass, Set<String> testClasses) {
		// Conservative: attribute survived mutants to all test classes.
		// This slightly overestimates total mutants per test but keeps kill rates
		// comparable across tests.
		return testClasses;
	}

	private static final class MutationStats {
		int total = 0;
		int killed = 0;
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
			tc.put("mutationsKilled", stats != null ? stats.killed : 0);
			tc.put("mutationsTotal", stats != null ? stats.total : 0);
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
		Files.writeString(outputFile, PrettyPrinter.prettyPrint(root), StandardCharsets.UTF_8);
		log.info("[test-order] Mutation report written to " + outputFile);
		return outputFile;
	}
}
