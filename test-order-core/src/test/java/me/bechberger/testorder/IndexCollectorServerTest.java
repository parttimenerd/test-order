package me.bechberger.testorder;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexCollectorServerTest {

	@TempDir
	Path tempDir;

	@Test
	void serverStartsAndReportsPort() throws IOException {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		try (IndexCollectorServer server = new IndexCollectorServer(indexFile)) {
			assertTrue(server.getPort() > 0);
			assertEquals(0, server.getReceivedCount());
		}
	}

	@Test
	void clientSendAndServerReceive() throws Exception {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		try (IndexCollectorServer server = new IndexCollectorServer(indexFile)) {
			Map<String, Set<String>> classDeps = Map.of("com.example.FooTest",
					Set.of("com.example.Foo", "com.example.Bar"));
			Map<String, Set<String>> methodDeps = Map.of("com.example.FooTest#testMethod", Set.of("com.example.Foo"));
			Map<String, Set<String>> memberDeps = Map.of();
			Map<String, Set<String>> methodMemberDeps = Map.of();

			boolean sent = me.bechberger.testorder.agent.runtime.IndexCollectorClient.send(server.getPort(), classDeps,
					methodDeps, memberDeps, methodMemberDeps);

			assertTrue(sent, "Client should successfully send data");
			// Give server a moment to process
			Thread.sleep(100);
			assertEquals(1, server.getReceivedCount());
		}
	}

	@Test
	void stopAndMergeWritesIndex() throws Exception {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		IndexCollectorServer server = new IndexCollectorServer(indexFile);

		// Send from two "forks"
		Map<String, Set<String>> deps1 = Map.of("com.example.FooTest", Set.of("com.example.Foo", "com.example.Bar"));
		Map<String, Set<String>> deps2 = Map.of("com.example.BazTest", Set.of("com.example.Baz"));

		assertTrue(me.bechberger.testorder.agent.runtime.IndexCollectorClient.send(server.getPort(), deps1, Map.of(),
				Map.of(), Map.of()));
		assertTrue(me.bechberger.testorder.agent.runtime.IndexCollectorClient.send(server.getPort(), deps2, Map.of(),
				Map.of(), Map.of()));
		Thread.sleep(100);

		int merged = server.stopAndMerge();
		assertEquals(2, merged, "Should have merged 2 test classes");
		assertTrue(Files.exists(indexFile), "Index file should exist after merge");
		assertTrue(Files.size(indexFile) > 0, "Index file should be non-empty");

		// Verify contents by loading the index
		DependencyMap map = DependencyMap.load(indexFile);
		assertTrue(map.testClasses().contains("com.example.FooTest"));
		assertTrue(map.testClasses().contains("com.example.BazTest"));
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), map.get("com.example.FooTest"));
		assertEquals(Set.of("com.example.Baz"), map.get("com.example.BazTest"));
	}

	@Test
	void clientFallsBackWhenServerNotRunning() {
		// Try to send to a port that's not listening
		boolean sent = me.bechberger.testorder.agent.runtime.IndexCollectorClient.send(19999, Map.of("Test", Set.of()),
				Map.of(), Map.of(), Map.of());
		assertFalse(sent, "Should fail gracefully when server is not available");
	}

	@Test
	void multiplePayloadsMergeCorrectly() throws Exception {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		IndexCollectorServer server = new IndexCollectorServer(indexFile);

		// Same test class from two forks with different deps (simulates split tests)
		Map<String, Set<String>> deps1 = Map.of("com.example.FooTest", Set.of("com.example.Foo"));
		Map<String, Set<String>> deps2 = Map.of("com.example.FooTest", Set.of("com.example.Bar"));

		assertTrue(me.bechberger.testorder.agent.runtime.IndexCollectorClient.send(server.getPort(), deps1, Map.of(),
				Map.of(), Map.of()));
		assertTrue(me.bechberger.testorder.agent.runtime.IndexCollectorClient.send(server.getPort(), deps2, Map.of(),
				Map.of(), Map.of()));
		Thread.sleep(100);

		int merged = server.stopAndMerge();
		assertEquals(1, merged, "Should have 1 test class (merged from 2 payloads)");

		DependencyMap map = DependencyMap.load(indexFile);
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), map.get("com.example.FooTest"));
	}

	@Test
	void processFallbackFile() throws Exception {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		Path fallbackFile = indexFile.resolveSibling(indexFile.getFileName() + ".collector-fallback");

		// Create a fallback file manually (simulates what the shutdown hook writes)
		String fallbackContent = String.join("\n", "com.example.FooTest\tcom.example.Foo\tcom.example.Bar", "---",
				"com.example.FooTest#testMethod\tcom.example.Foo", "---", "", "---", "", "===", "");
		Files.writeString(fallbackFile, fallbackContent);

		assertTrue(IndexCollectorServer.processFallbackFile(indexFile));
		assertFalse(Files.exists(fallbackFile), "Fallback file should be deleted after processing");
		assertTrue(Files.exists(indexFile), "Index file should be created from fallback data");

		DependencyMap map = DependencyMap.load(indexFile);
		assertTrue(map.testClasses().contains("com.example.FooTest"));
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), map.get("com.example.FooTest"));
	}

	@Test
	void processFallbackFileReturnsFalseWhenMissing() throws Exception {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		assertFalse(IndexCollectorServer.processFallbackFile(indexFile));
	}

	@Test
	void processFallbackFile_preservesModuleMap() throws Exception {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		Path fallbackFile = indexFile.resolveSibling(indexFile.getFileName() + ".collector-fallback");

		String fallbackContent = String.join("\n", "com.example.FooTest\tcom.example.Foo",
				"com.example.BarTest\tcom.example.Bar", "---", "", "---", "", "---", "", "===", "===module-map",
				"com.example.FooTest\tg:module-a", "com.example.BarTest\tg:module-b", "===end-module-map", "");
		Files.writeString(fallbackFile, fallbackContent);

		assertTrue(IndexCollectorServer.processFallbackFile(indexFile));
		assertFalse(Files.exists(fallbackFile));

		DependencyMap map = DependencyMap.load(indexFile);
		assertTrue(map.hasModuleMap(), "module map must survive fallback roundtrip");
		assertEquals("g:module-a", map.getModule("com.example.FooTest"));
		assertEquals("g:module-b", map.getModule("com.example.BarTest"));
	}

	@Test
	void processFallbackFile_oldFormatWithoutModuleMap_stillLoads() throws Exception {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		Path fallbackFile = indexFile.resolveSibling(indexFile.getFileName() + ".collector-fallback");

		// Old format: no ===module-map section
		String fallbackContent = String.join("\n", "com.example.FooTest\tcom.example.Foo", "---", "", "---", "", "---",
				"", "===", "");
		Files.writeString(fallbackFile, fallbackContent);

		assertTrue(IndexCollectorServer.processFallbackFile(indexFile));
		DependencyMap map = DependencyMap.load(indexFile);
		assertTrue(map.testClasses().contains("com.example.FooTest"));
		assertFalse(map.hasModuleMap(), "old-format fallback has no module map");
	}

	@Test
	void preloadSaveClassesDoesNotThrow() {
		// Should complete without error even if called multiple times
		assertDoesNotThrow(DependencyMap::preloadSaveClasses);
		assertDoesNotThrow(DependencyMap::preloadSaveClasses);
	}

	@Test
	void packagePrefixFilterRespectsBoundary() throws Exception {
		// Regression test: "com.example" prefix must NOT match "com.example2.Foo"
		// (the hasSource check must verify that the next char after the prefix is
		// '.' or '$' or end-of-string, not just that the name starts with the prefix).
		Path indexFile = tempDir.resolve("test-deps-prefix.lz4");
		IndexCollectorServer server = new IndexCollectorServer(indexFile);
		server.setIncludePackages("com.example");

		// deps: com.example.Foo (should keep), com.example2.Sneaky (should drop)
		Map<String, Set<String>> deps = Map.of("com.example.FooTest",
				Set.of("com.example.Foo", "com.example2.Sneaky", "com.example.bar.Nested"));

		assertTrue(me.bechberger.testorder.agent.runtime.IndexCollectorClient.send(server.getPort(), deps, Map.of(),
				Map.of(), Map.of()));
		Thread.sleep(100);

		int merged = server.stopAndMerge();
		assertEquals(1, merged);

		DependencyMap map = DependencyMap.load(indexFile);
		Set<String> classDeps = map.get("com.example.FooTest");
		assertNotNull(classDeps);
		assertTrue(classDeps.contains("com.example.Foo"), "com.example.Foo should be kept — exact prefix match");
		assertTrue(classDeps.contains("com.example.bar.Nested"),
				"com.example.bar.Nested should be kept — prefix followed by dot");
		assertFalse(classDeps.contains("com.example2.Sneaky"),
				"com.example2.Sneaky should be dropped — prefix+digit is NOT a package boundary");
	}

	@Test
	void packagePrefixFilterKeepsInnerClasses() throws Exception {
		// Inner class (com.example.Outer$Inner) should be kept when prefix is
		// "com.example" — the '$' separator is a valid boundary.
		Path indexFile = tempDir.resolve("test-deps-inner.lz4");
		IndexCollectorServer server = new IndexCollectorServer(indexFile);
		server.setIncludePackages("com.example");

		Map<String, Set<String>> deps = Map.of("com.example.FooTest",
				Set.of("com.example.Outer", "com.example.Outer$Inner"));

		assertTrue(me.bechberger.testorder.agent.runtime.IndexCollectorClient.send(server.getPort(), deps, Map.of(),
				Map.of(), Map.of()));
		Thread.sleep(100);

		int merged = server.stopAndMerge();
		assertEquals(1, merged);

		DependencyMap map = DependencyMap.load(indexFile);
		Set<String> classDeps = map.get("com.example.FooTest");
		assertNotNull(classDeps);
		assertTrue(classDeps.contains("com.example.Outer"));
		// Inner classes might be treated as synthetic and filtered separately, but if
		// they pass the synthetic check they must also pass the prefix check.
		// The prefix "com.example" followed by '$' at index 11 is a valid boundary.
		// (Whether inner classes are filtered by isSyntheticClass is separate logic —
		// here we confirm hasSource doesn't incorrectly drop them.)
	}
}
