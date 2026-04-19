package me.bechberger.testorder.changes;

import org.junit.jupiter.api.*;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that the JavaParser mode produces equivalent results to the island-grammar parser
 * for core structural parsing scenarios (types, methods, fields, initializers).
 * <p>
 * Also tests StructuralDiff works correctly when backed by JavaParser mode.
 */
class JavaParserModelTest {

    @BeforeEach
    void enableJavaParser() {
        SourceFileModel.setParserMode(SourceFileModel.ParserMode.JAVAPARSER);
    }

    @AfterEach
    void restoreDefault() {
        SourceFileModel.setParserMode(SourceFileModel.ParserMode.ISLAND);
    }

    // ── availability ─────────────────────────────────────────────────

    @Test
    void javaParserIsAvailable() {
        assertTrue(JavaParserModel.isAvailable());
    }

    // ── type extraction ──────────────────────────────────────────────

    @Test
    void singleClass() {
        var m = SourceFileModel.parse("package com.x; public class Foo {}", "com.x", SourceFileModel.Detail.TYPES);
        assertEquals(Set.of("com.x.Foo"), m.typeNames());
        assertEquals(SourceFileModel.TypeKind.CLASS, m.types().get(0).kind());
    }

    @Test
    void interfaceAndEnum() {
        var m = SourceFileModel.parse("""
                package p;
                interface I { void m(); }
                enum E { A, B }
                """, "p", SourceFileModel.Detail.TYPES);
        assertEquals(Set.of("p.I", "p.E"), m.typeNames());
    }

    @Test
    void recordType() {
        var m = SourceFileModel.parse("""
                package p;
                public record Point(int x, int y) {}
                """, "p", SourceFileModel.Detail.TYPES);
        assertEquals(Set.of("p.Point"), m.typeNames());
        assertEquals(SourceFileModel.TypeKind.RECORD, m.types().get(0).kind());
    }

    @Test
    void annotationType() {
        var m = SourceFileModel.parse("""
                package p;
                public @interface MyAnno { String value(); }
                """, "p", SourceFileModel.Detail.TYPES);
        assertEquals(Set.of("p.MyAnno"), m.typeNames());
        assertEquals(SourceFileModel.TypeKind.ANNOTATION, m.types().get(0).kind());
    }

    @Test
    void nestedClass() {
        var m = SourceFileModel.parse("""
                package p;
                public class Outer {
                    class Inner {}
                }
                """, "p", SourceFileModel.Detail.TYPES);
        assertEquals(Set.of("p.Outer", "p.Outer$Inner"), m.typeNames());
    }

    @Test
    void multipleTopLevelTypes() {
        var m = SourceFileModel.parse("""
                package p;
                public class A {}
                class B {}
                class C {}
                """, "p", SourceFileModel.Detail.TYPES);
        assertEquals(Set.of("p.A", "p.B", "p.C"), m.typeNames());
    }

    // ── method extraction ────────────────────────────────────────────

    @Test
    void methodsExtracted() {
        var m = SourceFileModel.parse("""
                package p;
                public class Foo {
                    void bar() { System.out.println("hi"); }
                    int compute(int a, int b) { return a + b; }
                }
                """, "p", SourceFileModel.Detail.METHODS);
        assertEquals(2, m.methods().size());
        var names = m.methods().stream().map(SourceFileModel.MethodNode::name).sorted().toList();
        assertEquals(List.of("bar", "compute"), names);
    }

    @Test
    void abstractMethodDetected() {
        var m = SourceFileModel.parse("""
                package p;
                public abstract class Foo {
                    abstract void doIt();
                    void concrete() {}
                }
                """, "p", SourceFileModel.Detail.METHODS);
        var abs = m.methods().stream().filter(SourceFileModel.MethodNode::isAbstract).toList();
        assertEquals(1, abs.size());
        assertEquals("doIt", abs.get(0).name());
    }

