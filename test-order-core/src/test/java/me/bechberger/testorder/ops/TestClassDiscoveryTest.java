package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;

class TestClassDiscoveryTest {

	@TempDir
	Path tempDir;

	@Test
	void scanTestClasses_includesNestedAndExcludesAnonymousAndLambdaArtifacts() throws IOException {
		Path testClasses = tempDir.resolve("test-classes/com/example");
		Files.createDirectories(testClasses);

		Files.write(testClasses.resolve("OuterTest.class"), new byte[0]);
		Files.write(testClasses.resolve("OuterTest$Nested.class"), new byte[0]);
		Files.write(testClasses.resolve("OuterTest$1.class"), new byte[0]);
		Files.write(testClasses.resolve("OuterTest$$Lambda$0.class"), new byte[0]);

		Set<String> classes = TestClassDiscovery.scanTestClasses(tempDir.resolve("test-classes"));

		assertTrue(classes.contains("com.example.OuterTest"));
		assertTrue(classes.contains("com.example.OuterTest$Nested"));
		assertFalse(classes.contains("com.example.OuterTest$1"));
		assertFalse(classes.contains("com.example.OuterTest$$Lambda$0"));
	}

	@Test
	void filterToModule_keepsNestedClassesPresentInDependencyMap() throws IOException {
		Path testClasses = tempDir.resolve("test-classes/com/example");
		Files.createDirectories(testClasses);
		Files.write(testClasses.resolve("OuterTest.class"), new byte[0]);
		Files.write(testClasses.resolve("OuterTest$Nested.class"), new byte[0]);

		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.OuterTest", Set.of("app.Service"));
		depMap.put("com.example.OuterTest$Nested", Set.of("app.Service"));
		depMap.put("com.example.RemovedTest", Set.of("app.Other"));

		DependencyMap filtered = TestClassDiscovery.filterToModule(depMap, tempDir.resolve("test-classes"));

		assertTrue(filtered.testClasses().contains("com.example.OuterTest"));
		assertTrue(filtered.testClasses().contains("com.example.OuterTest$Nested"));
		assertFalse(filtered.testClasses().contains("com.example.RemovedTest"));
	}

	@Test
	void filterToModuleId_keepsOnlyMatchingTestsAndUnknowns() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.alpha.AlphaTest", Set.of("com.alpha.Alpha"));
		depMap.put("com.beta.BetaTest", Set.of("com.beta.Beta"));
		depMap.put("com.legacy.LegacyTest", Set.of("com.legacy.Legacy"));
		depMap.putModule("com.alpha.AlphaTest", "g:alpha");
		depMap.putModule("com.beta.BetaTest", "g:beta");

		DependencyMap filtered = TestClassDiscovery.filterToModuleId(depMap, "g:alpha");

		assertTrue(filtered.testClasses().contains("com.alpha.AlphaTest"));
		assertFalse(filtered.testClasses().contains("com.beta.BetaTest"),
				"tests owned by other modules should be dropped");
		assertTrue(filtered.testClasses().contains("com.legacy.LegacyTest"),
				"tests with no recorded module should pass through (backward compat)");
	}

	@Test
	void filterToModuleId_returnsOriginalWhenIndexHasNoModuleMap() {
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.FooTest", Set.of("com.example.Foo"));

		DependencyMap filtered = TestClassDiscovery.filterToModuleId(depMap, "g:any");

		assertSame(depMap, filtered, "no module map → return original map unchanged");
	}
}
