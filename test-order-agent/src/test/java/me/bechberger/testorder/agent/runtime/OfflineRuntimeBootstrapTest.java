package me.bechberger.testorder.agent.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineRuntimeBootstrapTest {

	@TempDir
	Path tempDir;

	@BeforeEach
	void reset() {
		OfflineRuntimeBootstrap.resetForTesting();
		ClassIdMap.getInstance().reset();
	}

	@Test
	void initLoadsClassMappings() throws IOException {
		// Create a mapping with 2 classes
		ClassIdMap buildMap = ClassIdMap.createForBenchmark();
		buildMap.getOrRegisterClass("com.example.Alpha");
		buildMap.getOrRegisterClass("com.example.Beta");
		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(buildMap, 2, 0);

		Path mappingFile = tempDir.resolve("class-id-map.bin");
		mapping.save(mappingFile);

		Path outputDir = tempDir.resolve("deps");
		Path indexFile = tempDir.resolve("index.lz4");

		// Reset the singleton so init can populate it
		ClassIdMap.getInstance().reset();

		OfflineRuntimeBootstrap.init(mappingFile, outputDir.toString(), indexFile.toString(), false);

		// Verify classes were loaded into the global ClassIdMap
		ClassIdMap runtime = ClassIdMap.getInstance();
		assertEquals(0, runtime.getOrRegisterClass("com.example.Alpha"));
		assertEquals(1, runtime.getOrRegisterClass("com.example.Beta"));
	}

	@Test
	void initLoadsMemberMappings() throws IOException {
		ClassIdMap buildMap = ClassIdMap.createForBenchmark();
		buildMap.getOrRegisterClass("com.svc.Service");
		int m1 = buildMap.getOrRegisterMember("com.svc.Service#handle");

		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(buildMap, 1, m1 + 1);

		Path mappingFile = tempDir.resolve("members-map.bin");
		mapping.save(mappingFile);

		ClassIdMap.getInstance().reset();

		OfflineRuntimeBootstrap.init(mappingFile, tempDir.toString(), tempDir.resolve("idx.lz4").toString(), true);

		ClassIdMap runtime = ClassIdMap.getInstance();
		assertEquals(0, runtime.getOrRegisterClass("com.svc.Service"));
		// Member should have the same ID
		assertEquals(8_000_000, runtime.getOrRegisterMember("com.svc.Service#handle"));
	}

	@Test
	void initIdempotent() throws IOException {
		ClassIdMap buildMap = ClassIdMap.createForBenchmark();
		buildMap.getOrRegisterClass("com.idem.A");
		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(buildMap, 1, 0);

		Path mappingFile = tempDir.resolve("idem.bin");
		mapping.save(mappingFile);

		ClassIdMap.getInstance().reset();

		// Call init twice — should not throw
		OfflineRuntimeBootstrap.init(mappingFile, tempDir.toString(), tempDir.resolve("i.lz4").toString(), false);
		OfflineRuntimeBootstrap.init(mappingFile, tempDir.toString(), tempDir.resolve("i.lz4").toString(), false);

		// Still works
		assertEquals(0, ClassIdMap.getInstance().getOrRegisterClass("com.idem.A"));
	}

	@Test
	void initWithInvalidFileThrows() {
		Path bogus = tempDir.resolve("nonexistent.bin");
		assertThrows(RuntimeException.class, () -> OfflineRuntimeBootstrap.init(bogus, tempDir.toString(),
				tempDir.resolve("x.lz4").toString(), false));
	}

	@Test
	void newClassRegistrationAfterBulkLoad() throws IOException {
		ClassIdMap buildMap = ClassIdMap.createForBenchmark();
		buildMap.getOrRegisterClass("com.pre.Loaded");
		ClassIdMapping mapping = ClassIdMapping.fromClassIdMap(buildMap, 1, 0);

		Path mappingFile = tempDir.resolve("preloaded.bin");
		mapping.save(mappingFile);

		ClassIdMap.getInstance().reset();
		OfflineRuntimeBootstrap.init(mappingFile, tempDir.toString(), tempDir.resolve("i.lz4").toString(), false);

		// Register a new class after bulk load — should get the next ID
		ClassIdMap runtime = ClassIdMap.getInstance();
		int newId = runtime.getOrRegisterClass("com.new.Dynamic");
		assertTrue(newId >= 1, "New class should get ID >= 1 (after pre-loaded classes)");
		assertNotEquals(0, newId);
	}
}
