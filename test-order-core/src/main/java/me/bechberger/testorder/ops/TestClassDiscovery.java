package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import me.bechberger.testorder.DependencyMap;

/**
 * Framework-agnostic test class discovery and dependency map filtering.
 * Extracts logic shared between Maven AbstractTestOrderMojo and Gradle
 * TestOrderPlugin.
 */
public final class TestClassDiscovery {

	private static final Pattern ANONYMOUS_INNER_CLASS = Pattern.compile(".*\\$\\d+.*");

	private TestClassDiscovery() {
	}

	/**
	 * Scans a directory for compiled test class files and returns their
	 * fully-qualified class names.
	 *
	 * Includes nested/member classes (e.g. {@code OuterTest$Nested}) because JUnit
	 * can execute them as separate test classes, but excludes anonymous/synthetic
	 * classes (e.g. {@code OuterTest$1}, lambda artifacts).
	 */
	public static Set<String> scanTestClasses(Path testClassesDir) {
		if (testClassesDir == null || !Files.isDirectory(testClassesDir)) {
			return Set.of();
		}
		Set<String> tests = new LinkedHashSet<>();
		try (Stream<Path> walk = Files.walk(testClassesDir)) {
			walk.filter(path -> path.toString().endsWith(".class")).forEach(path -> {
				String relative = testClassesDir.relativize(path).toString();
				String className = relative.replace('/', '.').replace('\\', '.').replaceAll("\\.class$", "");
				if (isDiscoverableTestClass(className)) {
					tests.add(className);
				}
			});
		} catch (IOException e) {
			// Swallow — caller should log if needed
		}
		return tests;
	}

	private static boolean isDiscoverableTestClass(String className) {
		if (!className.contains("$")) {
			return true;
		}
		// Exclude anonymous/local inner classes such as Outer$1 and Outer$1Inner.
		if (ANONYMOUS_INNER_CLASS.matcher(className).matches()) {
			return false;
		}
		// Exclude synthetic lambda implementation classes.
		return !className.contains("$$Lambda$");
	}

	/**
	 * Groups method keys (className#methodName) by class name for O(N+M) filtering.
	 */
	private static Map<String, List<String>> groupMethodKeysByClass(Set<String> methodKeys) {
		Map<String, List<String>> byClass = new HashMap<>();
		for (String key : methodKeys) {
			int hash = key.indexOf('#');
			if (hash > 0) {
				byClass.computeIfAbsent(key.substring(0, hash), k -> new ArrayList<>()).add(key);
			}
		}
		return byClass;
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
		Map<String, List<String>> methodKeysByClass = depMap.hasMethodDeps()
				? groupMethodKeysByClass(depMap.methodKeys())
				: Map.of();
		DependencyMap filtered = new DependencyMap();
		for (String testClass : moduleTests) {
			if (depMap.testClasses().contains(testClass)) {
				filtered.put(testClass, depMap.get(testClass));
				List<String> methodKeys = methodKeysByClass.getOrDefault(testClass, List.of());
				for (String methodKey : methodKeys) {
					filtered.putMethodDeps(methodKey, depMap.getMethodDeps(methodKey));
					if (depMap.hasMemberDeps()) {
						filtered.putMethodMemberDeps(methodKey, depMap.getMethodMemberDeps(methodKey));
					}
				}
				if (depMap.hasMemberDeps()) {
					filtered.putMemberDeps(testClass, depMap.getMemberDeps(testClass));
				}
				String mod = depMap.getModule(testClass);
				if (mod != null) {
					filtered.putModule(testClass, mod);
				}
			}
		}
		return filtered;
	}

	/**
	 * Filters a dependency map to only include test classes whose recorded owning
	 * module matches {@code moduleId}. Tests with no recorded module are treated as
	 * owned by every module (included in every per-module filter result) — this
	 * preserves correct behaviour for indexes built before the module-map section
	 * existed, and for any tests whose ownership wasn't recorded.
	 *
	 * <p>
	 * Returns {@code depMap} unchanged when the map carries no module data at all,
	 * to avoid silently dropping tests when an old index is loaded.
	 */
	public static DependencyMap filterToModuleId(DependencyMap depMap, String moduleId) {
		if (moduleId == null || moduleId.isEmpty() || !depMap.hasModuleMap()) {
			return depMap;
		}
		Map<String, List<String>> methodKeysByClass = depMap.hasMethodDeps()
				? groupMethodKeysByClass(depMap.methodKeys())
				: Map.of();
		DependencyMap filtered = new DependencyMap();
		for (String testClass : depMap.testClasses()) {
			String recorded = depMap.getModule(testClass);
			if (recorded != null && !recorded.equals(moduleId)) {
				continue;
			}
			filtered.put(testClass, depMap.get(testClass));
			List<String> methodKeys = methodKeysByClass.getOrDefault(testClass, List.of());
			for (String methodKey : methodKeys) {
				filtered.putMethodDeps(methodKey, depMap.getMethodDeps(methodKey));
				if (depMap.hasMemberDeps()) {
					filtered.putMethodMemberDeps(methodKey, depMap.getMethodMemberDeps(methodKey));
				}
			}
			if (depMap.hasMemberDeps()) {
				filtered.putMemberDeps(testClass, depMap.getMemberDeps(testClass));
			}
			if (recorded != null) {
				filtered.putModule(testClass, recorded);
			}
		}
		return filtered;
	}

	/**
	 * Identifies test classes present in the compiled output but not in the
	 * dependency index (i.e. new tests).
	 *
	 * Excludes nested/inner classes (containing {@code $}) because they are
	 * discovered and run as part of their enclosing outer class, and are never
	 * recorded as top-level entries in the dependency index.
	 */
	public static Set<String> findNewTestClasses(DependencyMap depMap, Path testClassesDir, PluginLog log) {
		Set<String> moduleTests = scanTestClasses(testClassesDir);
		Set<String> newTests = new LinkedHashSet<>();
		for (String tc : moduleTests) {
			if (tc.contains("$")) {
				continue; // nested/inner class — runs via enclosing class
			}
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
