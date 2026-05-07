package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.ErrorCode;

class DiagnosticOperationTest {

	@TempDir
	Path tempDir;

	@Test
	void reportsMissingArtifactsAsInformational() {
		Path baseDir = tempDir.resolve(".test-order");
		Path projectRoot = tempDir;
		Path testSourceRoot = tempDir.resolve("src/test/java");

		DiagnosticOperation.DiagnosticConfig config = new DiagnosticOperation.DiagnosticConfig(projectRoot,
				baseDir.resolve("test-order.idx"), baseDir.resolve("test-order.state"), baseDir.resolve("source.hash"),
				baseDir.resolve("test.hash"), baseDir.resolve("method.hash"), baseDir.resolve("deps"), testSourceRoot,
				"auto", PluginLog.NOOP);

		DiagnosticOperation.DiagnosticReport report = DiagnosticOperation.diagnose(config);

		assertEquals(6, report.results().size());
		assertTrue(report.results().stream().anyMatch(r -> r.code() == ErrorCode.INDEX_NOT_FOUND));
		assertTrue(report.results().stream().anyMatch(r -> r.code() == ErrorCode.DEPS_NOT_FOUND));
		assertTrue(report.results().stream().anyMatch(r -> r.code() == ErrorCode.TEST_SOURCE_ROOT_ABSENT));
	}

	@Test
	void reportsCorruptedIndexWithRecoverySuggestions() throws IOException {
		Path baseDir = tempDir.resolve(".test-order");
		Files.createDirectories(baseDir);

		Path indexFile = baseDir.resolve("test-order.idx");
		Files.writeString(indexFile, "X".repeat(256));

		Path testSourceRoot = tempDir.resolve("src/test/java");
		Files.createDirectories(testSourceRoot);

		DiagnosticOperation.DiagnosticConfig config = new DiagnosticOperation.DiagnosticConfig(tempDir, indexFile,
				baseDir.resolve("test-order.state"), baseDir.resolve("source.hash"), baseDir.resolve("test.hash"),
				baseDir.resolve("method.hash"), baseDir.resolve("deps"), testSourceRoot, "auto", PluginLog.NOOP);

		DiagnosticOperation.DiagnosticReport report = DiagnosticOperation.diagnose(config);

		DiagnosticResult indexResult = report.results().stream().filter(r -> r.code() == ErrorCode.INDEX_CORRUPTED)
				.findFirst().orElseThrow();

		assertTrue(indexResult.isError());
		assertTrue(indexResult.suggestions().stream().anyMatch(s -> s.contains("test-order:clean")));
	}
}
