package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DashboardGenerator;
import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

class GenerateDashboardOperationTest {

	@TempDir
	Path tempDir;

	@Test
	void generateLogsGuidanceWithoutMisleadingOpenBrowserMessage() throws IOException {
		List<String> infos = new ArrayList<>();
		PluginLog log = new PluginLog() {
			@Override
			public void info(String message) {
				infos.add(message);
			}

			@Override
			public void warn(String message) {
			}

			@Override
			public void debug(String message) {
			}
		};

		Path out = tempDir.resolve("dashboard/index.html");
		Path generated = GenerateDashboardOperation.generate(List.of(), null, new TestOrderState(),
				TestOrderState.ScoringWeights.DEFAULT, Set.of(), Set.of(), new DependencyMap(), "demo-project",
				".test-order/state.lz4", ".test-order/test-dependencies.lz4", "0.0.1-SNAPSHOT", null,
				"<html><body>" + DashboardGenerator.DATA_PLACEHOLDER + "</body></html>", out, log);

		assertTrue(Files.exists(generated), "Dashboard file should be written");
		assertTrue(infos.stream().anyMatch(s -> s.contains("Dashboard written to:")),
				"Should log written dashboard path");
		assertTrue(infos.stream().anyMatch(s -> s.contains("To open automatically, set testorder.dashboard.open=true")),
				"Should log explicit open guidance");
		assertFalse(infos.stream().anyMatch(s -> s.contains("Open in browser")),
				"Should not log misleading unconditional open-browser message");
	}
}