    @Test
    void constructorDetected() {
        var m = SourceFileModel.parse("""
                package p;
                public class Foo {
                    public Foo(int x) { this.x = x; }
                    int x;
                }
                """, "p", SourceFileModel.Detail.METHODS);
        var ctors = m.methods().stream().filter(SourceFileModel.MethodNode::isConstructor).toList();
        assertEquals(1, ctors.size());
        assertEquals("Foo", ctors.get(0).name());
    }

    @Test
    void methodBodyHashStable() {
        String source = """
                package p;
                public class Foo {
                    int compute() { return 42; }
                }
                """;
        var m1 = SourceFileModel.parse(source, "p", SourceFileModel.Detail.METHODS);
        var m2 = SourceFileModel.parse(source, "p", SourceFileModel.Detail.METHODS);
        assertFalse(m1.methods().isEmpty());
        assertEquals(m1.methods().get(0).bodyHash(), m2.methods().get(0).bodyHash());
    }

    // ── field extraction ─────────────────────────────────────────────

    @Test
    void fieldsExtracted() {
        var m = SourceFileModel.parse("""
                package p;
                public class Foo {
                    int x = 1;
                    String name = "hello";
                }
                """, "p", SourceFileModel.Detail.FIELDS);
        assertEquals(2, m.fields().size());
        var names = m.fields().stream().map(SourceFileModel.FieldNode::name).sorted().toList();
        assertEquals(List.of("name", "x"), names);
    }

    @Test
    void multiFieldDeclaration() {
        var m = SourceFileModel.parse("""
                package p;
                public class Foo {
                    int a, b, c;
                }
                """, "p", SourceFileModel.Detail.FIELDS);
        var names = m.fields().stream().map(SourceFileModel.FieldNode::name).sorted().toList();
        assertEquals(List.of("a", "b", "c"), names);
    }

    // ── initializer blocks ───────────────────────────────────────────

    @Test
    void staticInitializerDetected() {
        var m = SourceFileModel.parse("""
                package p;
                public class Foo {
                    static { System.out.println("init"); }
                }
                """, "p", SourceFileModel.Detail.FIELDS);
        assertEquals(1, m.initializers().size());
        assertTrue(m.initializers().get(0).isStatic());
    }

    @Test
    void instanceInitializerDetected() {
        var m = SourceFileModel.parse("""
                package p;
                public class Foo {
                    { x = 1; }
                    int x;
                }
                """, "p", SourceFileModel.Detail.FIELDS);
        assertEquals(1, m.initializers().size());
        assertFalse(m.initializers().get(0).isStatic());
    }

    // ── structural diff integration ──────────────────────────────────

