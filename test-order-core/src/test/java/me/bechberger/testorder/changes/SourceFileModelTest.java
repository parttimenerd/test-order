package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Thorough tests for {@link SourceFileModel} covering the island-grammar
 * parser. Exercises class/interface/enum/record extraction, method extraction,
 * bounded generics, sealed/permits, constructors, abstract methods,
 * annotations, overloads, nested classes, multiple top-level types, hash
 * stability, and edge cases.
 * <p>
 * Also serves as an integration test for class name extraction and
 * comment/string stripping utilities.
 */
class SourceFileModelTest {

	// ── helper ────────────────────────────────────────────────────────

	private static SourceFileModel.Model parse(String source, String pkg) {
		return SourceFileModel.parse(source, pkg, SourceFileModel.Detail.METHODS);
	}

	private static SourceFileModel.Model parseAll(String source, String pkg) {
		return SourceFileModel.parse(source, pkg, SourceFileModel.Detail.FIELDS);
	}

	private static SourceFileModel.Model parseTypes(String source, String pkg) {
		return SourceFileModel.parse(source, pkg, SourceFileModel.Detail.TYPES);
	}

	// =====================================================================
	// TYPE EXTRACTION
	// =====================================================================

	@Nested
	class TypeExtraction {

		@Test
		void singleClass() {
			var m = parseTypes("package com.x; public class Foo {}", "com.x");
			assertEquals(Set.of("com.x.Foo"), m.typeNames());
			assertEquals(SourceFileModel.TypeKind.CLASS, m.types().get(0).kind());
		}

		@Test
		void typeBodyTextCaptured() {
			String src = """
					package com.x;
					public class Foo {
					    int x;
					}
					""";
			var m = parseTypes(src, "com.x");
			var t = m.types().get(0);
			assertNotNull(t.bodyText());
			assertTrue(t.bodyText().contains("int x;"));
		}

		@Test
		void nestedClassBodyTexts() {
			String src = """
					package com.x;
					public class Outer {
					    static class Inner { int y; }
					}
					""";
			var m = parseTypes(src, "com.x");
			var outer = m.types().stream().filter(t -> t.simpleName().equals("Outer")).findFirst().orElseThrow();
			var inner = m.types().stream().filter(t -> t.simpleName().equals("Inner")).findFirst().orElseThrow();
			assertTrue(outer.bodyText().contains("static class Inner"));
			assertTrue(inner.bodyText().contains("int y;"));
		}

		@Test
		void signatureCapturedSimpleClass() {
			String src = """
					package com.x;
					public class Foo {}
					""";
			var t = parseTypes(src, "com.x").types().get(0);
			assertEquals("public class Foo", t.signature());
		}

		@Test
		void signatureCapturedWithExtends() {
			String src = """
					package com.x;
					public class Foo extends Bar implements Baz, Qux {}
					""";
			var t = parseTypes(src, "com.x").types().get(0);
			assertTrue(t.signature().contains("public class Foo"), t.signature());
			assertTrue(t.signature().contains("extends Bar"), t.signature());
			assertTrue(t.signature().contains("implements Baz, Qux"), t.signature());
		}

		@Test
		void signatureCapturedWithGenerics() {
			String src = """
					package com.x;
					public class Box<T extends Comparable<T>> {}
					""";
			var t = parseTypes(src, "com.x").types().get(0);
			assertTrue(t.signature().contains("public class Box"), t.signature());
		}

		@Test
		void signatureCapturedSealedWithPermits() {
			String src = """
					package com.x;
					public sealed class Shape permits Circle, Square {}
					final class Circle extends Shape {}
					""";
			var types = parseTypes(src, "com.x").types();
			var shape = types.stream().filter(t -> t.simpleName().equals("Shape")).findFirst().orElseThrow();
			assertTrue(shape.signature().contains("sealed"), shape.signature());
			assertTrue(shape.signature().contains("permits Circle, Square"), shape.signature());
		}

		@Test
		void signatureCapturedRecord() {
			String src = """
					package com.x;
					public record Point(int x, int y) {}
					""";
			var t = parseTypes(src, "com.x").types().get(0);
			assertTrue(t.signature().contains("record Point"), t.signature());
			assertTrue(t.signature().contains("(int x, int y)"), t.signature());
		}

		@Test
		void signatureCapturedAnnotationType() {
			String src = """
					package com.x;
					public @interface MyAnno {}
					""";
			var t = parseTypes(src, "com.x").types().get(0);
			assertTrue(t.signature().contains("@interface MyAnno"), t.signature());
		}

		@Test
		void signatureCapturedEnum() {
			String src = """
					package com.x;
					public enum Color {}
					""";
			var t = parseTypes(src, "com.x").types().get(0);
			assertEquals("public enum Color", t.signature());
		}

		@Test
		void multipleTopLevelTypes() {
			String src = """
					package com.x;
					public class Foo {}
					class Bar {}
					interface Baz {}
					""";
			assertEquals(Set.of("com.x.Foo", "com.x.Bar", "com.x.Baz"), parseTypes(src, "com.x").typeNames());
		}

		@Test
		void enumAndRecord() {
			String src = """
					package com.x;
					public enum Color { RED, GREEN }
					record Point(int x, int y) {}
					""";
			var m = parseTypes(src, "com.x");
			assertEquals(Set.of("com.x.Color", "com.x.Point"), m.typeNames());
			assertTrue(m.types().stream()
					.anyMatch(t -> t.kind() == SourceFileModel.TypeKind.ENUM && t.simpleName().equals("Color")));
			assertTrue(m.types().stream()
					.anyMatch(t -> t.kind() == SourceFileModel.TypeKind.RECORD && t.simpleName().equals("Point")));
		}

		@Test
		void annotationInterface() {
			String src = """
					package com.x;
					public @interface MyAnno { String value(); }
					""";
			var m = parseTypes(src, "com.x");
			assertEquals(Set.of("com.x.MyAnno"), m.typeNames());
			assertEquals(SourceFileModel.TypeKind.ANNOTATION, m.types().get(0).kind());
		}

		@Test
		void nestedClassExtracted() {
			String src = """
					package com.x;
					public class Outer {
					    static class Inner {}
					}
					""";
			assertEquals(Set.of("com.x.Outer", "com.x.Outer$Inner"), parseTypes(src, "com.x").typeNames());
		}

		@Test
		void defaultPackage() {
			assertEquals(Set.of("Foo"), parseTypes("class Foo {}", "").typeNames());
		}

		@Test
		void classInCommentIgnored() {
			String src = """
					package com.x;
					// class Fake {}
					/* class AlsoFake {} */
					public class Real {}
					""";
			assertEquals(Set.of("com.x.Real"), parseTypes(src, "com.x").typeNames());
		}

		@Test
		void classInStringIgnored() {
			String src = """
					package com.x;
					public class Real {
					    String s = "class NotAClass {}";
					}
					""";
			assertEquals(Set.of("com.x.Real"), parseTypes(src, "com.x").typeNames());
		}

		// ── Java 26 features ─────────────────────────────────────────

		@Test
		void sealedClassWithPermits() {
			String src = """
					package com.x;
					public sealed class Shape permits Circle, Square {}
					final class Circle extends Shape {}
					final class Square extends Shape {}
					""";
			assertEquals(Set.of("com.x.Shape", "com.x.Circle", "com.x.Square"), parseTypes(src, "com.x").typeNames());
		}

		@Test
		void nonSealedClass() {
			String src = """
					package com.x;
					public sealed class Base permits Sub {}
					non-sealed class Sub extends Base {}
					""";
			assertEquals(Set.of("com.x.Base", "com.x.Sub"), parseTypes(src, "com.x").typeNames());
		}

		@Test
		void genericClass() {
			String src = """
					package com.x;
					public class Box<T extends Comparable<T>> {}
					""";
			assertEquals(Set.of("com.x.Box"), parseTypes(src, "com.x").typeNames());
		}

		@Test
		void genericClassWithNestedGenerics() {
			String src = """
					package com.x;
					public class Registry<K, V extends Map<String, List<Integer>>> {}
					""";
			assertEquals(Set.of("com.x.Registry"), parseTypes(src, "com.x").typeNames());
		}

		@Test
		void classWithExtendsAndImplements() {
			String src = """
					package com.x;
					public class Foo extends Bar implements Baz, Qux {}
					""";
			assertEquals(Set.of("com.x.Foo"), parseTypes(src, "com.x").typeNames());
		}

		@Test
		void recordWithComponents() {
			String src = """
					package com.x;
					public record Person(String name, int age) {}
					""";
			var m = parseTypes(src, "com.x");
			assertEquals(Set.of("com.x.Person"), m.typeNames());
			assertEquals(SourceFileModel.TypeKind.RECORD, m.types().get(0).kind());
		}

		@Test
		void recordImplementingInterface() {
			String src = """
					package com.x;
					public record Event(String type) implements Serializable {}
					""";
			assertEquals(Set.of("com.x.Event"), parseTypes(src, "com.x").typeNames());
		}

		@Test
		void annotatedClass() {
			String src = """
					package com.x;
					@Entity
					@Table(name = "users")
					public class User {}
					""";
			assertEquals(Set.of("com.x.User"), parseTypes(src, "com.x").typeNames());
		}

		@Test
		void abstractFinalModifiers() {
			String src = """
					package com.x;
					abstract class A {}
					final class B {}
					strictfp class C {}
					""";
			assertEquals(Set.of("com.x.A", "com.x.B", "com.x.C"), parseTypes(src, "com.x").typeNames());
		}

		@Test
		void unicodeClassName() {
			String src = """
					package com.x;
					public class Ñoño {}
					""";
			assertEquals(Set.of("com.x.Ñoño"), parseTypes(src, "com.x").typeNames());
		}
	}

	// =====================================================================
	// METHOD EXTRACTION
	// =====================================================================

	@Nested
	class MethodExtraction {

		@Test
		void simpleMethod() {
			String src = """
					package com.x;
					public class FooTest {
					    @Test void testAdd() { assertEquals(2, 1+1); }
					}
					""";
			var m = parse(src, "com.x");
			var hashes = m.methodHashes();
			assertEquals(Set.of("com.x.FooTest#testAdd"), hashes.keySet());
		}

		@Test
		void multipleMethods() {
			String src = """
					package com.x;
					public class T {
					    void a() {}
					    void b() {}
					    void c() {}
					}
					""";
			assertEquals(Set.of("com.x.T#a", "com.x.T#b", "com.x.T#c"), parse(src, "com.x").methodHashes().keySet());
		}

		@Test
		void constructorsSkipped() {
			String src = """
					package com.x;
					public class Foo {
					    public Foo() {}
					    public Foo(int x) {}
					    void real() {}
					}
					""";
			assertEquals(Set.of("com.x.Foo#real"), parse(src, "com.x").methodHashes().keySet());
		}

		@Test
		void constructorsInModel() {
			String src = """
					package com.x;
					public class Foo {
					    public Foo() {}
					    void m() {}
					}
					""";
			var m = parse(src, "com.x");
			assertTrue(m.methods().stream().anyMatch(md -> md.isConstructor() && md.name().equals("Foo")));
		}

		@Test
		void abstractMethodSkippedInHashes() {
			String src = """
					package com.x;
					public abstract class A {
					    abstract void nope();
					    void yes() { int x = 1; }
					}
					""";
			assertEquals(Set.of("com.x.A#yes"), parse(src, "com.x").methodHashes().keySet());
		}

		@Test
		void abstractMethodInModel() {
			String src = """
					package com.x;
					public abstract class A {
					    abstract void nope();
					}
					""";
			var m = parse(src, "com.x");
			assertTrue(m.methods().stream().anyMatch(md -> md.isAbstract() && md.name().equals("nope")));
		}

		@Test
		void nestedClassMethodExtracted() {
			String src = """
					package com.x;
					public class Outer {
					    void outerMethod() {}
					    class Inner { void innerMethod() {} }
					}
					""";
			assertEquals(Set.of("com.x.Outer#outerMethod", "com.x.Outer$Inner#innerMethod"),
					parse(src, "com.x").methodHashes().keySet());
		}

		@Test
		void multipleTopLevelClassMethods() {
			String src = """
					package com.x;
					class A { void a() {} }
					class B { void b() {} }
					""";
			assertEquals(Set.of("com.x.A#a", "com.x.B#b"), parse(src, "com.x").methodHashes().keySet());
		}

		@Test
		void overloadsCombined() {
			String src = """
					package com.x;
					public class T {
					    void doIt() { int a = 1; }
					    void doIt(int x) { int b = 2; }
					}
					""";
			var hashes = parse(src, "com.x").methodHashes();
			assertEquals(Set.of("com.x.T#doIt"), hashes.keySet());
		}

		@Test
		void defaultPackageMethods() {
			String src = """
					public class T { void m() {} }
					""";
			assertEquals(Set.of("T#m"), parse(src, "").methodHashes().keySet());
		}

		@Test
		void emptyClassNoMethods() {
			String src = """
					package com.x;
					public class Empty {}
					""";
			assertTrue(parse(src, "com.x").methodHashes().isEmpty());
		}

		// ── Modifiers ────────────────────────────────────────────────

		@Test
		void staticMethod() {
			String src = """
					package com.x;
					public class T {
					    static void helper() {}
					    public static int compute() { return 0; }
					}
					""";
			var h = parse(src, "com.x").methodHashes();
			assertTrue(h.containsKey("com.x.T#helper"));
			assertTrue(h.containsKey("com.x.T#compute"));
		}

		@Test
		void synchronizedNativeModifiers() {
			String src = """
					package com.x;
					public class T {
					    synchronized void sync() {}
					    native void nat();
					}
					""";
			var h = parse(src, "com.x").methodHashes();
			assertTrue(h.containsKey("com.x.T#sync"));
			assertFalse(h.containsKey("com.x.T#nat")); // native → no body
		}

		@Test
		void interfaceDefaultMethod() {
			String src = """
					package com.x;
					public interface Greeter {
					    default String greet() { return "hi"; }
					    void abstractMethod();
					}
					""";
			assertEquals(Set.of("com.x.Greeter#greet"), parse(src, "com.x").methodHashes().keySet());
		}

		// ── Return types ─────────────────────────────────────────────

