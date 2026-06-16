package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;

/**
 * Unit tests for the package-private helpers in
 * {@link MutationAnalysisOperation}. These cover XML parsing, class-name
 * normalisation, and production-class derivation without invoking PIT itself.
 */
class MutationAnalysisOperationTest {

	@TempDir
	Path tempDir;

	// ── parseMutationsXml ─────────────────────────────────────────────────────

	private Path writeXml(String content) throws IOException {
		Path f = tempDir.resolve("mutations.xml");
		Files.writeString(f, content);
		return f;
	}

	@Test
	void parsesKilledMutantForKnownTestClass() throws IOException {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mutations>
				  <mutation detected="true">
				    <killingTest>com.example.FooTest</killingTest>
				  </mutation>
				</mutations>
				""";
		Set<String> known = new LinkedHashSet<>(List.of("com.example.FooTest"));
		var stats = MutationAnalysisOperation.parseMutationsXml(writeXml(xml), known);

		MutationAnalysisOperation.MutationStats global = stats.get("__total__");
		assertNotNull(global);
		assertEquals(1, global.total);
		assertEquals(1, global.killed);

		MutationAnalysisOperation.MutationStats foo = stats.get("com.example.FooTest");
		assertNotNull(foo);
		assertEquals(1, foo.killed);
	}

	@Test
	void survivedMutantNotAttributedToAnyTest() throws IOException {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mutations>
				  <mutation detected="false">
				    <killingTest></killingTest>
				  </mutation>
				</mutations>
				""";
		Set<String> known = new LinkedHashSet<>(List.of("com.example.FooTest"));
		var stats = MutationAnalysisOperation.parseMutationsXml(writeXml(xml), known);

		MutationAnalysisOperation.MutationStats global = stats.get("__total__");
		assertEquals(1, global.total);
		assertEquals(0, global.killed);

		assertEquals(0, stats.get("com.example.FooTest").killed);
	}

	@Test
	void stripsJUnit5EngineDescriptorFromKillingTest() throws IOException {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mutations>
				  <mutation detected="true">
				    <killingTest>com.example.BarTest.[engine:junit-jupiter]/[class:com.example.BarTest]/[method:test1()]</killingTest>
				  </mutation>
				</mutations>
				""";
		Set<String> known = new LinkedHashSet<>(List.of("com.example.BarTest"));
		var stats = MutationAnalysisOperation.parseMutationsXml(writeXml(xml), known);

		assertEquals(1, stats.get("com.example.BarTest").killed);
	}

	@Test
	void stripsSlashSuffixFromKillingTest() throws IOException {
		// Older PIT format: "com.example.FooTest/testMethod"
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mutations>
				  <mutation detected="true">
				    <killingTest>com.example.FooTest/testMethod</killingTest>
				  </mutation>
				</mutations>
				""";
		Set<String> known = new LinkedHashSet<>(List.of("com.example.FooTest"));
		var stats = MutationAnalysisOperation.parseMutationsXml(writeXml(xml), known);

		assertEquals(1, stats.get("com.example.FooTest").killed);
	}

	@Test
	void normalisesInnerClassSeparator() throws IOException {
		// PIT emits "com.example.Outer$Inner" — the dep map also stores inner classes
		// with '$', so no conversion is needed and the name should match directly.
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mutations>
				  <mutation detected="true">
				    <killingTest>com.example.Outer$InnerTest</killingTest>
				  </mutation>
				</mutations>
				""";
		Set<String> known = new LinkedHashSet<>(List.of("com.example.Outer$InnerTest"));
		var stats = MutationAnalysisOperation.parseMutationsXml(writeXml(xml), known);

		assertEquals(1, stats.get("com.example.Outer$InnerTest").killed);
	}

	@Test
	void allKnownTestClassesPresentEvenWithZeroKills() throws IOException {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mutations>
				  <mutation detected="true">
				    <killingTest>com.example.FooTest</killingTest>
				  </mutation>
				</mutations>
				""";
		Set<String> known = new LinkedHashSet<>(List.of("com.example.FooTest", "com.example.BarTest"));
		var stats = MutationAnalysisOperation.parseMutationsXml(writeXml(xml), known);

		assertTrue(stats.containsKey("com.example.BarTest"));
		assertEquals(0, stats.get("com.example.BarTest").killed);
	}

