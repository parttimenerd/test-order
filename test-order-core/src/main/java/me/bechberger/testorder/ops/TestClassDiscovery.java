package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

import me.bechberger.testorder.DependencyMap;

/**
 * Framework-agnostic test class discovery and dependency map filtering.
 * Extracts logic shared between Maven AbstractTestOrderMojo and Gradle
 * TestOrderPlugin.
 */
public final class TestClassDiscovery {

	private TestClassDiscovery() {
	}

	/**
	 * Scans a directory for compiled test class files and returns their
	 * fully-qualified class names. Skips inner classes ({@code $}).
	 */
	public static Set<String> scanTestClasses(Path testClassesDir) {
		if (testClassesDir == null || !Files.isDirectory(testClassesDir)) {
			return Set.of();
		}
		Set<String> tests = new LinkedHashSet<>();
		try (Stream<Path> walk = Files.walk(testClassesDir)) {
			walk.filter(path -> path.toString().endsWith(".class") && !path.toString().contains("$")).forEach(path -> {
				String relative = testClassesDir.relativize(path).toString();
				tests.add(relative.replace('/', '.').replace('\\', '.').replaceAll("\\.class$", ""));
			});
		} catch (IOException e) {
			// Swallow — caller should log if needed
		}
		return tests;
	}

	/**
	 * Returns true if the test source root contains at least one Java/Kotlin/Groovy
	 * source file. Used as a fallback before test classes are compiled.
	 */
	public static boolean hasTestSources(Path testSourceRoot) {
		if (testSourceRoot == null || !Files.isDirectory(testSourceRoot)) {
			return false;
		}
		try (Stream<Path> walk = Files.walk(testSourceRoot)) {
			return walk.anyMatch(path -> {
				String s = path.toString();
				return s.endsWith(".java") || s.endsWith(".kt") || s.endsWith(".groovy");
			});
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Filters a dependency map to only include test classes found in the given
	 * test-classes directory. Preserves method and member dependency data.
	 */
	public static DependencyMap filterToModule(DependencyMap depMap, Path testClassesDir) {
		Set<String> moduleTests = scanTestClasses(testClassesDir);
		if (moduleTests.isEmpty()) {
			return depMap;
		}
		DependencyMap filtered = new DependencyMap();
		for (String testClass : moduleTests) {
			if (depMap.testClasses().contains(testClass)) {
				filtered.put(testClass, depMap.get(testClass));
				if (depMap.hasMethodDeps()) {
					for (String methodKey : depMap.methodKeys()) {
						if (methodKey.startsWith(testClass + "#")) {
							filtered.putMethodDeps(methodKey, depMap.getMethodDeps(methodKey));
							if (depMap.hasMemberDeps()) {
								filtered.putMethodMemberDeps(methodKey, depMap.getMethodMemberDeps(methodKey));
							}
						}
					}
				}
				if (depMap.hasMemberDeps()) {
					filtered.putMemberDeps(testClass, depMap.getMemberDeps(testClass));
				}
			}
		}
		return filtered;
	}

	/**
	 * Identifies test classes present in the compiled output but not in the
	 * dependency index (i.e. new tests).
	 */
	public static Set<String> findNewTestClasses(DependencyMap depMap, Path testClassesDir, PluginLog log) {
		Set<String> moduleTests = scanTestClasses(testClassesDir);
		Set<String> newTests = new LinkedHashSet<>();
		for (String tc : moduleTests) {
			if (!depMap.testClasses().contains(tc)) {
				newTests.add(tc);
			}
		}
		if (!newTests.isEmpty()) {
			log.debug("[test-order] Found " + newTests.size() + " new test classes not in index");
		}
		return newTests;
	}

	/**
	 * Warns if the dependency map has no meaningful application-class dependencies.
	 * Common when groupId/package prefix is misconfigured.
	 */
	public static void warnIfNoDeps(DependencyMap depMap, String includePackagesProperty, PluginLog log) {
		if (depMap.size() == 0) {
			return;
		}
		boolean noAppDeps = depMap.testClasses().stream().allMatch(tc -> {
			var deps = depMap.get(tc);
			if (deps == null || deps.isEmpty())
				return true;
			return deps.stream().allMatch(dep -> dep.equals(tc) || dep.startsWith("me.bechberger.testorder.")
					|| dep.startsWith("org.junit.") || dep.startsWith("org.opentest4j."));
		});
		if (noAppDeps) {
			log.warn("[test-order] No application-class dependencies found in the dependency index. "
					+ "Test ordering will not be effective.");
			log.warn("[test-order] If your source packages differ from the project groupId, " + "set "
					+ includePackagesProperty + "=your.package.prefix");
		}
	}
}