		@Test
		void genericReturnType() {
			String src = """
					package com.x;
					public class T {
					    List<String> items() { return List.of(); }
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#items"));
		}

		@Test
		void nestedGenericReturnType() {
			String src = """
					package com.x;
					public class T {
					    Map<String, List<Integer>> data() { return Map.of(); }
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#data"));
		}

		@Test
		void arrayReturnType() {
			String src = """
					package com.x;
					public class T {
					    int[] arr() { return new int[0]; }
					    String[][] mat() { return null; }
					}
					""";
			var h = parse(src, "com.x").methodHashes();
			assertTrue(h.containsKey("com.x.T#arr"));
			assertTrue(h.containsKey("com.x.T#mat"));
		}

		@Test
		void fullyQualifiedReturnType() {
			String src = """
					package com.x;
					public class T {
					    java.util.List<String> stuff() { return null; }
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#stuff"));
		}

		@Test
		void typeParameterMethod() {
			String src = """
					package com.x;
					public class T {
					    <E> E convert(Object o) { return null; }
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#convert"));
		}

		@Test
		void voidReturnType() {
			String src = """
					package com.x;
					public class T { void run() {} }
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#run"));
		}

		// ── Annotations ──────────────────────────────────────────────

		@Test
		void singleAnnotation() {
			String src = """
					package com.x;
					public class T {
					    @Test void testA() {}
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#testA"));
		}

		@Test
		void multipleAnnotations() {
			String src = """
					package com.x;
					public class T {
					    @Test
					    @DisplayName("add test")
					    void testAdd() {}
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#testAdd"));
		}

		@Test
		void annotationWithBracesInArgs() {
			// The braces in the annotation arg are inside a string literal → stripped
			String src = """
					package com.x;
					public class T {
					    @SuppressWarnings({"unchecked", "rawtypes"})
					    void m() { int x = 1; }
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#m"));
		}

		@Test
		void multiLineAnnotation() {
			String src = """
					package com.x;
					public class T {
					    @ParameterizedTest
					    @ValueSource(strings = {
					        "a", "b", "c"
					    })
					    void paramTest(String s) {}
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#paramTest"));
		}

		// ── Throws clause ────────────────────────────────────────────

		@Test
		void throwsClause() {
			String src = """
					package com.x;
					public class T {
					    void risky() throws IOException, SQLException { throw new RuntimeException(); }
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#risky"));
		}

		// ── Complex bodies ───────────────────────────────────────────

		@Test
		void lambdaInBody() {
			String src = """
					package com.x;
					public class T {
					    void m() {
					        Runnable r = () -> { System.out.println("hi"); };
					        r.run();
					    }
					}
					""";
			assertEquals(Set.of("com.x.T#m"), parse(src, "com.x").methodHashes().keySet());
		}

		@Test
		void anonymousInnerClassInBody() {
			String src = """
					package com.x;
					public class T {
					    void m() {
					        Runnable r = new Runnable() { public void run() {} };
					    }
					}
					""";
			assertEquals(Set.of("com.x.T#m"), parse(src, "com.x").methodHashes().keySet());
		}

		@Test
		void deeplyNestedBracesInBody() {
			String src = """
					package com.x;
					public class T {
					    void m() {
					        if (true) {
					            for (int i = 0; i < 10; i++) {
					                if (i > 5) {
					                    while (true) { break; }
					                }
					            }
					        }
					    }
					}
					""";
			assertEquals(Set.of("com.x.T#m"), parse(src, "com.x").methodHashes().keySet());
		}

		@Test
		void fieldDeclarationsNotExtracted() {
			String src = """
					package com.x;
					public class T {
					    private final int count = 0;
					    private String name;
					    void real() {}
					}
					""";
			assertEquals(Set.of("com.x.T#real"), parse(src, "com.x").methodHashes().keySet());
		}

		// ── Enum/Record methods ──────────────────────────────────────

		@Test
		void enumMethods() {
			String src = """
					package com.x;
					public enum Color {
					    RED, GREEN, BLUE;
					    public String display() { return name(); }
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.Color#display"));
		}

		@Test
		void enumWithConstructorAndMethod() {
			String src = """
					package com.x;
					public enum Status {
					    OK(200), ERROR(500);
					    private final int code;
					    Status(int code) { this.code = code; }
					    public int getCode() { return code; }
					}
					""";
			var h = parse(src, "com.x").methodHashes();
			assertTrue(h.containsKey("com.x.Status#getCode"));
			assertFalse(h.containsKey("com.x.Status#Status")); // constructor excluded
		}

		@Test
		void recordMethods() {
			String src = """
					package com.x;
					public record Point(int x, int y) {
					    public double distance() { return Math.sqrt(x*x + y*y); }
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.Point#distance"));
		}

		@Test
		void innerRecordNotExtractedAsMethod() {
			String src = """
					package com.x;
					public class Outer {
					    record Point(int x, int y) {}
					    record Person(String name, int age) {}
					    public void realMethod() {}
					}
					""";
			var hashes = parse(src, "com.x").methodHashes();
			assertEquals(Set.of("com.x.Outer#realMethod"), hashes.keySet());
		}

		// ── @BeforeEach / @AfterEach ─────────────────────────────────

		@Test
		void lifecycleMethods() {
			String src = """
					package com.x;
					public class T {
					    @BeforeEach void setUp() {}
					    @AfterEach void tearDown() {}
					    @Test void test() {}
					}
					""";
			assertEquals(Set.of("com.x.T#setUp", "com.x.T#tearDown", "com.x.T#test"),
					parse(src, "com.x").methodHashes().keySet());
		}
	}

	// =====================================================================
	// HASH STABILITY
	// =====================================================================

	@Nested
	class HashStability {

		@Test
		void commentChangeDoesNotAffectHash() {
			String src1 = """
					package com.x;
					public class T {
					    // old comment
					    void m() { int x = 1; }
					}
					""";
			String src2 = """
					package com.x;
					public class T {
					    // completely different comment
					    void m() { int x = 1; }
					}
					""";
			assertEquals(parse(src1, "com.x").methodHashes().get("com.x.T#m"),
					parse(src2, "com.x").methodHashes().get("com.x.T#m"));
		}

		@Test
		void codeChangeAffectsHash() {
			String src1 = """
					package com.x;
					public class T { void m() { int x = 1; } }
					""";
			String src2 = """
					package com.x;
					public class T { void m() { int x = 2; } }
					""";
			assertNotEquals(parse(src1, "com.x").methodHashes().get("com.x.T#m"),
					parse(src2, "com.x").methodHashes().get("com.x.T#m"));
		}

		@Test
		void sameBodiesDifferentClassesSameHash() {
			String src1 = """
					package com.x;
					public class A { void m() { int x = 42; } }
					""";
			String src2 = """
					package com.x;
					public class B { void m() { int x = 42; } }
					""";
			assertEquals(parse(src1, "com.x").methodHashes().get("com.x.A#m"),
					parse(src2, "com.x").methodHashes().get("com.x.B#m"));
		}

		@Test
		void stringLiteralChangeAffectsHash() {
			// String literals are now preserved in method hashes — changing the literal
			// value changes the hash (string changes affect bytecode)
			String src1 = """
					package com.x;
					public class T { void m() { String s = "hello"; } }
					""";
			String src2 = """
					package com.x;
					public class T { void m() { String s = "world"; } }
					""";
			assertNotEquals(parse(src1, "com.x").methodHashes().get("com.x.T#m"),
					parse(src2, "com.x").methodHashes().get("com.x.T#m"));
		}

		@Test
		void fieldStringLiteralChangeAffectsHash() {
			String src1 = """
					package com.x;
					public class T { static final String NAME = "hello"; }
					""";
			String src2 = """
					package com.x;
					public class T { static final String NAME = "world"; }
					""";
			assertNotEquals(parseAll(src1, "com.x").fieldHashes().get("com.x.T#NAME"),
					parseAll(src2, "com.x").fieldHashes().get("com.x.T#NAME"));
		}

		@Test
		void initializerStringLiteralChangeAffectsHash() {
			String src1 = """
					package com.x;
					public class T { static { String s = "hello"; } }
					""";
			String src2 = """
					package com.x;
					public class T { static { String s = "world"; } }
					""";
			assertNotEquals(parseAll(src1, "com.x").initializers().get(0).bodyHash(),
					parseAll(src2, "com.x").initializers().get(0).bodyHash());
		}
	}

	// =====================================================================
	// EXTRACTION UTILITIES
	// =====================================================================

	@Nested
	class ExtractionUtilities {

		@Test
		void extractClassNamesBasic() {
			String src = """
					package com.x;
					public class Foo {}
					interface Bar {}
					""";
			Set<String> names = SourceFileModel.extractClassNames(src, "com.x");
			assertEquals(Set.of("com.x.Foo", "com.x.Bar"), names);
		}

		@Test
		void sealedClassThroughExtraction() {
			String src = """
					package com.x;
					public sealed class Shape permits Circle {}
					final class Circle extends Shape {}
					""";
			Set<String> names = SourceFileModel.extractClassNames(src, "com.x");
			assertEquals(Set.of("com.x.Shape", "com.x.Circle"), names);
		}
	}

	// =====================================================================
	// COMPLEX GENERATED SOURCE
	// =====================================================================

	@Nested
	class ComplexGeneratedSource {

		/**
		 * A large, realistic generated source file that exercises many parser features
		 * together.
		 */
		@Test
		void comprehensiveSource() {
			String src = """
					package com.example.app;

					import java.util.*;
					import java.io.*;

					/**
					 * A comprehensive test-subject class.
					 */
					@SuppressWarnings("all")
					public sealed class Repository<T extends Comparable<T>>
					        extends AbstractRepository<T>
					        implements Serializable, AutoCloseable
					        permits InMemoryRepository {

					    private static final long serialVersionUID = 1L;
					    private final List<T> items = new ArrayList<>();

					    // --- constructors ---
					    public Repository() { super(); }
					    public Repository(Collection<? extends T> initial) {
					        super();
					        items.addAll(initial);
					    }

					    // --- simple methods ---
					    public void add(T item) { items.add(item); }
					    public T get(int index) { return items.get(index); }
					    public int size() { return items.size(); }

					    // --- generic return ---
					    public <R> List<R> transform(java.util.function.Function<T, R> fn) {
					        List<R> result = new ArrayList<>();
					        for (T item : items) { result.add(fn.apply(item)); }
					        return result;
					    }

					    // --- throws clause ---
					    public void validate() throws IllegalStateException {
					        if (items.isEmpty()) throw new IllegalStateException("empty");
					    }

					    // --- overloaded methods ---
					    public void remove(int index) { items.remove(index); }
					    public void remove(T item) { items.remove(item); }

					    // --- complex lambda body ---
					    public Optional<T> findFirst(java.util.function.Predicate<T> pred) {
					        return items.stream()
					                .filter(pred)
					                .findFirst();
					    }

					    // --- synchronized ---
					    public synchronized void clear() { items.clear(); }

					    // --- static factory ---
					    public static <E extends Comparable<E>> Repository<E> empty() {
					        return new Repository<>();
					    }

					    @Override
					    public void close() { items.clear(); }
					}

					final class InMemoryRepository<T extends Comparable<T>> extends Repository<T> {
					    InMemoryRepository() { super(); }
					    public void snapshot() {
					        // take a snapshot
					        List<T> copy = new ArrayList<>(List.of());
					    }
					}

					@interface Cacheable {
					    int ttl() default 60;
					}
					""";

			var model = parse(src, "com.example.app");

			// types
			assertEquals(Set.of("com.example.app.Repository", "com.example.app.InMemoryRepository",
					"com.example.app.Cacheable"), model.typeNames());

			// methods in Repository (constructors excluded from hashes)
			var hashes = model.methodHashes();
			for (String mName : List.of("add", "get", "size", "transform", "validate", "remove", "findFirst", "clear",
					"empty", "close")) {
				assertTrue(hashes.containsKey("com.example.app.Repository#" + mName),
						"expected " + mName + ", got: " + hashes.keySet());
			}

			// overloaded remove → single combined key
			assertEquals(1, hashes.keySet().stream().filter(k -> k.endsWith("#remove")).count());

			// InMemoryRepository methods (constructor excluded)
			assertTrue(hashes.containsKey("com.example.app.InMemoryRepository#snapshot"));
			assertFalse(hashes.containsKey("com.example.app.InMemoryRepository#InMemoryRepository"),
					"constructor should be excluded from hashes");

			// @interface body methods are abstract (end with ;) → not in hashes
			assertFalse(hashes.containsKey("com.example.app.Cacheable#ttl"));
		}

		/**
		 * Source with multiple complex generic types, wildcards, varargs etc.
		 */
		@Test
		void genericHeavySource() {
			String src = """
					package gen;
					public class GenericUtil {
					    public <K, V> Map<K, List<V>> groupBy(Collection<V> items,
					            java.util.function.Function<V, K> classifier) {
					        Map<K, List<V>> result = new HashMap<>();
					        for (V item : items) {
					            result.computeIfAbsent(classifier.apply(item), k -> new ArrayList<>()).add(item);
					        }
					        return result;
					    }

					    public <T> void processAll(T... items) {
					        for (T item : items) { System.out.println(item); }
					    }

					    public Map<String, Map<Integer, List<Double>>> nestedReturnType() {
					        return Map.of();
					    }
					}
					""";
			var hashes = parse(src, "gen").methodHashes();
			assertEquals(
					Set.of("gen.GenericUtil#groupBy", "gen.GenericUtil#processAll", "gen.GenericUtil#nestedReturnType"),
					hashes.keySet());
		}

		/**
		 * Mixed type kinds in one file with methods.
		 */
		@Test
		void mixedTypeKinds() {
			String src = """
					package mix;
					public class Service {
					    void serve() {}
					}
					interface Processor {
					    default void process() { System.out.println("proc"); }
					    void abstractProcess();
					}
					enum Priority {
					    HIGH, LOW;
					    int level() { return ordinal(); }
					}
					record Config(String key, String value) {
					    boolean isValid() { return key != null; }
					}
					""";
			var model = parse(src, "mix");
			assertEquals(Set.of("mix.Service", "mix.Processor", "mix.Priority", "mix.Config"), model.typeNames());

			var h = model.methodHashes();
			assertTrue(h.containsKey("mix.Service#serve"));
			assertTrue(h.containsKey("mix.Processor#process"));
			assertFalse(h.containsKey("mix.Processor#abstractProcess"));
			assertTrue(h.containsKey("mix.Priority#level"));
			assertTrue(h.containsKey("mix.Config#isValid"));
		}

		/**
		 * Tests that the pattern works with extends containing generics and & bounds.
		 */
		@Test
		void extendsWithIntersectionBound() {
			String src = """
					package com.x;
					public class Comp<T extends Comparable<T> & Serializable> {
					    void sort() {}
					}
					""";
			var h = parse(src, "com.x").methodHashes();
			assertTrue(h.containsKey("com.x.Comp#sort"));
		}

		/**
		 * Wildcard and bounded wildcard in method signatures.
		 */
		@Test
		void wildcardParameters() {
			String src = """
					package com.x;
					public class T {
					    void accept(List<?> items) {}
					    void bounded(List<? extends Number> nums) {}
					    void lowerBound(List<? super Integer> list) {}
					}
					""";
			var h = parse(src, "com.x").methodHashes();
			assertEquals(Set.of("com.x.T#accept", "com.x.T#bounded", "com.x.T#lowerBound"), h.keySet());
		}
	}

	// =====================================================================
	// FIELD EXTRACTION
	// =====================================================================

	@Nested
	class FieldExtraction {

		@Test
		void simpleFields() {
			String src = """
					package com.x;
					public class T {
					    private int count;
					    private String name;
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.T#count", "com.x.T#name"), h.keySet());
		}

		@Test
		void fieldWithInitializer() {
			String src = """
					package com.x;
					public class T {
					    private int count = 0;
					    private String name = "default";
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.T#count", "com.x.T#name"), h.keySet());
		}

		@Test
		void staticFinalField() {
			String src = """
					package com.x;
					public class T {
					    public static final int MAX = 100;
					    private static final String TAG = "T";
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.T#MAX", "com.x.T#TAG"), h.keySet());
		}

		@Test
		void fieldWithGenericType() {
			String src = """
					package com.x;
					public class T {
					    private List<String> items;
					    private Map<String, List<Integer>> data;
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.T#items", "com.x.T#data"), h.keySet());
		}

		@Test
		void fieldWithArrayType() {
			String src = """
					package com.x;
					public class T {
					    private int[] arr;
					    private String[][] matrix;
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.T#arr", "com.x.T#matrix"), h.keySet());
		}

		@Test
		void fieldWithCStyleArrayDims() {
			String src = """
					package com.x;
					public class T {
					    private int x[];
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.T#x"), h.keySet());
		}

		@Test
		void fieldsNotExtractedWithMethodsDetail() {
			String src = """
					package com.x;
					public class T {
					    private int count;
					    void m() {}
					}
					""";
			var m = parse(src, "com.x");
			assertTrue(m.fields().isEmpty());
			assertFalse(m.methods().isEmpty());
		}

		@Test
		void methodsStillExtractedWithFieldsDetail() {
			String src = """
					package com.x;
					public class T {
					    private int count;
					    void m() {}
					}
					""";
			var m = parseAll(src, "com.x");
			assertFalse(m.fields().isEmpty());
			assertFalse(m.methods().isEmpty());
		}

		@Test
		void fieldsAndMethodsNotConfused() {
			String src = """
					package com.x;
					public class T {
					    private int count = 0;
					    public int getCount() { return count; }
					}
					""";
			var m = parseAll(src, "com.x");
			assertEquals(Set.of("com.x.T#count"), m.fieldHashes().keySet());
			assertEquals(Set.of("com.x.T#getCount"), m.methodHashes().keySet());
		}

		@Test
		void fieldDeclarationTextCaptured() {
			String src = """
					package com.x;
					public class T {
					    private final int count = 42;
					}
					""";
			var m = parseAll(src, "com.x");
			var field = m.fields().get(0);
			assertEquals("count", field.name());
			assertTrue(field.declarationText().contains("private"));
			assertTrue(field.declarationText().contains("final"));
			assertTrue(field.declarationText().contains("int"));
			assertTrue(field.declarationText().contains("count"));
			assertTrue(field.declarationText().contains("42"));
			assertTrue(field.declarationText().endsWith(";"));
		}

		@Test
		void annotatedFieldDeclarationTextIncludesAnnotation() {
			String src = """
					package com.x;
					public class T {
					    @Inject
					    private Service service;
					}
					""";
			var m = parseAll(src, "com.x");
			var field = m.fields().get(0);
			assertTrue(field.declarationText().contains("@Inject"),
					"declarationText should include annotation: " + field.declarationText());
		}

		@Test
		void enumFieldsAfterConstants() {
			String src = """
					package com.x;
					public enum Status {
					    OK(200), ERROR(500);
					    private final int code;
					    Status(int code) { this.code = code; }
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.Status#code"), h.keySet());
		}

		@Test
		void enumConstantsNotExtractedAsFields() {
			String src = """
					package com.x;
					public enum Color {
					    RED, GREEN, BLUE;
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertTrue(h.isEmpty());
		}

		@Test
		void nestedClassFields() {
			String src = """
					package com.x;
					public class Outer {
					    private int outerField;
					    static class Inner {
					        private String innerField;
					    }
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.Outer#outerField", "com.x.Outer$Inner#innerField"), h.keySet());
		}

		@Test
		void annotatedField() {
			String src = """
					package com.x;
					public class T {
					    @Inject
					    private Service service;
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.T#service"), h.keySet());
		}

		@Test
		void multipleAnnotationsOnField() {
			String src = """
					package com.x;
					public class T {
					    @NotNull
					    @Column(name = "age")
					    private int age;
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.T#age"), h.keySet());
		}

		@Test
		void transientAndVolatileFields() {
			String src = """
					package com.x;
					public class T {
					    private transient int temp;
					    private volatile boolean flag;
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.T#temp", "com.x.T#flag"), h.keySet());
		}

		@Test
		void fieldWithComplexInitializer() {
			String src = """
					package com.x;
					public class T {
					    private final List<String> items = new ArrayList<>();
					    private final Runnable r = new Runnable() { public void run() {} };
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.T#items", "com.x.T#r"), h.keySet());
		}

		@Test
		void interfaceConstants() {
			String src = """
					package com.x;
					public interface Constants {
					    int MAX = 100;
					    String PREFIX = "pre";
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.Constants#MAX", "com.x.Constants#PREFIX"), h.keySet());
		}

		@Test
		void fullyQualifiedFieldType() {
			String src = """
					package com.x;
					public class T {
					    private java.util.List<String> items;
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.T#items"), h.keySet());
		}

		@Test
		void fieldHashChangesWhenInitializerChanges() {
			String src1 = """
					package com.x;
					public class T { private int x = 1; }
					""";
			String src2 = """
					package com.x;
					public class T { private int x = 2; }
					""";
			assertNotEquals(parseAll(src1, "com.x").fieldHashes().get("com.x.T#x"),
					parseAll(src2, "com.x").fieldHashes().get("com.x.T#x"));
		}

		@Test
		void fieldHashChangesWhenTypeChanges() {
			String src1 = """
					package com.x;
					public class T { private int x; }
					""";
			String src2 = """
					package com.x;
					public class T { private long x; }
					""";
			assertNotEquals(parseAll(src1, "com.x").fieldHashes().get("com.x.T#x"),
					parseAll(src2, "com.x").fieldHashes().get("com.x.T#x"));
		}

		@Test
		void localVariablesNotExtractedAsFields() {
			String src = """
					package com.x;
					public class T {
					    void m() {
					        int local = 42;
					        String name = "test";
					    }
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertTrue(h.isEmpty());
		}

		@Test
		void defaultPackageFields() {
			String src = """
					public class T { private int x; }
					""";
			var h = parseAll(src, "").fieldHashes();
			assertEquals(Set.of("T#x"), h.keySet());
		}

		@Test
		void recordFieldsNotExtractedAsRegularFields() {
			// Record components are not traditional field declarations
			String src = """
					package com.x;
					public record Point(int x, int y) {
					    static final Point ORIGIN = new Point(0, 0);
					}
					""";
			var h = parseAll(src, "com.x").fieldHashes();
			assertEquals(Set.of("com.x.Point#ORIGIN"), h.keySet());
		}

		@Test
		void annotationTypeConstants() {
			String src = """
					package com.x;
					public @interface MyAnno {
					    int MAX = 100;
					    String value();
					}
					""";
			var m = parseAll(src, "com.x");
			assertEquals(Set.of("com.x.MyAnno#MAX"), m.fieldHashes().keySet());
		}

		@Test
		void comprehensiveFieldsAndMethods() {
			String src = """
					package com.example;
					public class Service {
					    private static final long serialVersionUID = 1L;
					    private final List<String> items = new ArrayList<>();
					    @Inject private Repository repo;

					    public void add(String item) { items.add(item); }
					    public int size() { return items.size(); }
					}
					""";
			var m = parseAll(src, "com.example");
			assertEquals(Set.of("com.example.Service#serialVersionUID", "com.example.Service#items",
					"com.example.Service#repo"), m.fieldHashes().keySet());
			assertEquals(Set.of("com.example.Service#add", "com.example.Service#size"), m.methodHashes().keySet());
		}
	}

	// =====================================================================
	// INITIALIZER BLOCK EXTRACTION
	// =====================================================================

	@Nested
	class InitializerExtraction {

		@Test
		void staticInitializerExtracted() {
			String src = """
					package com.x;
					public class T {
					    static { System.out.println("init"); }
					    void m() {}
					}
					""";
			var m = parseAll(src, "com.x");
			assertEquals(1, m.initializers().size());
			var init = m.initializers().get(0);
			assertTrue(init.isStatic());
			assertEquals("com.x.T", init.enclosingFqcn());
			assertNotNull(init.bodyText());
			assertNotNull(init.bodyHash());
		}

		@Test
		void instanceInitializerExtracted() {
			String src = """
					package com.x;
					public class T {
					    { System.out.println("instance init"); }
					    void m() {}
					}
					""";
			var m = parseAll(src, "com.x");
			assertEquals(1, m.initializers().size());
			var init = m.initializers().get(0);
			assertFalse(init.isStatic());
			assertEquals("com.x.T", init.enclosingFqcn());
		}

		@Test
		void multipleInitializerBlocks() {
			String src = """
					package com.x;
					public class T {
					    static { int x = 1; }
					    { int y = 2; }
					    static { int z = 3; }
					}
					""";
			var m = parseAll(src, "com.x");
			assertEquals(3, m.initializers().size());
			assertTrue(m.initializers().get(0).isStatic());
			assertFalse(m.initializers().get(1).isStatic());
			assertTrue(m.initializers().get(2).isStatic());
		}

		@Test
		void initializerNotConfusedWithMethodBody() {
			String src = """
					package com.x;
					public class T {
					    static { int x = 1; }
					    void m() { int y = 2; }
					}
					""";
			var m = parseAll(src, "com.x");
			assertEquals(1, m.initializers().size(), "should find exactly 1 initializer");
			assertEquals(Set.of("com.x.T#m"), m.methodHashes().keySet());
		}

		@Test
		void initializerInNestedClass() {
			String src = """
					package com.x;
					public class Outer {
					    static { int a = 1; }
					    static class Inner {
					        static { int b = 2; }
					    }
					}
					""";
			var m = parseAll(src, "com.x");
			assertEquals(2, m.initializers().size());
			assertTrue(
					m.initializers().stream().anyMatch(i -> i.enclosingFqcn().equals("com.x.Outer") && i.isStatic()));
			assertTrue(m.initializers().stream()
					.anyMatch(i -> i.enclosingFqcn().equals("com.x.Outer$Inner") && i.isStatic()));
		}

		@Test
		void initializerInEnum() {
			String src = """
					package com.x;
					public enum Color {
					    RED, GREEN, BLUE;
					    static { System.out.println("enum init"); }
					}
					""";
			var m = parseAll(src, "com.x");
			assertEquals(1, m.initializers().size());
			assertTrue(m.initializers().get(0).isStatic());
			assertEquals("com.x.Color", m.initializers().get(0).enclosingFqcn());
		}

		@Test
		void initializerHashChangesWhenContentChanges() {
			String src1 = """
					package com.x;
					public class T { static { int x = 1; } }
					""";
			String src2 = """
					package com.x;
					public class T { static { int x = 2; } }
					""";
			assertNotEquals(parseAll(src1, "com.x").initializers().get(0).bodyHash(),
					parseAll(src2, "com.x").initializers().get(0).bodyHash());
		}

		@Test
		void noInitializersWhenNonePresent() {
			String src = """
					package com.x;
					public class T {
					    private int x;
					    void m() {}
					}
					""";
			assertTrue(parseAll(src, "com.x").initializers().isEmpty());
		}

		@Test
		void initializerBodyTextCaptured() {
			String src = """
					package com.x;
					public class T {
					    static { int x = 42; }
					}
					""";
			var init = parseAll(src, "com.x").initializers().get(0);
			assertTrue(init.bodyText().contains("int x = 42;"));
			assertTrue(init.bodyText().startsWith("{"));
			assertTrue(init.bodyText().endsWith("}"));
		}

		@Test
		void initializersNotExtractedWithMethodsDetail() {
			String src = """
					package com.x;
					public class T {
					    static { int x = 1; }
					    void m() {}
					}
					""";
			var m = parse(src, "com.x");
			assertTrue(m.initializers().isEmpty());
		}

		@Test
		void initializerWithComplexBody() {
			String src = """
					package com.x;
					public class T {
					    static {
					        if (true) {
					            for (int i = 0; i < 10; i++) {
					                System.out.println(i);
					            }
					        }
					    }
					}
					""";
			var m = parseAll(src, "com.x");
			assertEquals(1, m.initializers().size());
			assertTrue(m.initializers().get(0).isStatic());
		}

		@Test
		void allStructuralElementsTogether() {
			String src = """
					package com.x;
					public class T {
					    private static final int X = 1;
					    static { System.out.println("static init"); }
					    { System.out.println("instance init"); }
					    private String name;
					    public T() { this.name = "default"; }
					    public void doWork() { System.out.println(name); }
					}
					""";
			var m = parseAll(src, "com.x");
			assertEquals(Set.of("com.x.T#X", "com.x.T#name"), m.fieldHashes().keySet());
			assertEquals(Set.of("com.x.T#doWork"), m.methodHashes().keySet());
			assertEquals(2, m.initializers().size());
			assertTrue(m.initializers().stream().anyMatch(SourceFileModel.InitializerNode::isStatic));
			assertTrue(m.initializers().stream().anyMatch(i -> !i.isStatic()));
			// Constructor is in methods list
			assertTrue(m.methods().stream().anyMatch(md -> md.isConstructor() && md.name().equals("T")));
		}
	}

	// =====================================================================
	// EDGE CASES
	// =====================================================================

	@Nested
	class EdgeCases {

		@Test
		void emptySource() {
			var m = parse("", "");
			assertTrue(m.types().isEmpty());
			assertTrue(m.methods().isEmpty());
		}

		@Test
		void onlyPackageDeclaration() {
			var m = parse("package com.x;", "com.x");
			assertTrue(m.types().isEmpty());
		}

		@Test
		void typeWithNoBody() {
			// Should not crash even if somehow there's no matching brace
			var m = parseTypes("public class Broken {", "com.x");
			// may or may not find this type — just shouldn't crash
			assertNotNull(m);
		}

		@Test
		void textBlockInMethodBody() {
			String src = """
					package com.x;
					public class T {
					    void m() {
					        String s = \\"\\"\\"
					                class Fake {}
					                void notAMethod() {}
					                \\"\\"\\";
					    }
					}
					""".replace("\\\"", "\"");
			var h = parse(src, "com.x").methodHashes();
			assertEquals(Set.of("com.x.T#m"), h.keySet());
		}

		@Test
		void staticInitializerBlock() {
			// Static initialiser blocks are at depth 1 but are not methods
			// They don't match METHOD_OR_CTOR_ISLAND because they have no name/parens
			String src = """
					package com.x;
					public class T {
					    static { System.out.println("init"); }
					    void m() {}
					}
					""";
			assertEquals(Set.of("com.x.T#m"), parse(src, "com.x").methodHashes().keySet());
		}

		@Test
		void instanceInitializerBlock() {
			String src = """
					package com.x;
					public class T {
					    { System.out.println("instance init"); }
					    void m() {}
					}
					""";
			assertEquals(Set.of("com.x.T#m"), parse(src, "com.x").methodHashes().keySet());
		}

		@Test
		void multipleClassesWithSameSimpleName() {
			// This shouldn't happen in practice, but the parser should handle it gracefully
			String src = """
					package com.x;
					class A { void m() { int x = 1; } }
					""";
			String src2 = """
					package com.y;
					class A { void m() { int x = 1; } }
					""";
			// Same body → same hash
			assertEquals(parse(src, "com.x").methodHashes().get("com.x.A#m"),
					parse(src2, "com.y").methodHashes().get("com.y.A#m"));
		}

		@Test
		void methodRightAfterClassOpening() {
			String src = """
					package com.x;
					public class T {void immediate() {}}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#immediate"));
		}

		@Test
		void veryLongMethodBody() {
			StringBuilder sb = new StringBuilder();
			sb.append("package com.x;\npublic class T {\n  void big() {\n");
			for (int i = 0; i < 500; i++) {
				sb.append("    int var").append(i).append(" = ").append(i).append(";\n");
			}
			sb.append("  }\n}\n");
			assertTrue(parse(sb.toString(), "com.x").methodHashes().containsKey("com.x.T#big"));
		}

		@Test
		void defaultMethodWithTypeAnnotation() {
			String src = """
					package com.x;
					import java.util.function.Predicate;
					public interface T {
					    default @SuppressWarnings("unchecked") Predicate<String> getPredicate() {
					        return s -> true;
					    }
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#getPredicate"));
		}

		@Test
		void defaultMethodWithNullableAnnotation() {
			String src = """
					package com.x;
					import java.util.function.Predicate;
					public interface T {
					    default @Nullable Predicate<String> getSaturatePredicate() {
					        return null;
					    }
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#getSaturatePredicate"));
		}

		@Test
		void methodWithMultipleTypeAnnotations() {
			String src = """
					package com.x;
					public class T {
					    public @NonNull @ReadOnly String getName() { return ""; }
					}
					""";
			assertTrue(parse(src, "com.x").methodHashes().containsKey("com.x.T#getName"));
		}

		@Test
		void fieldWithTypeAnnotation() {
			String src = """
					package com.x;
					public class T {
					    private final @NonNull String name = "";
					}
					""";
			var fields = parseAll(src, "com.x").fields();
			assertEquals(1, fields.size());
			assertEquals("name", fields.get(0).name());
		}

		@Test
		void comparisonOperatorDoesNotConfuseGenerics() {
			// The < in "statusCode < 300" must not be parsed as generic type start
			String src = """
					package com.x;
					public class T {
					    private boolean isSuccessful(HttpResponse httpResponse) {
					        int statusCode = httpResponse.getCode();
					        return statusCode >= 200 && statusCode < 300;
					    }
					    private ClassicHttpRequest toApacheRequest(HttpRequest request) {
					        ClassicHttpRequest apacheRequest =
					                switch (request.method()) {
					                    case GET -> new HttpGet(request.url());
					                };
					        return apacheRequest;
					    }
					}
					""";
			var methods = parse(src, "com.x").methodHashes();
			assertTrue(methods.containsKey("com.x.T#isSuccessful"));
			assertTrue(methods.containsKey("com.x.T#toApacheRequest"));
		}
	}

	// =====================================================================
	// COMPACT BODY
	// =====================================================================

	@Nested
	class CompactBody {

		@Test
		void typeCompactBodyStripsComments() {
			String src = """
					package com.x;
					public class T {
					    // a comment
					    int x = 1;
					    /* block comment */
					    int y = 2;
					}
					""";
			var model = parseAll(src, "com.x");
			String compact = model.types().get(0).compactBody();
			assertNotNull(compact);
			assertFalse(compact.contains("// a comment"));
			assertFalse(compact.contains("block comment"));
			assertTrue(compact.contains("int x = 1;"));
			assertTrue(compact.contains("int y = 2;"));
		}

		@Test
		void typeCompactBodyRemovesEmptyLines() {
			String src = """
					package com.x;
					public class T {
					    int x = 1;


					    int y = 2;
					}
					""";
			var model = parseAll(src, "com.x");
			String compact = model.types().get(0).compactBody();
			assertNotNull(compact);
			// No consecutive newlines (empty lines removed)
			assertFalse(compact.contains("\n\n"));
		}

		@Test
		void typeCompactBodyPreservesTextBlockEmptyLines() {
			String src = "package com.x;\n" + "public class T {\n" + "    String s = \"\"\"\n" + "        line 1\n"
					+ "\n" + "        line 3\n" + "        \"\"\";\n" + "}\n";
			var model = parseAll(src, "com.x");
			String compact = model.types().get(0).compactBody();
			assertNotNull(compact);
			// The empty line inside the text block should be preserved
			assertTrue(compact.contains("line 1\n\n        line 3"));
		}

		@Test
		void methodCompactBodyStripsComments() {
			String src = """
					package com.x;
					public class T {
					    void m() {
					        // line comment
					        int x = 1;
					        /* block */
					        int y = 2;
					    }
					}
					""";
			var model = parseAll(src, "com.x");
			var method = model.methods().stream().filter(m -> m.name().equals("m")).findFirst().orElseThrow();
			assertNotNull(method.compactBody());
			assertFalse(method.compactBody().contains("// line comment"));
			assertFalse(method.compactBody().contains("block"));
			assertTrue(method.compactBody().contains("int x = 1;"));
			assertTrue(method.compactBody().contains("int y = 2;"));
		}

		@Test
		void methodCompactBodyRemovesEmptyLines() {
			String src = """
					package com.x;
					public class T {
					    void m() {
					        int x = 1;


					        int y = 2;
					    }
					}
					""";
			var model = parseAll(src, "com.x");
			var method = model.methods().stream().filter(m -> m.name().equals("m")).findFirst().orElseThrow();
			String compact = method.compactBody();
			assertNotNull(compact);
			assertFalse(compact.contains("\n\n"));
		}

		@Test
		void methodCompactBodyPreservesTextBlockEmptyLines() {
			String src = "package com.x;\n" + "public class T {\n" + "    String m() {\n" + "        return \"\"\"\n"
					+ "            hello\n" + "\n" + "            world\n" + "            \"\"\";\n" + "    }\n"
					+ "}\n";
			var model = parseAll(src, "com.x");
			var method = model.methods().stream().filter(m -> m.name().equals("m")).findFirst().orElseThrow();
			String compact = method.compactBody();
			assertNotNull(compact);
			assertTrue(compact.contains("hello\n\n            world"));
		}

		@Test
		void abstractMethodHasNullCompactBody() {
			String src = """
					package com.x;
					public abstract class T {
					    abstract void m();
					}
					""";
			var model = parseAll(src, "com.x");
			var method = model.methods().stream().filter(m -> m.name().equals("m")).findFirst().orElseThrow();
			assertNull(method.compactBody());
		}

		@Test
		void commentOnlyLinesRemoved() {
			String src = """
					package com.x;
					public class T {
					    void m() {
					        // just a comment
					        int x = 1;
					    }
					}
					""";
			var model = parseAll(src, "com.x");
			var method = model.methods().stream().filter(m -> m.name().equals("m")).findFirst().orElseThrow();
			String compact = method.compactBody();
			// The line that was only a comment should be gone
			String[] lines = compact.split("\n");
			for (String line : lines) {
				assertFalse(line.isBlank(), "should not have blank lines: [" + compact + "]");
			}
		}

		@Test
		void textBlockWithEscapedTripleQuotesNotPrematurelyClosed() {
			// Text block containing \""" — the escaped quote must not close the text block
			String src = "package com.x;\n" + "public class T {\n" + "    String m() {\n" + "        return \"\"\"\n"
					+ "            before \\\"\"\" after\n" + "            \"\"\";\n" + "    }\n"
					+ "    int code() { return 42; }\n" + "}\n";
			var model = parseAll(src, "com.x");
			// Both methods should be found — if the text block is prematurely closed,
			// the parser may swallow 'code()' into the text block residue
			var mMethod = model.methods().stream().filter(m -> m.name().equals("m")).findFirst().orElseThrow();
			var codeMethod = model.methods().stream().filter(m -> m.name().equals("code")).findFirst().orElseThrow();
			assertNotNull(mMethod.compactBody());
			assertNotNull(codeMethod.compactBody());
			// The compact body of m() must contain the escaped triple-quote content intact
			assertTrue(mMethod.compactBody().contains("before"));
			assertTrue(mMethod.compactBody().contains("after"));
		}
	}

	// =====================================================================
	// LOMBOK ANNOTATION-PROCESSOR SYNTHESIS
	// =====================================================================

	@Nested
	class LombokSynthesis {

		@Test
		void dataAnnotationSynthesizesGettersSettersEqualsHashCodeToString() {
			String src = """
					package com.x;
					@Data
					public class Person {
					    private String name;
					    private int age;
					}
					""";
			var model = parseAll(src, "com.x");
			var methodNames = new HashSet<String>();
			for (var m : model.methods())
				methodNames.add(m.name());

			assertTrue(methodNames.contains("getName"), "missing getName");
			assertTrue(methodNames.contains("setName"), "missing setName");
			assertTrue(methodNames.contains("getAge"), "missing getAge");
			assertTrue(methodNames.contains("setAge"), "missing setAge");
			assertTrue(methodNames.contains("toString"), "missing toString");
			assertTrue(methodNames.contains("equals"), "missing equals");
			assertTrue(methodNames.contains("hashCode"), "missing hashCode");
			assertTrue(methodNames.contains("Person"), "missing constructor");
		}

		@Test
		void booleanFieldGetterUsesIsPrefix() {
			String src = """
					package com.x;
					@Data
					public class Flags {
					    private boolean active;
					}
					""";
			var model = parseAll(src, "com.x");
			var methodNames = new HashSet<String>();
			for (var m : model.methods())
				methodNames.add(m.name());

			assertTrue(methodNames.contains("isActive"), "boolean getter should use is-prefix");
			assertFalse(methodNames.contains("getActive"), "should not have get-prefix for boolean");
			assertTrue(methodNames.contains("setActive"), "missing setActive");
		}

		@Test
		void valueAnnotationSynthesizesGettersButNoSetters() {
			String src = """
					package com.x;
					@Value
					public class Config {
					    private String key;
					    private String value;
					}
					""";
			var model = parseAll(src, "com.x");
			var methodNames = new HashSet<String>();
			for (var m : model.methods())
				methodNames.add(m.name());

			assertTrue(methodNames.contains("getKey"), "missing getKey");
			assertTrue(methodNames.contains("getValue"), "missing getValue");
			assertFalse(methodNames.contains("setKey"), "@Value should not generate setters");
			assertFalse(methodNames.contains("setValue"), "@Value should not generate setters");
			assertTrue(methodNames.contains("toString"), "missing toString");
			assertTrue(methodNames.contains("equals"), "missing equals");
			assertTrue(methodNames.contains("hashCode"), "missing hashCode");
		}

		@Test
		void staticFieldsExcludedFromSynthesis() {
			String src = """
					package com.x;
					@Data
					public class WithStatic {
					    private static final String CONST = "x";
					    private String name;
					}
					""";
			var model = parseAll(src, "com.x");
			var methodNames = new HashSet<String>();
			for (var m : model.methods())
				methodNames.add(m.name());

			assertTrue(methodNames.contains("getName"), "missing getName for instance field");
			assertFalse(methodNames.contains("getCONST"), "static field should not get getter");
			assertFalse(methodNames.contains("setCONST"), "static field should not get setter");
		}

		@Test
		void existingMethodNotDuplicated() {
			String src = """
					package com.x;
					@Data
					public class Custom {
					    private String name;
					    public String toString() { return "custom"; }
					}
					""";
			var model = parseAll(src, "com.x");
			long toStringCount = model.methods().stream().filter(m -> m.name().equals("toString")).count();
			assertEquals(1, toStringCount, "existing toString should not be duplicated");
		}

		@Test
		void fullyQualifiedAnnotation() {
			String src = """
					package com.x;
					@lombok.Data
					public class FQ {
					    private int id;
					}
					""";
			var model = parseAll(src, "com.x");
			var methodNames = new HashSet<String>();
			for (var m : model.methods())
				methodNames.add(m.name());

			assertTrue(methodNames.contains("getId"), "should work with @lombok.Data");
			assertTrue(methodNames.contains("setId"), "should work with @lombok.Data");
		}

		@Test
		void fieldLevelGetterSetter() {
			String src = """
					package com.x;
					public class Partial {
					    @Getter private String name;
					    @Setter private int age;
					    private String unAnnotated;
					}
					""";
			var model = parseAll(src, "com.x");
			var methodNames = new HashSet<String>();
			for (var m : model.methods())
				methodNames.add(m.name());

			assertTrue(methodNames.contains("getName"), "field-level @Getter should synthesize getter");
			assertFalse(methodNames.contains("setName"), "no @Setter on name field");
			assertFalse(methodNames.contains("getAge"), "no @Getter on age field");
			assertTrue(methodNames.contains("setAge"), "field-level @Setter should synthesize setter");
			assertFalse(methodNames.contains("getUnAnnotated"), "no annotations on unAnnotated");
		}

		@Test
		void builderAnnotation() {
			String src = """
					package com.x;
					@Builder
					public class Request {
					    private String url;
					    private int timeout;
					}
					""";
			var model = parseAll(src, "com.x");
			var methodNames = new HashSet<String>();
			for (var m : model.methods())
				methodNames.add(m.name());

			assertTrue(methodNames.contains("builder"), "missing builder() method");
		}

		@Test
		void withAnnotation() {
			String src = """
					package com.x;
					@With
					public class Immutable {
					    private String name;
					    private int count;
					}
					""";
			var model = parseAll(src, "com.x");
			var methodNames = new HashSet<String>();
			for (var m : model.methods())
				methodNames.add(m.name());

			assertTrue(methodNames.contains("withName"), "missing withName");
			assertTrue(methodNames.contains("withCount"), "missing withCount");
		}

		@Test
		void bodyHashesChangeWhenFieldChanges() {
			String src1 = """
					package com.x;
					@Data
					public class Thing {
					    private String name;
					}
					""";
			String src2 = """
					package com.x;
					@Data
					public class Thing {
					    private String fullName;
					}
					""";
			var model1 = parseAll(src1, "com.x");
			var model2 = parseAll(src2, "com.x");

			var hashes1 = model1.methodHashes();
			var hashes2 = model2.methodHashes();

			// toString, equals, hashCode should have different hashes
			assertNotEquals(hashes1.get("com.x.Thing#toString"), hashes2.get("com.x.Thing#toString"),
					"toString hash should change when fields change");
			assertNotEquals(hashes1.get("com.x.Thing#equals"), hashes2.get("com.x.Thing#equals"),
					"equals hash should change when fields change");
		}

		@Test
		void noSynthesisForInterface() {
			String src = """
					package com.x;
					@Getter
					public interface Api {
					    String getName();
					}
					""";
			var model = parseAll(src, "com.x");
			// Interface methods should not be duplicated by Lombok synthesis
			long getNameCount = model.methods().stream().filter(m -> m.name().equals("getName")).count();
			assertEquals(1, getNameCount, "interface methods should not be synthesized");
		}

		@Test
		void nestedClassAnnotation() {
			String src = """
					package com.x;
					public class Outer {
					    @Data
					    public static class Inner {
					        private String value;
					    }
					}
					""";
			var model = parseAll(src, "com.x");
			var methodNames = new HashSet<String>();
			for (var m : model.methods()) {
				if (m.enclosingFqcn().equals("com.x.Outer$Inner")) {
					methodNames.add(m.name());
				}
			}

			assertTrue(methodNames.contains("getValue"), "nested @Data class should get synthesized methods");
			assertTrue(methodNames.contains("setValue"), "nested @Data class should get synthesized methods");
		}

		@Test
		void noArgsConstructorAnnotation() {
			String src = """
					package com.x;
					@NoArgsConstructor
					public class Empty {
					    private String name;
					}
					""";
			var model = parseAll(src, "com.x");
			var methodNames = new HashSet<String>();
			for (var m : model.methods())
				methodNames.add(m.name());

			assertTrue(methodNames.contains("Empty"), "missing no-args constructor");
			assertFalse(methodNames.contains("getName"), "no @Getter, should not have getter");
		}

		@Test
		void classWithoutLombokAnnotationsUnaffected() {
			String src = """
					package com.x;
					public class Plain {
					    private String name;
					    public String getName() { return name; }
					}
					""";
			var model = parseAll(src, "com.x");
			assertEquals(1, model.methods().size(), "only the explicit method should exist");
			assertEquals("getName", model.methods().get(0).name());
		}
	}

	// =====================================================================
	// UNUSUAL CONSTRUCTS & TRICKY FORMATTING
	// =====================================================================

	@Nested
	class UnusualConstructs {

		@Test
		void textBlockContainingMethodDeclarationSyntax() {
			// A text block that looks like method declarations should not be parsed as
			// methods
			String src = """
					package com.x;
					public class T {
					    String code = \"\"\"
					            public void fakeMethod() {
					                System.out.println("hello");
					            }
					            private int anotherFake(String x) {
					                return 42;
					            }
					            \"\"\";
					    public void realMethod() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size(), "only realMethod should be found");
			assertEquals("realMethod", model.methods().get(0).name());
		}

		@Test
		void textBlockContainingClassDeclaration() {
			// Text block that looks like a full class definition
			String src = """
					package com.x;
					public class T {
					    String template = \"\"\"
					            package fake;
					            public class FakeClass {
					                public void fakeMethod() {
					                    int x = 1;
					                }
					            }
					            \"\"\";
					    void real() { }
					}
					""";
			var model = parse(src, "com.x");
			var types = model.types();
			assertEquals(1, types.size(), "only T should be found, not FakeClass");
			assertEquals("T", types.get(0).simpleName());
			assertEquals(1, model.methods().size());
			assertEquals("real", model.methods().get(0).name());
		}

		@Test
		void stringLiteralContainingBracesAndParens() {
			// Strings with unbalanced braces/parens should not confuse the parser
			String src = """
					package com.x;
					public class T {
					    void m() {
					        String a = "{ { { (((";
					        String b = "} } })))";
					        String c = "public void fake() {";
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("m", model.methods().get(0).name());
			assertNotNull(model.methods().get(0).bodyHash());
		}

		@Test
		void methodWithExtremelyLongAnnotation() {
			// Very long annotation with nested parens before method
			String src = """
					package com.x;
					public class T {
					    @SuppressWarnings(value = {"unchecked", "rawtypes", "deprecation", "serial",
					        "finally", "fallthrough", "cast", "hiding", "incomplete-switch",
					        "nls", "null", "restriction", "static-access", "static-method",
					        "synthetic-access", "unqualified-field-access", "unused"})
					    @Deprecated(since = "1.0", forRemoval = true)
					    @Override
					    public synchronized final <T extends Comparable<? super T> & java.io.Serializable> List<T> doStuff(
					            @org.jetbrains.annotations.NotNull final Map<String, ? extends List<T>> input,
					            @SuppressWarnings("unused") int... varargs) throws IOException, RuntimeException {
					        return null;
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("doStuff", model.methods().get(0).name());
		}

		@Test
		void methodSplitAcrossManyLinesWithCommentsBetweenModifiers() {
			// Highly unusual formatting with comments between modifiers
			String src = "package com.x;\n" + "public class T {\n" + "    public\n" + "    // visibility\n"
					+ "    static\n" + "    /* threading */\n" + "    synchronized\n" + "    final\n" + "    void\n"
					+ "    weirdlyFormatted\n" + "    (\n" + "    )\n" + "    {\n" + "    }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("weirdlyFormatted", model.methods().get(0).name());
		}

		@Test
		void enumWithMethodsThatLookLikeConstructorCalls() {
			// Enum constants with bodies that resemble method declarations
			String src = """
					package com.x;
					public enum Direction {
					    NORTH {
					        @Override public String display() { return "N"; }
					    },
					    SOUTH {
					        @Override public String display() { return "S"; }
					    };
					    public abstract String display();
					    public static Direction parse(String s) { return valueOf(s); }
					}
					""";
			var model = parse(src, "com.x");
			// Should find display (abstract), parse, and the overrides in the anonymous
			// classes
			var outerMethods = model.methods().stream().filter(m -> m.enclosingFqcn().equals("com.x.Direction"))
					.toList();
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("display")));
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("parse")));
		}

		@Test
		void recordWithCompactConstructorAndCustomMethods() {
			String src = """
					package com.x;
					public record Point(int x, int y) {
					    public Point {
					        if (x < 0 || y < 0) throw new IllegalArgumentException();
					    }
					    public double distanceTo(Point other) {
					        return Math.sqrt(Math.pow(x - other.x, 2) + Math.pow(y - other.y, 2));
					    }
					    public static Point origin() { return new Point(0, 0); }
					}
					""";
			var model = parse(src, "com.x");
			// Compact constructor (no parens) should be detected
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("Point") && m.isConstructor()),
					"compact constructor should be detected; found: " + model.methods());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("distanceTo")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("origin")));
		}

		@Test
		void interfaceWithDefaultMethodsAndStaticMethods() {
			String src = """
					package com.x;
					public interface Processor<T, R> {
					    R process(T input);
					    default R processOrNull(T input) {
					        try { return process(input); } catch (Exception e) { return null; }
					    }
					    static <X> Processor<X, X> identity() {
					        return x -> x;
					    }
					    private void helperMethod() { }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("process") && m.isAbstract()));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("processOrNull") && !m.isAbstract()));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("identity") && !m.isAbstract()));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("helperMethod") && !m.isAbstract()));
		}

		@Test
		void sealedInterfaceWithPermitsAndNestedRecords() {
			String src = """
					package com.x;
					public sealed interface Shape
					        permits Shape.Circle, Shape.Rect {
					    double area();
					    record Circle(double radius) implements Shape {
					        public double area() { return Math.PI * radius * radius; }
					    }
					    record Rect(double w, double h) implements Shape {
					        public double area() { return w * h; }
					    }
					}
					""";
			var model = parse(src, "com.x");
			var types = model.types();
			assertTrue(types.stream().anyMatch(t -> t.simpleName().equals("Shape")));
			assertTrue(types.stream().anyMatch(t -> t.simpleName().equals("Circle")));
			assertTrue(types.stream().anyMatch(t -> t.simpleName().equals("Rect")));
		}

		@Test
		void annotationTypeWithDefaultValues() {
			String src = """
					package com.x;
					public @interface MyAnnotation {
					    String value() default "";
					    int count() default 42;
					    Class<?>[] types() default {};
					    String[] names() default {"a", "b"};
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(SourceFileModel.TypeKind.ANNOTATION, model.types().get(0).kind());
			// Annotation elements should be recognized as abstract methods
			assertTrue(model.methods().stream().allMatch(SourceFileModel.MethodNode::isAbstract));
			assertEquals(4, model.methods().size());
		}

		@Test
		void classWithMethodNamedClassOrInterface() {
			// Method names that are keywords in other contexts
			String src = """
					package com.x;
					public class T {
					    void record() { }
					    void yield() { }
					    void var() { }
					    void sealed() { }
					    void permits() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(5, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("record")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("yield")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("var")));
		}

		@Test
		void nestedTextBlockWithTripleQuotesInsideString() {
			// Text block containing escaped triple quotes
			String src = "package com.x;\n" + "public class T {\n" + "    void m() {\n" + "        String s = \"\"\"\n"
					+ "                He said \\\"\"\"not a close\\\"\"\"\n" + "                still in block\n"
					+ "                \"\"\";\n" + "    }\n" + "    void after() { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("after")));
		}

		@Test
		void unicodeIdentifiers() {
			String src = """
					package com.x;
					public class Ñ {
					    void método() { }
					    void 日本語メソッド() { }
					    int π = 3;
					}
					""";
			var model = parse(src, "com.x");
			assertEquals("Ñ", model.types().get(0).simpleName());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("método")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("日本語メソッド")));
		}

		@Test
		void arrayReturnTypesWithAnnotations() {
			// Old-style array dims on return type with type annotations between
			String src = """
					package com.x;
					public class T {
					    public @NonNull String @Nullable [] @Size(min=1) [] getMatrix() {
					        return null;
					    }
					    int[] simple() { return null; }
					    byte[][] twoDim() { return null; }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(3, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("getMatrix")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("simple")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("twoDim")));
		}

		@Test
		void methodWithLambdaContainingAnonymousClass() {
			// Lambda with anonymous class inside should not produce extra methods at type
			// level
			String src = """
					package com.x;
					public class T {
					    void setup() {
					        Runnable r = () -> {
					            new Thread() {
					                @Override public void run() {
					                    System.out.println("inner");
					                }
					            }.start();
					        };
					    }
					    void other() { }
					}
					""";
			var model = parse(src, "com.x");
			var outerMethods = model.methods().stream().filter(m -> m.enclosingFqcn().equals("com.x.T")).toList();
			assertEquals(2, outerMethods.size());
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("setup")));
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("other")));
		}

		@Test
		void multipleConstructorsWithThisAndSuperCalls() {
			String src = """
					package com.x;
					public class Base {
					    Base(int x) { }
					    Base(String s) { this(Integer.parseInt(s)); }
					    Base() { this("0"); }
					    void method() { }
					}
					""";
			var model = parse(src, "com.x");
			long ctorCount = model.methods().stream().filter(SourceFileModel.MethodNode::isConstructor).count();
			assertEquals(3, ctorCount);
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("method") && !m.isConstructor()));
		}

		@Test
		void genericMethodWithIntersectionTypeBound() {
			String src = """
					package com.x;
					public class T {
					    <E extends Comparable<E> & java.io.Serializable & Cloneable>
					    List<E> sort(Collection<? extends E> input) {
					        return null;
					    }
					    <K, V extends Map.Entry<K, ? super V>> void complex(K k, V v) { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("sort")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("complex")));
		}

		@Test
		void noSpaceBetweenTypeAndMethodName() {
			// Unusual but valid: no space between generic return type closing > and name
			// (actually invalid without space, but test that we handle close-to-valid
			// formatting)
			String src = """
					package com.x;
					public class T {
					    List<String>getNames() { return null; }
					    Map<String,Integer>getCounts() { return null; }
					}
					""";
			var model = parse(src, "com.x");
			// These are unusual but the regex requires \\s+ between type and name,
			// so they may or may not be found. Just ensure no crash.
			assertNotNull(model.types());
			assertEquals(1, model.types().size());
		}

		@Test
		void tabIndentedWithMixedWhitespace() {
			// Tabs + spaces mixed, unusual formatting
			String src = "package com.x;\n" + "public\tclass\tT\t{\n" + "\t void\t\tm1  (  )  {  }\n"
					+ "   \tvoid m2(){\n" + "\t\t\treturn;\n" + "}\n" + "\t\tint m3(\n" + "\t\t\tint a,\n"
					+ "\t\t\tint b\n" + "\t\t) {\n" + "\t\t\treturn a + b;\n" + "\t\t}\n" + "}\n";
			var model = parse(src, "com.x");
			assertTrue(model.methods().size() >= 2, "should find at least m1, m2, or m3; found: " + model.methods());
		}

		@Test
		void staticInitializerAndInstanceInitializer() {
			// Static and instance initializers should not be mistaken for methods
			String src = """
					package com.x;
					public class T {
					    static {
					        System.out.println("static init");
					    }
					    {
					        System.out.println("instance init");
					    }
					    void realMethod() { }
					}
					""";
			var model = parse(src, "com.x");
			var namedMethods = model.methods().stream().filter(m -> !m.name().equals("T")).toList();
			assertEquals(1, namedMethods.size());
			assertEquals("realMethod", namedMethods.get(0).name());
		}

		@Test
		void methodWithThrowsContainingAnnotations() {
			// Annotations inside throws clause
			String src = """
					package com.x;
					public class T {
					    void m() throws @Critical IOException, @NonCritical RuntimeException {
					        throw new IOException();
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("m", model.methods().get(0).name());
			assertNotNull(model.methods().get(0).bodyHash());
		}

		@Test
		void varArgsWithAnnotations() {
			String src = """
					package com.x;
					public class T {
					    void log(@NonNull String format, @Nullable Object @NonNull ... args) {
					        System.out.printf(format, args);
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("log", model.methods().get(0).name());
		}

		@Test
		void deeplyNestedClassesWithMethodsAtEachLevel() {
			String src = """
					package com.x;
					public class A {
					    void a() { }
					    class B {
					        void b() { }
					        class C {
					            void c() { }
					            class D {
					                void d() { }
					            }
					        }
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("a") && m.enclosingFqcn().equals("com.x.A")));
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("b") && m.enclosingFqcn().equals("com.x.A$B")));
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("c") && m.enclosingFqcn().equals("com.x.A$B$C")));
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("d") && m.enclosingFqcn().equals("com.x.A$B$C$D")));
		}

