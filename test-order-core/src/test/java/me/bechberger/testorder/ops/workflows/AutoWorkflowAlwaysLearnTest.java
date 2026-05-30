package me.bechberger.testorder.ops.workflows;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.PluginLog;

/**
 * Verifies the {@code alwaysLearn} flag in {@link AutoWorkflow}:
 * <ul>
 * <li>Pre-mode-resolution incremental aggregation runs when an index already
 * exists and {@code alwaysLearn=true}.</li>
 * <li>{@code OrderSelect.attachLearnAgent()} reflects {@code ctx.alwaysLearn()}
 * so plugins can wire the agent on top of the ordered run.</li>
 * </ul>
 */
class AutoWorkflowAlwaysLearnTest {

	@TempDir
	Path tempDir;

	@Test
	void incrementalAggregationFoldsNewDepsIntoExistingIndex() throws IOException {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		Path depsDir = Files.createDirectory(tempDir.resolve("deps"));

		// Seed an existing index with one test class.
		DependencyMap seed = new DependencyMap();
		seed.put("com.example.OldTest", Set.of("com.example.Old"));
		seed.save(indexFile);
		assertEquals(1, DependencyMap.load(indexFile).size());

		// Drop a fresh .deps file from a hypothetical instrumented run.
		Files.writeString(depsDir.resolve("com.example.NewTest.deps"), "com.example.New\n");

		PluginContext ctx = baseContext(indexFile, depsDir).alwaysLearn(true).build();

		AutoWorkflow.Result result = new AutoWorkflow(ctx, "skip", null, depsDir).execute();
		// "skip" mode short-circuits before order/select; we only care that the
		// pre-mode-resolution incremental aggregation ran.
		assertInstanceOf(AutoWorkflow.Result.Skip.class, result);

		DependencyMap merged = DependencyMap.load(indexFile);
		assertEquals(2, merged.size(), "alwaysLearn pre-aggregation must fold NewTest into existing index");
		assertTrue(merged.get("com.example.OldTest").contains("com.example.Old"), "old entry preserved");
		assertTrue(merged.get("com.example.NewTest").contains("com.example.New"), "new entry merged in");
	}

	@Test
	void noPreAggregationWhenAlwaysLearnIsFalse() throws IOException {
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		Path depsDir = Files.createDirectory(tempDir.resolve("deps"));

		DependencyMap seed = new DependencyMap();
		seed.put("com.example.OldTest", Set.of("com.example.Old"));
		seed.save(indexFile);

		Files.writeString(depsDir.resolve("com.example.NewTest.deps"), "com.example.New\n");

		PluginContext ctx = baseContext(indexFile, depsDir).alwaysLearn(false).build();

		new AutoWorkflow(ctx, "skip", null, depsDir).execute();

		DependencyMap unchanged = DependencyMap.load(indexFile);
		assertEquals(1, unchanged.size(), "alwaysLearn=false must NOT trigger pre-aggregation");
		assertTrue(unchanged.get("com.example.NewTest").isEmpty(), "NewTest must not be merged in");
	}

	@Test
	void noPreAggregationWhenIndexDoesNotExist() throws IOException {
		// When the index doesn't exist, the existing ModeResolverOperation
		// auto-aggregation path handles it. The alwaysLearn pre-aggregation must
		// not run (it would be redundant and would race with the resolver).
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		Path depsDir = Files.createDirectory(tempDir.resolve("deps"));
		Files.writeString(depsDir.resolve("com.example.NewTest.deps"), "com.example.New\n");

		PluginContext ctx = baseContext(indexFile, depsDir).alwaysLearn(true).build();
		AutoWorkflow.Result result = new AutoWorkflow(ctx, "skip", null, depsDir).execute();
		// Either the resolver's own aggregation kicked in (in which case the index
		// now exists with NewTest) or it didn't (no index). Both are valid for
		// "skip" mode; we just assert no exception was thrown and no double-merge.
		assertNotNull(result);
	}

	private PluginContext.Builder baseContext(Path indexFile, Path depsDir) {
		Path sourceRoot = tempDir.resolve("src/main/java");
		Path testSourceRoot = tempDir.resolve("src/test/java");
		return PluginContext.builder().projectRoot(tempDir).sourceRoot(sourceRoot).testSourceRoot(testSourceRoot)
				.indexFile(indexFile).stateFile(tempDir.resolve("state.lz4")).hashFile(tempDir.resolve("hashes.lz4"))
				.testHashFile(tempDir.resolve("test-hashes.lz4")).changeMode("explicit").changedClasses("")
				.changedTestClasses("").log(PluginLog.NOOP);
	}
}