	@Test
	void multipleTestsKillDifferentMutants() throws IOException {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mutations>
				  <mutation detected="true">
				    <killingTest>com.example.FooTest</killingTest>
				  </mutation>
				  <mutation detected="true">
				    <killingTest>com.example.FooTest</killingTest>
				  </mutation>
				  <mutation detected="true">
				    <killingTest>com.example.BarTest</killingTest>
				  </mutation>
				  <mutation detected="false">
				    <killingTest></killingTest>
				  </mutation>
				</mutations>
				""";
		Set<String> known = new LinkedHashSet<>(List.of("com.example.FooTest", "com.example.BarTest"));
		var stats = MutationAnalysisOperation.parseMutationsXml(writeXml(xml), known);

		assertEquals(2, stats.get("com.example.FooTest").killed);
		assertEquals(1, stats.get("com.example.BarTest").killed);

		MutationAnalysisOperation.MutationStats global = stats.get("__total__");
		assertEquals(4, global.total);
		assertEquals(3, global.killed);
	}

	@Test
	void unknownTestClassFallsBackToSuffixMatch() throws IOException {
		// PIT may strip package, emitting just "FooTest" (no package prefix).
		// matchKnownClass uses endsWith, so "com.example.FooTest".endsWith("FooTest")
		// matches.
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mutations>
				  <mutation detected="true">
				    <killingTest>FooTest</killingTest>
				  </mutation>
				</mutations>
				""";
		Set<String> known = new LinkedHashSet<>(List.of("com.example.FooTest"));
		var stats = MutationAnalysisOperation.parseMutationsXml(writeXml(xml), known);

		// matchKnownClass resolves "FooTest" → "com.example.FooTest" via endsWith
		assertEquals(1, stats.get("com.example.FooTest").killed);
	}