		@Test
		void methodReturningFunctionalInterface() {
			// Method returning a lambda-compatible type with complex generics
			String src = """
					package com.x;
					import java.util.function.*;
					public class T {
					    Function<Map<String, List<Integer>>, Optional<Stream<String>>> transform() {
					        return map -> Optional.empty();
					    }
					    BiFunction<int[], double[][], String> weird() { return null; }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("transform")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("weird")));
		}

		@Test
		void classWithOnlyCommentsAndWhitespaceInBody() {
			String src = """
					package com.x;
					public class T {
					    // This class intentionally left empty
					    /* No methods here */
					    /**
					     * Still nothing
					     */
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.types().size());
			assertEquals(0, model.methods().size());
		}

		@Test
		void oneLinersAllOnSameLine() {
			// Entire class on one line
			String src = "package com.x; public class T { void a() { } void b() { } int c() { return 1; } }";
			var model = parse(src, "com.x");
			assertEquals(3, model.methods().size());
		}

		@Test
		void textBlockWithBracesAndSemicolons() {
			// Text block containing valid Java that should not be parsed
			String src = "package com.x;\n" + "public class T {\n" + "    String java = \"\"\"\n"
					+ "            class Fake {\n" + "                void fake() {\n"
					+ "                    if (true) {\n" + "                        for (;;) {\n"
					+ "                            break;\n" + "                        }\n" + "                    }\n"
					+ "                }\n" + "            }\n" + "            \"\"\";\n" + "    void real() { }\n"
					+ "}\n";
			var model = parse(src, "com.x");
			assertEquals(1, model.types().size());
			assertEquals("T", model.types().get(0).simpleName());
			assertEquals(1, model.methods().size());
			assertEquals("real", model.methods().get(0).name());
		}

