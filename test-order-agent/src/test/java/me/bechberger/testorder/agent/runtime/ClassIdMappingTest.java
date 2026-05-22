package me.bechberger.testorder.agent.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
}
