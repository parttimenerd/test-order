package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.ops.ReactorOrderOperation.ModuleScore;
import me.bechberger.testorder.ops.ReactorOrderOperation.ReactorOrderInput;
import me.bechberger.testorder.ops.ReactorOrderOperation.ReactorOrderResult;

/**
 * Integration tests for {@link ReactorOrderOperation#compute} covering
 * per-module urgency scoring.
 *
 * <p>
 * BUG-185: when a module has compiled test classes on disk but none of them are
 * in the dependency index (e.g. the module has never been included in a learn
 * pass), {@code totalTestCount} was incorrectly set to {@code 0}. The operator
 * then had no indication the module even had tests, and could not distinguish
 * it from a module that genuinely has an empty test directory.
 */
class ReactorOrderOperationTest {

	@TempDir
	Path tempDir;

	/** Creates a minimal DependencyMap with one test class and saves it. */
	private Path saveMinimalIndex(String testClass) throws IOException {
		DependencyMap map = new DependencyMap();
		map.put(testClass, Set.of("app.Service"));
		Path indexFile = tempDir.resolve("index.lz4");
		map.save(indexFile);
		return indexFile;
	}

	/** Creates a fake compiled class file under the given dir. */
	private void createFakeClassFile(Path testClassesDir, String fqcn) throws IOException {
		String[] parts = fqcn.split("\\.");
		String className = parts[parts.length - 1];
		String[] packageParts = java.util.Arrays.copyOf(parts, parts.length - 1);
		Path pkg = testClassesDir;
		for (String p : packageParts) {
			pkg = pkg.resolve(p);
		}
		Files.createDirectories(pkg);
		Files.write(pkg.resolve(className + ".class"), new byte[0]);
	}

	private PluginLog noopLog() {
		return PluginLog.NOOP;
	}

	/**
	 * BUG-185: a module whose compiled tests are not yet in the dependency index
	 * must report {@code totalTestCount = N} (the count of compiled classes found
	 * on disk), not 0. Before the fix it was reported as 0, making it
	 * indistinguishable from a truly empty module.
	 */
	@Test
	void unlearnedModule_reportsDiskTestCountNotZero() throws IOException {
		// Index file contains only module-a's test
		Path indexFile = saveMinimalIndex("com.a.FooTest");
		Path stateFile = tempDir.resolve("state.lz4");

		// module-a: in index, one compiled class
		Path moduleADir = tempDir.resolve("module-a");
		createFakeClassFile(moduleADir, "com.a.FooTest");

		// module-b: NOT in index, but has two compiled test classes on disk
		Path moduleBDir = tempDir.resolve("module-b");
		createFakeClassFile(moduleBDir, "com.b.BarTest");
		createFakeClassFile(moduleBDir, "com.b.BazTest");

		Map<String, Path> moduleTestDirs = new LinkedHashMap<>();
		moduleTestDirs.put("g:module-a", moduleADir);
		moduleTestDirs.put("g:module-b", moduleBDir);

		ReactorOrderInput input = new ReactorOrderInput(indexFile, stateFile, Set.of(), Set.of(), moduleTestDirs, null,
				5, noopLog());
		ReactorOrderResult result = ReactorOrderOperation.compute(input);

		ModuleScore moduleA = result.moduleScores().stream().filter(m -> m.moduleId().equals("g:module-a")).findFirst()
				.orElseThrow();
		ModuleScore moduleB = result.moduleScores().stream().filter(m -> m.moduleId().equals("g:module-b")).findFirst()
				.orElseThrow();

		// module-a: in index, total = 1 (or however many were learned)
		assertTrue(moduleA.totalTestCount() > 0, "module in dep map must have positive totalTestCount");

		// module-b: not in index, but 2 compiled classes on disk — must report 2, not 0
		assertEquals(2, moduleB.totalTestCount(),
				"unlearned module must report its on-disk test class count as totalTestCount (BUG-185)");
		assertEquals(0, moduleB.affectedTestCount(), "unlearned module must have affectedTestCount=0");
		assertEquals(0, moduleB.maxTestScore(), "unlearned module must have maxTestScore=0");
	}

	@Test
	void emptyModule_reportsTotalTestCountOfZero() throws IOException {
		Path indexFile = saveMinimalIndex("com.a.FooTest");
		Path stateFile = tempDir.resolve("state.lz4");

		// module-a: in index, one compiled class
		Path moduleADir = tempDir.resolve("module-a");
		createFakeClassFile(moduleADir, "com.a.FooTest");

		// module-b: no compiled classes at all
		Path moduleBDir = tempDir.resolve("module-b-empty");
		Files.createDirectories(moduleBDir);

		Map<String, Path> moduleTestDirs = new LinkedHashMap<>();
		moduleTestDirs.put("g:module-a", moduleADir);
		moduleTestDirs.put("g:module-b-empty", moduleBDir);

		ReactorOrderInput input = new ReactorOrderInput(indexFile, stateFile, Set.of(), Set.of(), moduleTestDirs, null,
				5, noopLog());
		ReactorOrderResult result = ReactorOrderOperation.compute(input);

		ModuleScore moduleB = result.moduleScores().stream().filter(m -> m.moduleId().equals("g:module-b-empty"))
				.findFirst().orElseThrow();

		assertEquals(0, moduleB.totalTestCount(), "truly empty module must have totalTestCount=0");
	}
}