    @Test
    void structuralDiffNoChanges() {
        String source = """
                package com.example;
                public class Foo {
                    int x = 1;
                    void bar() { return; }
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), source, source);
        assertFalse(diff.hasChanges());
    }

    @Test
    void structuralDiffMethodAdded() {
        String oldSource = """
                package com.example;
                public class Foo {
                    void bar() { return; }
                }
                """;
        String newSource = """
                package com.example;
                public class Foo {
                    void bar() { return; }
                    void baz() { System.out.println("hello"); }
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
        assertTrue(diff.hasChanges());
        var added = diff.changes().stream()
                .filter(c -> c.kind() == StructuralDiff.Change.Kind.ADDED)
                .toList();
        assertEquals(1, added.size());
        assertEquals("baz", added.get(0).name());
    }

    @Test
    void structuralDiffMethodBodyModified() {
        String oldSource = """
                package com.example;
                public class Foo {
                    int compute() { return 1 + 2; }
                }
                """;
        String newSource = """
                package com.example;
                public class Foo {
                    int compute() { return 3 + 4; }
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
        assertTrue(diff.hasChanges());
        assertEquals(StructuralDiff.Change.Kind.MODIFIED, diff.changes().get(0).kind());
        assertEquals("compute", diff.changes().get(0).name());
    }

    @Test
    void structuralDiffFieldAdded() {
        String oldSource = """
                package com.example;
                public class Foo {
                    int x = 1;
                }
                """;
        String newSource = """
                package com.example;
                public class Foo {
                    int x = 1;
                    String name = "hello";
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
        assertTrue(diff.hasChanges());
        var added = diff.changes().stream()
                .filter(c -> c.category() == StructuralDiff.Change.Category.FIELD)
                .toList();
        assertEquals(1, added.size());
        assertEquals("name", added.get(0).name());
    }

    @Test
    void structuralDiffCommentOnlyNoChange() {
        String oldSource = """
                package com.example;
                public class Foo {
                    // old comment
                    void bar() { return; }
                }
                """;
        String newSource = """
                package com.example;
                public class Foo {
                    // new comment
                    void bar() { return; }
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
        assertFalse(diff.hasChanges(), "Comment-only changes should not be structural");
    }

    // ── cross-mode equivalence ───────────────────────────────────────

    @Test
    void typeNamesMatchIslandMode() {
        String source = """
                package p;
                public class Outer {
                    interface Inner {}
                    enum Kind { A, B }
                }
                """;
        SourceFileModel.setParserMode(SourceFileModel.ParserMode.JAVAPARSER);
        var jpTypes = SourceFileModel.parse(source, "p", SourceFileModel.Detail.TYPES).typeNames();
        SourceFileModel.setParserMode(SourceFileModel.ParserMode.ISLAND);
        var islandTypes = SourceFileModel.parse(source, "p", SourceFileModel.Detail.TYPES).typeNames();
        assertEquals(islandTypes, jpTypes);
    }

    @Test
    void methodNamesMatchIslandMode() {
        String source = """
                package p;
                public class Foo {
                    void bar() {}
                    int compute(int a) { return a * 2; }
                    abstract void nope();
                }
                """.replace("abstract void nope();",
                // make it compile: put in abstract class
                "");
        source = """
                package p;
                public class Foo {
                    void bar() {}
                    int compute(int a) { return a * 2; }
                }
                """;
        SourceFileModel.setParserMode(SourceFileModel.ParserMode.JAVAPARSER);
        var jpMethods = SourceFileModel.parse(source, "p", SourceFileModel.Detail.METHODS)
                .methods().stream().map(SourceFileModel.MethodNode::name).sorted().toList();
        SourceFileModel.setParserMode(SourceFileModel.ParserMode.ISLAND);
        var islandMethods = SourceFileModel.parse(source, "p", SourceFileModel.Detail.METHODS)
                .methods().stream().map(SourceFileModel.MethodNode::name).sorted().toList();
        assertEquals(islandMethods, jpMethods);
    }

    @Test
    void fieldNamesMatchIslandMode() {
        String source = """
                package p;
                public class Foo {
                    int x = 1;
                    String name = "hello";
                    static final double PI = 3.14;
                }
                """;
        SourceFileModel.setParserMode(SourceFileModel.ParserMode.JAVAPARSER);
        var jpFields = SourceFileModel.parse(source, "p", SourceFileModel.Detail.FIELDS)
                .fields().stream().map(SourceFileModel.FieldNode::name).sorted().toList();
        SourceFileModel.setParserMode(SourceFileModel.ParserMode.ISLAND);
        var islandFields = SourceFileModel.parse(source, "p", SourceFileModel.Detail.FIELDS)
                .fields().stream().map(SourceFileModel.FieldNode::name).sorted().toList();
        assertEquals(islandFields, jpFields);
    }

    @Test
    void methodHashesMatchIslandMode() {
        String source = """
                package p;
                public class Foo {
                    int compute() { return 42; }
                    void doStuff() { System.out.println("hello"); }
                }
                """;
        SourceFileModel.setParserMode(SourceFileModel.ParserMode.JAVAPARSER);
        var jpHashes = SourceFileModel.parse(source, "p", SourceFileModel.Detail.METHODS).methodHashes();
        SourceFileModel.setParserMode(SourceFileModel.ParserMode.ISLAND);
        var islandHashes = SourceFileModel.parse(source, "p", SourceFileModel.Detail.METHODS).methodHashes();
        assertEquals(islandHashes, jpHashes);
    }
}
