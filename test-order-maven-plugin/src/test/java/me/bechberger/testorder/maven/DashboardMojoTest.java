package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

class DashboardMojoTest {

	@TempDir
	Path tempDir;

	private TestableDashboardMojo mojo;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new TestableDashboardMojo();

		MavenProject project = mock(MavenProject.class);
		when(project.getArtifactId()).thenReturn("my-project");
		when(project.getBasedir()).thenReturn(tempDir.toFile());
		when(project.getProperties()).thenReturn(new Properties());
		when(project.getCompileSourceRoots()).thenReturn(List.of(tempDir.resolve("src/main/java").toString()));
		when(project.getTestCompileSourceRoots()).thenReturn(List.of(tempDir.resolve("src/test/java").toString()));

		Build build = new Build();
		build.setDirectory(tempDir.resolve("target").toString());
		build.setTestOutputDirectory(tempDir.resolve("target/test-classes").toString());
		when(project.getBuild()).thenReturn(build);

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);

		inject(mojo, "project", project);
		inject(mojo, "session", session);
		inject(mojo, "indexFile", tempDir.resolve("index.lz4").toString());
		inject(mojo, "stateFile", tempDir.resolve(".test-order-state").toString());
		inject(mojo, "depsDir", tempDir.resolve("deps").toString());
		inject(mojo, "dashboardOutput", tempDir.resolve("target/test-order-dashboard/index.html").toString());
		inject(mojo, "openBrowser", false);
	}

	// ── Missing index ─────────────────────────────────────────────────────────

	@Test
	void noIndexFailsWithHelpfulMessage() {
		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		String msg = ex.getMessage();
		assertTrue(msg.contains("No dependency index") || msg.contains("dependency index"),
				"Expected helpful 'no index' message, got: " + msg);
	}

	// ── HTML generation ───────────────────────────────────────────────────────

	@Test
	void withSimpleIndexGeneratesHtmlFile() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		assertTrue(Files.exists(resolveOutput()), "Dashboard HTML should exist");
	}

	@Test
	void withSimpleIndexInlinesWebAssets() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		String html = Files.readString(resolveOutput());
		// Assets must be inlined — no external <script src="./assets/..."> references
		assertFalse(html.contains("src=\"./assets/"), "HTML must not contain external asset src references");
		// Each library's known identifier must appear inline
		assertTrue(html.contains("Vue"), "Vue.js must be inlined in HTML");
		assertTrue(html.contains("Chart"), "Chart.js must be inlined in HTML");
		assertTrue(html.contains("d3"), "D3 must be inlined in HTML");
		assertTrue(Files.size(resolveOutput()) > 400_000,
				"Self-contained HTML should be >400 KB (contains all three libraries)");
	}

	@Test
	void generatedHtmlIsNonEmpty() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		assertTrue(Files.size(resolveOutput()) > 1000, "Dashboard HTML should be non-trivially large");
	}

	@Test
	void generatedHtmlContainsDashboardDataScript() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		String html = Files.readString(resolveOutput());
		assertTrue(html.contains("id=\"dashboard-data\""), "HTML must contain the dashboard-data script tag");
		assertFalse(html.contains("DASHBOARD_DATA_PLACEHOLDER"),
				"Placeholder must be replaced with real JSON — not left as-is");
	}

	@Test
	void generatedHtmlContainsTestEntry() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		assertTrue(Files.readString(resolveOutput()).contains("com.example.MyTest"),
				"JSON data should contain the test class name");
	}

	@Test
	void generatedHtmlContainsProjectName() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		assertTrue(Files.readString(resolveOutput()).contains("my-project"),
				"JSON data should contain the Maven artifactId as project name");
	}

	@Test
	void generatedJsonContainsTopLevelFields() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		String json = extractJson(Files.readString(resolveOutput()));
		assertTrue(json.contains("\"tests\""), "JSON must have 'tests' array");
		assertTrue(json.contains("\"runs\""), "JSON must have 'runs' array");
		assertTrue(json.contains("\"weights\""), "JSON must have 'weights' object");
		assertTrue(json.contains("\"weightDefs\""), "JSON must have 'weightDefs' for sliders");
		assertTrue(json.contains("\"project\""), "JSON must have 'project' object");
		assertTrue(json.contains("\"config\""), "JSON must have 'config' object");
		assertTrue(json.contains("\"changedClasses\""), "JSON must have 'changedClasses'");
		assertTrue(json.contains("\"medianDuration\""), "JSON must have 'medianDuration'");
	}

	@Test
	void testEntryContainsRequiredScoreFields() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		String json = extractJson(Files.readString(resolveOutput()));
		assertTrue(json.contains("\"score\""), "test entry must have 'score'");
		assertTrue(json.contains("\"rank\""), "test entry must have 'rank'");
		assertTrue(json.contains("\"depOverlap\""), "test entry must have 'depOverlap'");
		assertTrue(json.contains("\"depTotal\""), "test entry must have 'depTotal'");
		assertTrue(json.contains("\"failScore\""), "test entry must have 'failScore'");
		assertTrue(json.contains("\"isNew\""), "test entry must have 'isNew'");
		assertTrue(json.contains("\"isChanged\""), "test entry must have 'isChanged'");
		assertTrue(json.contains("\"isFast\""), "test entry must have 'isFast'");
		assertTrue(json.contains("\"isSlow\""), "test entry must have 'isSlow'");
		assertTrue(json.contains("\"duration\""), "test entry must have 'duration'");
		assertTrue(json.contains("\"speedRatio\""), "test entry must have 'speedRatio'");
		assertTrue(json.contains("\"deps\""), "test entry must have 'deps'");
		assertTrue(json.contains("\"memberDeps\""), "test entry must have 'memberDeps'");
	}

	@Test
	void multipleTestsAreAllIncluded() throws Exception {
		DependencyMap map = new DependencyMap();
		map.put("com.example.AlphaTest", Set.of("com.app.A"));
		map.put("com.example.BetaTest", Set.of("com.app.B"));
		map.put("com.example.GammaTest", Set.of("com.app.C", "com.app.D"));
		map.save(tempDir.resolve("index.lz4"));

		mojo.execute();
		String json = extractJson(Files.readString(resolveOutput()));
		assertTrue(json.contains("AlphaTest"), "AlphaTest must appear in JSON");
		assertTrue(json.contains("BetaTest"), "BetaTest must appear in JSON");
		assertTrue(json.contains("GammaTest"), "GammaTest must appear in JSON");
	}

	@Test
	void testDepsAreIncludedInEntry() throws Exception {
		DependencyMap map = new DependencyMap();
		map.put("com.example.MyTest", Set.of("com.app.ServiceA", "com.app.ServiceB"));
		map.save(tempDir.resolve("index.lz4"));

		mojo.execute();
		String json = extractJson(Files.readString(resolveOutput()));
		assertTrue(json.contains("ServiceA"), "deps must include ServiceA");
		assertTrue(json.contains("ServiceB"), "deps must include ServiceB");
	}

	@Test
	void runHistoryIsEmptyWhenNoRuns() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		// "runs":[] — empty array
		assertTrue(extractJson(Files.readString(resolveOutput())).contains("\"runs\":[]"),
				"runs should be empty array when no history");
	}

	@Test
	void runHistoryIsIncludedWhenPresent() throws Exception {
		writeMinimalIndex();

		TestOrderState state = new TestOrderState();
		state.addRunRecord(TestOrderState.buildRunRecord(List.of("com.example.MyTest"), Set.of()));
		state.save(tempDir.resolve(".test-order-state"));
		mojo.overrideState = state;

		mojo.execute();
		String json = extractJson(Files.readString(resolveOutput()));
		assertTrue(json.contains("\"timestamp\""), "run entry must have 'timestamp'");
		assertTrue(json.contains("\"totalTests\""), "run entry must have 'totalTests'");
		assertTrue(json.contains("\"totalFailures\""), "run entry must have 'totalFailures'");
		assertTrue(json.contains("\"apfd\""), "run entry must have 'apfd'");
	}

	@Test
	void failedRunIsReflectedInRunHistory() throws Exception {
		writeMinimalIndex();

		TestOrderState state = new TestOrderState();
		state.addRunRecord(TestOrderState.buildRunRecord(List.of("com.example.MyTest"), Set.of("com.example.MyTest")));
		state.save(tempDir.resolve(".test-order-state"));
		mojo.overrideState = state;

		mojo.execute();
		String json = extractJson(Files.readString(resolveOutput()));
		// totalFailures should be 1
		assertTrue(json.contains("\"totalFailures\":1"), "Failed run should record totalFailures=1");
	}

	@Test
	void durationAppearsInTestEntry() throws Exception {
		writeMinimalIndex();
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.example.MyTest", 1234L);
		state.save(tempDir.resolve(".test-order-state"));
		mojo.overrideState = state;

		mojo.execute();
		assertTrue(extractJson(Files.readString(resolveOutput())).contains("1234"),
				"Recorded duration 1234 ms should appear in JSON");
	}

	@Test
	void medianDurationIsZeroWithNoHistory() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		assertTrue(extractJson(Files.readString(resolveOutput())).contains("\"medianDuration\":0"),
				"medianDuration should be 0 when no durations have been recorded");
	}

	@Test
	void medianDurationMatchesSingleRecordedValue() throws Exception {
		writeMinimalIndex();
		TestOrderState state = new TestOrderState();
		state.recordDuration("com.example.MyTest", 500L);
		state.save(tempDir.resolve(".test-order-state"));
		mojo.overrideState = state;

		mojo.execute();
		assertTrue(extractJson(Files.readString(resolveOutput())).contains("\"medianDuration\":500"),
				"medianDuration should equal the single recorded duration (500 ms)");
	}

	@Test
	void changedClassesArePopulated() throws Exception {
		writeMinimalIndex();
		inject(mojo, "changeMode", "explicit");
		// Set the String field on AbstractTestOrderMojo (parent) directly
		Field f = AbstractTestOrderMojo.class.getDeclaredField("changedClasses");
		f.setAccessible(true);
		f.set(mojo, "com.app.ChangedClass");

		mojo.execute();
		String json = extractJson(Files.readString(resolveOutput()));
		assertTrue(json.contains("\"changedClasses\""), "JSON must have 'changedClasses'");
		assertTrue(json.contains("ChangedClass"), "Changed class name must appear in JSON");
	}

	@Test
	void outputDirectoryIsCreatedAutomatically() throws Exception {
		writeMinimalIndex();
		Path deep = tempDir.resolve("deep/nested/dir/dashboard.html");
		inject(mojo, "dashboardOutput", deep.toString());
		mojo.execute();
		assertTrue(Files.exists(deep), "Output file should be created even with missing parent dirs");
	}

	@Test
	void firstRankIsOne() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		assertTrue(extractJson(Files.readString(resolveOutput())).contains("\"rank\":1"),
				"First test in the list should have rank=1");
	}

	@Test
	void coverageDataIsPopulatedWhenDepsExist() throws Exception {
		writeMinimalIndex();
		mojo.execute();
		String json = extractJson(Files.readString(resolveOutput()));
		assertTrue(json.contains("\"coverage\":"), "JSON must have 'coverage' field");
		assertFalse(json.contains("\"coverage\":null"), "coverage must not be null when deps exist");
		assertTrue(json.contains("\"totalSourceClasses\""), "coverage must have totalSourceClasses");
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private void writeMinimalIndex() throws Exception {
		DependencyMap map = new DependencyMap();
		map.put("com.example.MyTest", Set.of("com.app.A"));
		map.save(tempDir.resolve("index.lz4"));
	}

	private Path resolveOutput() {
		return tempDir.resolve("target/test-order-dashboard/index.html");
	}

	/** Extracts the JSON blob from the {@code id="dashboard-data"} script tag. */
	static String extractJson(String html) {
		String marker = "id=\"dashboard-data\">";
		int start = html.indexOf(marker);
		assertTrue(start >= 0, "Could not find dashboard-data script tag in HTML");
		start += marker.length();
		int end = html.indexOf("</script>", start);
		assertTrue(end > start, "Could not find closing </script> tag");
		return html.substring(start, end).trim();
	}

	static void inject(Object target, String fieldName, Object value) throws Exception {
		Class<?> clazz = target.getClass();
		while (clazz != null) {
			try {
				Field f = clazz.getDeclaredField(fieldName);
				f.setAccessible(true);
				f.set(target, value);
				return;
			} catch (NoSuchFieldException e) {
				clazz = clazz.getSuperclass();
			}
		}
		throw new NoSuchFieldException("Field not found in class hierarchy: " + fieldName);
	}

	// ── Testable subclass ─────────────────────────────────────────────────────

	static final class TestableDashboardMojo extends DashboardMojo {
		TestOrderState overrideState;
		Set<String> stubbedChangedClasses = Set.of();
		Set<String> stubbedChangedTestClasses = Set.of();

		@Override
		protected Set<String> detectChangedClasses() {
			return stubbedChangedClasses;
		}
		@Override
		protected Set<String> detectChangedTestClasses() {
			return stubbedChangedTestClasses;
		}
		@Override
		protected TestOrderState loadState() {
			return overrideState != null ? overrideState : new TestOrderState();
		}
	}
}
