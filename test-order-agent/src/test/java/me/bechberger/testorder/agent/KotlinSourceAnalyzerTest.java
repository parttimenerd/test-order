package me.bechberger.testorder.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for KotlinSourceAnalyzer. Verifies proper extraction of package
 * names and class definitions from Kotlin source files.
 */
public class KotlinSourceAnalyzerTest {

	@TempDir
	Path tempDir;

	@Test
	public void testExtractPackageFromSimpleKotlinFile() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example.myapp

				class MyClass {
				    fun example() = "hello"
				}
				""");

		String pkg = KotlinSourceAnalyzer.extractPackage(kotlinFile);
		assertEquals("com.example.myapp", pkg);
	}

	@Test
	public void testExtractPackageWithWhitespace() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				// Header comment
				package  com.example.test
				/* Block comment */
				class MyClass { }
				""");

		String pkg = KotlinSourceAnalyzer.extractPackage(kotlinFile);
		assertEquals("com.example.test", pkg);
	}

	@Test
	public void testExtractPackageIgnoresCommentsAfterDeclaration() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example // inline comment
				class Test { }
				""");

		String pkg = KotlinSourceAnalyzer.extractPackage(kotlinFile);
		assertEquals("com.example", pkg);
	}

	@Test
	public void testNoPackageReturnNull() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				class MyClass {
				    fun test() { }
				}
				""");

		String pkg = KotlinSourceAnalyzer.extractPackage(kotlinFile);
		assertNull(pkg, "Should return null when no package is declared");
	}

	@Test
	public void testPackageInStringLiteralIgnored() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				const val TEXT = "package com.false.package"
				package com.example.real

				class MyClass { }
				""");

		String pkg = KotlinSourceAnalyzer.extractPackage(kotlinFile);
		assertEquals("com.example.real", pkg);
	}

	@Test
	public void testPackageInTripleQuotedStringIgnored() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile,
				"\"\"\"package com.fake\nmore text\"\"\"\npackage com.example.real\nclass Test { }");

		String pkg = KotlinSourceAnalyzer.extractPackage(kotlinFile);
		assertEquals("com.example.real", pkg);
	}

	@Test
	public void testExtractTopLevelClass() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example

				class MyClass {
				    class InnerClass { }
				}

				class AnotherClass { }
				""");

		Set<String> classes = KotlinSourceAnalyzer.extractTopLevelClasses(kotlinFile);
		assertTrue(classes.contains("MyClass"));
		assertTrue(classes.contains("AnotherClass"));
		assertFalse(classes.contains("InnerClass"), "Inner class should not be extracted");
	}

	@Test
	public void testExtractTopLevelObject() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example

				object Singleton { }

				class MyClass { }
				""");

		Set<String> classes = KotlinSourceAnalyzer.extractTopLevelClasses(kotlinFile);
		assertTrue(classes.contains("Singleton"));
		assertTrue(classes.contains("MyClass"));
	}

	@Test
	public void testExtractDataClass() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example

				data class User(val name: String, val age: Int)
				""");

		Set<String> classes = KotlinSourceAnalyzer.extractTopLevelClasses(kotlinFile);
		assertTrue(classes.contains("User"));
	}

	@Test
	public void testExtractSealedClass() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example

				sealed class Result {
				    class Success : Result()
				    class Failure : Result()
				}
				""");

		Set<String> classes = KotlinSourceAnalyzer.extractTopLevelClasses(kotlinFile);
		assertTrue(classes.contains("Result"));
		assertFalse(classes.contains("Success"), "Nested class should not be extracted");
	}

	@Test
	public void testExtractAbstractClass() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example

				abstract class BaseClass {
				    abstract fun test()
				}
				""");

		Set<String> classes = KotlinSourceAnalyzer.extractTopLevelClasses(kotlinFile);
		assertTrue(classes.contains("BaseClass"));
	}

	@Test
	public void testExtractInterface() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example

				interface MyInterface {
				    fun doSomething()
				}
				""");

		Set<String> classes = KotlinSourceAnalyzer.extractTopLevelClasses(kotlinFile);
		assertTrue(classes.contains("MyInterface"));
	}

	@Test
	public void testClassNameInStringIgnored() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example

				const val FAKE = "class FakeClass { }"
				class RealClass { }
				""");

		Set<String> classes = KotlinSourceAnalyzer.extractTopLevelClasses(kotlinFile);
		assertTrue(classes.contains("RealClass"));
		assertFalse(classes.contains("FakeClass"));
	}

	@Test
	public void testClassNameInCommentIgnored() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example

				// class FakeClass { }
				/* class AnotherFake { } */
				class RealClass { }
				""");

		Set<String> classes = KotlinSourceAnalyzer.extractTopLevelClasses(kotlinFile);
		assertTrue(classes.contains("RealClass"));
		assertFalse(classes.contains("FakeClass"));
	}

	@Test
	public void testEmptyFileReturnsNullPackage() throws Exception {
		Path kotlinFile = tempDir.resolve("Empty.kt");
		Files.writeString(kotlinFile, "");

		String pkg = KotlinSourceAnalyzer.extractPackage(kotlinFile);
		assertNull(pkg);
	}

	@Test
	public void testNonexistentFileReturnsNull() {
		Path nonexistent = tempDir.resolve("nonexistent.kt");
		assertFalse(Files.exists(nonexistent));

		String pkg = KotlinSourceAnalyzer.extractPackage(nonexistent);
		assertNull(pkg);
	}

	@Test
	public void testComplexPackageName() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package org.springframework.boot.test.autoconfigure.web.servlet

				class TestClass { }
				""");

		String pkg = KotlinSourceAnalyzer.extractPackage(kotlinFile);
		assertEquals("org.springframework.boot.test.autoconfigure.web.servlet", pkg);
	}

	@Test
	public void testMultipleClassesExtracted() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example

				class FirstClass { }
				class SecondClass { }
				class ThirdClass { }
				object MyObject { }
				""");

		Set<String> classes = KotlinSourceAnalyzer.extractTopLevelClasses(kotlinFile);
		assertEquals(4, classes.size());
		assertTrue(classes.contains("FirstClass"));
		assertTrue(classes.contains("SecondClass"));
		assertTrue(classes.contains("ThirdClass"));
		assertTrue(classes.contains("MyObject"));
	}

	@Test
	public void testClassWithTypeParametersExtracted() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example

				class GenericClass<T : Any> { }
				""");

		Set<String> classes = KotlinSourceAnalyzer.extractTopLevelClasses(kotlinFile);
		assertTrue(classes.contains("GenericClass"));
	}

	@Test
	public void testClassWithInheritanceExtracted() throws Exception {
		Path kotlinFile = tempDir.resolve("Test.kt");
		Files.writeString(kotlinFile, """
				package com.example

				class MyClass : BaseClass(), Interface { }
				""");

		Set<String> classes = KotlinSourceAnalyzer.extractTopLevelClasses(kotlinFile);
		assertTrue(classes.contains("MyClass"));
	}
}