	@Test
	void emptyXmlProducesZeroTotals() throws IOException {
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mutations>
				</mutations>
				""";
		Set<String> known = new LinkedHashSet<>(List.of("com.example.FooTest"));
		var stats = MutationAnalysisOperation.parseMutationsXml(writeXml(xml), known);

		MutationAnalysisOperation.MutationStats global = stats.get("__total__");
		assertEquals(0, global.total);
		assertEquals(0, global.killed);
	}

	// ── matchKnownClass ───────────────────────────────────────────────────────

	@Test
	void matchKnownClassExactMatch() {
		Set<String> known = Set.of("com.example.FooTest", "com.example.BarTest");
		assertEquals("com.example.FooTest", MutationAnalysisOperation.matchKnownClass("com.example.FooTest", known));
	}

	@Test
	void matchKnownClassSuffixMatch() {
		Set<String> known = Set.of("com.example.FooTest");
		// candidate is a suffix of the known class
		assertEquals("com.example.FooTest", MutationAnalysisOperation.matchKnownClass("FooTest", known));
	}

	@Test
	void matchKnownClassPrefixMatch() {
		// known is a suffix of candidate
		Set<String> known = Set.of("FooTest");
		assertEquals("FooTest", MutationAnalysisOperation.matchKnownClass("com.example.FooTest", known));
	}

	@Test
	void matchKnownClassNoMatchReturnsOriginal() {
		Set<String> known = Set.of("com.example.BarTest");
		String result = MutationAnalysisOperation.matchKnownClass("com.example.FooTest", known);
		assertEquals("com.example.FooTest", result);
	}

	// ── deriveProductionClasses ───────────────────────────────────────────────

	private DependencyMap buildDepMap(Map<String, Set<String>> deps) {
		DependencyMap map = new DependencyMap();
		for (var e : deps.entrySet()) {
			map.put(e.getKey(), e.getValue());
		}
		return map;
	}

	@Test
	void derivesProductionClassesExcludesTestClasses() {
		DependencyMap depMap = buildDepMap(Map.of("com.example.FooTest",
				Set.of("com.example.Foo", "com.example.Helper"), "com.example.BarTest", Set.of("com.example.Bar")));

		Set<String> prod = MutationAnalysisOperation.deriveProductionClasses(depMap, null);
		assertTrue(prod.contains("com.example.Foo"));
		assertTrue(prod.contains("com.example.Helper"));
		assertTrue(prod.contains("com.example.Bar"));
		assertFalse(prod.contains("com.example.FooTest"));
		assertFalse(prod.contains("com.example.BarTest"));
	}

	@Test
	void derivesProductionClassesUsesOverrideWhenProvided() {
		DependencyMap depMap = buildDepMap(Map.of("com.example.FooTest", Set.of("com.example.Foo")));

		Set<String> prod = MutationAnalysisOperation.deriveProductionClasses(depMap, "com.custom.A,com.custom.B");
		assertEquals(Set.of("com.custom.A", "com.custom.B"), prod);
	}

	@Test
	void derivesProductionClassesBlankOverrideFallsBackToDepGraph() {
		DependencyMap depMap = buildDepMap(Map.of("com.example.FooTest", Set.of("com.example.Foo")));

		Set<String> prod = MutationAnalysisOperation.deriveProductionClasses(depMap, "  ");
		assertTrue(prod.contains("com.example.Foo"));
	}

	@Test
	void derivesProductionClassesReturnsEmptyWhenAllDepsAreTests() {
		// Both test classes are indexed; each depends only on the other test class.
		// Since both are in testClasses, deriveProductionClasses should return empty.
		DependencyMap depMap = buildDepMap(Map.of("com.example.FooTest", Set.of("com.example.BarTest"),
				"com.example.BarTest", Set.of("com.example.FooTest")));

		Set<String> prod = MutationAnalysisOperation.deriveProductionClasses(depMap, null);
		assertTrue(prod.isEmpty(), "Expected no production classes when all deps are test classes, got: " + prod);
	}

	// ── Config resolver methods ───────────────────────────────────────────────

	@Test
	void configResolvedClassesDirUsesDefaultWhenNull() throws IOException {
		Path root = tempDir.resolve("project");
		MutationAnalysisOperation.Config config = new MutationAnalysisOperation.Config(root.resolve("idx.lz4"),
				root.resolve("state"), root.resolve("out.json"), root, null, 0,
				me.bechberger.testorder.ops.PluginLog.NOOP, List.of(), null, null, null);

		assertEquals(root.resolve("target/classes"), config.resolvedClassesDir());
		assertEquals(root.resolve("target/test-classes"), config.resolvedTestClassesDir());
		assertEquals(root.resolve("target/pit-reports"), config.resolvedPitReportDir());
	}

	@Test
	void configResolvedClassesDirUsesProvidedValue() throws IOException {
		Path root = tempDir.resolve("project");
		Path customClasses = tempDir.resolve("custom/classes");
		Path customTestClasses = tempDir.resolve("custom/test-classes");
		Path customPitDir = tempDir.resolve("custom/pit");
		MutationAnalysisOperation.Config config = new MutationAnalysisOperation.Config(root.resolve("idx.lz4"),
				root.resolve("state"), root.resolve("out.json"), root, null, 0,
				me.bechberger.testorder.ops.PluginLog.NOOP, List.of(), customClasses, customTestClasses, customPitDir);

		assertEquals(customClasses, config.resolvedClassesDir());
		assertEquals(customTestClasses, config.resolvedTestClassesDir());
		assertEquals(customPitDir, config.resolvedPitReportDir());
	}

	// ── Glob pattern correctness ──────────────────────────────────────────────

	@Test
	void deriveProductionClassesPreservesInnerClassSeparator() {
		DependencyMap depMap = buildDepMap(
				Map.of("com.example.FooTest", Set.of("com.example.Outer$Inner", "com.example.Outer")));

		Set<String> prod = MutationAnalysisOperation.deriveProductionClasses(depMap, null);
		// Inner class separator must be preserved; NOT replaced with *
		assertTrue(prod.contains("com.example.Outer$Inner"),
				"Expected 'com.example.Outer$Inner' in production classes, got: " + prod);
		assertFalse(prod.stream().anyMatch(c -> c.contains("*")),
				"Production class names must not contain wildcard (*), got: " + prod);
	}
}
