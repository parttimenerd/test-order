package me.bechberger.testorder.ops.workflows;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import javax.tools.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.ops.PluginContext;
import me.bechberger.testorder.ops.PluginLog;

/**
 * End-to-end integration coverage for the bytecode-aware steps wired into
 * {@link ChangeAnalysis#analyze}. Verifies the missing verification step from
 * the original bytecode-aware change detection plan: that a bytecode-only
 * change (source unchanged on disk) flows through the analyzer and shows up in
 * {@code Result.changedClasses()}, and that the dependency-map augmenter fills
 * in edges that the recorded {@code DependencyMap} is missing.
 */
class ChangeAnalysisBytecodeIntegrationTest {

	@TempDir
	Path tempDir;

	@Test
	void bytecodeOnlyChangeFlowsThroughAnalyze() throws Exception {
		Path classesDir = Files.createDirectory(tempDir.resolve("classes"));
		Path testClassesDir = Files.createDirectory(tempDir.resolve("test-classes"));

		// v1: compile production class with one body, write to classesDir.
		compile(classesDir, "package com.example; public class Prod { public int x() { return 1; } }");

		// Build a minimal index with a single test that depends on Prod.
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.ProdTest", Set.of("com.example.Prod"));
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		depMap.save(indexFile);

		PluginContext ctx = baseContext(indexFile, classesDir, testClassesDir);

		// First run: writes the bytecode snapshot, no changes yet.
		ChangeAnalysis.Result first = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);
		assertTrue(first.changedClasses().isEmpty(),
				"first analyze must not flag changes (snapshot fresh): " + first.changedClasses());
		assertTrue(Files.exists(ctx.bytecodeHashFile()), "bytecode-hashes file must be written on first run");

		// Recompile the class with a different body. Nothing else on disk has
		// changed: source hashes file is empty/unchanged, no .java moved, no git
		// state — only the bytecode now differs from the persisted snapshot.
		compile(classesDir, "package com.example; public class Prod { public int x() { return 999; } }");

