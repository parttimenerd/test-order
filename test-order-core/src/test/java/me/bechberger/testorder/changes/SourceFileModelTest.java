package me.bechberger.testorder.changes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Thorough tests for {@link SourceFileModel} covering the island-grammar parser.
 * Exercises class/interface/enum/record extraction, method extraction, bounded
 * generics, sealed/permits, constructors, abstract methods, annotations, overloads,
 * nested classes, multiple top-level types, hash stability, and edge cases.
 * <p>
 * Also serves as an integration test for class name extraction
 * and comment/string stripping utilities.
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
    //  TYPE EXTRACTION
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
            assertEquals(Set.of("com.x.Foo", "com.x.Bar", "com.x.Baz"),
                    parseTypes(src, "com.x").typeNames());
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
            assertTrue(m.types().stream().anyMatch(
                    t -> t.kind() == SourceFileModel.TypeKind.ENUM && t.simpleName().equals("Color")));
            assertTrue(m.types().stream().anyMatch(
                    t -> t.kind() == SourceFileModel.TypeKind.RECORD && t.simpleName().equals("Point")));
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
            assertEquals(Set.of("com.x.Shape", "com.x.Circle", "com.x.Square"),
                    parseTypes(src, "com.x").typeNames());
        }

        @Test
        void nonSealedClass() {
            String src = """
                    package com.x;
                    public sealed class Base permits Sub {}
                    non-sealed class Sub extends Base {}
                    """;
            assertEquals(Set.of("com.x.Base", "com.x.Sub"),
                    parseTypes(src, "com.x").typeNames());
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
            assertEquals(Set.of("com.x.A", "com.x.B", "com.x.C"),
                    parseTypes(src, "com.x").typeNames());
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
    //  METHOD EXTRACTION
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
            assertEquals(Set.of("com.x.T#a", "com.x.T#b", "com.x.T#c"),
                    parse(src, "com.x").methodHashes().keySet());
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
            assertEquals(Set.of("com.x.Foo#real"),
                    parse(src, "com.x").methodHashes().keySet());
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
            assertTrue(m.methods().stream().anyMatch(
                    md -> md.isConstructor() && md.name().equals("Foo")));
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
            assertEquals(Set.of("com.x.A#yes"),
                    parse(src, "com.x").methodHashes().keySet());
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
            assertEquals(Set.of("com.x.A#a", "com.x.B#b"),
                    parse(src, "com.x").methodHashes().keySet());
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
            assertEquals(Set.of("com.x.Greeter#greet"),
                    parse(src, "com.x").methodHashes().keySet());
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
    //  HASH STABILITY
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
            assertEquals(
                    parse(src1, "com.x").methodHashes().get("com.x.T#m"),
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
            assertNotEquals(
                    parse(src1, "com.x").methodHashes().get("com.x.T#m"),
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
            assertEquals(
                    parse(src1, "com.x").methodHashes().get("com.x.A#m"),
                    parse(src2, "com.x").methodHashes().get("com.x.B#m"));
        }

        @Test
        void stringLiteralChangeDoesNotAffectHash() {
            // String literals are stripped → changing the literal value does not change the hash
            String src1 = """
                    package com.x;
                    public class T { void m() { String s = "hello"; } }
                    """;
            String src2 = """
                    package com.x;
                    public class T { void m() { String s = "world"; } }
                    """;
            assertEquals(
                    parse(src1, "com.x").methodHashes().get("com.x.T#m"),
                    parse(src2, "com.x").methodHashes().get("com.x.T#m"));
        }
    }

    // =====================================================================
    //  EXTRACTION UTILITIES
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
    //  COMPLEX GENERATED SOURCE
    // =====================================================================

    @Nested
    class ComplexGeneratedSource {

        /**
         * A large, realistic generated source file that exercises many parser features together.
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
            assertEquals(Set.of(
                    "com.example.app.Repository",
                    "com.example.app.InMemoryRepository",
                    "com.example.app.Cacheable"
            ), model.typeNames());

            // methods in Repository (constructors excluded from hashes)
            var hashes = model.methodHashes();
            for (String mName : List.of("add", "get", "size", "transform", "validate",
                    "remove", "findFirst", "clear", "empty", "close")) {
                assertTrue(hashes.containsKey("com.example.app.Repository#" + mName),
                        "expected " + mName + ", got: " + hashes.keySet());
            }

            // overloaded remove → single combined key
            assertEquals(1, hashes.keySet().stream()
                    .filter(k -> k.endsWith("#remove")).count());

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
            assertEquals(Set.of("gen.GenericUtil#groupBy", "gen.GenericUtil#processAll",
                    "gen.GenericUtil#nestedReturnType"), hashes.keySet());
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
            assertEquals(Set.of("mix.Service", "mix.Processor", "mix.Priority", "mix.Config"),
                    model.typeNames());

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
            assertEquals(Set.of("com.x.T#accept", "com.x.T#bounded", "com.x.T#lowerBound"),
                    h.keySet());
        }
    }

    // =====================================================================
    //  FIELD EXTRACTION
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
            assertEquals(Set.of("com.x.Outer#outerField", "com.x.Outer$Inner#innerField"),
                    h.keySet());
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
            assertNotEquals(
                    parseAll(src1, "com.x").fieldHashes().get("com.x.T#x"),
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
            assertNotEquals(
                    parseAll(src1, "com.x").fieldHashes().get("com.x.T#x"),
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
            assertEquals(Set.of("com.example.Service#serialVersionUID",
                            "com.example.Service#items",
                            "com.example.Service#repo"),
                    m.fieldHashes().keySet());
            assertEquals(Set.of("com.example.Service#add", "com.example.Service#size"),
                    m.methodHashes().keySet());
        }
    }

    // =====================================================================
    //  INITIALIZER BLOCK EXTRACTION
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
            assertTrue(m.initializers().stream().anyMatch(
                    i -> i.enclosingFqcn().equals("com.x.Outer") && i.isStatic()));
            assertTrue(m.initializers().stream().anyMatch(
                    i -> i.enclosingFqcn().equals("com.x.Outer$Inner") && i.isStatic()));
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
            assertNotEquals(
                    parseAll(src1, "com.x").initializers().get(0).bodyHash(),
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
    //  EDGE CASES
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
            assertEquals(
                    parse(src, "com.x").methodHashes().get("com.x.A#m"),
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
    //  COMPACT BODY
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
            String src = "package com.x;\n"
                    + "public class T {\n"
                    + "    String s = \"\"\"\n"
                    + "        line 1\n"
                    + "\n"
                    + "        line 3\n"
                    + "        \"\"\";\n"
                    + "}\n";
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
            var model = parse(src, "com.x");
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
            var model = parse(src, "com.x");
            var method = model.methods().stream().filter(m -> m.name().equals("m")).findFirst().orElseThrow();
            String compact = method.compactBody();
            assertNotNull(compact);
            assertFalse(compact.contains("\n\n"));
        }

        @Test
        void methodCompactBodyPreservesTextBlockEmptyLines() {
            String src = "package com.x;\n"
                    + "public class T {\n"
                    + "    String m() {\n"
                    + "        return \"\"\"\n"
                    + "            hello\n"
                    + "\n"
                    + "            world\n"
                    + "            \"\"\";\n"
                    + "    }\n"
                    + "}\n";
            var model = parse(src, "com.x");
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
            var model = parse(src, "com.x");
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
            var model = parse(src, "com.x");
            var method = model.methods().stream().filter(m -> m.name().equals("m")).findFirst().orElseThrow();
            String compact = method.compactBody();
            // The line that was only a comment should be gone
            String[] lines = compact.split("\n");
            for (String line : lines) {
                assertFalse(line.isBlank(), "should not have blank lines: [" + compact + "]");
            }
        }
    }
}