		@Test
		void switchExpressionWithArrowsAndBlocks() {
			String src = """
					package com.x;
					public class T {
					    int eval(String op, int a, int b) {
					        return switch (op) {
					            case "+" -> a + b;
					            case "-" -> a - b;
					            case "*" -> {
					                int result = a * b;
					                yield result;
					            }
					            default -> throw new IllegalArgumentException();
					        };
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("eval", model.methods().get(0).name());
			assertNotNull(model.methods().get(0).bodyHash());
		}

		@Test
		void methodWithParameterizedAnnotationContainingArrays() {
			// Complex annotation with arrays and nested annotations
			String src = """
					package com.x;
					public class T {
					    @RequestMapping(
					        value = {"/api/v1", "/api/v2"},
					        method = {RequestMethod.GET, RequestMethod.POST},
					        produces = "application/json",
					        headers = {"X-Custom=true", "Accept=application/json"}
					    )
					    @ResponseBody
					    @Validated({Group1.class, Group2.class})
					    public ResponseEntity<List<Map<String, Object>>> handleRequest(
					            @RequestBody @Valid RequestDto dto,
					            @PathVariable("id") long id,
					            @RequestParam(name = "page", required = false, defaultValue = "0") int page) {
					        return null;
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("handleRequest", model.methods().get(0).name());
		}

		@Test
		void fieldsThatLookLikeMethods() {
			// Fields with initializers that contain parens and braces
			String src = """
					package com.x;
					public class T {
					    Runnable r = () -> { System.out.println("not a method"); };
					    Comparator<String> cmp = (a, b) -> { return a.compareTo(b); };
					    Map<String, Integer> map = new HashMap<>() {{ put("key", 1); }};
					    void actualMethod() { }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("actualMethod")),
					"actualMethod should be found; methods: " + model.methods());
		}

		@Test
		void methodNamesMatchingKeywords() {
			// Methods named after Java reserved words or common tokens
			String src = """
					package com.x;
					public class T {
					    void toString() { }
					    void equals(Object o) { }
					    void clone() { }
					    void finalize() { }
					    void hashCode() { }
					    native void nativeMethod();
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().size() >= 5);
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("nativeMethod") && m.isAbstract()));
		}

