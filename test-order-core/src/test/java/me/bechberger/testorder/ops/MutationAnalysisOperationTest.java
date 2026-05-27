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
		// PIT may emit "com.example.Outer$Inner" — should be normalised to
		// "com.example.Outer.Inner"
		String xml = """
				<?xml version="1.0" encoding="UTF-8"?>
				<mutations>
				  <mutation detected="true">
				    <killingTest>com.example.Outer$InnerTest</killingTest>
				  </mutation>
				</mutations>
				""";
		Set<String> known = new LinkedHashSet<>(List.of("com.example.Outer.InnerTest"));
		var stats = MutationAnalysisOperation.parseMutationsXml(writeXml(xml), known);

		assertEquals(1, stats.get("com.example.Outer.InnerTest").killed);
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
}
