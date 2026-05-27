package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;

import javax.tools.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("StaticCallGraphAnalyzer")
class StaticCallGraphAnalyzerTest {

	@TempDir
	Path tempDir;

	// ── Helpers ─────────────────────────────────────────────────────

	/**
	 * Compiles a single Java source string to {@code tempDir} and returns the class
	 * root.
	 */
	private Path compile(String... sources) throws Exception {
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull(compiler, "javax.tools.JavaCompiler not available (run with JDK, not JRE)");

		List<JavaFileObject> units = new ArrayList<>();
		for (String src : sources) {
			// Extract class name from "public class Foo" or "class Foo"
			String name = extractClassName(src);
			units.add(new InMemorySource(name, src));
		}

		StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null);
		fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempDir.toFile()));

		JavaCompiler.CompilationTask task = compiler.getTask(null, fm, null, List.of("-source", "11", "-target", "11"),
				null, units);
		assertTrue(task.call(), "Compilation failed");
		return tempDir;
	}

	private static String extractClassName(String src) {
		// heuristic: find "class Foo", "interface Foo", or "@interface Foo" pattern
		for (String line : src.split("\n")) {
			line = line.trim();
			boolean isAnno = line.contains("@interface ");
			boolean isIface = !isAnno && (line.startsWith("interface ") || line.startsWith("public interface "));
			boolean isClass = line.startsWith("public class ") || line.startsWith("class ");
			if (!(isAnno || isIface || isClass)) {
				continue;
			}
			String[] parts = line.split("\\s+");
			for (int i = 0; i < parts.length - 1; i++) {
				if (parts[i].equals("class") || parts[i].equals("interface") || parts[i].equals("@interface")) {
					return parts[i + 1].replaceAll("[{<].*", "");
				}
			}
		}
		throw new IllegalArgumentException(
				"Could not extract class name from: " + src.substring(0, Math.min(80, src.length())));
	}

	private static class InMemorySource extends SimpleJavaFileObject {
		private final String code;

		InMemorySource(String name, String code) {
			super(URI.create("string:///" + name + ".java"), Kind.SOURCE);
			this.code = code;
		}

		@Override
		public CharSequence getCharContent(boolean ignoreEncodingErrors) {
			return code;
		}
	}

	// ── Tests ────────────────────────────────────────────────────────

	@Test
	@DisplayName("returns original unchanged when no class dirs provided")
	void emptyClassDirsReturnsOriginal() {
		var original = changedMembers("com.example.Foo#bar");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(), 2);
		assertSame(original, result);
	}

	@Test
	@DisplayName("returns original unchanged when depth is 0")
	void zeroDepthReturnsOriginal() {
		var original = changedMembers("com.example.Foo#bar");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 0);
		assertSame(original, result);
	}

	@Test
	@DisplayName("returns original unchanged when changed members is null")
	void nullInputReturnsNull() {
		var result = StaticCallGraphAnalyzer.expand(null, List.of(tempDir), 2);
		assertNull(result);
	}

	@Test
	@DisplayName("direct caller of changed method is discovered (depth 1)")
	void directCallerDiscovered() throws Exception {
		// Caller.doWork() calls Callee.compute()
		// If Callee#compute changes → Caller#doWork should be expanded into the changed
		// set
		compile("class Callee { public int compute() { return 42; } }",
				"class Caller { public void doWork() { new Callee().compute(); } }");

		var original = changedMembers("Callee#compute");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Caller#doWork"),
				"Caller#doWork should be in expanded changed members");
		assertTrue(result.changedClasses().contains("Caller"), "Caller class should be in expanded changed classes");
	}

	@Test
	@DisplayName("indirect caller discovered at depth 2 but not depth 1")
	void transitiveCallerRespectsDepth() throws Exception {
		// A.foo() calls B.bar() calls C.baz()
		// If C#baz changes:
		// depth 1 → B#bar discovered
		// depth 2 → A#foo also discovered
		compile("class C { public void baz() {} }", "class B { public void bar() { new C().baz(); } }",
				"class A { public void foo() { new B().bar(); } }");

		var original = changedMembers("C#baz");

		var depth1 = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);
		assertTrue(depth1.changedMemberKeys().contains("B#bar"), "B#bar should be at depth 1");
		assertFalse(depth1.changedMemberKeys().contains("A#foo"), "A#foo should NOT be at depth 1");

		var depth2 = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 2);
		assertTrue(depth2.changedMemberKeys().contains("B#bar"), "B#bar should be at depth 2");
		assertTrue(depth2.changedMemberKeys().contains("A#foo"), "A#foo should be at depth 2");
	}

	@Test
	@DisplayName("original changed members are preserved in expanded result")
	void originalMembersPreserved() throws Exception {
		compile("class X { public void m() {} }", "class Y { public void n() { new X().m(); } }");

		var original = changedMembers("X#m");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("X#m"), "original member X#m must remain");
	}

	@Test
	@DisplayName("non-existent class dir is silently skipped")
	void nonExistentDirSkipped() {
		var original = changedMembers("foo.Bar#baz");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(Path.of("/no/such/dir")), 2);
		// Should return original unchanged since nothing could be scanned
		assertSame(original, result);
	}

	@Test
	@DisplayName("buildReverseCallGraph handles multiple callers for same callee")
	void multipleCallersForSameCallee() throws Exception {
		compile("class Service { public void handle() {} }",
				"class HandlerA { public void run() { new Service().handle(); } }",
				"class HandlerB { public void run() { new Service().handle(); } }");

		var graph = StaticCallGraphAnalyzer.buildReverseCallGraph(List.of(tempDir));
		Set<String> callers = graph.getOrDefault("Service#handle", Set.of());
		assertTrue(callers.contains("HandlerA#run"), "HandlerA#run should call Service#handle");
		assertTrue(callers.contains("HandlerB#run"), "HandlerB#run should call Service#handle");
	}

	@Test
	@DisplayName("method reference (invokedynamic) caller is discovered")
	void methodReferenceCallerDiscovered() throws Exception {
		// Util::compute referenced via method reference produces an invokedynamic
		// instruction whose bootstrap arguments contain a Handle to Util#compute.
		compile("class Util { public static int compute(int x) { return x + 1; } }",
				"import java.util.function.IntUnaryOperator;\n"
						+ "class RefCaller { public IntUnaryOperator make() { return Util::compute; } }");

		var original = changedMembers("Util#compute");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("RefCaller#make"),
				"RefCaller#make should be discovered via the method reference to Util#compute");
	}

	@Test
	@DisplayName("lambda body capturing a method call is discovered via invokedynamic")
	void lambdaCapturingCallDiscovered() throws Exception {
		// Lambda body desugars to a synthetic method on the enclosing class.
		// The invokedynamic in LambdaCaller#run binds to that synthetic;
		// we should capture the edge from LambdaCaller#run to it indirectly via the
		// regular invokevirtual inside the synthetic. Easier: directly assert that
		// LambdaCaller (in some method) reaches Target#hit via the desugared body.
		compile("class Target { public void hit() {} }", "import java.util.function.Supplier;\n"
				+ "class LambdaCaller { public Supplier<?> make(Target t) { return () -> { t.hit(); return null; }; } }");

		var original = changedMembers("Target#hit");
		// Depth 2 because the lambda body lives in a synthetic method; one hop
		// reaches the synthetic, the next reaches LambdaCaller#make.
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 2);

		assertTrue(result.changedClasses().contains("LambdaCaller"),
				"LambdaCaller should be reached via lambda desugaring; got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("static field read (GETSTATIC) propagates to caller")
	void staticFieldReadDiscovered() throws Exception {
		// Note: a `static final int` initialized with a compile-time constant is
		// inlined, so we use a non-constant initializer to force a real GETSTATIC.
		compile("class Constants { public static int MAX = computeMax(); private static int computeMax() { return 42; } }",
				"class Reader { public int max() { return Constants.MAX; } }");

		// Build a ChangedMembers seeded with a changed static field, not a method.
		var original = new StructuralChangeAnalyzer.ChangedMembers(Set.of("Constants"), Set.of(),
				Map.of("Constants", Set.of()), Set.of(), Set.of("Constants#MAX"));
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Reader#max"),
				"Reader#max should be discovered as a caller of static field Constants#MAX");
	}

	@Test
	@DisplayName("subtype override is propagated when supertype method changes")
	void subtypeOverridePropagated() throws Exception {
		// If Super#run changes, Sub#run (which overrides it) should also be flagged.
		compile("class Super { public void run() {} }", "class Sub extends Super { public void run() {} }",
				"class SubCaller { public void invoke() { new Sub().run(); } }");

		var original = changedMembers("Super#run");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Sub#run"),
				"Sub#run should be propagated as an override of changed Super#run");
	}

	@Test
	@DisplayName("inherited method called via subclass receiver still reaches changed supertype")
	void inheritedMethodCallDiscovered() throws Exception {
		// Sub does NOT declare m(); it inherits from Super.
		// Caller invokes new Sub().m() — bytecode owner is Sub.
		// If Super#m changes, Caller#use should still be discovered because the
		// upward walk in addCallEdge should record an edge from Caller#use to Super#m.
		compile("class Super { public void m() {} }", "class Sub extends Super {}",
				"class InhCaller { public void use() { new Sub().m(); } }");

		var original = changedMembers("Super#m");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("InhCaller#use"),
				"InhCaller#use should reach changed Super#m even though it called Sub.m()");
	}

	@Test
	@DisplayName("annotation on a method makes the method reachable when the annotation type changes")
	void annotationOnMethodDiscovered() throws Exception {
		// If MyAnno changes, AnnotatedTarget#run (which is annotated with it) should
		// be discovered. We seed a class-level change on MyAnno.
		compile("import java.lang.annotation.*;\n" + "@Retention(RetentionPolicy.RUNTIME)\n"
				+ "@interface MyAnno { String value() default \"\"; }",
				"class AnnotatedTarget { @MyAnno(\"x\") public void run() {} }");

		var original = new StructuralChangeAnalyzer.ChangedMembers(Set.of("MyAnno"),
				Set.of("MyAnno#" + StaticCallGraphAnalyzer.CLASS_MARKER),
				Map.of("MyAnno", Set.of(StaticCallGraphAnalyzer.CLASS_MARKER)), Set.of(), Set.of());
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("AnnotatedTarget#run"),
				"AnnotatedTarget#run should be discovered via @MyAnno; got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("method parameter type appears as an edge")
	void methodParameterTypeDiscovered() throws Exception {
		// Param#use(Service) declares Service as a parameter type. Even with no body
		// call (the body is empty), changing Service should reach Param#use.
		compile("class Service {}", "class Param { public void use(Service s) {} }");

		var original = new StructuralChangeAnalyzer.ChangedMembers(Set.of("Service"),
				Set.of("Service#" + StaticCallGraphAnalyzer.CLASS_MARKER),
				Map.of("Service", Set.of(StaticCallGraphAnalyzer.CLASS_MARKER)), Set.of(), Set.of());
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Param#use"),
				"Param#use should be discovered via parameter type Service; got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("declared field type creates a class-level edge")
	void declaredFieldTypeDiscovered() throws Exception {
		// Holder declares a field of type Service. Changing Service should reach
		// Holder at the class level even with no method bodies referring to Service.
		compile("class Service {}", "class Holder { Service svc; }");

		var original = new StructuralChangeAnalyzer.ChangedMembers(Set.of("Service"),
				Set.of("Service#" + StaticCallGraphAnalyzer.CLASS_MARKER),
				Map.of("Service", Set.of(StaticCallGraphAnalyzer.CLASS_MARKER)), Set.of(), Set.of());
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedClasses().contains("Holder"),
				"Holder should be discovered via declared field of type Service; got " + result.changedClasses());
	}

	@Test
	@DisplayName("subclass methods reach changed supertype via supertype edge")
	void supertypeClassEdgeDiscovered() throws Exception {
		// Child extends Base and declares `op()`. Even with no call into Base from
		// Child#op, a class-level change to Base should reach Child#op via the
		// supertype edge.
		compile("class Base {}", "class Child extends Base { public void op() {} }");

		var original = new StructuralChangeAnalyzer.ChangedMembers(Set.of("Base"),
				Set.of("Base#" + StaticCallGraphAnalyzer.CLASS_MARKER),
				Map.of("Base", Set.of(StaticCallGraphAnalyzer.CLASS_MARKER)), Set.of(), Set.of());
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Child#op"),
				"Child#op should be discovered via supertype edge Base -> Child; got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("interface change reaches implementing class methods")
	void interfaceClassEdgeDiscovered() throws Exception {
		// Impl implements MyIface. A change to MyIface (class-level) should reach
		// Impl#run via the interface edge.
		compile("interface MyIface {}", "class Impl implements MyIface { public void run() {} }");

		var original = new StructuralChangeAnalyzer.ChangedMembers(Set.of("MyIface"),
				Set.of("MyIface#" + StaticCallGraphAnalyzer.CLASS_MARKER),
				Map.of("MyIface", Set.of(StaticCallGraphAnalyzer.CLASS_MARKER)), Set.of(), Set.of());
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Impl#run"),
				"Impl#run should be discovered via interface edge MyIface -> Impl; got " + result.changedMemberKeys());
	}

	// ── Test fixture helper ──────────────────────────────────────────

	private static StructuralChangeAnalyzer.ChangedMembers changedMembers(String... memberKeys) {
		Set<String> classes = new LinkedHashSet<>();
		Set<String> keys = new LinkedHashSet<>();
		Map<String, Set<String>> byClass = new LinkedHashMap<>();
		for (String key : memberKeys) {
			int hash = key.lastIndexOf('#');
			String cls = hash > 0 ? key.substring(0, hash) : key;
			String member = hash > 0 ? key.substring(hash + 1) : "";
			keys.add(key);
			classes.add(cls);
			byClass.computeIfAbsent(cls, k -> new LinkedHashSet<>()).add(member);
		}
		return new StructuralChangeAnalyzer.ChangedMembers(classes, keys, byClass, Set.of(), Set.of());
	}
}