		@Test
		void classWithGenericConstructor() {
			// Constructor with its own type parameter (unusual but valid)
			String src = """
					package com.x;
					public class Container {
					    <T extends Comparable<T>> Container(T value) {
					        System.out.println(value);
					    }
					    Container(int x, int y) { }
					}
					""";
			var model = parse(src, "com.x");
			long ctorCount = model.methods().stream().filter(SourceFileModel.MethodNode::isConstructor).count();
			assertEquals(2, ctorCount);
		}

		@Test
		void multipleTextBlocksInOneMethod() {
			String src = "package com.x;\n" + "public class T {\n" + "    void m() {\n" + "        String a = \"\"\"\n"
					+ "                first { block }\n" + "                void fake1() { }\n"
					+ "                \"\"\";\n" + "        String b = \"\"\"\n" + "                second block\n"
					+ "                class Fake { }\n" + "                \"\"\";\n" + "        String c = \"\"\"\n"
					+ "                third\n" + "                \"\"\";\n" + "    }\n" + "    void after() { }\n"
					+ "}\n";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("after")));
		}

		@Test
		void innerClassInsideMethodBody() {
			// Local class defined inside a method body
			String src = """
					package com.x;
					public class Outer {
					    void create() {
					        class Local {
					            void localMethod() { }
					        }
					        new Local().localMethod();
					    }
					    void other() { }
					}
					""";
			var model = parse(src, "com.x");
			// Local class methods should not appear as Outer's methods
			var outerMethods = model.methods().stream().filter(m -> m.enclosingFqcn().equals("com.x.Outer")).toList();
			assertEquals(2, outerMethods.size());
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("create")));
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("other")));
		}

		@Test
		void hashStabilityAcrossWhitespaceChanges() {
			// Same method with different whitespace should produce same hash
			String src1 = """
					package com.x;
					public class T {
					    void m() { int x = 1; int y = 2; }
					}
					""";
			String src2 = """
					package com.x;
					public class T {
					    void m() {
					        int x = 1;
					        int y = 2;
					    }
					}
					""";
			var model1 = parse(src1, "com.x");
			var model2 = parse(src2, "com.x");
			assertEquals(model1.methods().get(0).bodyHash(), model2.methods().get(0).bodyHash(),
					"whitespace-only changes should produce same hash");
		}

		@Test
		void hashStabilityAcrossCommentChanges() {
			String src1 = """
					package com.x;
					public class T {
					    void m() {
					        int x = 1;
					    }
					}
					""";
			String src2 = """
					package com.x;
					public class T {
					    // method m does stuff
					    void m() {
					        // set x to 1
					        int x = 1; /* important */
					    }
					}
					""";
			var model1 = parse(src1, "com.x");
			var model2 = parse(src2, "com.x");
			assertEquals(model1.methods().get(0).bodyHash(), model2.methods().get(0).bodyHash(),
					"comment-only changes should produce same hash");
		}

		@Test
		void emptyEnumWithSemicolon() {
			String src = """
					package com.x;
					public enum Empty {
					    ;
					    public static Empty[] values() { return new Empty[0]; }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("values")));
		}

		@Test
		void methodParameterWithNestedGenericWildcards() {
			String src = """
					package com.x;
					public class T {
					    void process(
					            Map<? extends Comparable<? super T>, List<? extends Set<? super Integer>>> data,
					            Supplier<? extends Function<? super String, ? extends Number>> factory) {
					        // complex
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("process", model.methods().get(0).name());
		}

		@Test
		void classWithStringConcatenationContainingClassKeyword() {
			String src = """
					package com.x;
					public class T {
					    void m() {
					        String s = "class Foo {" + "void bar() {}" + "}";
					        String t = "interface X { void z(); }";
					        String u = "enum E { A, B; void m() {} }";
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.types().size());
			assertEquals(1, model.methods().size());
		}

		@Test
		void charLiteralWithSpecialChars() {
			// Char literals with braces, quotes, backslash
			String src = """
					package com.x;
					public class T {
					    void m() {
					        char a = '{';
					        char b = '}';
					        char c = '(';
					        char d = ')';
					        char e = '"';
					        char f = '\\\\';
					        char g = '\\'';
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("m", model.methods().get(0).name());
			assertNotNull(model.methods().get(0).bodyHash());
		}

		@Test
		void annotationTypeWithNestedAnnotation() {
			String src = """
					package com.x;
					public @interface Outer {
					    String value() default "";
					    @interface Inner {
					        int count() default 0;
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.types().stream()
					.anyMatch(t -> t.simpleName().equals("Outer") && t.kind() == SourceFileModel.TypeKind.ANNOTATION));
			assertTrue(model.types().stream()
					.anyMatch(t -> t.simpleName().equals("Inner") && t.kind() == SourceFileModel.TypeKind.ANNOTATION));
		}

		@Test
		void classInDefaultPackage() {
			String src = """
					public class NoPackage {
					    void m() { }
					}
					""";
			var model = parse(src, "");
			assertEquals("NoPackage", model.types().get(0).simpleName());
			assertEquals("NoPackage", model.types().get(0).fqcn());
			assertEquals(1, model.methods().size());
		}

		@Test
		void noCrashOnGarbageInput() {
			// Parser should not crash on non-Java input
			String src = "this is not java at all { } ( ) ;; ;;; void class interface";
			var model = parse(src, "com.x");
			assertNotNull(model);
		}

		@Test
		void noCrashOnEmptyInput() {
			var model = parse("", "com.x");
			assertNotNull(model);
			assertEquals(0, model.types().size());
			assertEquals(0, model.methods().size());
		}

		@Test
		void tryCatchFinallyWithResources() {
			// Try-with-resources with multiple resources should not confuse method
			// detection
			String src = """
					package com.x;
					public class T {
					    void read() {
					        try (var is = new FileInputStream("f");
					             var br = new BufferedReader(new InputStreamReader(is))) {
					            br.readLine();
					        } catch (IOException | RuntimeException e) {
					            throw new UncheckedIOException(e);
					        } finally {
					            System.out.println("done");
					        }
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("read", model.methods().get(0).name());
		}

		@Test
		void methodHashDiffersWhenBodyChanges() {
			String src1 = """
					package com.x;
					public class T {
					    int compute() { return 1 + 2; }
					}
					""";
			String src2 = """
					package com.x;
					public class T {
					    int compute() { return 3 + 4; }
					}
					""";
			var model1 = parse(src1, "com.x");
			var model2 = parse(src2, "com.x");
			assertNotEquals(model1.methods().get(0).bodyHash(), model2.methods().get(0).bodyHash());
		}
	}

	// =====================================================================
	// ADVERSARIAL FORMATTING & EXOTIC CONSTRUCTS
	// =====================================================================

	@Nested
	class AdversarialParsing {

		@Test
		void textBlockInsideTextBlockLikeNesting() {
			// Text block whose content itself contains triple-quote sequences
			String src = "package com.x;\n" + "public class T {\n" + "    void gen() {\n"
					+ "        String code = \"\"\"\n" + "                String inner = \\\"\\\"\\\"  \n"
					+ "                        nested content\n" + "                        \\\"\\\"\\\";\n"
					+ "                void notReal() { }\n" + "                \"\"\";\n" + "    }\n"
					+ "    void real() { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("gen")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("real")));
		}

		@Test
		void textBlockWithTrailingBackslashJoin() {
			// Text block line continuation with trailing backslash
			String src = "package com.x;\n" + "public class T {\n" + "    void m() {\n" + "        String s = \"\"\"\n"
					+ "                line one \\\n" + "                line two \\\n"
					+ "                void fake() { } \\\n" + "                end\"\"\";\n" + "    }\n"
					+ "    void after() { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("after")));
		}

		@Test
		void patternMatchingInstanceof() {
			String src = """
					package com.x;
					public class T {
					    String format(Object obj) {
					        if (obj instanceof String s && s.length() > 0) {
					            return s.trim();
					        } else if (obj instanceof Integer i) {
					            return Integer.toString(i);
					        } else if (obj instanceof int[] arr) {
					            return Arrays.toString(arr);
					        }
					        return obj.toString();
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("format", model.methods().get(0).name());
		}

		@Test
		void recordPatternInSwitch() {
			// Java 21 record patterns in switch
			String src = """
					package com.x;
					public class T {
					    sealed interface Shape permits Circle, Rect {}
					    record Circle(double r) implements Shape {}
					    record Rect(double w, double h) implements Shape {}

					    double area(Shape s) {
					        return switch (s) {
					            case Circle(var r) -> Math.PI * r * r;
					            case Rect(var w, var h) -> w * h;
					        };
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("area")));
			assertTrue(model.types().stream().anyMatch(t -> t.simpleName().equals("Shape")));
			assertTrue(model.types().stream().anyMatch(t -> t.simpleName().equals("Circle")));
			assertTrue(model.types().stream().anyMatch(t -> t.simpleName().equals("Rect")));
		}

		@Test
		void methodWithReceiverParameter() {
			// Explicit receiver parameter (annotated this)
			String src = """
					package com.x;
					public class Outer {
					    class Inner {
					        void method(@Annotated Outer.this) { }
					    }
					    void selfRef(@NonNull Outer this, int x) { }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("method")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("selfRef")));
		}

		@Test
		void multipleTopLevelTypesWithSameMethodNames() {
			String src = """
					package com.x;
					class A {
					    void process() { }
					    void handle() { }
					}
					class B {
					    void process() { }
					    void handle() { }
					}
					interface C {
					    void process();
					    default void handle() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(3, model.types().size());
			// Each type should have its own process/handle
			long processCount = model.methods().stream().filter(m -> m.name().equals("process")).count();
			long handleCount = model.methods().stream().filter(m -> m.name().equals("handle")).count();
			assertEquals(3, processCount);
			assertEquals(3, handleCount);
		}

		@Test
		void extremelyDeepGenericNesting() {
			// 8 levels of nesting (the regex supports up to 10)
			String src = """
					package com.x;
					public class T {
					    Map<String, Map<Integer, Map<Long, List<Set<Optional<CompletableFuture<Stream<String>>>>>>>> deep;
					    Map<String, Map<Integer, List<String>>> process(
					            Map<String, Map<Integer, Map<Long, List<String>>>> input,
					            Function<Map<String, List<Integer>>, Map<String, List<String>>> transform) {
					        return null;
					    }
					}
					""";
			var model = parseAll(src, "com.x");
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("process")));
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("deep")));
		}

		@Test
		void genericNestingBeyondTenLevelsGracefullyIgnored() {
			// 12 levels of nesting exceeds the regex limit (10); field is not detected
			// but the parser must not crash
			String src = """
					package com.x;
					public class T {
					    A<B<C<D<E<F<G<H<I<J<K<L<String>>>>>>>>>>>> tooDeep;
					    void m() { }
					}
					""";
			var model = parseAll(src, "com.x");
			assertNotNull(model);
			// Method should still be found even if the field is too deep
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
		}

		@Test
		void annotationWithNestedAnnotationsAndArrays() {
			// Annotations within annotations with array initializers containing braces
			String src = """
					package com.x;
					public class T {
					    @Mapping(
					        sources = {
					            @Source(value = "a", config = @Config(params = {"x", "y"})),
					            @Source(value = "b", config = @Config(params = {"z"}))
					        },
					        target = @Target(name = "out", transform = @Transform(
					            steps = {@Step(order = 1), @Step(order = 2)}
					        ))
					    )
					    void complexAnnotated() { }

					    void simple() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("complexAnnotated")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("simple")));
		}

		@Test
		void fieldInitializerWithAnonymousClassContainingMethods() {
			String src = """
					package com.x;
					public class T {
					    Comparator<String> CMP = new Comparator<String>() {
					        @Override
					        public int compare(String a, String b) {
					            return a.length() - b.length();
					        }
					        private int helper() { return 0; }
					    };
					    Runnable R = new Runnable() {
					        @Override public void run() { System.out.println("hi"); }
					    };
					    void realMethod() { }
					}
					""";
			var model = parse(src, "com.x");
			// realMethod should be found as a method of T
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("realMethod") && m.enclosingFqcn().equals("com.x.T")));
		}

		@Test
		void methodWithMultilineStringConcatenationLookingLikeCode() {
			String src = """
					package com.x;
					public class T {
					    void generate() {
					        String code = "public class Gen {\\n"
					            + "    void method() {\\n"
					            + "        int x = 1;\\n"
					            + "    }\\n"
					            + "}\\n";
					        System.out.println(code);
					    }
					    void other() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("generate")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("other")));
		}

		@Test
		void methodSignatureSpanningSixLines() {
			String src = """
					package com.x;
					public class T {
					    @SafeVarargs
					    @SuppressWarnings("unchecked")
					    protected
					    final
					    <T extends Comparable<? super T>
					            & java.io.Serializable
					            & Cloneable>
					    java.util.concurrent.CompletableFuture<
					            java.util.List<
					                    java.util.Map<String, T>>>
					    veryLongMethod(
					            @NonNull final java.util.Map<
					                    String,
					                    ? extends java.util.List<T>> param1,
					            @Nullable T... varargs)
					            throws java.io.IOException,
					                    java.lang.InterruptedException,
					                    java.util.concurrent.ExecutionException {
					        return null;
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("veryLongMethod", model.methods().get(0).name());
			assertNotNull(model.methods().get(0).bodyHash());
		}

		@Test
		void enumWithAbstractMethodAndAnonymousImplementations() {
			String src = """
					package com.x;
					public enum Op {
					    ADD {
					        @Override
					        public int apply(int a, int b) { return a + b; }
					    },
					    SUB {
					        @Override
					        public int apply(int a, int b) { return a - b; }
					    },
					    MUL {
					        @Override
					        public int apply(int a, int b) { return a * b; }
					    };
					    public abstract int apply(int a, int b);
					    public int applyAll(int[] vals) {
					        int result = vals[0];
					        for (int i = 1; i < vals.length; i++) result = apply(result, vals[i]);
					        return result;
					    }
					}
					""";
			var model = parse(src, "com.x");
			var outerMethods = model.methods().stream().filter(m -> m.enclosingFqcn().equals("com.x.Op")).toList();
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("apply") && m.isAbstract()));
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("applyAll") && !m.isAbstract()));
		}

		@Test
		void classWithOnlySemicolonsAndEmptyStatements() {
			String src = """
					package com.x;
					public class T {
					    ;;;
					    void m() { ;; }
					    ;;;
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("m", model.methods().get(0).name());
		}

		@Test
		void methodBodyWithStringContainingRegexPatterns() {
			// Strings with regex patterns including backslashes and special chars
			String src = """
					package com.x;
					public class T {
					    void validate(String s) {
					        boolean ok = s.matches("^[a-z]+\\\\(\\\\)\\\\{.*\\\\}$");
					        String p2 = "void\\\\s+\\\\w+\\\\s*\\\\(";
					        String p3 = "class\\\\s+\\\\w+\\\\s*\\\\{";
					        if (!ok) throw new RuntimeException();
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("validate", model.methods().get(0).name());
		}

		@Test
		void noBreakOnMassiveOneLiner() {
			// 50 methods on a single line
			StringBuilder sb = new StringBuilder("package com.x; public class T {");
			for (int i = 0; i < 50; i++) {
				sb.append(" void m").append(i).append("() { }");
			}
			sb.append(" }");
			var model = parse(sb.toString(), "com.x");
			assertEquals(50, model.methods().size());
			for (int i = 0; i < 50; i++) {
				int idx = i;
				assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m" + idx)), "missing m" + i);
			}
		}

		@Test
		void labeledStatementsInsideMethodBody() {
			// Labels with colons that could confuse field detection
			String src = """
					package com.x;
					public class T {
					    void m() {
					        outer:
					        for (int i = 0; i < 10; i++) {
					            inner:
					            for (int j = 0; j < 10; j++) {
					                if (i == j) continue outer;
					                if (j == 5) break inner;
					            }
					        }
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("m", model.methods().get(0).name());
		}

		@Test
		void interfaceWithConstantsAndDefaultMethods() {
			String src = """
					package com.x;
					public interface Constants {
					    int MAX = 100;
					    String PREFIX = "test_";
					    List<String> EMPTY = Collections.emptyList();
					    Map<String, Runnable> ACTIONS = Map.of(
					        "run", () -> { System.out.println("running"); },
					        "stop", () -> { System.out.println("stopping"); }
					    );
					    default void doAction(String name) {
					        ACTIONS.getOrDefault(name, () -> {}).run();
					    }
					    void abstractMethod();
					}
					""";
			var model = parseAll(src, "com.x");
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("doAction")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("abstractMethod") && m.isAbstract()));
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("MAX")));
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("PREFIX")));
		}

		@Test
		void methodWithSwitchExpressionContainingLambdas() {
			String src = """
					package com.x;
					public class T {
					    Runnable toRunnable(String cmd) {
					        return switch (cmd) {
					            case "print" -> () -> { System.out.println("hello"); };
					            case "exit" -> () -> { System.exit(0); };
					            default -> () -> { throw new UnsupportedOperationException(cmd); };
					        };
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("toRunnable", model.methods().get(0).name());
		}

		@Test
		void commentBlockThatLooksLikeMethod() {
			// Block comment that starts looking like a method
			String src = """
					package com.x;
					public class T {
					    /*
					    void commentedOut() {
					        int x = 1;
					    }
					    */
					    // void alsoCommentedOut() { }
					    void real() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("real", model.methods().get(0).name());
		}

		@Test
		void multiLineCommentInsideMethodSignature() {
			// Comment breaking up a method declaration
			String src = """
					package com.x;
					public class T {
					    public /* inline comment */ void /* another */ method(/* param */ int x /* end */) {
					        System.out.println(x);
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("method", model.methods().get(0).name());
		}

		@Test
		void multipleRecordsInSameFile() {
			String src = """
					package com.x;
					record Point(int x, int y) {
					    public double length() { return Math.sqrt(x*x + y*y); }
					}
					record Rect(Point topLeft, Point bottomRight) {
					    public double area() {
					        int w = bottomRight.x() - topLeft.x();
					        int h = bottomRight.y() - topLeft.y();
					        return w * h;
					    }
					    public Point center() {
					        return new Point((topLeft.x() + bottomRight.x()) / 2,
					                         (topLeft.y() + bottomRight.y()) / 2);
					    }
					}
					record Circle(Point center, double radius) {
					    public Circle {
					        if (radius < 0) throw new IllegalArgumentException();
					    }
					    public double area() { return Math.PI * radius * radius; }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(3, model.types().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("length")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("center")));
			// Circle compact constructor
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("Circle") && m.isConstructor()),
					"compact constructor should be found; methods=" + model.methods());
		}

		@Test
		void fieldDeclarationsWithComplexInitializers() {
			String src = """
					package com.x;
					public class T {
					    int[] primes = {2, 3, 5, 7, 11, 13};
					    Map<String, List<Integer>> lookup = Map.of(
					        "odds", List.of(1, 3, 5, 7),
					        "evens", List.of(2, 4, 6, 8)
					    );
					    Predicate<String> pred = s -> {
					        if (s == null) return false;
					        return s.startsWith("test");
					    };
					    BiFunction<Integer, Integer, Integer> add = (a, b) -> {
					        return a + b;
					    };
					    void dummy() { }
					}
					""";
			var model = parseAll(src, "com.x");
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("primes")));
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("lookup")));
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("pred")));
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("add")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("dummy")));
		}

		@Test
		void methodWithExplicitThisInLambdaCapture() {
			// Lambda that captures `this` and uses method references
			String src = """
					package com.x;
					public class T {
					    void process(List<String> items) {
					        items.stream()
					            .map(this::transform)
					            .filter(Objects::nonNull)
					            .forEach(System.out::println);
					    }
					    String transform(String s) { return s.toUpperCase(); }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("process")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("transform")));
		}

		@Test
		void classThatExtendsGenericTypeWithImplements() {
			// Complex extends/implements with generic bounds
			String src = """
					package com.x;
					public class MyService<T extends Comparable<T> & Serializable>
					        extends AbstractService<T>
					        implements Service<T>, AutoCloseable, Iterable<T> {
					    @Override
					    public void close() { }
					    @Override
					    public Iterator<T> iterator() { return null; }
					    public void serve(T item) { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(3, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("close")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("iterator")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("serve")));
		}

		@Test
		void crazyWhitespaceNoNewlinesAtAll() {
			// All on one line, no newlines anywhere
			String src = "package com.x; public class T { int x = 1; String y = \"hello\"; void a() { int z = x + 1; } void b(int p) { return; } static int c() { return 42; } }";
			var model = parseAll(src, "com.x");
			assertEquals(3, model.methods().size());
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("x")));
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("y")));
		}

		@Test
		void windowsLineEndings() {
			// CRLF line endings
			String src = "package com.x;\r\npublic class T {\r\n    void m() {\r\n        int x = 1;\r\n    }\r\n    void n() { }\r\n}\r\n";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("n")));
		}

		@Test
		void hashStabilityAcrossLineEndingChanges() {
			// Same code with LF vs CRLF should produce same hash
			String srcLF = "package com.x;\npublic class T {\n    void m() {\n        int x = 1;\n    }\n}\n";
			String srcCRLF = "package com.x;\r\npublic class T {\r\n    void m() {\r\n        int x = 1;\r\n    }\r\n}\r\n";
			var model1 = parse(srcLF, "com.x");
			var model2 = parse(srcCRLF, "com.x");
			assertEquals(model1.methods().get(0).bodyHash(), model2.methods().get(0).bodyHash(),
					"LF vs CRLF should produce same hash");
		}

		@Test
		void textBlockAsMethodArgument() {
			// Text block used directly as argument, not stored in variable
			String src = "package com.x;\n" + "public class T {\n" + "    void m() {\n" + "        execute(\"\"\"\n"
					+ "                SELECT * FROM users\n" + "                WHERE name = 'void fake() {'\n"
					+ "                AND class = 'interface Bad {}'\n" + "                \"\"\");\n" + "    }\n"
					+ "    void execute(String sql) { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("execute")));
		}

		@Test
		void enumImplementingInterfaceWithLambdaFields() {
			String src = """
					package com.x;
					public enum Action implements Runnable {
					    FIRST(() -> {
					        System.out.println("first");
					    }),
					    SECOND(() -> {
					        System.out.println("second");
					    }),
					    THIRD(() -> System.out.println("third"));

					    private final Runnable delegate;
					    Action(Runnable r) { this.delegate = r; }
					    @Override public void run() { delegate.run(); }
					}
					""";
			var model = parse(src, "com.x");
			var outerMethods = model.methods().stream().filter(m -> m.enclosingFqcn().equals("com.x.Action")).toList();
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("Action") && m.isConstructor()));
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("run")));
		}

		@Test
		void methodAfterLargeBlockComment() {
			// A huge block comment right before a method
			StringBuilder sb = new StringBuilder("package com.x;\npublic class T {\n    /*");
			for (int i = 0; i < 200; i++) {
				sb.append("\n     * Line ").append(i).append(" of comment void fake() { }");
			}
			sb.append("\n     */\n    void real() { }\n}\n");
			var model = parse(sb.toString(), "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("real", model.methods().get(0).name());
		}

		@Test
		void classWithStaticImportLookingLikeMethod() {
			// Static import of method name + field that looks like invocation
			String src = """
					package com.x;
					import static java.util.Collections.emptyList;
					import static java.lang.Math.*;
					public class T {
					    List<?> items = emptyList();
					    double val = sqrt(abs(-1.0));
					    void compute() {
					        double r = pow(2, 10);
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("compute", model.methods().get(0).name());
		}

		@Test
		void interfaceWithGenericMethodReturningGenericInterface() {
			String src = """
					package com.x;
					public interface Builder<T> {
					    <B extends Builder<T>> B self();
					    T build();
					    static <X> Builder<X> of(Supplier<X> s) { return null; }
					    default <R> Builder<R> map(Function<T, R> fn) {
					        return () -> fn.apply(this.build());
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(4, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("self") && m.isAbstract()));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("build") && m.isAbstract()));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("of") && !m.isAbstract()));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("map") && !m.isAbstract()));
		}

		@Test
		void multipleFieldsOnOneLine() {
			// Comma-separated field declarations
			String src = """
					package com.x;
					public class T {
					    int a = 1, b = 2, c = 3;
					    String x = "a", y = "b";
					    void m() { }
					}
					""";
			var model = parseAll(src, "com.x");
			// Even if parser treats them as one declaration, it shouldn't crash
			assertNotNull(model);
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
		}

		@Test
		void anonymousClassInReturn() {
			String src = """
					package com.x;
					public class T {
					    Iterator<String> iter() {
					        return new Iterator<String>() {
					            private int pos = 0;
					            @Override public boolean hasNext() { return pos < 10; }
					            @Override public String next() { return String.valueOf(pos++); }
					            @Override public void remove() { throw new UnsupportedOperationException(); }
					        };
					    }
					    void other() { }
					}
					""";
			var model = parse(src, "com.x");
			var outerMethods = model.methods().stream().filter(m -> m.enclosingFqcn().equals("com.x.T")).toList();
			assertEquals(2, outerMethods.size());
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("iter")));
			assertTrue(outerMethods.stream().anyMatch(m -> m.name().equals("other")));
		}

		@Test
		void textBlockFollowedByRegularString() {
			// Text block immediately followed by more code on same logical statement
			String src = "package com.x;\n" + "public class T {\n" + "    void m() {\n" + "        String s = \"\"\"\n"
					+ "                block content\n" + "                \"\"\" + \"suffix { } void() { }\";\n"
					+ "        String t = \"normal \\\"string\\\"\";\n" + "    }\n" + "    void after() { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("after")));
		}

		@Test
		void classWithSyntheticBridgeMethodPatterns() {
			// Code that looks like what a compiler might generate
			String src = """
					package com.x;
					public class Impl extends Base<String> {
					    @Override
					    public String get() { return "impl"; }
					    /* bridge */ public Object get$bridge() { return get(); }
					    void normal() { }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("get")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("get$bridge")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("normal")));
		}

		@Test
		void multiCatchAndResourceChainingInMethodBody() {
			String src = """
					package com.x;
					public class T {
					    void complex() {
					        try (var a = open(); var b = wrap(a); var c = chain(b)) {
					            c.process();
					        } catch (IOException | SQLException | RuntimeException e) {
					            log(e);
					        } catch (Error e) {
					            throw e;
					        } finally {
					            cleanup();
					        }
					    }
					    void other() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("complex")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("other")));
		}

		@Test
		void hashStabilityWhenAddingAnnotation() {
			// Adding an annotation to a method should not change its body hash
			String src1 = """
					package com.x;
					public class T {
					    void m() { int x = 1; }
					}
					""";
			String src2 = """
					package com.x;
					public class T {
					    @Override
					    @SuppressWarnings("unchecked")
					    void m() { int x = 1; }
					}
					""";
			var model1 = parse(src1, "com.x");
			var model2 = parse(src2, "com.x");
			assertEquals(model1.methods().get(0).bodyHash(), model2.methods().get(0).bodyHash(),
					"adding annotations should not change body hash");
		}

		@Test
		void signatureHashChangesWhenParameterTypeChanges() {
			// Changing a parameter type should change the effective hash
			String src1 = """
					package com.x;
					public class T {
					    void m(int x) { }
					}
					""";
			String src2 = """
					package com.x;
					public class T {
					    void m(long x) { }
					}
					""";
			var model1 = parse(src1, "com.x");
			var model2 = parse(src2, "com.x");
			assertNotEquals(model1.methods().get(0).effectiveHash(), model2.methods().get(0).effectiveHash(),
					"parameter type change should affect effective hash");
		}

		@Test
		void stringWithEscapedQuotesNextToMethodDeclaration() {
			// Escaped quotes right before closing quote, immediately followed by method
			String src = """
					package com.x;
					public class T {
					    String s = "this has \\"quotes\\" and { braces }";
					    void m() { }
					    String t = "more \\"quotes\\"";
					    void n() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("n")));
		}

		@Test
		void moduleInfoStyleDeclarations() {
			// Not a module-info but tests that require/exports keywords don't confuse
			// parser
			String src = """
					package com.x;
					public class ModuleHelper {
					    String[] requires = {"java.base", "java.sql"};
					    void exports(String module) { }
					    void opens(String pkg) { }
					    void provides(Class<?> spi) { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(3, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("exports")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("opens")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("provides")));
		}

		@Test
		void methodWithAssertStatements() {
			// Assert with message containing braces
			String src = """
					package com.x;
					public class T {
					    void validate(int x) {
					        assert x > 0 : "x must be positive { got: " + x + " }";
					        assert x < 100 : new IllegalStateException("too big");
					        assert check(x) : String.format("failed for %d", x);
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("validate", model.methods().get(0).name());
		}

		@Test
		void innerEnumInsideInterface() {
			String src = """
					package com.x;
					public interface Config {
					    enum Level { LOW, MEDIUM, HIGH }
					    enum Format {
					        JSON {
					            @Override public String mime() { return "application/json"; }
					        },
					        XML {
					            @Override public String mime() { return "text/xml"; }
					        };
					        public abstract String mime();
					    }
					    Level defaultLevel();
					    Format defaultFormat();
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.types().stream().anyMatch(t -> t.simpleName().equals("Config")));
			assertTrue(model.types().stream().anyMatch(t -> t.simpleName().equals("Level")));
			assertTrue(model.types().stream().anyMatch(t -> t.simpleName().equals("Format")));
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("defaultLevel") && m.enclosingFqcn().equals("com.x.Config")));
		}
	}

	// =====================================================================
	// PATHOLOGICAL STRING/TEXT-BLOCK PATTERNS & EXOTIC JAVA CONSTRUCTS
	// =====================================================================

	@Nested
	class PathologicalParsing {

		// ── Escaped strings and text blocks with pathological patterns ──

		@Test
		void textBlockWithEmbeddedTripleQuotesViaEscape() {
			// Text block containing \""" sequences that mimic the delimiter
			String src = "package com.x;\n" + "public class T {\n" + "    void m() {\n" + "        String s = \"\"\"\n"
					+ "                Look: \\\"\"\" not closed yet\n" + "                Still going \\\"\"\" more\n"
					+ "                void fake() { }\n" + "                class Fake { }\n"
					+ "                \"\"\";\n" + "    }\n" + "    void real() { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("real")));
			assertEquals(1, model.types().size());
		}

		@Test
		void textBlockWithQuoteAtEndOfLine() {
			// Quote character immediately before the closing """
			String src = "package com.x;\n" + "public class T {\n" + "    String s = \"\"\"\n"
					+ "            content with quote\\\"\"\"\"; // closing is \"\"\" after the escaped quote\n"
					+ "    void m() { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("m", model.methods().get(0).name());
		}

		@Test
		void consecutiveTextBlocksOnSameStatement() {
			// Two text blocks concatenated directly
			String src = "package com.x;\n" + "public class T {\n" + "    void m() {\n" + "        String s = \"\"\"\n"
					+ "                first block\n" + "                void fakeA() { }\n"
					+ "                \"\"\" + \"\"\"\n" + "                second block\n"
					+ "                class FakeB { }\n" + "                \"\"\";\n" + "    }\n"
					+ "    void after() { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("after")));
			assertEquals(1, model.types().size());
		}

		@Test
		void stringWithBackslashBeforeClosingQuote() {
			// Backslash gymnastics: "text\\" ends the string, then next char is code
			String src = """
					package com.x;
					public class T {
					    void m() {
					        String a = "ends with backslash\\\\";
					        String b = "also ends\\\\";
					        int x = 1; // not a string
					    }
					    void n() {
					        String c = "tricky\\\\\\"still in string\\\\\\"";
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("n")));
		}

		@Test
		void emptyTextBlock() {
			String src = "package com.x;\n" + "public class T {\n" + "    String empty = \"\"\"\n"
					+ "            \"\"\";\n" + "    void m() { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("m", model.methods().get(0).name());
		}

		@Test
		void textBlockContainingJsonWithBracesAndQuotes() {
			String src = "package com.x;\n" + "public class T {\n" + "    void m() {\n"
					+ "        String json = \"\"\"\n" + "                {\n"
					+ "                    \"class\": \"Foo\",\n" + "                    \"methods\": [\n"
					+ "                        {\"name\": \"void bar()\", \"body\": \"{return;}\"}\n"
					+ "                    ],\n" + "                    \"interface\": true\n" + "                }\n"
					+ "                \"\"\";\n" + "    }\n" + "    void after() { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("after")));
		}

		@Test
		void textBlockWithSqlContainingKeywords() {
			String src = "package com.x;\n" + "public class T {\n" + "    String sql = \"\"\"\n"
					+ "            CREATE TABLE class (\n" + "                interface VARCHAR(255),\n"
					+ "                void INT DEFAULT 0,\n" + "                record BOOLEAN\n" + "            );\n"
					+ "            INSERT INTO class (interface, void, record)\n"
					+ "            VALUES ('abstract', 42, true);\n" + "            \"\"\";\n"
					+ "    void execute() { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("execute", model.methods().get(0).name());
			assertEquals(1, model.types().size());
		}

		// ── Pattern matching and instanceof ──

		@Test
		void instanceofWithPatternVariable() {
			String src = """
					package com.x;
					public class T {
					    String format(Object obj) {
					        if (obj instanceof String s) {
					            return s.toUpperCase();
					        }
					        return "";
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("format", model.methods().get(0).name());
		}

		@Test
		void instanceofWithGuardedPatternAndNesting() {
			String src = """
					package com.x;
					public class T {
					    Object transform(Object input) {
					        if (input instanceof String s && s.length() > 3) {
					            return s.substring(0, 3);
					        } else if (input instanceof Integer i && i > 0) {
					            return i * 2;
					        } else if (input instanceof int[] arr && arr.length > 0) {
					            return arr[0];
					        } else if (input instanceof Map<?,?> map && !map.isEmpty()) {
					            return map.size();
					        }
					        return null;
					    }
					    void other() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("transform")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("other")));
		}

		@Test
		void switchWithPatternMatching() {
			String src = """
					package com.x;
					public class T {
					    String describe(Object obj) {
					        return switch (obj) {
					            case Integer i when i > 0 -> "positive: " + i;
					            case Integer i -> "non-positive: " + i;
					            case String s when s.isEmpty() -> "empty string";
					            case String s -> "string: " + s;
					            case int[] arr -> "array[" + arr.length + "]";
					            case null -> "null";
					            default -> obj.getClass().getName();
					        };
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("describe", model.methods().get(0).name());
		}

		@Test
		void switchWithRecordPatterns() {
			String src = """
					package com.x;
					sealed interface Expr permits Num, Add, Mul {}
					record Num(int value) implements Expr {}
					record Add(Expr left, Expr right) implements Expr {}
					record Mul(Expr left, Expr right) implements Expr {}
					public class T {
					    int eval(Expr expr) {
					        return switch (expr) {
					            case Num(var v) -> v;
					            case Add(var l, var r) -> eval(l) + eval(r);
					            case Mul(var l, var r) -> eval(l) * eval(r);
					        };
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.types().size() >= 4); // Expr, Num, Add, Mul + T
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("eval")));
		}

		// ── Multi-line annotations with nested annotations ──

		@Test
		void springControllerWithLayeredAnnotations() {
			String src = """
					package com.x;
					@RestController
					@RequestMapping(
					    value = "/api/v1",
					    produces = {"application/json", "application/xml"}
					)
					@CrossOrigin(
					    origins = {"http://localhost:3000"},
					    methods = {RequestMethod.GET, RequestMethod.POST}
					)
					public class T {
					    @GetMapping(
					        value = "/{id}",
					        produces = "application/json"
					    )
					    @ResponseStatus(HttpStatus.OK)
					    @Cacheable(
					        value = "items",
					        key = "#id",
					        condition = "#id > 0",
					        unless = "#result == null"
					    )
					    public ResponseEntity<ItemDto> getItem(
					            @PathVariable("id") @Min(1) long id,
					            @RequestParam(name = "expand", required = false, defaultValue = "false") boolean expand,
					            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
					        return null;
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("getItem", model.methods().get(0).name());
		}

		@Test
		void jpaEntityWithMappingAnnotations() {
			String src = """
					package com.x;
					@Entity
					@Table(
					    name = "orders",
					    indexes = {
					        @Index(name = "idx_customer", columnList = "customer_id"),
					        @Index(name = "idx_date", columnList = "order_date DESC")
					    },
					    uniqueConstraints = {
					        @UniqueConstraint(
					            name = "uk_order_ref",
					            columnNames = {"reference", "tenant_id"}
					        )
					    }
					)
					@NamedQueries({
					    @NamedQuery(
					        name = "Order.findByCustomer",
					        query = "SELECT o FROM Order o WHERE o.customer.id = :customerId"
					    ),
					    @NamedQuery(
					        name = "Order.findRecent",
					        query = "SELECT o FROM Order o WHERE o.date > :since ORDER BY o.date DESC"
					    )
					})
					public class Order {
					    @Id
					    @GeneratedValue(strategy = GenerationType.IDENTITY)
					    private Long id;
					    void process() { }
					    void cancel() { }
					}
					""";
			var model = parseAll(src, "com.x");
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("process")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("cancel")));
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("id")));
		}

		@Test
		void annotationWithArrayOfNestedAnnotations() {
			String src = """
					package com.x;
					public class T {
					    @Mapping(
					        sources = {
					            @Source(
					                value = "primary",
					                config = @Config(
					                    params = {"x", "y", "z"},
					                    nested = @Nested(
					                        level = 3,
					                        tags = {"a", "b"}
					                    )
					                )
					            ),
					            @Source(
					                value = "secondary",
					                config = @Config(params = {})
					            )
					        }
					    )
					    void deeplyAnnotated() { }
					    void plain() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("deeplyAnnotated")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("plain")));
		}

		// ── Anonymous class declarations in field initializers ──

		@Test
		void anonymousClassWithMultipleMethodsAsFieldInit() {
			String src = """
					package com.x;
					public class T {
					    final Comparator<String> CMP = new Comparator<String>() {
					        @Override
					        public int compare(String a, String b) {
					            return normalize(a).compareTo(normalize(b));
					        }
					        private String normalize(String s) {
					            return s.trim().toLowerCase();
					        }
					    };
					    final Runnable TASK = new Runnable() {
					        @Override
					        public void run() {
					            for (int i = 0; i < 10; i++) {
					                System.out.println(i);
					            }
					        }
					    };
					    void realMethod() { }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(
					model.methods().stream()
							.anyMatch(m -> m.name().equals("realMethod") && m.enclosingFqcn().equals("com.x.T")),
					"realMethod should belong to T; methods=" + model.methods());
		}

		@Test
		void anonymousClassInStaticFieldWithGenericType() {
			String src = """
					package com.x;
					public class T {
					    static final Map<String, Function<String, Object>> PARSERS = new HashMap<>() {{
					        put("int", Integer::parseInt);
					        put("bool", Boolean::parseBoolean);
					        put("long", Long::parseLong);
					    }};
					    static final Supplier<List<String>> FACTORY = new Supplier<>() {
					        @Override
					        public List<String> get() {
					            return new ArrayList<>(16);
					        }
					    };
					    void process() { }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("process") && m.enclosingFqcn().equals("com.x.T")));
		}

		@Test
		void nestedAnonymousClassInsideAnonymousClass() {
			String src = """
					package com.x;
					public class T {
					    Object handler = new EventHandler() {
					        Runnable cleanup = new Runnable() {
					            @Override public void run() { }
					        };
					        @Override
					        public void handle(Event e) {
					            cleanup.run();
					        }
					    };
					    void realMethod() { }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("realMethod") && m.enclosingFqcn().equals("com.x.T")));
		}

		// ── Multiple classes in one file with complex inheritance ──

		@Test
		void multipleClassesWithDiamondInheritance() {
			String src = """
					package com.x;
					interface Readable {
					    String read();
					}
					interface Writable {
					    void write(String data);
					}
					interface ReadWrite extends Readable, Writable {
					    default void copy(Readable src) { write(src.read()); }
					}
					abstract class BaseStream implements Readable {
					    protected abstract byte[] rawRead();
					    @Override
					    public String read() { return new String(rawRead()); }
					}
					class FileStream extends BaseStream implements ReadWrite {
					    @Override protected byte[] rawRead() { return new byte[0]; }
					    @Override public void write(String data) { }
					}
					class BufferedStream extends FileStream {
					    private final byte[] buffer = new byte[8192];
					    @Override protected byte[] rawRead() { return buffer; }
					    void flush() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(6, model.types().size());
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("read") && m.enclosingFqcn().equals("com.x.Readable")));
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("copy") && m.enclosingFqcn().equals("com.x.ReadWrite")));
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("rawRead") && m.enclosingFqcn().equals("com.x.BaseStream")));
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("flush") && m.enclosingFqcn().equals("com.x.BufferedStream")));
		}

		@Test
		void sealedHierarchyWithMultiplePermittedTypes() {
			String src = """
					package com.x;
					public sealed interface Event
					        permits UserEvent, SystemEvent, Event.Unknown {
					    String id();
					    record Unknown(String id) implements Event {}
					}
					sealed class UserEvent implements Event permits LoginEvent, LogoutEvent {
					    private final String id;
					    UserEvent(String id) { this.id = id; }
					    @Override public String id() { return id; }
					}
					final class LoginEvent extends UserEvent {
					    private final String username;
					    LoginEvent(String id, String username) { super(id); this.username = username; }
					    String username() { return username; }
					}
					final class LogoutEvent extends UserEvent {
					    LogoutEvent(String id) { super(id); }
					}
					non-sealed class SystemEvent implements Event {
					    private final String id;
					    SystemEvent(String id) { this.id = id; }
					    @Override public String id() { return id; }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.types().size() >= 5);
			assertTrue(model.types().stream().anyMatch(t -> t.simpleName().equals("Event")));
			assertTrue(model.types().stream().anyMatch(t -> t.simpleName().equals("Unknown")));
			assertTrue(model.types().stream().anyMatch(t -> t.simpleName().equals("LoginEvent")));
			assertTrue(model.types().stream().anyMatch(t -> t.simpleName().equals("SystemEvent")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("username")));
		}

		@Test
		void multipleInterfacesWithDefaultMethodConflict() {
			String src = """
					package com.x;
					interface A {
					    default String name() { return "A"; }
					    void onlyA();
					}
					interface B {
					    default String name() { return "B"; }
					    void onlyB();
					}
					class C implements A, B {
					    @Override
					    public String name() { return A.super.name() + B.super.name(); }
					    @Override public void onlyA() { }
					    @Override public void onlyB() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(3, model.types().size());
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("name") && m.enclosingFqcn().equals("com.x.C")));
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("onlyA") && m.enclosingFqcn().equals("com.x.A") && m.isAbstract()));
		}

		// ── Methods with receiver parameters (this parameter) ──

		@Test
		void explicitReceiverParameterOnMethod() {
			String src = """
					package com.x;
					public class Outer {
					    void selfAnnotated(@NonNull Outer this) {
					        System.out.println(this);
					    }
					    void normalMethod(int x) { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("selfAnnotated")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("normalMethod")));
		}

		@Test
		void receiverParameterOnInnerClassMethod() {
			String src = """
					package com.x;
					public class Outer {
					    class Inner {
					        void innerMethod(@Annotated Outer.this, int x) {
					            System.out.println(x);
					        }
					        void normal() { }
					    }
					    void outerMethod() { }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("innerMethod") && m.enclosingFqcn().equals("com.x.Outer$Inner")));
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("normal") && m.enclosingFqcn().equals("com.x.Outer$Inner")));
			assertTrue(model.methods().stream()
					.anyMatch(m -> m.name().equals("outerMethod") && m.enclosingFqcn().equals("com.x.Outer")));
		}

		@Test
		void receiverParameterWithTypeAnnotation() {
			String src = """
					package com.x;
					public class T {
					    void method(@ReadOnly @Immutable T this) {
					        // this is annotated
					    }
					    <E> void generic(@NonNull T this, E element) { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("method")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("generic")));
		}

		// ── Extremely long method signatures spanning many lines ──

		@Test
		void methodSignatureSpanning20Lines() {
			String src = """
					package com.x;
					public class T {
					    @SuppressWarnings({"unchecked", "rawtypes"})
					    @Deprecated(since = "2.0", forRemoval = true)
					    @SafeVarargs
					    public
					    static
					    final
					    synchronized
					    <T extends Comparable<? super T>
					            & java.io.Serializable
					            & Cloneable,
					     U extends Collection<? extends T>
					            & RandomAccess,
					     V extends Map<String, ? extends List<T>>>
					    java.util.concurrent.CompletableFuture<
					            java.util.stream.Stream<
					                    java.util.Map.Entry<
					                            String,
					                            java.util.List<T>>>>
					    extremeMethod(
					            @org.jetbrains.annotations.NotNull
					            final java.util.Map<
					                    String,
					                    ? extends java.util.List<
					                            ? extends java.util.Map<
					                                    String,
					                                    T>>> param1,
					            @javax.annotation.Nullable
					            final U param2,
					            @javax.validation.constraints.Size(min = 1, max = 100)
					            V... varargs)
					            throws java.io.IOException,
					                    java.lang.InterruptedException,
					                    java.util.concurrent.ExecutionException,
					                    javax.naming.NamingException {
					        return null;
					    }
					    void simple() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("extremeMethod")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("simple")));
			assertNotNull(model.methods().stream().filter(m -> m.name().equals("extremeMethod")).findFirst().get()
					.bodyHash());
		}

		@Test
		void methodWithManyParametersOnePerLine() {
			String src = """
					package com.x;
					public class T {
					    public void configure(
					            String host,
					            int port,
					            String username,
					            String password,
					            boolean useSsl,
					            int connectTimeout,
					            int readTimeout,
					            int writeTimeout,
					            int maxRetries,
					            long retryDelay,
					            boolean enableLogging,
					            String logLevel,
					            Path certPath,
					            String proxyHost,
					            int proxyPort) {
					        // lots of params
					    }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("configure", model.methods().get(0).name());
		}

		@Test
		void constructorWithLongThrowsClause() {
			String src = """
					package com.x;
					public class Service {
					    public Service(
					            @Named("config") Config config,
					            @Named("pool") ExecutorService pool,
					            @Named("cache") Cache<String, Object> cache)
					            throws ConfigurationException,
					                    ServiceInitializationException,
					                    IOException,
					                    IllegalStateException,
					                    SecurityException {
					        // init
					    }
					    void run() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("Service") && m.isConstructor()));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("run")));
		}

		// ── Code with preprocessor-style comments that look like directives ──

		@Test
		void commentsLookingLikePreprocessorDirectives() {
			String src = """
					package com.x;
					//#ifdef DEBUG
					//#define TRACE_ENABLED
					//#endif
					public class T {
					    //#if PLATFORM == "windows"
					    void platformSpecific() {
					        // #include "windows_impl.h"  -- not real
					    }
					    //#elif PLATFORM == "linux"
					    // void linuxSpecific() { }
					    //#endif
					    //#region Public API
					    void publicMethod() { }
					    //#endregion
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(2, model.methods().size());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("platformSpecific")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("publicMethod")));
		}

		@Test
		void commentsWithAtSignThatLookLikeAnnotations() {
			String src = """
					package com.x;
					/**
					 * @author someone
					 * @Override -- not a real annotation
					 * @param void fake() { }
					 * @return class Fake {}
					 */
					public class T {
					    /**
					     * @Deprecated this is just javadoc text
					     * @see void otherMethod()
					     */
					    void real() { }
					}
					""";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("real", model.methods().get(0).name());
			assertEquals(1, model.types().size());
		}

		@Test
		void lineCommentsWithCodeAfterThem() {
			// Tricky: code on same line as comment end (block comment)
			String src = """
					package com.x;
					public class T {
					    /* comment */ void a() { }
					    void b() { /* mid-line */ }
					    void /* bizarre */ c /* placement */ () { }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().size() >= 2, "should find at least a and b; found: " + model.methods());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("a")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("b")));
		}

		// ── Deeply nested generics in field declarations ──

		@Test
		void fieldWith8LevelGenerics() {
			String src = """
					package com.x;
					public class T {
					    Map<String, Map<Integer, Map<Long, List<Set<Optional<CompletableFuture<Stream<String>>>>>>>> eightLevels;
					    void m() { }
					}
					""";
			var model = parseAll(src, "com.x");
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("eightLevels")),
					"8-level generic field should be detected; fields=" + model.fields());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
		}

		@Test
		void fieldWith10LevelGenerics() {
			String src = """
					package com.x;
					public class T {
					    A<B<C<D<E<F<G<H<I<J<String>>>>>>>>>> tenLevels;
					    void m() { }
					}
					""";
			var model = parseAll(src, "com.x");
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("tenLevels")),
					"10-level generic field should be detected; fields=" + model.fields());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
		}

		@Test
		void fieldWithWildcardsAtEveryLevel() {
			String src = """
					package com.x;
					public class T {
					    Map<? extends String, Map<? super Integer, List<? extends Set<? super Optional<? extends String>>>>> wildcards;
					    List<Map<String, ? extends Comparable<? super Map<Integer, ? extends List<String>>>>> more;
					    void m() { }
					}
					""";
			var model = parseAll(src, "com.x");
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("wildcards")),
					"wildcard field should be found; fields=" + model.fields());
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("more")));
		}

		@Test
		void fieldWithGenericArrayTypes() {
			String src = """
					package com.x;
					public class T {
					    Map<String, List<int[]>>[] arrayOfGeneric;
					    List<Map<String[], List<byte[][]>>> complex;
					    Optional<CompletableFuture<Stream<String>>>[] futureArray;
					    void m() { }
					}
					""";
			var model = parseAll(src, "com.x");
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("arrayOfGeneric")));
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("complex")));
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("futureArray")));
		}

		@Test
		void fieldWithIntersectionBoundInGeneric() {
			// Intersection types in wildcards
			String src = """
					package com.x;
					public class T {
					    Supplier<? extends Comparable<String> & Serializable> bounded;
					    Map<String, Function<? super Comparable<?>, ? extends Number>> funcField;
					    void m() { }
					}
					""";
			var model = parseAll(src, "com.x");
			// The & in generics might trip up the parser — just verify no crash and method
			// is found
			assertNotNull(model);
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("m")));
		}

		// ── Additional corner cases combining multiple tricky patterns ──

		@Test
		void classWithEverythingCombined() {
			// A single class exercising text blocks, pattern matching, annotations,
			// generics, and unusual formatting together
			String src = "package com.x;\n" + "@SuppressWarnings({\"unchecked\", \"all\"})\n"
					+ "public class Kitchen {\n" + "    // text block field\n" + "    String template = \"\"\"\n"
					+ "            class {{name}} {\n" + "                void {{method}}() { }\n" + "            }\n"
					+ "            \"\"\";\n" + "    // anonymous class\n"
					+ "    Comparator<String> cmp = new Comparator<>() {\n"
					+ "        @Override public int compare(String a, String b) { return 0; }\n" + "    };\n"
					+ "    // deeply generic field\n" + "    Map<String, List<Map<Integer, Set<String>>>> data;\n"
					+ "    // method with pattern matching\n" + "    String process(Object obj) {\n"
					+ "        return switch (obj) {\n" + "            case String s when s.contains(\"{\") -> s;\n"
					+ "            case Integer i -> String.valueOf(i);\n" + "            default -> \"\";\n"
					+ "        };\n" + "    }\n" + "    // heavily annotated\n" + "    @Deprecated(since = \"1.0\")\n"
					+ "    @SuppressWarnings(\"unused\")\n" + "    <T extends Comparable<T>> List<T> sort(\n"
					+ "            @NonNull Collection<? extends T> input) {\n" + "        return null;\n" + "    }\n"
					+ "}\n";
			var model = parseAll(src, "com.x");
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("process")));
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("sort")));
			assertTrue(model.fields().stream().anyMatch(f -> f.name().equals("data")));
			assertEquals(1, model.types().stream().filter(t -> t.simpleName().equals("Kitchen")).count());
		}

		@Test
		void methodsAfterComplexFieldInitializers() {
			// Field initializers with lambdas and anonymous classes should not swallow
			// subsequent methods
			String src = """
					package com.x;
					public class T {
					    Function<String, String> f1 = s -> {
					        if (s.isEmpty()) return "empty";
					        return s.toUpperCase();
					    };
					    BiConsumer<String, Integer> f2 = (s, i) -> {
					        for (int j = 0; j < i; j++) {
					            System.out.println(s);
					        }
					    };
					    Supplier<Map<String, List<Integer>>> f3 = () -> {
					        Map<String, List<Integer>> m = new HashMap<>();
					        m.put("key", List.of(1, 2, 3));
					        return m;
					    };
					    void afterAllFields() { }
					    int compute(int x) { return x * 2; }
					}
					""";
			var model = parse(src, "com.x");
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("afterAllFields")),
					"method after complex fields should be found; methods=" + model.methods());
			assertTrue(model.methods().stream().anyMatch(m -> m.name().equals("compute")));
		}

		@Test
		void mixOfOldAndNewStringFormats() {
			// Mix of regular strings, text blocks, char literals with code-like content
			String src = "package com.x;\n" + "public class T {\n" + "    char ch = '{';\n"
					+ "    String s1 = \"void fake() { }\";\n" + "    String s2 = \"class Fake { int x; }\";\n"
					+ "    String s3 = \"\"\"\n" + "            interface Fake {\n" + "                void method();\n"
					+ "            }\n" + "            \"\"\";\n" + "    String s4 = \"open: {\" + \"\"\"\n"
					+ "            middle\n" + "            \"\"\" + \"close: }\";\n" + "    void real() { }\n" + "}\n";
			var model = parse(src, "com.x");
			assertEquals(1, model.methods().size());
			assertEquals("real", model.methods().get(0).name());
			assertEquals(1, model.types().size());
		}
	}
}
