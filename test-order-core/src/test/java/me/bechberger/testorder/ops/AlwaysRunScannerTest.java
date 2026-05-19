package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlwaysRunScannerTest {

	private static final String ALWAYS_RUN_DESCRIPTOR = "Lme/bechberger/testorder/annotations/AlwaysRun;";

	@TempDir
	Path tempDir;

	private Path writeClassFile(String relativePath, boolean hasAlwaysRun) throws IOException {
		Path file = tempDir.resolve(relativePath);
		Files.createDirectories(file.getParent());
		// Minimal fake .class file content: just needs to contain the annotation
		// descriptor
		// if hasAlwaysRun, else some dummy content
		String content = hasAlwaysRun ? ("CAFEBABE" + ALWAYS_RUN_DESCRIPTOR + "END") : "CAFEBABE_DUMMY_END";
		Files.write(file, content.getBytes(StandardCharsets.ISO_8859_1));
		return file;
	}

	@Test
	void scan_findsTopLevelAnnotatedClass() throws IOException {
		writeClassFile("com/example/SmokeTest.class", true);
		writeClassFile("com/example/NormalTest.class", false);

		Set<String> result = AlwaysRunScanner.scan(tempDir);

		assertTrue(result.contains("com.example.SmokeTest"));
		assertFalse(result.contains("com.example.NormalTest"));
	}

	@Test
	void scan_nestedClassAnnotation_addsTopLevelParent() throws IOException {
		// Outer class is NOT annotated, but its nested class IS
		writeClassFile("com/example/OuterTest.class", false);
		writeClassFile("com/example/OuterTest$Critical.class", true);

		Set<String> result = AlwaysRunScanner.scan(tempDir);

		assertTrue(result.contains("com.example.OuterTest"),
				"should add top-level class when nested class has @AlwaysRun");
		assertFalse(result.contains("com.example.OuterTest$Critical"), "should not add the nested class name directly");
	}

	@Test
	void scan_bothTopLevelAndNestedAnnotated() throws IOException {
		writeClassFile("com/example/SmokeTest.class", true);
		writeClassFile("com/example/SmokeTest$Inner.class", true);

		Set<String> result = AlwaysRunScanner.scan(tempDir);

		assertTrue(result.contains("com.example.SmokeTest"));
		assertEquals(1, result.size(), "should deduplicate to single top-level entry");
	}

	@Test
	void scan_nonExistentDir_returnsEmpty() {
		Set<String> result = AlwaysRunScanner.scan(tempDir.resolve("nonexistent"));
		assertTrue(result.isEmpty());
	}
}
