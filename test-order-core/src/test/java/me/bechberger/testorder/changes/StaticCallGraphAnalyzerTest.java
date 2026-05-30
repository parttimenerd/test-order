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

	@Test
	@DisplayName("return type creates a class-level edge from the method")
	void returnTypeDiscovered() throws Exception {
		compile("class Response {}", "class Builder { public Response build() { return null; } }");

		var original = classLevelChange("Response");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Builder#build"),
				"Builder#build should be discovered via return type Response; got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("array of changed type in parameter creates an edge")
	void arrayParameterTypeDiscovered() throws Exception {
		compile("class Item {}", "class Processor { public void process(Item[] items) {} }");

		var original = classLevelChange("Item");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Processor#process"),
				"Processor#process should be reached via Item[] parameter; got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("declared throws clause creates an edge from the throwing method")
	void declaredThrowsDiscovered() throws Exception {
		compile("class AppException extends RuntimeException {}",
				"class Service { public void run() throws AppException {} }");

		var original = classLevelChange("AppException");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Service#run"),
				"Service#run should be discovered via throws AppException; got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("catch block exception type creates an edge to the catching method")
	void catchBlockTypeDiscovered() throws Exception {
		compile("class DomainException extends RuntimeException {}",
				"class Handler { public void handle() { try { throw new DomainException(); } catch (DomainException e) { e.getMessage(); } } }");

		var original = classLevelChange("DomainException");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Handler#handle"),
				"Handler#handle should be discovered via catch(DomainException); got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("class literal (Foo.class) creates an edge to the referencing method")
	void classLiteralDiscovered() throws Exception {
		compile("class Meta {}", "class Registry { public void register() { Class<?> c = Meta.class; } }");

		var original = classLevelChange("Meta");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Registry#register"),
				"Registry#register should be discovered via Meta.class literal; got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("static field write (PUTSTATIC) propagates to writer method")
	void staticFieldWriteDiscovered() throws Exception {
		compile("class Config { public static int VALUE = 0; }",
				"class Initializer { public void init() { Config.VALUE = 42; } }");

		// Seed with the static field write target
		var original = new StructuralChangeAnalyzer.ChangedMembers(Set.of("Config"), Set.of(),
				Map.of("Config", Set.of()), Set.of(), Set.of("Config#VALUE"));
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Initializer#init"),
				"Initializer#init should be discovered via PUTSTATIC Config#VALUE; got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("NEW instruction (constructor) creates a class-level edge")
	void newInstructionDiscovered() throws Exception {
		compile("class Widget {}", "class Factory { public Object create() { return new Widget(); } }");

		var original = classLevelChange("Widget");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Factory#create"),
				"Factory#create should be discovered via new Widget(); got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("instanceof check creates an edge to the checking method")
	void instanceofCheckDiscovered() throws Exception {
		compile("class Shape {}", "class Checker { public boolean isShape(Object o) { return o instanceof Shape; } }");

		var original = classLevelChange("Shape");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Checker#isShape"),
				"Checker#isShape should be discovered via instanceof Shape; got " + result.changedMemberKeys());
	}

	@Test
	@DisplayName("degradation: expansion exceeding ratio falls back to class-level")
	void degradationFallback() throws Exception {
		// Create many classes that all call a single Callee.
		// ratio=2 means: if expanded > 2×seed, degrade.
		// Seed: 1 member key. With 5 callers we get 6 keys → 6×seed → degrade at
		// ratio=2.
		compile("class Callee { public void compute() {} }",
				"class C1 { public void a() { new Callee().compute(); } public void b() {} public void c() {} }",
				"class C2 { public void a() { new Callee().compute(); } public void b() {} public void c() {} }",
				"class C3 { public void a() { new Callee().compute(); } public void b() {} public void c() {} }",
				"class C4 { public void a() { new Callee().compute(); } public void b() {} public void c() {} }",
				"class C5 { public void a() { new Callee().compute(); } public void b() {} public void c() {} }");

		var original = changedMembers("Callee#compute");
		var report = StaticCallGraphAnalyzer.expandWithReport(original, List.of(tempDir), 1, 2);

		assertTrue(report.degraded(), "Should be degraded (expanded >> 2× seed)");
		// Fallback should be class-level: Callee at minimum
		assertTrue(report.expanded().changedClasses().contains("Callee"),
				"Fallback should still include original changed class");
	}

	@Test
	@DisplayName("transitive interface chain: change in grandparent interface reaches implementor")
	void transitiveInterfaceChain() throws Exception {
		// GrandIface → SubIface → ImplClass
		compile("interface GrandIface {}", "interface SubIface extends GrandIface {}",
				"class ImplClass implements SubIface { public void run() {} }");

		var original = classLevelChange("GrandIface");
		// Needs depth ≥ 2 to go through SubIface to ImplClass
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 2);

		assertTrue(result.changedClasses().contains("ImplClass"),
				"ImplClass should be reached transitively via SubIface from GrandIface change; got "
						+ result.changedClasses());
	}

	@Test
	@DisplayName("default interface method change reaches implementing class via override propagation")
	void defaultMethodOverridePropagated() throws Exception {
		// MyService declares a default method; Impl overrides it.
		// If MyService#execute (a concrete default method) changes, Impl#execute should
		// be flagged as an override.
		compile("interface MyService { default void execute() {} }",
				"class Impl implements MyService { public void execute() {} }");

		var original = changedMembers("MyService#execute");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Impl#execute"),
				"Impl#execute should be discovered as override of default MyService#execute; got "
						+ result.changedMemberKeys());
	}

	@Test
	@DisplayName("generic return type in signature discovers inner-class type reference")
	void genericReturnTypeWithInnerClassDiscovered() throws Exception {
		// Container$Item is an inner class; the generic signature references it.
		// Changing it should reach Container#getItems via the signature edge.
		compile("import java.util.List;\n" + "class Container {\n" + "  static class Item {}\n"
				+ "  public List<Item> getItems() { return null; }\n" + "}");

		// The inner class is named Container$Item in bytecode
		var original = classLevelChange("Container$Item");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedClasses().contains("Container"),
				"Container should be discovered via generic signature <Container$Item>; got "
						+ result.changedClasses());
	}

	@Test
	@DisplayName("generic return type in signature creates an edge")
	void genericReturnTypeInSignatureDiscovered() throws Exception {
		// The generic signature on getItems() references Element.
		// Even if the return type erasure is Object, the signature attribute contains
		// it.
		compile("class Element {}",
				"import java.util.List;\n" + "class Container { public List<Element> getItems() { return null; } }");

		var original = classLevelChange("Element");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedMemberKeys().contains("Container#getItems"),
				"Container#getItems should be discovered via generic signature <Element>; got "
						+ result.changedMemberKeys());
	}

	// ── Annotation-value edge tests ─────────────────────────────────

	@Test
	@DisplayName("single Class<?> annotation value produces an edge to the referenced class")
	void singleClassAnnotationValueEdge() throws Exception {
		compile("public @interface MyTest { Class<?> value(); }", "class App { public void run() {} }",
				"@MyTest(value = App.class)\nclass TestCase {}");

		var original = changedMembers("App#run");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedClasses().contains("TestCase"),
				"TestCase uses App.class in annotation value; should be reached; got: " + result.changedClasses());
	}

	@Test
	@DisplayName("Class<?>[] annotation array values produce edges to all referenced classes")
	void classArrayAnnotationValueEdges() throws Exception {
		compile("public @interface BootTest { Class<?>[] classes() default {}; }",
				"class MyApp { public void init() {} }", "class Config {}",
				"@BootTest(classes = { MyApp.class, Config.class })\nclass MyTest {}");

		var original = changedMembers("MyApp#init");
		var result = StaticCallGraphAnalyzer.expand(original, List.of(tempDir), 1);

		assertTrue(result.changedClasses().contains("MyTest"),
				"MyTest references MyApp via annotation array; should be reached; got: " + result.changedClasses());
	}

	// ── Test fixture helper ──────────────────────────────────────────

	/** Builds a class-level ChangedMembers from a single FQCN. */
	private static StructuralChangeAnalyzer.ChangedMembers classLevelChange(String className) {
		return new StructuralChangeAnalyzer.ChangedMembers(Set.of(className),
				Set.of(className + "#" + StaticCallGraphAnalyzer.CLASS_MARKER),
				Map.of(className, Set.of(StaticCallGraphAnalyzer.CLASS_MARKER)), Set.of(), Set.of());
	}

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
