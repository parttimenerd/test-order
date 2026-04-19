package me.bechberger.testorder.changes;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StructuralDiffTest {

    @Test
    void noChanges() {
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
    void addedMethod() {
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

        List<StructuralDiff.Change> changes = diff.changes();
        assertEquals(1, changes.size());
        assertEquals(StructuralDiff.Change.Kind.ADDED, changes.get(0).kind());
        assertEquals(StructuralDiff.Change.Category.METHOD, changes.get(0).category());
        assertEquals("baz", changes.get(0).name());
    }

    @Test
    void removedMethod() {
        String oldSource = """
                package com.example;
                public class Foo {
                    void bar() { return; }
                    void baz() { System.out.println("hello"); }
                }
                """;
        String newSource = """
                package com.example;
                public class Foo {
                    void bar() { return; }
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
        assertTrue(diff.hasChanges());

        List<StructuralDiff.Change> changes = diff.changes();
        assertEquals(1, changes.size());
        assertEquals(StructuralDiff.Change.Kind.REMOVED, changes.get(0).kind());
        assertEquals("baz", changes.get(0).name());
    }

    @Test
    void modifiedMethodBody() {
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

        List<StructuralDiff.Change> changes = diff.changes();
        assertEquals(1, changes.size());
        assertEquals(StructuralDiff.Change.Kind.MODIFIED, changes.get(0).kind());
        assertEquals(StructuralDiff.Change.Category.METHOD, changes.get(0).category());
        assertEquals("compute", changes.get(0).name());
    }

    @Test
    void addedType() {
        String oldSource = """
                package com.example;
                public class Foo {
                }
                """;
        String newSource = """
                package com.example;
                public class Foo {
                }
                class Bar {
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
        assertTrue(diff.hasChanges());

        var added = diff.changes().stream()
                .filter(c -> c.kind() == StructuralDiff.Change.Kind.ADDED && c.category() == StructuralDiff.Change.Category.TYPE)
                .toList();
        assertEquals(1, added.size());
        assertEquals("Bar", added.get(0).name());
    }

    @Test
    void removedType() {
        String oldSource = """
                package com.example;
                public class Foo {
                }
                class Bar {
                }
                """;
        String newSource = """
                package com.example;
                public class Foo {
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
        assertTrue(diff.hasChanges());

        var removed = diff.changes().stream()
                .filter(c -> c.kind() == StructuralDiff.Change.Kind.REMOVED && c.category() == StructuralDiff.Change.Category.TYPE)
                .toList();
        assertEquals(1, removed.size());
        assertEquals("Bar", removed.get(0).name());
    }

    @Test
    void fieldAdded() {
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
                .filter(c -> c.kind() == StructuralDiff.Change.Kind.ADDED && c.category() == StructuralDiff.Change.Category.FIELD)
                .toList();
        assertEquals(1, added.size());
        assertEquals("name", added.get(0).name());
    }

    @Test
    void fieldModified() {
        String oldSource = """
                package com.example;
                public class Foo {
                    int x = 1;
                }
                """;
        String newSource = """
                package com.example;
                public class Foo {
                    int x = 42;
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
        assertTrue(diff.hasChanges());

        var modified = diff.changes().stream()
                .filter(c -> c.kind() == StructuralDiff.Change.Kind.MODIFIED && c.category() == StructuralDiff.Change.Category.FIELD)
                .toList();
        assertEquals(1, modified.size());
        assertEquals("x", modified.get(0).name());
    }

    @Test
    void nestedInnerFieldModified() {
        String oldSource = """
                package com.example;
                public class Outer {
                    static class Branch {
                        static class Leaf {
                            int value = 1;
                        }
                    }
                }
                """;
        String newSource = """
                package com.example;
                public class Outer {
                    static class Branch {
                        static class Leaf {
                            int value = 42;
                        }
                    }
                }
                """;

        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Outer.java"), oldSource, newSource);
        assertTrue(diff.hasChanges());

        var modified = diff.changes().stream()
                .filter(c -> c.kind() == StructuralDiff.Change.Kind.MODIFIED
                        && c.category() == StructuralDiff.Change.Category.FIELD)
                .toList();
        assertEquals(1, modified.size());
        assertEquals("value", modified.get(0).name());
        assertEquals("com.example.Outer$Branch$Leaf", modified.get(0).fqcn());
    }

    @Test
    void typeSignatureChanged() {
        String oldSource = """
                package com.example;
                public class Foo {
                }
                """;
        String newSource = """
                package com.example;
                public class Foo implements Serializable {
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
        assertTrue(diff.hasChanges());

        var sigChanges = diff.changes().stream()
                .filter(c -> c.kind() == StructuralDiff.Change.Kind.SIGNATURE_CHANGED)
                .toList();
        assertEquals(1, sigChanges.size());
        assertTrue(sigChanges.get(0).detail().contains("Serializable"));
    }

    @Test
    void newFileAllAdded() {
        String newSource = """
                package com.example;
                public class Foo {
                    int x = 1;
                    void bar() { }
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), "", newSource);
        assertTrue(diff.hasChanges());
        assertTrue(diff.changes().stream().allMatch(c -> c.kind() == StructuralDiff.Change.Kind.ADDED));
    }

    @Test
    void deletedFileAllRemoved() {
        String oldSource = """
                package com.example;
                public class Foo {
                    void bar() { }
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, "");
        assertTrue(diff.hasChanges());
        assertTrue(diff.changes().stream().allMatch(c -> c.kind() == StructuralDiff.Change.Kind.REMOVED));
    }

    @Test
    void formatReport() {
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
                    void baz() { }
                }
                """;
        StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
        String report = StructuralDiff.formatReport(List.of(diff));
        assertTrue(report.contains("Foo.java"));
        assertTrue(report.contains("+"));
        assertTrue(report.contains("baz"));
    }

    @Test
    void commentOnlyChangeNotDetected() {
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
        // Comments are stripped before parsing — purely comment changes should produce no structural diff
        assertFalse(diff.hasChanges(), "Comment-only changes should not appear as structural changes");
    }
}