		ChangeAnalysis.Result second = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);
		assertTrue(second.changedClasses().contains("com.example.Prod"),
				"bytecode-only change must flow into changedClasses: " + second.changedClasses());
	}

	/**
	 * BUG-165: when the user supplies an explicit non-empty changed-class list,
	 * that list is authoritative and bytecode-detected classes must NOT be unioned
	 * in. Otherwise a stale bytecode snapshot (e.g. after a recompile) contaminates
	 * {@code changedClasses()} with unrelated classes — observed in the field as a
	 * phantom {@code Printer$Pretty} appearing when only {@code Attributes} was
	 * requested. Contrast with {@link #bytecodeOnlyChangeFlowsThroughAnalyze()}
	 * where the explicit list is EMPTY and bytecode detection is the intended
	 * fallback signal.
	 */
	@Test
	void explicitChangedClassesSuppressBytecodeUnion() throws Exception {
		Path classesDir = Files.createDirectory(tempDir.resolve("classes"));
		Path testClassesDir = Files.createDirectory(tempDir.resolve("test-classes"));

		compile(classesDir, "package com.example; public class Prod { public int x() { return 1; } }");
		compile(classesDir, "package com.example; public class Other { public int y() { return 2; } }");

		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.ProdTest", Set.of("com.example.Prod"));
		depMap.put("com.example.OtherTest", Set.of("com.example.Other"));
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		depMap.save(indexFile);

		// changeMode=explicit but with a NON-EMPTY explicit list (only Prod).
		PluginContext ctx = PluginContext.builder().projectRoot(tempDir).sourceRoot(tempDir.resolve("src/main/java"))
				.testSourceRoot(tempDir.resolve("src/test/java")).indexFile(indexFile)
				.stateFile(tempDir.resolve("state.lz4")).classesDir(classesDir).testClassesDir(testClassesDir)
				.hashFile(tempDir.resolve("hashes.lz4")).testHashFile(tempDir.resolve("test-hashes.lz4"))
				.bytecodeHashFile(tempDir.resolve("bytecode-hashes.lz4")).changeMode("explicit")
				.changedClasses("com.example.Prod").changedTestClasses("").log(PluginLog.NOOP).build();

		// First run seeds the bytecode snapshot.
		ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);

		// Recompile Other so its bytecode differs from the seeded snapshot. The
		// user still only asked for Prod.
		compile(classesDir, "package com.example; public class Other { public int y() { return 999; } }");

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);
		assertTrue(result.changedClasses().contains("com.example.Prod"),
				"explicit class must be present: " + result.changedClasses());
		assertFalse(result.changedClasses().contains("com.example.Other"),
				"bytecode-detected class must NOT contaminate an explicit non-empty list: " + result.changedClasses());
		assertEquals(Set.of("com.example.Prod"), result.changedClasses(),
				"explicit non-empty list must be authoritative: " + result.changedClasses());
	}

	@Test
	void augmenterPopulatesMissingEdge() throws Exception {
		Path classesDir = Files.createDirectory(tempDir.resolve("classes"));
		Path testClassesDir = Files.createDirectory(tempDir.resolve("test-classes"));

		// Two production classes; a test that touches both in bytecode.
		compile(classesDir, "package com.example; public class Prod1 { public int x() { return 1; } }");
		compile(classesDir, "package com.example; public class Prod2 { public int y() { return 2; } }");
		compile(testClassesDir, List.of(classesDir), "package com.example; public class ProdTest {"
				+ "  public void run() { new Prod1().x(); new Prod2().y(); }" + "}");

		// Recorded DependencyMap only knows about Prod1 — Prod2 is the gap.
		DependencyMap depMap = new DependencyMap();
		depMap.put("com.example.ProdTest", Set.of("com.example.Prod1"));
		Path indexFile = tempDir.resolve("test-dependencies.lz4");
		depMap.save(indexFile);

		PluginContext ctx = baseContext(indexFile, classesDir, testClassesDir);

		ChangeAnalysis.Result result = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.CHANGES_ONLY);
		Set<String> deps = result.depMap().get("com.example.ProdTest");
		assertTrue(deps.contains("com.example.Prod1"), "Prod1 must remain (augment-only): " + deps);
		assertTrue(deps.contains("com.example.Prod2"), "Prod2 must be added by augmenter: " + deps);
	}

	// ── helpers ──────────────────────────────────────────────────────

	private PluginContext baseContext(Path indexFile, Path classesDir, Path testClassesDir) {
		// changeMode=explicit + empty changedClasses → source-side detection
		// returns empty, so any signal in changedClasses() must come from the
		// bytecode path. sourceRoot/testSourceRoot must still be non-null because
		// the Kotlin sibling check resolveSibling("kotlin") runs unconditionally
		// (ChangeDetectionOps.java:166). Hash files don't need to pre-exist.
		Path sourceRoot = tempDir.resolve("src/main/java");
		Path testSourceRoot = tempDir.resolve("src/test/java");
		return PluginContext.builder().projectRoot(tempDir).sourceRoot(sourceRoot).testSourceRoot(testSourceRoot)
				.indexFile(indexFile).stateFile(tempDir.resolve("state.lz4")).classesDir(classesDir)
				.testClassesDir(testClassesDir).hashFile(tempDir.resolve("hashes.lz4"))
				.testHashFile(tempDir.resolve("test-hashes.lz4"))
				.bytecodeHashFile(tempDir.resolve("bytecode-hashes.lz4")).changeMode("explicit").changedClasses("")
				.changedTestClasses("").log(PluginLog.NOOP).build();
	}

	private static void compile(Path dst, String... sources) throws Exception {
		compile(dst, List.of(), sources);
	}

	private static void compile(Path dst, List<Path> classpath, String... sources) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "javax.tools.JavaCompiler not available (run with JDK)");
		List<JavaFileObject> units = new ArrayList<>();
		for (String src : sources) {
			units.add(new InMemorySource(extractClassName(src), src));
		}
		StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
		fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(dst.toFile()));
		if (!classpath.isEmpty()) {
			List<java.io.File> cp = new ArrayList<>();
			for (Path p : classpath) {
				cp.add(p.toFile());
			}
			fm.setLocation(StandardLocation.CLASS_PATH, cp);
		}
		DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<>();
		boolean ok = compiler.getTask(null, fm, diag, List.of("-source", "11", "-target", "11"), null, units).call();
		if (!ok) {
			StringBuilder msg = new StringBuilder("Compilation failed:\n");
			for (var d : diag.getDiagnostics()) {
				msg.append(d).append('\n');
			}
			fail(msg.toString());
		}
	}

	private static String extractClassName(String src) {
		String pkg = "";
		// Tokenize by both ';' and line breaks so single-line and multi-line
		// sources both work.
		for (String raw : src.split(";|\\R")) {
			String line = raw.trim();
			if (line.startsWith("package ")) {
				pkg = line.substring("package ".length()).trim();
				continue;
			}
			boolean isClass = line.startsWith("public class ") || line.startsWith("class ");
			if (!isClass) {
				continue;
			}
			String[] parts = line.split("\\s+");
			for (int i = 0; i < parts.length - 1; i++) {
				if (parts[i].equals("class")) {
					String name = parts[i + 1].replaceAll("[{<].*", "");
					return pkg.isEmpty() ? name : pkg + "." + name;
				}
			}
		}
		throw new IllegalArgumentException("could not extract class name from: " + src);
	}

	private static class InMemorySource extends SimpleJavaFileObject {
		private final String code;

		InMemorySource(String name, String code) {
			super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE);
			this.code = code;
		}

		@Override
		public CharSequence getCharContent(boolean ignoreEncodingErrors) {
			return code;
		}
	}
}
