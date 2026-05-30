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
 * Synthetic-history integration test for {@code testorder.auto.alwaysLearn}.
 *
 * <p>
 * Simulates the multi-run lifecycle: an "instrumented run" drops {@code .deps}
 * files into the deps directory, then on the next auto-mode invocation
 * {@link AutoWorkflow#execute()} folds them into the existing index via the
 * pre-mode-resolution incremental aggregation hook. Exercises three scenarios a
 * real project would hit:
 * <ol>
 * <li>fresh deps from a previously-unseen test class are merged in,</li>
 * <li>updated deps for an existing test class are unioned (never lost),</li>
 * <li>a test that wasn't re-instrumented this run is preserved across runs (the
 * whole point of incremental over replace).</li>
 * </ol>
 */
class AutoWorkflowAlwaysLearnHistoryTest {

	@TempDir
	Path tempDir;

	private Path indexFile;
	private Path depsDir;

	private PluginContext makeCtx(boolean alwaysLearn) {
		return PluginContext.builder().projectRoot(tempDir).sourceRoot(tempDir.resolve("src/main/java"))
				.testSourceRoot(tempDir.resolve("src/test/java")).indexFile(indexFile)
				.stateFile(tempDir.resolve("state.lz4")).hashFile(tempDir.resolve("hashes.lz4"))
				.testHashFile(tempDir.resolve("test-hashes.lz4")).changeMode("explicit").changedClasses("")
				.changedTestClasses("").alwaysLearn(alwaysLearn).log(PluginLog.NOOP).build();
	}

	private void simulateInstrumentedRun(String testClass, Set<String> deps) throws IOException {
		Files.writeString(depsDir.resolve(testClass + ".deps"), String.join("\n", deps) + "\n");
	}

	private void clearDepsDir() throws IOException {
		try (var s = Files.list(depsDir)) {
			s.forEach(p -> {
				try {
					Files.delete(p);
				} catch (IOException ignored) {
				}
			});
		}
	}

	@Test
	void multiRunHistoryGrowsAndPreservesIndex() throws IOException {
		indexFile = tempDir.resolve("test-dependencies.lz4");
		depsDir = Files.createDirectory(tempDir.resolve("deps"));

		// ── Run 0: full learn — seed index with two tests covering A and B.
		// Simulated by directly building the index (real flow: explicit learn goal).
		DependencyMap seed = new DependencyMap();
		seed.put("com.example.FooTest", Set.of("com.example.A"));
		seed.put("com.example.BarTest", Set.of("com.example.B"));
		seed.save(indexFile);
		assertEquals(2, DependencyMap.load(indexFile).size());

		// ── Run 1: alwaysLearn=true, instrumented run records FooTest now also
		// touches a new class C (e.g. user added a call). BarTest is NOT
		// re-instrumented (selectiveLearn would have pruned it).
		simulateInstrumentedRun("com.example.FooTest", Set.of("com.example.A", "com.example.C"));
		// The "next auto run" then folds the deps into the index.
		new AutoWorkflow(makeCtx(true), "skip", null, depsDir).execute();

		DependencyMap afterRun1 = DependencyMap.load(indexFile);
		assertEquals(2, afterRun1.size(), "BarTest must be preserved across runs (incremental, not replace)");
		assertTrue(afterRun1.get("com.example.FooTest").contains("com.example.A"), "FooTest A retained");
		assertTrue(afterRun1.get("com.example.FooTest").contains("com.example.C"), "FooTest C added via merge");
		assertTrue(afterRun1.get("com.example.BarTest").contains("com.example.B"),
				"BarTest B preserved (not re-instrumented)");

		// ── Run 2: clear last run's .deps (the agent rewrites them per run),
		// then a new test class BazTest appears and gets instrumented.
		clearDepsDir();
		simulateInstrumentedRun("com.example.BazTest", Set.of("com.example.D"));
		new AutoWorkflow(makeCtx(true), "skip", null, depsDir).execute();

		DependencyMap afterRun2 = DependencyMap.load(indexFile);
		assertEquals(3, afterRun2.size(), "Index now covers 3 test classes");
		assertTrue(afterRun2.get("com.example.BazTest").contains("com.example.D"), "BazTest D recorded");
		// Older entries from run 0 / run 1 still there
		assertTrue(afterRun2.get("com.example.FooTest").contains("com.example.C"),
				"FooTest's run-1 entry survives run 2");
		assertTrue(afterRun2.get("com.example.BarTest").contains("com.example.B"),
				"BarTest still preserved across two more runs");

		// ── Run 3: same code, no .deps written this time (e.g. selective learn
		// detected no source changes and wrote an empty uncertain-classes file,
		// so nothing got instrumented). Pre-aggregation should be a no-op.
		clearDepsDir();
		new AutoWorkflow(makeCtx(true), "skip", null, depsDir).execute();

		DependencyMap afterRun3 = DependencyMap.load(indexFile);
		assertEquals(3, afterRun3.size(), "No-deps run must leave index untouched");
	}

	@Test
	void alwaysLearnDisabledDoesNotMergeDespiteHistory() throws IOException {
		indexFile = tempDir.resolve("test-dependencies.lz4");
		depsDir = Files.createDirectory(tempDir.resolve("deps"));

		DependencyMap seed = new DependencyMap();
		seed.put("com.example.FooTest", Set.of("com.example.A"));
		seed.save(indexFile);

		// Three "instrumented runs" worth of deps accumulated, but alwaysLearn=false
		// so AutoWorkflow must NOT touch the index.
		for (int i = 0; i < 3; i++) {
			clearDepsDir();
			simulateInstrumentedRun("com.example.NewTest" + i, Set.of("com.example.New" + i));
			new AutoWorkflow(makeCtx(false), "skip", null, depsDir).execute();
		}

		DependencyMap untouched = DependencyMap.load(indexFile);
		assertEquals(1, untouched.size(), "alwaysLearn=false: index must remain at its seed state");
		assertTrue(untouched.get("com.example.FooTest").contains("com.example.A"));
	}

	@Test
	void emptyDepsDirAcrossRunsIsBenign() throws IOException {
		indexFile = tempDir.resolve("test-dependencies.lz4");
		depsDir = Files.createDirectory(tempDir.resolve("deps"));

		DependencyMap seed = new DependencyMap();
		seed.put("com.example.FooTest", Set.of("com.example.A"));
		seed.save(indexFile);

		// Many empty runs in a row should not corrupt or grow the index.
		for (int i = 0; i < 10; i++) {
			new AutoWorkflow(makeCtx(true), "skip", null, depsDir).execute();
		}

		DependencyMap stable = DependencyMap.load(indexFile);
		assertEquals(1, stable.size());
		assertTrue(stable.get("com.example.FooTest").contains("com.example.A"));
	}
}
