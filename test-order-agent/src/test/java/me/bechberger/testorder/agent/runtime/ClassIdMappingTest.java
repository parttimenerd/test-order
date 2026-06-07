package me.bechberger.testorder.agent.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassIdMappingTest {

	@TempDir
	Path tempDir;

	@Test
	void saveAndLoadRoundTrip() throws IOException {
		ClassIdMap map = ClassIdMap.createForBenchmark();
		int idA = map.getOrRegisterClass("com.example.ClassA");
		int idB = map.getOrRegisterClass("com.example.ClassB");
		int idC = map.getOrRegisterClass("com.example.ClassC");

		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(map, 3, 0);

		Path file = tempDir.resolve("class-id-map.bin");
		mapping.save(file);

		assertTrue(Files.exists(file));
		assertTrue(Files.size(file) > 16); // at least header + some data

		ClassIdMapping loaded = ClassIdMapping.load(file);
		assertEquals(3, loaded.classCount());
		assertEquals(0, loaded.memberCount());
		assertEquals("com.example.ClassA", loaded.getClassName(0));
		assertEquals("com.example.ClassB", loaded.getClassName(1));
		assertEquals("com.example.ClassC", loaded.getClassName(2));
	}

	@Test
	void saveAndLoadWithMembers() throws IOException {
		ClassIdMap map = ClassIdMap.createForBenchmark();
		map.getOrRegisterClass("com.example.Foo");
		int m1 = map.getOrRegisterMember("com.example.Foo#bar");
		int m2 = map.getOrRegisterMember("com.example.Foo#baz");

		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(map, 1, m2 + 1);

		Path file = tempDir.resolve("mapping.bin");
		mapping.save(file);

		ClassIdMapping loaded = ClassIdMapping.load(file);
		assertEquals(1, loaded.classCount());
		assertEquals(2, loaded.memberCount());
		assertEquals("com.example.Foo", loaded.getClassName(0));
		assertEquals("com.example.Foo#bar", loaded.getMemberName(8_000_000));
		assertEquals("com.example.Foo#baz", loaded.getMemberName(8_000_001));
	}

	@Test
	void toClassMapProducesCorrectMapping() throws IOException {
		ClassIdMap map = ClassIdMap.createForBenchmark();
		map.getOrRegisterClass("com.a.A");
		map.getOrRegisterClass("com.b.B");

		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(map, 2, 0);

		Path file = tempDir.resolve("map.bin");
		mapping.save(file);
		ClassIdMapping loaded = ClassIdMapping.load(file);

		Map<String, Integer> classMap = loaded.toClassMap();
		assertEquals(0, classMap.get("com.a.A"));
		assertEquals(1, classMap.get("com.b.B"));
		assertEquals(2, classMap.size());
	}

	@Test
	void toMemberMapProducesCorrectMapping() throws IOException {
		ClassIdMap map = ClassIdMap.createForBenchmark();
		map.getOrRegisterClass("com.x.X");
		int m1 = map.getOrRegisterMember("com.x.X#method1");

		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(map, 1, m1 + 1);

		Path file = tempDir.resolve("members.bin");
		mapping.save(file);
		ClassIdMapping loaded = ClassIdMapping.load(file);

		Map<String, Integer> memberMap = loaded.toMemberMap();
		assertEquals(8_000_000, memberMap.get("com.x.X#method1"));
	}

	@Test
	void loadInvalidMagicThrows() throws IOException {
		Path file = tempDir.resolve("bad.bin");
		Files.write(file, new byte[]{0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0});

		assertThrows(IOException.class, () -> ClassIdMapping.load(file));
	}

	@Test
	void loadUnsupportedVersionThrows() throws IOException {
		Path file = tempDir.resolve("badver.bin");
		// Valid magic "TOIM" but version 99
		byte[] data = new byte[]{'T', 'O', 'I', 'M', 0, 0, 0, 99, // version=99
				0, 0, 0, 0, // classCount=0
				0, 0, 0, 0 // memberCount=0
		};
		Files.write(file, data);

		IOException ex = assertThrows(IOException.class, () -> ClassIdMapping.load(file));
		assertTrue(ex.getMessage().contains("version"));
	}

	@Test
	void emptyMappingRoundTrip() throws IOException {
		ClassIdMap map = ClassIdMap.createForBenchmark();
		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(map, 0, 0);

		Path file = tempDir.resolve("empty.bin");
		mapping.save(file);

		ClassIdMapping loaded = ClassIdMapping.load(file);
		assertEquals(0, loaded.classCount());
		assertEquals(0, loaded.memberCount());
		assertTrue(loaded.toClassMap().isEmpty());
		assertTrue(loaded.toMemberMap().isEmpty());
	}

	@Test
	void getClassNameOutOfBoundsReturnsNull() {
		ClassIdMap map = ClassIdMap.createForBenchmark();
		map.getOrRegisterClass("com.only.One");
		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(map, 1, 0);

		assertNull(mapping.getClassName(-1));
		assertNull(mapping.getClassName(1));
		assertEquals("com.only.One", mapping.getClassName(0));
	}

	@Test
	void getMemberNameOutOfBoundsReturnsNull() {
		ClassIdMap map = ClassIdMap.createForBenchmark();
		map.getOrRegisterClass("com.x.X");
		int m = map.getOrRegisterMember("com.x.X#foo");
		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(map, 1, m + 1);

		assertNull(mapping.getMemberName(7_999_999));
		assertNull(mapping.getMemberName(8_000_001));
		assertEquals("com.x.X#foo", mapping.getMemberName(8_000_000));
	}

	@Test
	void reactorPrePassThenModuleGrowsMap() throws IOException {
		// Simulates the full lifecycle: reactor pre-pass writes a file with all
		// known top-level FQNs, then a module's prepare loads it, registers an
		// extra inner-class FQN, and saves the grown map back.
		ClassIdMap pre = ClassIdMap.createForBenchmark();
		pre.getOrRegisterClass("com.modulea.Library"); // 0
		pre.getOrRegisterClass("com.modulea.LibraryTest"); // 1
		pre.getOrRegisterClass("com.moduleb.Service"); // 2
		pre.getOrRegisterClass("com.moduleb.ServiceTest"); // 3
		ClassIdMapping preMapping = ClassIdMapping.fromClassIdMap(pre, 4, 0);
		Path file = tempDir.resolve("class-id-map.bin");
		preMapping.save(file);

		// Module-A's prepare: load and register inner classes.
		ClassIdMap moduleA = ClassIdMap.createForBenchmark();
		ClassIdMapping loaded = ClassIdMapping.load(file);
		moduleA.bulkLoadClasses(loaded.toClassMap());

		// IDs preserved
		assertEquals(0, moduleA.getOrRegisterClass("com.modulea.Library"));
		assertEquals(1, moduleA.getOrRegisterClass("com.modulea.LibraryTest"));
		assertEquals(2, moduleA.getOrRegisterClass("com.moduleb.Service"));
		assertEquals(3, moduleA.getOrRegisterClass("com.moduleb.ServiceTest"));

		// New inner class gets next free ID (4), not a collision.
		int innerId = moduleA.getOrRegisterClass("com.modulea.Library$Inner");
		assertEquals(4, innerId);

		ClassIdMapping grown = ClassIdMapping.fromClassIdMap(moduleA, moduleA.getNextClassId(),
				moduleA.getNextMemberId());
		grown.save(file);

		ClassIdMapping reloaded = ClassIdMapping.load(file);
		assertEquals(5, reloaded.classCount());
		assertEquals("com.modulea.Library$Inner", reloaded.getClassName(4));
	}

	@Test
	void reactorMapSurvivesMultipleModuleGrowsWithoutIdCollision() throws IOException {
		// Module-A registers 2 inner classes, then module-B (loading the
		// just-saved map) registers its own inner class — the IDs assigned in
		// module-B must skip past module-A's additions (load reads the highest
		// ID from the file).
		ClassIdMap pre = ClassIdMap.createForBenchmark();
		pre.getOrRegisterClass("a.A");
		pre.getOrRegisterClass("b.B");
		Path file = tempDir.resolve("map.bin");
		ClassIdMapping.fromClassIdMap(pre, 2, 0).save(file);

		// Module A grows the map with 2 new entries
		ClassIdMap modA = ClassIdMap.createForBenchmark();
		modA.bulkLoadClasses(ClassIdMapping.load(file).toClassMap());
		int aInner1 = modA.getOrRegisterClass("a.A$1");
		int aInner2 = modA.getOrRegisterClass("a.A$Inner");
		assertEquals(2, aInner1);
		assertEquals(3, aInner2);
		ClassIdMapping.fromClassIdMap(modA, modA.getNextClassId(), modA.getNextMemberId()).save(file);

		// Module B loads what A wrote, registers its own inner — must get ID 4
		ClassIdMap modB = ClassIdMap.createForBenchmark();
		modB.bulkLoadClasses(ClassIdMapping.load(file).toClassMap());
		int bInner = modB.getOrRegisterClass("b.B$Helper");
		assertEquals(4, bInner, "module-B's inner class must not collide with module-A's IDs");

		// Original IDs from pre-pass still preserved
		assertEquals(0, modB.getOrRegisterClass("a.A"));
		assertEquals(1, modB.getOrRegisterClass("b.B"));
	}

	@Test
	void bulkLoadIsIdempotent() throws IOException {
		// Loading the same map twice must not advance nextClassId.
		ClassIdMap pre = ClassIdMap.createForBenchmark();
		pre.getOrRegisterClass("x.A");
		pre.getOrRegisterClass("x.B");
		pre.getOrRegisterClass("x.C");
		Path file = tempDir.resolve("idem.bin");
		ClassIdMapping.fromClassIdMap(pre, 3, 0).save(file);

		ClassIdMap target = ClassIdMap.createForBenchmark();
		target.bulkLoadClasses(ClassIdMapping.load(file).toClassMap());
		int firstNext = target.getNextClassId();
		target.bulkLoadClasses(ClassIdMapping.load(file).toClassMap());
		int secondNext = target.getNextClassId();

		assertEquals(firstNext, secondNext);
		assertEquals(3, firstNext);
	}

	@Test
	void mapWithGapsPreservesIds() throws IOException {
		// fromClassIdMap walks IDs 0..maxClassId and looks up each. If a class
		// was registered then "removed" (we can't really remove from ClassIdMap,
		// but null entries are possible if maxClassId is set higher than the
		// actual range), the saved file has empty strings in those slots.
		// toClassMap skips empty entries; bulkLoadClasses then preserves the
		// surviving IDs and advances nextClassId past the max — so a new class
		// added after a gappy load gets a brand-new ID, not a recycled one.
		ClassIdMap pre = ClassIdMap.createForBenchmark();
		pre.getOrRegisterClass("p.A"); // 0
		pre.getOrRegisterClass("p.B"); // 1
		pre.getOrRegisterClass("p.C"); // 2
		// Save with maxClassId=5 to leave gaps at indices 3, 4 (null → empty).
		Path file = tempDir.resolve("gappy.bin");
		ClassIdMapping.fromClassIdMap(pre, 5, 0).save(file);

		ClassIdMapping loaded = ClassIdMapping.load(file);
		assertEquals(5, loaded.classCount(), "file preserves slot count even with gaps");
		Map<String, Integer> classMap = loaded.toClassMap();
		assertEquals(3, classMap.size(), "toClassMap drops null/empty entries");

		ClassIdMap target = ClassIdMap.createForBenchmark();
		target.bulkLoadClasses(classMap);
		// nextClassId advances past max loaded ID (2), not past the gap-padded count
		assertEquals(3, target.getNextClassId());
		// New class gets ID 3 (the first gap), not a colliding one
		assertEquals(3, target.getOrRegisterClass("p.NewlyAdded"));
	}

	@Test
	void truncatedFileThrows() throws IOException {
		// Header says 100 classes but file only has 1 entry — must throw, not
		// silently load partial data.
		Path file = tempDir.resolve("truncated.bin");
		byte[] header = new byte[]{'T', 'O', 'I', 'M', 0, 0, 0, 1, // magic + version
				0, 0, 0, 100, // classCount=100
				0, 0, 0, 0, // memberCount=0
				// Just 2 bytes of one UTF entry (length prefix says 0 chars)
				0, 0};
		Files.write(file, header);

		assertThrows(IOException.class, () -> ClassIdMapping.load(file));
	}

	@Test
	void sameSimpleNameAcrossModulesGetUniqueReactorIds() throws IOException {
		// The whole point of the reactor-wide ID space: two modules each defining
		// a class with the same SIMPLE name (com.a.Util vs com.b.Util) must get
		// distinct global IDs. Under the OLD per-module scheme, both could have
		// ended up as ID 0 in their own maps — instrumented bytecode in module-A
		// referencing classId 0 would silently mean "Util" in either A or B
		// depending on which fork loaded it. Reactor-wide IDs make that
		// silent-mis-attribution impossible.
		ClassIdMap pre = ClassIdMap.createForBenchmark();
		int aUtil = pre.getOrRegisterClass("com.a.Util");
		int bUtil = pre.getOrRegisterClass("com.b.Util");
		int cUtil = pre.getOrRegisterClass("com.c.Util");
		assertNotEquals(aUtil, bUtil, "same-simple-name must get distinct IDs");
		assertNotEquals(bUtil, cUtil);
		assertNotEquals(aUtil, cUtil);

		Path file = tempDir.resolve("samename.bin");
		ClassIdMapping.fromClassIdMap(pre, 3, 0).save(file);

		// Load from a different module and verify each Util resolves to its own FQN.
		ClassIdMapping loaded = ClassIdMapping.load(file);
		Map<String, Integer> m = loaded.toClassMap();
		assertEquals(aUtil, (int) m.get("com.a.Util"));
		assertEquals(bUtil, (int) m.get("com.b.Util"));
		assertEquals(cUtil, (int) m.get("com.c.Util"));
		// Reverse lookup: ID → FQN must be unambiguous
		assertEquals("com.a.Util", loaded.getClassName(aUtil));
		assertEquals("com.b.Util", loaded.getClassName(bUtil));
		assertEquals("com.c.Util", loaded.getClassName(cUtil));
	}

	@Test
	void deeplyNestedInnerClassFqnsRoundTrip() throws IOException {
		// Java allows arbitrary nesting depth: Outer$Inner$Deeper$Deepest. The
		// reactor-wide map must preserve them as opaque strings — no special
		// $-aware handling that might collapse them.
		ClassIdMap map = ClassIdMap.createForBenchmark();
		map.getOrRegisterClass("com.x.Outer");
		map.getOrRegisterClass("com.x.Outer$Inner");
		map.getOrRegisterClass("com.x.Outer$Inner$Deeper");
		map.getOrRegisterClass("com.x.Outer$Inner$Deeper$Deepest");
		map.getOrRegisterClass("com.x.Outer$Inner$Deeper$Deepest$1"); // anon inside the deepest

		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(map, 5, 0);
		Path file = tempDir.resolve("deep.bin");
		mapping.save(file);
		ClassIdMapping loaded = ClassIdMapping.load(file);

		Map<String, Integer> m = loaded.toClassMap();
		assertEquals(5, m.size());
		assertNotNull(m.get("com.x.Outer$Inner$Deeper$Deepest$1"));
		// Each level distinct
		Set<Integer> ids = new HashSet<>(m.values());
		assertEquals(5, ids.size(), "every nesting level must have a unique ID");
	}

	@Test
	void anonymousAndLambdaSyntheticClassIds() throws IOException {
		// At runtime, the JVM creates synthetic classes for anonymous inner
		// classes (Outer$1) and lambdas (Outer$$Lambda$42 on HotSpot,
		// Outer$$Lambda$0x000... on newer JVMs). The reactor-wide map should
		// accept them all as plain string keys; ID assignment must not differ.
		ClassIdMap map = ClassIdMap.createForBenchmark();
		int o = map.getOrRegisterClass("com.y.Outer");
		int anon1 = map.getOrRegisterClass("com.y.Outer$1");
		int anon2 = map.getOrRegisterClass("com.y.Outer$2");
		int lam1 = map.getOrRegisterClass("com.y.Outer$$Lambda$1");
		int lam2 = map.getOrRegisterClass("com.y.Outer$$Lambda/0x0000000800c01000");

		Set<Integer> all = new HashSet<>();
		all.add(o);
		all.add(anon1);
		all.add(anon2);
		all.add(lam1);
		all.add(lam2);
		assertEquals(5, all.size(), "synthetic classes must each get unique IDs");

		Path file = tempDir.resolve("synth.bin");
		ClassIdMapping.fromClassIdMap(map, 5, 0).save(file);
		ClassIdMapping loaded = ClassIdMapping.load(file);

		// Round-trip: every synthetic FQN survives byte-for-byte.
		assertEquals("com.y.Outer$1", loaded.getClassName(anon1));
		assertEquals("com.y.Outer$$Lambda$1", loaded.getClassName(lam1));
		assertEquals("com.y.Outer$$Lambda/0x0000000800c01000", loaded.getClassName(lam2));
	}

	@Test
	void crossModuleGrowDoesNotReassignBaselineIds() throws IOException {
		// Regression: imagine the reactor pre-pass writes IDs 0..N for top-level
		// FQNs across many modules. Then several modules run prepare in some
		// order. Each module's grow phase loads the map, registers its inner
		// classes, saves. After ALL modules finish, the baseline IDs must be
		// pointwise unchanged — otherwise instrumented bytecode in already-built
		// modules would reference stale IDs.
		ClassIdMap pre = ClassIdMap.createForBenchmark();
		String[] baseline = {"a.A", "a.ATest", "b.B", "b.BTest", "c.C", "c.CTest", "d.D", "d.DTest"};
		Map<String, Integer> baselineIds = new java.util.HashMap<>();
		for (String fqn : baseline) {
			baselineIds.put(fqn, pre.getOrRegisterClass(fqn));
		}
		Path file = tempDir.resolve("baseline.bin");
		ClassIdMapping.fromClassIdMap(pre, baseline.length, 0).save(file);

		// 4 modules each grow with their own innerclass
		String[][] perModule = {{"a.A$1", "a.A$Inner"}, {"b.B$Builder"}, {"c.C$Listener", "c.C$Listener$1"},
				{"d.D$$Lambda$0x42"}};
		for (String[] additions : perModule) {
			ClassIdMap mod = ClassIdMap.createForBenchmark();
			mod.bulkLoadClasses(ClassIdMapping.load(file).toClassMap());
			for (String add : additions) {
				mod.getOrRegisterClass(add);
			}
			ClassIdMapping.fromClassIdMap(mod, mod.getNextClassId(), mod.getNextMemberId()).save(file);
		}

		ClassIdMapping finalMap = ClassIdMapping.load(file);
		Map<String, Integer> finalClassMap = finalMap.toClassMap();
		// Every baseline ID is byte-identical to what the pre-pass assigned.
		for (Map.Entry<String, Integer> e : baselineIds.entrySet()) {
			assertEquals(e.getValue(), finalClassMap.get(e.getKey()),
					"baseline ID for " + e.getKey() + " must be unchanged after all module grows");
		}
		// And every per-module inner class is still there.
		for (String[] additions : perModule) {
			for (String add : additions) {
				assertNotNull(finalClassMap.get(add), add + " missing after grow chain");
			}
		}
	}

	@Test
	void unicodeAndDollarSignClassNames() throws IOException {
		// JVM allows $ in class names (inner classes use it heavily) and
		// Unicode characters; both must round-trip cleanly through writeUTF.
		ClassIdMap map = ClassIdMap.createForBenchmark();
		map.getOrRegisterClass("com.example.Outer$Inner$1");
		map.getOrRegisterClass("com.example.Outer$$Lambda$42");
		map.getOrRegisterClass("com.example.Café"); // non-ASCII
		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(map, 3, 0);

		Path file = tempDir.resolve("unicode.bin");
		mapping.save(file);
		ClassIdMapping loaded = ClassIdMapping.load(file);

		assertEquals("com.example.Outer$Inner$1", loaded.getClassName(0));
		assertEquals("com.example.Outer$$Lambda$42", loaded.getClassName(1));
		assertEquals("com.example.Café", loaded.getClassName(2));
	}

	@Test
	void longFqnRoundTrip() throws IOException {
		// Real-world deeply-nested generic-erased FQN can run hundreds of chars.
		// writeUTF caps at 65535 bytes, but realistic FQNs stay well below.
		// Verify a 1KB-ish FQN survives intact.
		StringBuilder fqn = new StringBuilder("com.example.deeply");
		for (int i = 0; i < 30; i++) {
			fqn.append(".sub").append(i);
		}
		fqn.append(".Outer");
		for (int i = 0; i < 10; i++) {
			fqn.append("$Level").append(i);
		}
		String longName = fqn.toString();
		assertTrue(longName.length() > 200, "test setup: should be a genuinely long FQN");

		ClassIdMap map = ClassIdMap.createForBenchmark();
		map.getOrRegisterClass(longName);
		map.getOrRegisterClass("short.Name");

		Path file = tempDir.resolve("long.bin");
		ClassIdMapping.fromClassIdMap(map, 2, 0).save(file);
		ClassIdMapping loaded = ClassIdMapping.load(file);

		assertEquals(longName, loaded.getClassName(0));
		assertEquals("short.Name", loaded.getClassName(1));
	}

	@Test
	void singleCharIdentifiersRoundTrip() throws IOException {
		// Other extreme: minimal identifiers. JVM permits "a.b.C" etc.
		ClassIdMap map = ClassIdMap.createForBenchmark();
		map.getOrRegisterClass("a.B");
		map.getOrRegisterClass("a.C");
		map.getOrRegisterClass("X"); // default-package class
		map.getOrRegisterClass("x$1"); // default-package anonymous

		Path file = tempDir.resolve("short.bin");
		ClassIdMapping.fromClassIdMap(map, 4, 0).save(file);
		ClassIdMapping loaded = ClassIdMapping.load(file);

		Map<String, Integer> m = loaded.toClassMap();
		assertEquals(4, m.size());
		assertNotNull(m.get("a.B"));
		assertNotNull(m.get("X"));
		assertNotNull(m.get("x$1"));
	}

	@Test
	void manyClassesScale() throws IOException {
		// A reactor with thousands of classes (large monorepo) — the format
		// must scale linearly without surprises around 1K, 10K boundaries.
		ClassIdMap map = ClassIdMap.createForBenchmark();
		int count = 5000;
		for (int i = 0; i < count; i++) {
			map.getOrRegisterClass("com.scale.Cls" + i);
		}
		Path file = tempDir.resolve("scale.bin");
		ClassIdMapping.fromClassIdMap(map, count, 0).save(file);

		ClassIdMapping loaded = ClassIdMapping.load(file);
		assertEquals(count, loaded.classCount());
		Map<String, Integer> m = loaded.toClassMap();
		assertEquals(count, m.size());
		// Spot-check IDs are dense and contiguous.
		for (int i = 0; i < count; i++) {
			assertEquals(i, (int) m.get("com.scale.Cls" + i));
		}
	}

	@Test
	void allCommonInnerClassFlavors() throws IOException {
		// Cover every legitimate $-form a real JVM produces:
		// - static nested: Outer$Static
		// - instance inner: Outer$Inner
		// - anonymous: Outer$1, Outer$2
		// - local inside method: Outer$1Local
		// - lambda (HotSpot): Outer$$Lambda$1
		// - lambda (newer): Outer$$Lambda/0x...
		// - generic erasure: Outer$Generic — same as inner; just here to
		// confirm no special-casing
		ClassIdMap map = ClassIdMap.createForBenchmark();
		String[] flavors = {"p.Outer", "p.Outer$Static", "p.Outer$Inner", "p.Outer$1", "p.Outer$2", "p.Outer$1Local",
				"p.Outer$$Lambda$1", "p.Outer$$Lambda/0x0000000800c01000", "p.Outer$Generic"};
		for (String f : flavors) {
			map.getOrRegisterClass(f);
		}
		Path file = tempDir.resolve("flavors.bin");
		ClassIdMapping.fromClassIdMap(map, flavors.length, 0).save(file);
		ClassIdMapping loaded = ClassIdMapping.load(file);
		Map<String, Integer> m = loaded.toClassMap();
		assertEquals(flavors.length, m.size());
		Set<Integer> ids = new HashSet<>(m.values());
		assertEquals(flavors.length, ids.size(), "every flavor gets a unique ID");
		for (String f : flavors) {
			assertNotNull(m.get(f), "flavor missing: " + f);
		}
	}
}
