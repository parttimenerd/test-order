package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

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

		var added = diff.changes().stream().filter(c -> c.kind() == StructuralDiff.Change.Kind.ADDED
				&& c.category() == StructuralDiff.Change.Category.TYPE).toList();
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

		var removed = diff.changes().stream().filter(c -> c.kind() == StructuralDiff.Change.Kind.REMOVED
				&& c.category() == StructuralDiff.Change.Category.TYPE).toList();
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

		var added = diff.changes().stream().filter(c -> c.kind() == StructuralDiff.Change.Kind.ADDED
				&& c.category() == StructuralDiff.Change.Category.FIELD).toList();
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

		var modified = diff.changes().stream().filter(c -> c.kind() == StructuralDiff.Change.Kind.MODIFIED
				&& c.category() == StructuralDiff.Change.Category.FIELD).toList();
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

		var modified = diff.changes().stream().filter(c -> c.kind() == StructuralDiff.Change.Kind.MODIFIED
				&& c.category() == StructuralDiff.Change.Category.FIELD).toList();
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

		var sigChanges = diff.changes().stream().filter(c -> c.kind() == StructuralDiff.Change.Kind.SIGNATURE_CHANGED)
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
		// Comments are stripped before parsing — purely comment changes should produce
		// no structural diff
		assertFalse(diff.hasChanges(), "Comment-only changes should not appear as structural changes");
	}

	@Test
	void parseGitBatchResponseSupportsPresentAndMissingFiles() throws Exception {
		byte[] payload = ("abc123 blob 11\nhello world\n" + "HEAD:src/Missing.java missing\n")
				.getBytes(StandardCharsets.UTF_8);

		Map<String, String> result = StructuralDiff.parseGitBatchResponse(List.of("src/Foo.java", "src/Missing.java"),
				new ByteArrayInputStream(payload));

		assertEquals("hello world", result.get("src/Foo.java"));
		assertTrue(result.containsKey("src/Missing.java"));
		assertNull(result.get("src/Missing.java"));
	}

	@Test
	void removingOneOfTwoAbstractOverloadsDetected() {
		// Two abstract overloads with the same name but different parameter types
		String oldSource = """
				package com.x;
				public interface Processor {
				    void process(int x);
				    void process(String s);
				}
				""";
		// One overload removed
		String newSource = """
				package com.x;
				public interface Processor {
				    void process(int x);
				}
				""";
		StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Processor.java"), oldSource, newSource);
		assertTrue(diff.hasChanges(), "Removing an abstract overload should be detected as a change");
		boolean hasProcessChange = diff.changes().stream().anyMatch(c -> c.name().equals("process"));
		assertTrue(hasProcessChange, "Change should mention 'process'; got: " + diff.changes());
	}

	@Test
	void addingAbstractOverloadDetected() {
		String oldSource = """
				package com.x;
				public interface Svc {
				    void handle(int x);
				}
				""";
		String newSource = """
				package com.x;
				public interface Svc {
				    void handle(int x);
				    void handle(String s);
				}
				""";
		StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Svc.java"), oldSource, newSource);
		assertTrue(diff.hasChanges(), "Adding an abstract overload should be detected");
	}

	// ── Lombok-annotated class diffs ─────────────────────────────────

	@Test
	void lombokDataFieldChangeDetectsGeneratedMethodChanges() {
		String oldSource = """
				package com.x;
				@Data
				public class Person {
				    private String name;
				    private int age;
				}
				""";
		String newSource = """
				package com.x;
				@Data
				public class Person {
				    private String fullName;
				    private int age;
				}
				""";
		StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Person.java"), oldSource, newSource);
		assertTrue(diff.hasChanges(), "changing a field in @Data class should detect changes");

		// Should detect field change + generated method changes
		boolean hasFieldChange = diff.changes().stream()
				.anyMatch(c -> c.category() == StructuralDiff.Change.Category.FIELD);
		assertTrue(hasFieldChange, "field change should be detected");

		// Should detect generated method changes (getName -> getFullName = removed +
		// added)
		boolean hasMethodChange = diff.changes().stream()
				.anyMatch(c -> c.category() == StructuralDiff.Change.Category.METHOD);
		assertTrue(hasMethodChange, "Lombok-generated method changes should be detected");
	}

	@Test
	void lombokDataAddFieldDetectsNewGeneratedMethods() {
		String oldSource = """
				package com.x;
				@Data
				public class Item {
				    private String name;
				}
				""";
		String newSource = """
				package com.x;
				@Data
				public class Item {
				    private String name;
				    private double price;
				}
				""";
		StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Item.java"), oldSource, newSource);
		assertTrue(diff.hasChanges());

		// getPrice/setPrice should appear as ADDED
		boolean hasAddedMethod = diff.changes().stream().anyMatch(c -> c.kind() == StructuralDiff.Change.Kind.ADDED
				&& c.category() == StructuralDiff.Change.Category.METHOD && c.name().equals("getPrice"));
		assertTrue(hasAddedMethod, "adding a field to @Data class should add getPrice");

		// toString, equals, hashCode should be MODIFIED (they now depend on different
		// fields)
		boolean hasModifiedToString = diff.changes().stream()
				.anyMatch(c -> c.kind() == StructuralDiff.Change.Kind.MODIFIED && c.name().equals("toString"));
		assertTrue(hasModifiedToString, "toString should be modified when fields are added");
	}

	@Test
	void nonLombokClassFieldChangeOnlyDetectsFieldChange() {
		String oldSource = """
				package com.x;
				public class Plain {
				    private String name;
				}
				""";
		String newSource = """
				package com.x;
				public class Plain {
				    private String fullName;
				}
				""";
		StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Plain.java"), oldSource, newSource);
		assertTrue(diff.hasChanges());

		// Only field changes, no method changes
		boolean hasMethodChange = diff.changes().stream()
				.anyMatch(c -> c.category() == StructuralDiff.Change.Category.METHOD);
		assertFalse(hasMethodChange, "non-Lombok class should not synthesize method changes");
	}

	@Test
	void methodSignatureChangedSameBody() {
		String oldSource = """
				package com.example;
				public class Foo {
				    public int bar(java.util.List<String> xs) { return xs.size(); }
				}
				""";
		String newSource = """
				package com.example;
				public class Foo {
				    public int bar(java.util.Collection<String> xs) { return xs.size(); }
				}
				""";
		StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
		assertTrue(diff.hasChanges());

		var methodChanges = diff.changes().stream().filter(c -> c.category() == StructuralDiff.Change.Category.METHOD)
				.toList();
		assertEquals(1, methodChanges.size());
		assertEquals(StructuralDiff.Change.Kind.SIGNATURE_CHANGED, methodChanges.get(0).kind(),
				"return type / param type change with identical body should be SIGNATURE_CHANGED");
		assertEquals("bar", methodChanges.get(0).name());
	}

	@Test
	void methodBodyChangedSameSignature() {
		String oldSource = """
				package com.example;
				public class Foo {
				    public int bar(int x) { return x + 1; }
				}
				""";
		String newSource = """
				package com.example;
				public class Foo {
				    public int bar(int x) { return x * 2; }
				}
				""";
		StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
		var methodChanges = diff.changes().stream().filter(c -> c.category() == StructuralDiff.Change.Category.METHOD)
				.toList();
		assertEquals(1, methodChanges.size());
		assertEquals(StructuralDiff.Change.Kind.MODIFIED, methodChanges.get(0).kind(),
				"body change with unchanged signature should remain MODIFIED");
	}

	@Test
	void fieldTypeChangedSameInitializer() {
		String oldSource = """
				package com.example;
				public class Foo {
				    int x = 5;
				}
				""";
		String newSource = """
				package com.example;
				public class Foo {
				    long x = 5;
				}
				""";
		StructuralDiff.FileDiff diff = StructuralDiff.diffSources(Path.of("Foo.java"), oldSource, newSource);
		var fieldChanges = diff.changes().stream().filter(c -> c.category() == StructuralDiff.Change.Category.FIELD)
				.toList();
		assertEquals(1, fieldChanges.size());
		assertEquals(StructuralDiff.Change.Kind.SIGNATURE_CHANGED, fieldChanges.get(0).kind(),
				"field declared-type change should be SIGNATURE_CHANGED even when initializer is identical");
	}

	@Test
	void fieldInitializerChangedSameType() {
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
		var fieldChanges = diff.changes().stream().filter(c -> c.category() == StructuralDiff.Change.Category.FIELD)
				.toList();
		assertEquals(1, fieldChanges.size());
		assertEquals(StructuralDiff.Change.Kind.MODIFIED, fieldChanges.get(0).kind(),
				"initializer-only change should remain MODIFIED");
	}
}
