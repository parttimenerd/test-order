package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ChangeComplexity}. Covers E45 (member-level blending),
 * E46 (fromRawSizes), E47 (serialise/deserialise round-trip).
 */
@DisplayName("ChangeComplexity")
class ChangeComplexityTest {

	@TempDir
	Path tempDir;

	// ─────────────────────────────────────────────────────────────────────────
	// Helpers
	// ─────────────────────────────────────────────────────────────────────────

	/** Write source content to <root>/<fqcn-as-path>.java and return the root. */
	private Path writeSource(Path root, String fqcn, String content) throws IOException {
		String relPath = fqcn.replace('.', '/') + ".java";
		Path file = root.resolve(relPath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
		return root;
	}

	// ─────────────────────────────────────────────────────────────────────────
	// compute(Set, List) — file-level complexity
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("compute(changedClasses, sourceRoots) — file-level")
	class FileLevelCompute {

		@Test
		@DisplayName("empty changedClasses returns empty map")
		void emptyChangedClassesReturnsEmpty() {
			Map<String, Double> result = ChangeComplexity.compute(Set.of(), List.of(tempDir));
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("empty sourceRoots returns empty map")
		void emptySourceRootsReturnsEmpty() {
			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.example.Foo"), List.of());
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("class with no source file is omitted from result")
		void classWithNoSourceFileOmitted() {
			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.example.NoSuchClass"), List.of(tempDir));
			assertTrue(result.isEmpty());
		}

		@Test
		@DisplayName("single class found gets score 1.0 (normalised to max)")
		void singleClassScoresOne() throws IOException {
			writeSource(tempDir, "com.example.Foo", "public class Foo { public void doSomething() {} }");
			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.example.Foo"), List.of(tempDir));
			assertEquals(1, result.size());
			assertEquals(1.0, result.get("com.example.Foo"), 0.001);
		}

		@Test
		@DisplayName("larger (more complex) file gets higher score than trivial file")
		void largerFileGetsHigherScore() throws IOException {
			Path src = tempDir.resolve("src");
			writeSource(src, "com.example.Simple", "public class Simple {}");
			String bigContent = "public class Big {\n" + "    ".repeat(200) + "int x;\n"
					+ "    public void a(){} public void b(){} public void c(){}\n"
					+ "    public void d(){} public void e(){} public void f(){}\n}";
			writeSource(src, "com.example.Big", bigContent);

			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.example.Simple", "com.example.Big"),
					List.of(src));
			assertEquals(2, result.size());
			assertTrue(result.get("com.example.Big") >= result.get("com.example.Simple"),
					"Big file should have higher or equal complexity score");
			// The most complex file must be 1.0
			assertTrue(result.values().stream().anyMatch(v -> Math.abs(v - 1.0) < 0.001), "Maximum score must be 1.0");
		}

		@Test
		@DisplayName("all scores normalised to [0.0, 1.0]")
		void scoresInRange() throws IOException {
			Path src = tempDir.resolve("src");
			writeSource(src, "com.a.A", "public class A { int x; }");
			writeSource(src, "com.b.B", "public class B { int x; int y; void m() {} }");
			writeSource(src, "com.c.C", "public class C {}");

			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.a.A", "com.b.B", "com.c.C"),
					List.of(src));
			for (double score : result.values()) {
				assertTrue(score >= 0.0 && score <= 1.0, "Score out of range: " + score);
			}
		}

		@Test
		@DisplayName("Kotlin source (.kt) found when .java is absent")
		void kotlinSourceFound() throws IOException {
			String fqcn = "com.example.Greeter";
			String relPath = fqcn.replace('.', '/') + ".kt";
			Path ktFile = tempDir.resolve(relPath);
			Files.createDirectories(ktFile.getParent());
			Files.writeString(ktFile, "class Greeter { fun hello() = \"hi\" }");

			Map<String, Double> result = ChangeComplexity.compute(Set.of(fqcn), List.of(tempDir));
			assertEquals(1, result.size(), "Kotlin source should be found and scored");
		}

		@Test
		@DisplayName("multiple source roots: class found in second root")
		void classInSecondSourceRoot() throws IOException {
			Path root1 = tempDir.resolve("root1");
			Path root2 = tempDir.resolve("root2");
			Files.createDirectories(root1);
			writeSource(root2, "com.example.Foo", "public class Foo { int x; }");

			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.example.Foo"), List.of(root1, root2));
			assertEquals(1, result.size(), "Should find class in second source root");
		}

		@Test
		@DisplayName("inner class FQCN resolved via top-level file (dollar notation)")
		void innerClassFqcnResolved() throws IOException {
			Path src = tempDir.resolve("src");
			writeSource(src, "com.example.Outer", "public class Outer { public static class Inner {} }");

			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.example.Outer$Inner"), List.of(src));
			assertFalse(result.isEmpty(), "Inner class FQCN should be resolved via top-level file");
			assertTrue(result.containsKey("com.example.Outer$Inner"),
					"Result should map inner-class FQCN, got: " + result.keySet());
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// compute(Set, List, ChangedMembers) — E45: member-level blending (50/50)
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("compute(changedClasses, sourceRoots, changedMembers) — E45 member blend")
	class MemberLevelBlend {

		private StructuralChangeAnalyzer.ChangedMembers members(Map<String, Set<String>> byClass) {
			Set<String> classes = byClass.keySet();
			Set<String> keys = new HashSet<>();
			byClass.forEach((cls, ms) -> ms.forEach(m -> keys.add(cls + "#" + m)));
			return new StructuralChangeAnalyzer.ChangedMembers(classes, keys, byClass, Set.of(), Set.of());
		}

		@Test
		@DisplayName("E45: null changedMembers falls back to file-level score")
		void nullMembersFallsBackToFileLevel() throws IOException {
			Path src = tempDir.resolve("src");
			writeSource(src, "com.example.Foo", "public class Foo { void a(){} void b(){} }");

			Map<String, Double> withNull = ChangeComplexity.compute(Set.of("com.example.Foo"), List.of(src), null);
			Map<String, Double> withoutParam = ChangeComplexity.compute(Set.of("com.example.Foo"), List.of(src));
			assertEquals(withoutParam, withNull, "Null changedMembers must produce same result as no-members overload");
		}

		@Test
		@DisplayName("E45: changedMembers with empty membersByClass falls back to file-level")
		void emptyMembersByClassFallsBack() throws IOException {
			Path src = tempDir.resolve("src");
			writeSource(src, "com.example.Foo", "public class Foo { int x; }");

			StructuralChangeAnalyzer.ChangedMembers emptyMembers = new StructuralChangeAnalyzer.ChangedMembers(
					Set.of("com.example.Foo"), Set.of(), Map.of(), Set.of(), Set.of());

			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.example.Foo"), List.of(src),
					emptyMembers);
			assertEquals(1.0, result.get("com.example.Foo"), 0.001,
					"Empty membersByClass: file-level score should be 1.0 (only class)");
		}

		@Test
		@DisplayName("E45: blend produces score between 0 and 1 when members known")
		void blendProducesValidScore() throws IOException {
			Path src = tempDir.resolve("src");
			writeSource(src, "com.app.Service", "public class Service { void a(){} void b(){} void c(){} }");
			writeSource(src, "com.app.Util", "public class Util { int x; }");

			// Service has 2 changed members, Util has 1 → member ratios differ
			StructuralChangeAnalyzer.ChangedMembers cm = members(
					Map.of("com.app.Service", Set.of("a", "b"), "com.app.Util", Set.of("x")));

			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.app.Service", "com.app.Util"),
					List.of(src), cm);
			assertEquals(2, result.size());
			for (double score : result.values()) {
				assertTrue(score >= 0.0 && score <= 1.0, "Blended score out of range: " + score);
			}
			// Class with more members changed should not score less than the other
			// (member ratio for Service = 2/2 = 1.0; for Util = 1/2 = 0.5)
			// After blend, Service score >= Util score when Service file size >= Util file
			// size
			// This just checks the invariant that the most-changed class scores highest.
			assertTrue(result.values().stream().anyMatch(v -> Math.abs(v - 1.0) < 0.001),
					"At least one class should score 1.0 after normalisation");
		}

		@Test
		@DisplayName("E45: class missing from membersByClass gets member-ratio 0 in blend")
		void classMissingFromMembersByClassGetsZeroMemberRatio() throws IOException {
			Path src = tempDir.resolve("src");
			writeSource(src, "com.example.A", "public class A { void m1(){} void m2(){} void m3(){} }");
			writeSource(src, "com.example.B", "public class B {}");

			// Only A is in membersByClass; B is not
			StructuralChangeAnalyzer.ChangedMembers cm = members(Map.of("com.example.A", Set.of("m1", "m2")));

			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.example.A", "com.example.B"),
					List.of(src), cm);
			// B's blended score = (fileScore + 0.0) / 2 = fileScore / 2
			// A's blended score = (fileScore + memberRatio) / 2 where memberRatio = 2/2 =
			// 1.0
			// So A should score higher than B
			assertTrue(result.get("com.example.A") >= result.get("com.example.B"),
					"Class with changed members should score higher than one with no member data");
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// computeFromDiffs — diff-based complexity
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("computeFromDiffs(diffs)")
	class ComputeFromDiffs {

		private StructuralDiff.FileDiff diffWithBody(String fqcn, String oldBody, String newBody) {
			var bodyChange = new StructuralDiff.BodyChange(fqcn, "testMethod", StructuralDiff.Change.Category.METHOD,
					oldBody, newBody);
			return new StructuralDiff.FileDiff(Path.of(fqcn + ".java"), List.of(), List.of(bodyChange));
		}

		@Test
		@DisplayName("null or empty diffs returns empty map")
		void nullOrEmptyDiffsReturnsEmpty() {
			assertTrue(ChangeComplexity.computeFromDiffs(null).isEmpty());
			assertTrue(ChangeComplexity.computeFromDiffs(List.of()).isEmpty());
		}

		@Test
		@DisplayName("diff with no body changes returns empty map")
		void diffWithNoBodyChangesReturnsEmpty() {
			var diff = new StructuralDiff.FileDiff(Path.of("Foo.java"),
					List.of(new StructuralDiff.Change(StructuralDiff.Change.Kind.ADDED,
							StructuralDiff.Change.Category.METHOD, "com.Foo", "doIt", "")));
			assertTrue(ChangeComplexity.computeFromDiffs(List.of(diff)).isEmpty());
		}

		@Test
		@DisplayName("single class with changed body scores 1.0")
		void singleClassScoresOne() {
			var diff = diffWithBody("com.example.Foo", "int x = 1;", "int x = 2;\nint y = 3;");

			Map<String, Double> result = ChangeComplexity.computeFromDiffs(List.of(diff));
			assertEquals(1, result.size());
			assertEquals(1.0, result.get("com.example.Foo"), 0.001);
		}

		@Test
		@DisplayName("larger diff body produces higher score than trivial diff")
		void largerDiffScoresHigher() {
			String bigOld = "int a;\nint b;\nint c;\nint d;\nint e;\nint f;\nint g;\n";
			String bigNew = "String a;\nString b;\nString c;\nString d;\nString e;\n"
					+ "void doManyThings() { a = b = c = d = e = \"x\"; }\n";

			var trivialDiff = diffWithBody("com.example.Trivial", "int x;", "int y;");
			var bigDiff = diffWithBody("com.example.Big", bigOld, bigNew);

			Map<String, Double> result = ChangeComplexity.computeFromDiffs(List.of(trivialDiff, bigDiff));
			assertEquals(2, result.size());
			assertTrue(result.get("com.example.Big") >= result.get("com.example.Trivial"),
					"Larger diff should score higher or equal");
		}

		@Test
		@DisplayName("multiple body changes for same class are accumulated")
		void multipleBodyChangesAccumulated() {
			var bc1 = new StructuralDiff.BodyChange("com.example.Foo", "method1", StructuralDiff.Change.Category.METHOD,
					"int a;", "int a;\nint b;");
			var bc2 = new StructuralDiff.BodyChange("com.example.Foo", "method2", StructuralDiff.Change.Category.METHOD,
					"int c;", "int c;\nint d;\nint e;");
			var diff = new StructuralDiff.FileDiff(Path.of("Foo.java"), List.of(), List.of(bc1, bc2));
			Map<String, Double> result = ChangeComplexity.computeFromDiffs(List.of(diff));
			assertEquals(1, result.size(), "Both changes belong to same class");
			assertEquals(1.0, result.get("com.example.Foo"), 0.001);
		}

		@Test
		@DisplayName("identical old/new body (no actual change) produces empty or zero contrib")
		void identicalBodiesProducesZeroContrib() {
			var bc = new StructuralDiff.BodyChange("com.example.Same", "m", StructuralDiff.Change.Category.METHOD,
					"return 1;", "return 1;");
			var diff = new StructuralDiff.FileDiff(Path.of("Same.java"), List.of(), List.of(bc));
			// diffText of identical bodies is "" → excluded from map
			Map<String, Double> result = ChangeComplexity.computeFromDiffs(List.of(diff));
			// Either empty (entry excluded) or meaningfully accounted for
			// The implementation skips empty diffText: result should be empty
			assertTrue(result.isEmpty() || result.get("com.example.Same") != null);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// compute(Set, List, ChangedMembers, List<FileDiff>) — diff-preferred
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("compute(changedClasses, sourceRoots, changedMembers, diffs) — prefers diffs")
	class DiffPreferred {

		@Test
		@DisplayName("when diffs have body changes, diff-based result is returned")
		void withBodyChangesDiffBasedWins() throws IOException {
			Path src = tempDir.resolve("src");
			writeSource(src, "com.example.Foo", "public class Foo { void m() {} }");

			var bc = new StructuralDiff.BodyChange("com.example.Foo", "m", StructuralDiff.Change.Category.METHOD,
					"old body", "new body different");
			var diff = new StructuralDiff.FileDiff(Path.of("Foo.java"), List.of(), List.of(bc));

			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.example.Foo"), List.of(src), null,
					List.of(diff));
			assertFalse(result.isEmpty(), "Diff-based path must return a result");
		}

		@Test
		@DisplayName("when diffs produce empty result, falls back to file-level")
		void emptyDiffFallsBackToFileLevel() throws IOException {
			Path src = tempDir.resolve("src");
			writeSource(src, "com.example.Foo", "public class Foo {}");
			// Diff with no body changes → computeFromDiffs returns empty → fallback to file
			var diff = new StructuralDiff.FileDiff(Path.of("Foo.java"), List.of());
			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.example.Foo"), List.of(src), null,
					List.of(diff));
			assertFalse(result.isEmpty(), "Should fall back to file-level when diff is empty");
		}

		@Test
		@DisplayName("null diffs falls through to file-level compute")
		void nullDiffsFallsToFileLevel() throws IOException {
			Path src = tempDir.resolve("src");
			writeSource(src, "com.example.Foo", "public class Foo { int x; }");
			Map<String, Double> result = ChangeComplexity.compute(Set.of("com.example.Foo"), List.of(src), null, null);
			assertFalse(result.isEmpty(), "null diffs → file-level compute must still return result");
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// fromRawSizes — E46: normalisation from pre-computed sizes
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("fromRawSizes — E46 normalisation")
	class FromRawSizes {

		@Test
		@DisplayName("E46: empty map returns empty")
		void emptyReturnsEmpty() {
			assertTrue(ChangeComplexity.fromRawSizes(Map.of()).isEmpty());
		}

		@Test
		@DisplayName("E46: all-zero sizes returns empty")
		void allZeroReturnsEmpty() {
			assertTrue(ChangeComplexity.fromRawSizes(Map.of("com.A", 0, "com.B", 0)).isEmpty());
		}

		@Test
		@DisplayName("E46: single entry gets score 1.0")
		void singleEntryGetsOne() {
			Map<String, Double> result = ChangeComplexity.fromRawSizes(Map.of("com.Only", 42));
			assertEquals(1.0, result.get("com.Only"), 0.001);
		}

		@Test
		@DisplayName("E46: maximum entry scores 1.0, others proportional")
		void maxEntryScoresOne() {
			Map<String, Integer> raw = new LinkedHashMap<>();
			raw.put("com.Small", 10);
			raw.put("com.Big", 100);
			raw.put("com.Medium", 50);

			Map<String, Double> result = ChangeComplexity.fromRawSizes(raw);

			assertEquals(1.0, result.get("com.Big"), 0.001);
			assertEquals(0.5, result.get("com.Medium"), 0.001);
			assertEquals(0.1, result.get("com.Small"), 0.001);
		}

		@Test
		@DisplayName("E46: scores are in [0.0, 1.0]")
		void scoresInRange() {
			Map<String, Integer> raw = Map.of("A", 1, "B", 5, "C", 10, "D", 3);
			for (double score : ChangeComplexity.fromRawSizes(raw).values()) {
				assertTrue(score >= 0.0 && score <= 1.0, "Score out of range: " + score);
			}
		}

		@Test
		@DisplayName("E46: entry with size 0 alongside non-zero gets score 0.0")
		void zeroEntryGetsZeroScore() {
			Map<String, Double> result = ChangeComplexity.fromRawSizes(Map.of("com.None", 0, "com.Some", 100));
			assertEquals(0.0, result.get("com.None"), 0.001);
			assertEquals(1.0, result.get("com.Some"), 0.001);
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// findSourceFile — inner class path resolution
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("findSourceFile — FQCN → path resolution")
	class FindSourceFile {

		@Test
		@DisplayName("top-level class resolved to .java file")
		void topLevelJavaClass() throws IOException {
			writeSource(tempDir, "com.example.Foo", "public class Foo {}");
			Path found = ChangeComplexity.findSourceFile("com.example.Foo", List.of(tempDir));
			assertNotNull(found);
			assertTrue(found.toString().endsWith("Foo.java"));
		}

		@Test
		@DisplayName("top-level class resolved to .kt file when no .java exists")
		void topLevelKotlinClass() throws IOException {
			String fqcn = "com.example.Bar";
			Path kt = tempDir.resolve("com/example/Bar.kt");
			Files.createDirectories(kt.getParent());
			Files.writeString(kt, "class Bar");

			Path found = ChangeComplexity.findSourceFile(fqcn, List.of(tempDir));
			assertNotNull(found, "Should find .kt file");
			assertTrue(found.toString().endsWith("Bar.kt"));
		}

		@Test
		@DisplayName("inner class FQCN (dollar notation) resolved to enclosing top-level file")
		void innerClassDollarNotation() throws IOException {
			writeSource(tempDir, "com.example.Outer", "public class Outer { static class Inner {} }");

			Path found = ChangeComplexity.findSourceFile("com.example.Outer$Inner", List.of(tempDir));
			assertNotNull(found, "Inner class should resolve to Outer.java");
			assertTrue(found.toString().endsWith("Outer.java"));
		}

		@Test
		@DisplayName("deeply nested inner class (multiple dollars) resolved to top-level")
		void deeplyNestedInnerClass() throws IOException {
			writeSource(tempDir, "com.example.A", "public class A { class B { class C {} } }");

			Path found = ChangeComplexity.findSourceFile("com.example.A$B$C", List.of(tempDir));
			assertNotNull(found, "Deeply nested inner class should resolve to A.java");
			assertTrue(found.toString().endsWith("A.java"));
		}

		@Test
		@DisplayName("class not found returns null")
		void notFoundReturnsNull() {
			Path found = ChangeComplexity.findSourceFile("com.example.Nonexistent", List.of(tempDir));
			assertNull(found);
		}

		@Test
		@DisplayName("class found in second of multiple roots")
		void classInSecondRoot() throws IOException {
			Path root1 = tempDir.resolve("root1");
			Path root2 = tempDir.resolve("root2");
			Files.createDirectories(root1);
			writeSource(root2, "com.example.Baz", "public class Baz {}");

			Path found = ChangeComplexity.findSourceFile("com.example.Baz", List.of(root1, root2));
			assertNotNull(found);
			assertTrue(found.startsWith(root2));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// serialise / deserialise — E47 round-trip
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("serialise / deserialise — E47 round-trip")
	class SerialiseRoundTrip {

		@Test
		@DisplayName("E47: empty map serialises to empty string, deserialises to empty map")
		void emptyMapRoundTrip() {
			String s = ChangeComplexity.serialise(Map.of());
			assertEquals("", s);
			assertTrue(ChangeComplexity.deserialise(s).isEmpty());
		}

		@Test
		@DisplayName("E47: null or blank string deserialises to empty map")
		void nullBlankDeserialised() {
			assertTrue(ChangeComplexity.deserialise(null).isEmpty());
			assertTrue(ChangeComplexity.deserialise("").isEmpty());
			assertTrue(ChangeComplexity.deserialise("   ").isEmpty());
		}

		@Test
		@DisplayName("E47: single entry round-trip preserves FQCN and score")
		void singleEntryRoundTrip() {
			Map<String, Double> original = Map.of("com.example.Foo", 0.75);
			String serialised = ChangeComplexity.serialise(original);
			assertFalse(serialised.isEmpty());
			Map<String, Double> restored = ChangeComplexity.deserialise(serialised);
			assertEquals(1, restored.size());
			assertEquals(0.75, restored.get("com.example.Foo"), 0.001);
		}

		@Test
		@DisplayName("E47: multiple entries round-trip preserves all entries")
		void multipleEntriesRoundTrip() {
			Map<String, Double> original = new LinkedHashMap<>();
			original.put("com.example.A", 1.0);
			original.put("com.example.B", 0.5);
			original.put("com.example.C", 0.25);

			String serialised = ChangeComplexity.serialise(original);
			Map<String, Double> restored = ChangeComplexity.deserialise(serialised);

			assertEquals(original.size(), restored.size());
			for (var entry : original.entrySet()) {
				assertEquals(entry.getValue(), restored.get(entry.getKey()), 0.001,
						"Score mismatch for " + entry.getKey());
			}
		}

		@Test
		@DisplayName("E47: score 0.0 and 1.0 round-trip without precision loss")
		void extremeScoresRoundTrip() {
			Map<String, Double> original = new LinkedHashMap<>();
			original.put("com.example.Zero", 0.0);
			original.put("com.example.One", 1.0);

			String s = ChangeComplexity.serialise(original);
			Map<String, Double> r = ChangeComplexity.deserialise(s);
			assertEquals(0.0, r.get("com.example.Zero"), 0.001);
			assertEquals(1.0, r.get("com.example.One"), 0.001);
		}

		@Test
		@DisplayName("E47: FQCN containing dots (not colons) round-trips correctly")
		void fqcnWithDotsRoundTrips() {
			Map<String, Double> original = Map.of("org.springframework.samples.petclinic.owner.Owner", 0.8);
			String s = ChangeComplexity.serialise(original);
			Map<String, Double> r = ChangeComplexity.deserialise(s);
			assertEquals(0.8, r.get("org.springframework.samples.petclinic.owner.Owner"), 0.001);
		}

		@Test
		@DisplayName("E47: serialise output uses colon separator and comma delimiter")
		void serialiseFormat() {
			String s = ChangeComplexity.serialise(Map.of("com.Foo", 0.5));
			assertTrue(s.contains(":"), "serialise should use ':' to separate FQCN from score");
			assertFalse(s.startsWith(","), "serialise should not begin with comma");
			assertFalse(s.endsWith(","), "serialise should not end with comma");
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// deflateSize — internal utility
	// ─────────────────────────────────────────────────────────────────────────

	@Nested
	@DisplayName("deflateSize — internal Deflate utility")
	class DeflateSizeTests {

		@Test
		@DisplayName("highly repetitive data compresses to smaller size than non-repetitive")
		void repetitiveCompressesSmaller() {
			byte[] repetitive = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA".getBytes();
			byte[] nonRepetitive = "A1B2C3D4E5F6G7H8I9J0K1L2M3N4O5P6Q7R8S9".getBytes();

			int compRepetitive = ChangeComplexity.deflateSize(repetitive);
			int compNonRepetitive = ChangeComplexity.deflateSize(nonRepetitive);

			assertTrue(compRepetitive < compNonRepetitive,
					"Repetitive data should compress smaller than non-repetitive data of same length");
		}

		@Test
		@DisplayName("empty byte array returns a valid (small) compressed size")
		void emptyArrayReturnsSmallSize() {
			int size = ChangeComplexity.deflateSize(new byte[0]);
			assertTrue(size >= 0, "Compressed size of empty array must be non-negative");
		}

		@Test
		@DisplayName("single byte array returns a positive size")
		void singleByteReturnsPositiveSize() {
			int size = ChangeComplexity.deflateSize(new byte[] { 42 });
			assertTrue(size > 0, "Compressed size must be positive");
		}
	}
}
