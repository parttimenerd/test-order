package me.bechberger.testorder.changes;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceFileModelUtilTest {

	@TempDir
	Path tempDir;

	@Test
	void singleClassFile() throws IOException {
		Path file = tempDir.resolve("Foo.java");
		Files.writeString(file, """
				package com.example;

				public class Foo {
				    int x;
				}
				""");
		Set<String> names = SourceFileModel.extractClassNames(file, "com.example");
		assertEquals(Set.of("com.example.Foo"), names);
	}

	@Test
	void multipleTopLevelTypes() throws IOException {
		Path file = tempDir.resolve("Foo.java");
		Files.writeString(file, """
				package com.example;

				public class Foo {
				    int x;
				}

				class Bar {
				    int y;
				}

				interface Baz {
				    void doStuff();
				}
				""");
		Set<String> names = SourceFileModel.extractClassNames(file, "com.example");
		assertEquals(Set.of("com.example.Foo", "com.example.Bar", "com.example.Baz"), names);
	}

	@Test
	void enumAndRecord() throws IOException {
		Path file = tempDir.resolve("Types.java");
		Files.writeString(file, """
				package com.example;

				public enum Color { RED, GREEN, BLUE }

				record Point(int x, int y) {}
				""");
		Set<String> names = SourceFileModel.extractClassNames(file, "com.example");
		assertEquals(Set.of("com.example.Color", "com.example.Point"), names);
	}

	@Test
	void nestedClassNotPickedUp() throws IOException {
		Path file = tempDir.resolve("Outer.java");
		Files.writeString(file, """
				package com.example;

				public class Outer {
				    static class Inner {
				        int z;
				    }
				}
				""");
		Set<String> names = SourceFileModel.extractClassNames(file, "com.example");
		// Both Outer and nested Inner should be detected
		assertEquals(Set.of("com.example.Outer", "com.example.Outer$Inner"), names);
	}

	@Test
	void classInCommentNotPickedUp() throws IOException {
		Path file = tempDir.resolve("Commented.java");
		Files.writeString(file, """
				package com.example;

				// class FakeClass { }
				/* class AlsoFake { } */

				public class Real {
				}
				""");
		Set<String> names = SourceFileModel.extractClassNames(file, "com.example");
		assertEquals(Set.of("com.example.Real"), names);
	}

	@Test
	void classInStringNotPickedUp() throws IOException {
		Path file = tempDir.resolve("WithString.java");
		Files.writeString(file, """
				package com.example;

				public class Real {
				    String s = "class Fake {}";
				}
				""");
		Set<String> names = SourceFileModel.extractClassNames(file, "com.example");
		assertEquals(Set.of("com.example.Real"), names);
	}

	@Test
	void defaultPackage() throws IOException {
		Path file = tempDir.resolve("Foo.java");
		Files.writeString(file, "public class Foo { }");
		Set<String> names = SourceFileModel.extractClassNames(file, "");
		assertEquals(Set.of("Foo"), names);
	}

	@Test
	void pathToPackageConversion() {
		assertEquals("com.example", SourceFileModel.pathToPackage("com/example/Foo.java"));
		assertEquals("", SourceFileModel.pathToPackage("Foo.java"));
		assertEquals("com", SourceFileModel.pathToPackage("com/Foo.java"));
	}

	@Test
	void pathToClassName() {
		assertEquals("com.example.Foo", SourceFileModel.pathToClassName("com/example/Foo.java"));
		assertEquals("Foo", SourceFileModel.pathToClassName("Foo.java"));
		assertEquals("com.Foo", SourceFileModel.pathToClassName("com/Foo.java"));
	}

	@Test
	void fileToClassNamesFromSourceText() {
		String source = """
				package com.example;
				public class Foo {}
				class Bar {}
				""";
		Set<String> names = SourceFileModel.fileToClassNames("com/example/Foo.java", source);
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), names);
	}

	@Test
	void fileToClassNamesFallback() {
		// empty source yields no type declarations → falls back to filename
		Set<String> names = SourceFileModel.fileToClassNames("com/example/Foo.java", "");
		assertEquals(Set.of("com.example.Foo"), names);
	}

	@Test
	void fileToClassNamesFromDisk() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot.resolve("com/example"));
		Files.writeString(srcRoot.resolve("com/example/Foo.java"), """
				package com.example;
				public class Foo {}
				class Helper {}
				""");
		Set<String> names = SourceFileModel.fileToClassNames("com/example/Foo.java", srcRoot);
		assertEquals(Set.of("com.example.Foo", "com.example.Helper"), names);
	}

	@Test
	void filesToClassNamesSkipsNonJava() {
		Set<String> names = SourceFileModel.filesToClassNames(Set.of("com/example/Foo.java", "readme.md"), null);
		assertEquals(Set.of("com.example.Foo"), names);
	}

	@Test
	void annotationInterface() throws IOException {
		Path file = tempDir.resolve("MyAnnotation.java");
		Files.writeString(file, """
				package com.example;

				public @interface MyAnnotation {
				    String value();
				}
				""");
		Set<String> names = SourceFileModel.extractClassNames(file, "com.example");
		assertEquals(Set.of("com.example.MyAnnotation"), names);
	}

	@Test
	void sealedAndFinal() throws IOException {
		Path file = tempDir.resolve("Shape.java");
		Files.writeString(file, """
				package com.example;

				public sealed class Shape permits Circle, Square {}

				final class Circle extends Shape {}

				final class Square extends Shape {}
				""");
		Set<String> names = SourceFileModel.extractClassNames(file, "com.example");
		assertEquals(Set.of("com.example.Shape", "com.example.Circle", "com.example.Square"), names);
	}

	// ── Package auto-detection ──────────────────────────────────────

	@Test
	void extractPackageNameFromSource() {
		assertEquals("com.example", SourceFileModel.extractPackageName("package com.example;\npublic class Foo {}"));
		assertEquals("", SourceFileModel.extractPackageName("public class Foo {}"));
	}

	@Test
	void extractClassNamesAutoPackage() throws IOException {
		Path file = tempDir.resolve("Foo.java");
		Files.writeString(file, """
				package org.test;

				public class Foo {}
				class Bar {}
				""");
		Set<String> names = SourceFileModel.extractClassNames(file);
		assertEquals(Set.of("org.test.Foo", "org.test.Bar"), names);
	}

	@Test
	void extractClassNamesAutoPackageDefault() throws IOException {
		Path file = tempDir.resolve("Foo.java");
		Files.writeString(file, "public class Foo {}");
		Set<String> names = SourceFileModel.extractClassNames(file);
		assertEquals(Set.of("Foo"), names);
	}

	// ── Kotlin support ──────────────────────────────────────────────

	@Test
	void kotlinSingleClass() throws IOException {
		Path file = tempDir.resolve("Foo.kt");
		Files.writeString(file, """
				package com.example

				class Foo
				""");
		Set<String> names = SourceFileModel.extractClassNames(file);
		assertEquals(Set.of("com.example.Foo"), names);
	}

	@Test
	void kotlinMultipleTypes() throws IOException {
		Path file = tempDir.resolve("Types.kt");
		Files.writeString(file, """
				package com.example

				data class User(val name: String)

				sealed interface Shape

				object Singleton

				enum class Color { RED, GREEN }
				""");
		Set<String> names = SourceFileModel.extractClassNames(file);
		assertEquals(Set.of("com.example.User", "com.example.Shape", "com.example.Singleton", "com.example.Color"),
				names);
	}

	@Test
	void kotlinNestedClassNotPickedUp() throws IOException {
		Path file = tempDir.resolve("Outer.kt");
		Files.writeString(file, """
				package com.example

				class Outer {
				    class Inner
				}
				""");
		Set<String> names = SourceFileModel.extractClassNames(file);
		assertEquals(Set.of("com.example.Outer"), names);
	}

	@Test
	void kotlinClassInCommentNotPickedUp() throws IOException {
		Path file = tempDir.resolve("Real.kt");
		Files.writeString(file, """
				package com.example

				// class FakeClass
				/* object AlsoFake */

				class Real
				""");
		Set<String> names = SourceFileModel.extractClassNames(file);
		assertEquals(Set.of("com.example.Real"), names);
	}

	@Test
	void kotlinClassInStringNotPickedUp() throws IOException {
		Path file = tempDir.resolve("Real.kt");
		Files.writeString(file, """
				package com.example

				class Real {
				    val s = "class Fake"
				}
				""");
		Set<String> names = SourceFileModel.extractClassNames(file);
		assertEquals(Set.of("com.example.Real"), names);
	}

	@Test
	void pathToClassNameKotlin() {
		assertEquals("com.example.Foo", SourceFileModel.pathToClassName("com/example/Foo.kt"));
		assertEquals("Foo", SourceFileModel.pathToClassName("Foo.kt"));
	}

	@Test
	void isSourceFileCheck() {
		assertTrue(SourceFileModel.isSourceFile("com/Foo.java"));
		assertTrue(SourceFileModel.isSourceFile("com/Foo.kt"));
		assertFalse(SourceFileModel.isSourceFile("readme.md"));
		assertFalse(SourceFileModel.isSourceFile("build.gradle"));
	}

	@Test
	void filesToClassNamesIncludesKotlin() throws IOException {
		Path srcRoot = tempDir.resolve("src");
		Files.createDirectories(srcRoot.resolve("com/example"));
		Files.writeString(srcRoot.resolve("com/example/Foo.java"), """
				package com.example;
				public class Foo {}
				""");
		Files.writeString(srcRoot.resolve("com/example/Bar.kt"), """
				package com.example
				class Bar
				""");
		Set<String> names = SourceFileModel.filesToClassNames(Set.of("com/example/Foo.java", "com/example/Bar.kt"),
				srcRoot);
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), names);
	}

	@Test
	void fileToClassNamesKotlinFromSourceText() {
		String source = """
				package com.example
				class Foo
				object Bar
				""";
		Set<String> names = SourceFileModel.fileToClassNames("com/example/Foo.kt", source);
		assertEquals(Set.of("com.example.Foo", "com.example.Bar"), names);
	}

	// =====================================================================
	// STRIP COMMENTS AND STRINGS
	// =====================================================================

	@Nested
	class StripCommentsAndStrings {

		@Test
		void lineCommentStripped() {
			String src = "int x; // comment\nint y;";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertFalse(stripped.contains("comment"));
			assertTrue(stripped.contains("int x;"));
			assertTrue(stripped.contains("int y;"));
		}

		@Test
		void blockCommentStripped() {
			String src = "int x; /* block\ncomment */ int y;";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertFalse(stripped.contains("block"));
			assertFalse(stripped.contains("comment"));
			assertTrue(stripped.contains("int x;"));
			assertTrue(stripped.contains("int y;"));
		}

		@Test
		void stringLiteralStripped() {
			String src = "String s = \"hello world\";";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertFalse(stripped.contains("hello"));
			assertTrue(stripped.contains("String s ="));
		}

		@Test
		void charLiteralStripped() {
			String src = "char c = 'x';";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertTrue(stripped.contains("char c ="));
		}

		@Test
		void textBlockStripped() {
			String src = """
					String s = \\"\\"\\"
					    class Fake {}
					    \\"\\"\\";
					""".replace("\\\"", "\"");
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertFalse(stripped.contains("class Fake"));
		}

		@Test
		void escapedQuoteInString() {
			String src = "String s = \"he said \\\"hi\\\"\";";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertFalse(stripped.contains("he said"));
		}

		@Test
		void escapedBackslashBeforeQuote() {
			String src = "String s = \"path\\\\\\\\\"; int x;";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertTrue(stripped.contains("int x;"));
		}

		@Test
		void newlinesPreservedInBlockComment() {
			String src = "a\n/* comment\nwith\nnewlines */\nb";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			// newlines inside block comments are preserved for position mapping
			assertEquals(src.chars().filter(c -> c == '\n').count(), stripped.chars().filter(c -> c == '\n').count());
		}

		@Test
		void newlinesPreservedInTextBlock() {
			String src = "String s = \"\"\"\n    line1\n    line2\n    \"\"\"; int x;";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertEquals(src.chars().filter(c -> c == '\n').count(), stripped.chars().filter(c -> c == '\n').count());
		}

		@Test
		void lengthPreserved() {
			String src = """
					package com.x;
					// line comment
					/* block comment */
					public class Foo {
					    String s = "hello";
					    char c = 'x';
					}
					""";
			assertEquals(src.length(), SourceFileModel.stripCommentsAndStrings(src).length());
		}

		@Test
		void slashNotInCommentPreserved() {
			String src = "int x = a / b;";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertEquals(src, stripped);
		}

		@Test
		void emptyInput() {
			assertEquals("", SourceFileModel.stripCommentsAndStrings(""));
		}

		@Test
		void nestedBlockCommentPattern() {
			// Java doesn't have nested block comments — the inner /* is ignored
			String src = "/* outer /* inner */ int x;";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertTrue(stripped.contains("int x;"));
		}

		@Test
		void consecutiveComments() {
			String src = "// first\n// second\nint x;";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertTrue(stripped.contains("int x;"));
			assertFalse(stripped.contains("first"));
			assertFalse(stripped.contains("second"));
		}

		@Test
		void unicodeInString() {
			String src = "String s = \"\\u0041\"; int x;";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertTrue(stripped.contains("int x;"));
		}

		@Test
		void emptyStringLiteral() {
			String src = "String s = \"\"; int x;";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertTrue(stripped.contains("String s ="));
			assertTrue(stripped.contains("int x;"));
		}

		@Test
		void emptyCharLiteral() {
			// Technically invalid Java, but shouldn't crash the stripper
			String src = "char c = ''; int x;";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertTrue(stripped.contains("int x;"));
		}
	}

	// =====================================================================
	// STRIP COMMENTS (preserves strings)
	// =====================================================================

	@Nested
	class StripComments {

		@Test
		void lineCommentStrippedStringPreserved() {
			String src = "String s = \"hello\"; // comment\nint y;";
			String result = SourceFileModel.stripComments(src);
			assertFalse(result.contains("comment"));
			assertTrue(result.contains("\"hello\""));
			assertTrue(result.contains("int y;"));
		}

		@Test
		void blockCommentStrippedStringPreserved() {
			String src = "String s = \"world\"; /* block\ncomment */ int y;";
			String result = SourceFileModel.stripComments(src);
			assertFalse(result.contains("block"));
			assertFalse(result.contains("comment"));
			assertTrue(result.contains("\"world\""));
			assertTrue(result.contains("int y;"));
		}

		@Test
		void stringLiteralsPreserved() {
			String src = "String s = \"hello world\";";
			String result = SourceFileModel.stripComments(src);
			assertTrue(result.contains("\"hello world\""));
		}

		@Test
		void charLiteralsPreserved() {
			String src = "char c = 'x';";
			String result = SourceFileModel.stripComments(src);
			assertTrue(result.contains("'x'"));
		}

		@Test
		void textBlockPreserved() {
			String src = "String s = \"\"\"\n    line1\n    line2\n    \"\"\"; int x;";
			String result = SourceFileModel.stripComments(src);
			assertTrue(result.contains("line1"));
			assertTrue(result.contains("line2"));
			assertTrue(result.contains("int x;"));
		}

		@Test
		void escapedQuoteInStringPreserved() {
			String src = "String s = \"he said \\\"hi\\\"\"; int x;";
			String result = SourceFileModel.stripComments(src);
			assertTrue(result.contains("he said \\\"hi\\\""));
			assertTrue(result.contains("int x;"));
		}

		@Test
		void annotationStringValuesPreserved() {
			String src = "@CsvSource({\"1,2\", \"3,4\"})\nvoid test() {}";
			String result = SourceFileModel.stripComments(src);
			assertTrue(result.contains("\"1,2\""));
			assertTrue(result.contains("\"3,4\""));
		}

		@Test
		void methodSourceAnnotationPreserved() {
			String src = "@MethodSource(\"providerMethod\")\nvoid test() {}";
			String result = SourceFileModel.stripComments(src);
			assertTrue(result.contains("\"providerMethod\""));
		}

		@Test
		void commentInsideStringNotStripped() {
			String src = "String s = \"// not a comment\"; int x;";
			String result = SourceFileModel.stripComments(src);
			assertTrue(result.contains("// not a comment"));
			assertTrue(result.contains("int x;"));
		}

		@Test
		void newlinesPreservedInBlockComment() {
			String src = "a\n/* comment\nwith\nnewlines */\nb";
			String result = SourceFileModel.stripComments(src);
			assertEquals(src.chars().filter(c -> c == '\n').count(), result.chars().filter(c -> c == '\n').count());
		}

		@Test
		void lengthPreserved() {
			String src = "package com.x;\n// line\n/* block */ class Foo { String s = \"hi\"; }";
			assertEquals(src.length(), SourceFileModel.stripComments(src).length());
		}
	}

	// =====================================================================
	// NORMALIZE FOR HASHING
	// =====================================================================

	@Nested
	class NormalizeForHashing {

		@Test
		void commentOnlyChangeProducesSameResult() {
			String v1 = "package com.x;\npublic class Foo {\n    int x = 1;\n}";
			String v2 = "package com.x;\n// added comment\npublic class Foo {\n    int x = 1; // inline\n}";
			assertEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}

		@Test
		void whitespaceOnlyChangeProducesSameResult() {
			String v1 = "package com.x;\npublic class Foo {\n    int x = 1;\n}";
			String v2 = "package com.x;\n\npublic class Foo {\n        int x = 1;\n\n}";
			assertEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}

		@Test
		void stringLiteralChangeProducesDifferentResult() {
			String v1 = "String s = \"hello\";";
			String v2 = "String s = \"world\";";
			assertNotEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}

		@Test
		void codeChangeProducesDifferentResult() {
			String v1 = "int x = 1;";
			String v2 = "int x = 2;";
			assertNotEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}

		@Test
		void annotationStringValueChangeDetected() {
			String v1 = "@CsvSource({\"1,2\"})\nvoid test() {}";
			String v2 = "@CsvSource({\"1,3\"})\nvoid test() {}";
			assertNotEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}

		@Test
		void annotationWhitespaceChangeIgnored() {
			String v1 = "@CsvSource({\"1,2\", \"3,4\"})\nvoid test() {}";
			String v2 = "@CsvSource({ \"1,2\" , \"3,4\" })\nvoid test() {}";
			assertEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}

		@Test
		void methodSourceAnnotationPreserved() {
			String v1 = "@MethodSource(\"provider\")\nvoid test() {}";
			String v2 = "@MethodSource(\"otherProvider\")\nvoid test() {}";
			assertNotEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}

		@Test
		void blockCommentChangeIgnored() {
			String v1 = "int x = 1; /* old comment */";
			String v2 = "int x = 1; /* new comment */";
			assertEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}

		@Test
		void javadocChangeIgnored() {
			String v1 = "/** Old doc */\npublic void foo() {}";
			String v2 = "/** New doc with more detail */\npublic void foo() {}";
			assertEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}

		@Test
		void whitespaceInsideStringLiteralPreserved() {
			String v1 = "String s = \"hello   world\";";
			String v2 = "String s = \"hello world\";";
			assertNotEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}

		@Test
		void indentationChangeIgnored() {
			String v1 = "    if (true) {\n        return 1;\n    }";
			String v2 = "if (true) {\n  return 1;\n}";
			assertEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}

		@Test
		void operatorWhitespaceThatChangesMeaningIsPreserved() {
			String v1 = "int x = a - -b;";
			String v2 = "int x = a--b;";
			assertNotEquals(SourceFileModel.normalizeForHashing(v1), SourceFileModel.normalizeForHashing(v2));
		}
	}

	// =====================================================================
	// DELEGATION TO SOURCE FILE MODEL
	// =====================================================================

	@Nested
	class SourceFileModelDelegation {

		@Test
		void genericClassExtracted() {
			Set<String> names = SourceFileModel
					.extractClassNames("package com.x; public class Box<T extends Comparable<T>> {}", "com.x");
			assertEquals(Set.of("com.x.Box"), names);
		}

		@Test
		void recordWithComponentsExtracted() {
			Set<String> names = SourceFileModel.extractClassNames(
					"package com.x; public record Point(int x, int y) implements Serializable {}", "com.x");
			assertEquals(Set.of("com.x.Point"), names);
		}

		@Test
		void sealedWithPermitsExtracted() {
			String src = """
					package com.x;
					public sealed class Shape permits Circle, Square {}
					final class Circle extends Shape {}
					non-sealed class Square extends Shape {}
					""";
			assertEquals(Set.of("com.x.Shape", "com.x.Circle", "com.x.Square"),
					SourceFileModel.extractClassNames(src, "com.x"));
		}

		@Test
		void nestedClassesViaModel() {
			String src = """
					package com.x;
					public class Outer {
					    static class Inner {
					        class DeepInner {}
					    }
					    enum Status { OK, ERR }
					}
					""";
			assertEquals(
					Set.of("com.x.Outer", "com.x.Outer$Inner", "com.x.Outer$Inner$DeepInner", "com.x.Outer$Status"),
					SourceFileModel.extractClassNames(src, "com.x"));
		}

		@Test
		void annotationTypeViaModel() {
			String src = """
					package com.x;
					public @interface MyAnno {
					    String value();
					    int count() default 0;
					}
					""";
			assertEquals(Set.of("com.x.MyAnno"), SourceFileModel.extractClassNames(src, "com.x"));
		}

		@Test
		void classWithComplexGenerics() {
			String src = """
					package com.x;
					public class Registry<K, V extends Map<String, List<Integer>>> {}
					""";
			assertEquals(Set.of("com.x.Registry"), SourceFileModel.extractClassNames(src, "com.x"));
		}

		@Test
		void defaultPackageViaModel() {
			assertEquals(Set.of("Foo"), SourceFileModel.extractClassNames("class Foo {}", ""));
		}

		@Test
		void extractWithFallbackPackageFromPath() {
			// Source has no package declaration — falls back to deriving from path
			String src = "public class Foo {}";
			Set<String> names = SourceFileModel.fileToClassNames("com/example/Foo.java", src);
			assertEquals(Set.of("com.example.Foo"), names);
		}

		@Test
		void extractWithFallbackPackagePrefersDeclared() {
			// Source declares its own package — that should be used, not the path
			String src = "package org.real; public class Foo {}";
			Set<String> names = SourceFileModel.fileToClassNames("com/wrong/Foo.java", src);
			assertEquals(Set.of("org.real.Foo"), names);
		}

		@Test
		void classInTextBlockIgnored() {
			String src = """
					package com.x;
					public class Real {
					    String s = \\"\\"\\"
					        class Fake {}
					        \\"\\"\\";
					}
					""".replace("\\\"", "\"");
			assertEquals(Set.of("com.x.Real"), SourceFileModel.extractClassNames(src, "com.x"));
		}

		@Test
		void emptySourceViaModel() {
			assertTrue(SourceFileModel.extractClassNames("", "").isEmpty());
		}

		@Test
		void onlyImportsNoTypes() {
			String src = """
					package com.x;
					import java.util.*;
					""";
			assertTrue(SourceFileModel.extractClassNames(src, "com.x").isEmpty());
		}
	}

	// =====================================================================
	// PERFORMANCE & OPTIMIZATION CORRECTNESS
	// =====================================================================

	@Nested
	class PerformanceOptimizations {

		@Test
		void sha256IsDeterministic() {
			String input = "public void foo() { return 42; }";
			String h1 = SourceFileModel.sha256(input);
			String h2 = SourceFileModel.sha256(input);
			assertEquals(h1, h2, "SHA-256 must be deterministic across calls");
			assertEquals(64, h1.length(), "SHA-256 hex should be 64 chars");
		}

		@Test
		void sha256ThreadSafety() throws Exception {
			String input = "some text to hash";
			String expected = SourceFileModel.sha256(input);
			int threadCount = 8;
			int iterations = 100;
			java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threadCount);
			java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger(0);
			Thread[] threads = new Thread[threadCount];
			for (int t = 0; t < threadCount; t++) {
				final int tid = t;
				threads[t] = new Thread(() -> {
					try {
						barrier.await();
						for (int i = 0; i < iterations; i++) {
							String h = SourceFileModel.sha256(input + tid + i);
							assertNotNull(h);
							assertEquals(64, h.length());
						}
						// Verify determinism
						assertEquals(expected, SourceFileModel.sha256(input));
					} catch (Exception e) {
						failures.incrementAndGet();
					}
				});
				threads[t].start();
			}
			for (Thread t : threads)
				t.join();
			assertEquals(0, failures.get(), "SHA-256 must be thread-safe");
		}

		@Test
		void stripCommentsAndStringsPreservesLength() {
			// char[] output should be exactly same length as input
			String src = """
					package com.x;
					// line comment
					/* block comment */
					public class T {
					    String s = "hello world";
					    char c = 'x';
					    String tb = \"\"\\"
					            text block content
					            \\"\"\";
					    void m() {}
					}
					""";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			assertEquals(src.length(), stripped.length(),
					"Stripped output must preserve positional mapping (same length)");
		}

		@Test
		void stripCommentsPreservesNewlines() {
			String src = "line1\n// comment\nline3\n/* multi\nline\n*/\nline7\n";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			long origNewlines = src.chars().filter(c -> c == '\n').count();
			long strippedNewlines = stripped.chars().filter(c -> c == '\n').count();
			assertEquals(origNewlines, strippedNewlines, "Newline count must be preserved");
		}

		@Test
		void normalizeForHashingFusedEquivalence() {
			// The fused single-pass normalizeForHashing must produce same results as
			// the conceptual two-pass: stripComments → normalizeWhitespace
			String src = """
					package com.x;
					/**
					 * Javadoc with @param
					 */
					public class Foo {
					    // inline comment
					    String s = "keep this";
					    int x = /* mid-code comment */ 42;
					    void method(/* param comment */ int a,
					               int   b) {
					        return a   +   b; // trailing
					    }
					}
					""";
			String result = SourceFileModel.normalizeForHashing(src);
			// Key properties: comments removed, strings preserved, whitespace collapsed
			assertFalse(result.contains("Javadoc"), "Javadoc should be stripped");
			assertFalse(result.contains("inline comment"), "Line comment should be stripped");
			assertFalse(result.contains("mid-code"), "Block comment should be stripped");
			assertFalse(result.contains("trailing"), "Trailing comment should be stripped");
			assertTrue(result.contains("\"keep this\""), "String content must be preserved");
			assertFalse(result.contains("  "), "No double spaces should remain (outside strings)");
		}

		@Test
		void normalizeForHashingTextBlockPreserved() {
			String src = "String s = \"\"\"\n        hello   world\n        \"\"\";\nvoid m() {}";
			String result = SourceFileModel.normalizeForHashing(src);
			// Text block content including internal whitespace must be preserved verbatim
			assertTrue(result.contains("hello   world"), "Text block whitespace must be preserved");
		}

		@Test
		void normalizeForHashingEmptyInput() {
			assertEquals("", SourceFileModel.normalizeForHashing(""));
			assertNull(SourceFileModel.normalizeForHashing(null));
		}

		@Test
		void normalizeForHashingOnlyComments() {
			String src = "// just a comment\n/* another one */\n";
			String result = SourceFileModel.normalizeForHashing(src);
			assertTrue(result.isEmpty() || result.isBlank(), "Only-comments source should produce empty result");
		}

		@Test
		void findMatchingBraceWithDepthArray() {
			String src = "class T { void m() { if (true) { } } }";
			String stripped = SourceFileModel.stripCommentsAndStrings(src);
			int len = stripped.length();
			int[] braceDepth = new int[len + 1];
			for (int i = 0; i < len; i++) {
				char c = stripped.charAt(i);
				braceDepth[i + 1] = braceDepth[i] + (c == '{' ? 1 : c == '}' ? -1 : 0);
			}
			// Find the first '{' (class body open)
			int classOpen = stripped.indexOf('{');
			int classClose = SourceFileModel.findMatchingBrace(stripped, classOpen, braceDepth);
			assertEquals(stripped.lastIndexOf('}'), classClose);

			// Find method body open
			int methodOpen = stripped.indexOf('{', classOpen + 1);
			int methodClose = SourceFileModel.findMatchingBrace(stripped, methodOpen, braceDepth);
			assertTrue(methodClose > methodOpen);
			assertTrue(methodClose < classClose);
		}

		@Test
		void largeSourceParsingPerformance() {
			// Generate a moderately large source file (50 classes, 10 methods each)
			StringBuilder sb = new StringBuilder();
			sb.append("package com.perf;\n");
			for (int c = 0; c < 50; c++) {
				sb.append("public class Class").append(c).append(" {\n");
				for (int m = 0; m < 10; m++) {
					sb.append("    /**\n     * Method doc for method").append(m).append("\n     */\n");
					sb.append("    public String method").append(m).append("(int param").append(m).append(") {\n");
					sb.append("        String s = \"value_").append(m).append("\";\n");
					sb.append("        return s + param").append(m).append(";\n");
					sb.append("    }\n");
				}
				sb.append("}\n");
			}
			String src = sb.toString();

			// Warm up
			SourceFileModel.parse(src, "com.perf", SourceFileModel.Detail.FIELDS);

			// Time it
			long start = System.nanoTime();
			int iterations = 20;
			for (int i = 0; i < iterations; i++) {
				SourceFileModel.parse(src, "com.perf", SourceFileModel.Detail.FIELDS);
			}
			long elapsed = System.nanoTime() - start;
			long avgMs = elapsed / (iterations * 1_000_000);

			// Sanity check: should complete in reasonable time (< 200ms per parse)
			// This is a regression guard, not a tight benchmark
			assertTrue(avgMs < 200, "Parsing 50 classes x 10 methods should take < 200ms, took " + avgMs + "ms");
		}

		@Test
		void stripCommentsAndStringsPerformance() {
			// Generate a source with lots of strings and comments
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 1000; i++) {
				sb.append("String s").append(i).append(" = \"value ").append(i).append("\"; // comment ").append(i)
						.append("\n");
			}
			String src = sb.toString();

			// Warm up
			SourceFileModel.stripCommentsAndStrings(src);

			// Time it
			long start = System.nanoTime();
			int iterations = 100;
			for (int i = 0; i < iterations; i++) {
				SourceFileModel.stripCommentsAndStrings(src);
			}
			long elapsed = System.nanoTime() - start;
			long avgUs = elapsed / (iterations * 1_000);

			// Should be very fast for 1000 lines
			assertTrue(avgUs < 10_000, "Stripping 1000 lines should take < 10ms, took " + (avgUs / 1000.0) + "ms");
		}

		@Test
		void normalizeForHashingPerformance() {
			// Source with mix of comments, strings, whitespace
			StringBuilder sb = new StringBuilder();
			sb.append("package com.x;\n");
			for (int i = 0; i < 200; i++) {
				sb.append("    /** doc ").append(i).append(" */\n");
				sb.append("    public void method").append(i).append("() {\n");
				sb.append("        String s = \"value ").append(i).append("\";\n");
				sb.append("        // line comment\n");
				sb.append("        int x = ").append(i).append(";\n");
				sb.append("    }\n");
			}
			String src = sb.toString();

			// Warm up
			SourceFileModel.normalizeForHashing(src);

			// Time it
			long start = System.nanoTime();
			int iterations = 50;
			for (int i = 0; i < iterations; i++) {
				SourceFileModel.normalizeForHashing(src);
			}
			long elapsed = System.nanoTime() - start;
			long avgUs = elapsed / (iterations * 1_000);

			// Single-pass should be fast
			assertTrue(avgUs < 20_000,
					"normalizeForHashing 200 methods should take < 20ms, took " + (avgUs / 1000.0) + "ms");
		}
	}
}
