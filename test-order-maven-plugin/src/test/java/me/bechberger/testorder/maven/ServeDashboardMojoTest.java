package me.bechberger.testorder.maven;

import static me.bechberger.testorder.maven.DashboardMojoTest.inject;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServeDashboardMojoTest {

	@TempDir
	Path tempDir;

	private TestableServeDashboardMojo mojo;
	private Path htmlFile;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new TestableServeDashboardMojo();

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

		htmlFile = tempDir.resolve("target/test-order-dashboard/index.html");

		inject(mojo, "project", project);
		inject(mojo, "session", session);
		inject(mojo, "indexFile", tempDir.resolve("index.lz4").toString());
		inject(mojo, "stateFile", tempDir.resolve(".test-order-state").toString());
		inject(mojo, "depsDir", tempDir.resolve("deps").toString());
		inject(mojo, "dashboardOutput", htmlFile.toString());
		inject(mojo, "openBrowser", false);
		inject(mojo, "port", 0); // auto-assign free port
		inject(mojo, "regenerate", "false"); // default: don't regenerate in serve tests
		inject(mojo, "serveSeconds", 0);
	}

	// ── regenerate=false, no file → error ─────────────────────────────────────

	@Test
	void missingFileAndNoRegenerateFails() {
		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		String msg = ex.getMessage();
		assertTrue(msg.contains("Dashboard not found") || msg.contains("not found"),
				"Expected 'not found' error, got: " + msg);
	}

	@Test
	void errorMessageContainsFilePath() {
		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains(htmlFile.getFileName().toString()),
				"Error message should mention the missing file name");
	}

	// ── regenerate=auto with existing file → serve only ──────────────────────

	@Test
	void existingFileIsServedWithoutRegeneration() throws Exception {
		String content = "<html><body>dashboard</body></html>";
		writeHtml(content);
		inject(mojo, "regenerate", "auto");

		runAndVerify(content);
	}

	// ── regenerate=false with existing file → serve ───────────────────────────

	@Test
	void servesExistingFileSuccessfully() throws Exception {
		String content = "<html><head><title>Test Dashboard</title></head><body>ok</body></html>";
		writeHtml(content);

		runAndVerify(content);
	}

	@Test
	void httpResponseIs200() throws Exception {
		writeHtml("<html><body>test</body></html>");

		runServeInBackground();
		waitForBound();

		HttpResponse<String> resp = get("/");
		assertEquals(200, resp.statusCode(), "Server should respond with HTTP 200");
		stopServing();
	}

	@Test
	void contentTypeIsHtml() throws Exception {
		writeHtml("<html><body>test</body></html>");

		runServeInBackground();
		waitForBound();

		HttpResponse<String> resp = get("/");
		String ct = resp.headers().firstValue("content-type").orElse("");
		assertTrue(ct.contains("text/html"), "Content-Type must be text/html, got: " + ct);
		stopServing();
	}

	@Test
	void responseBodyMatchesDashboardContent() throws Exception {
		String marker = "UNIQUE_MARKER_XYZ_987";
		writeHtml("<html><body>" + marker + "</body></html>");

		runServeInBackground();
		waitForBound();

		HttpResponse<String> resp = get("/");
		assertTrue(resp.body().contains(marker), "Response body should contain the HTML content");
		stopServing();
	}

	@Test
	void unknownPathAlsoServesDashboard() throws Exception {
		// Paths that are not /assets/... fall through to the dashboard HTML
		writeHtml("<html><body>dash</body></html>");

		runServeInBackground();
		waitForBound();

		HttpResponse<String> resp = get("/anything/else");
		assertEquals(200, resp.statusCode(), "Non-asset paths should fall back to the dashboard HTML");
		stopServing();
	}

	@Test
	void selfContainedHtmlIsServedForAnyPath() throws Exception {
		// The HTML is fully self-contained — server returns it for every path
		writeHtml("<html><body>self-contained</body></html>");

		runServeInBackground();
		waitForBound();

		// Root serves the HTML
		HttpResponse<String> root = get("/");
		assertEquals(200, root.statusCode(), "Root path should return the HTML");
		assertTrue(root.headers().firstValue("content-type").orElse("").contains("text/html"),
				"Content-Type must be text/html");
		assertTrue(root.body().contains("self-contained"), "Body must be the dashboard HTML");

		// Any other path also returns the HTML (no separate asset endpoints)
		HttpResponse<String> other = get("/anything/else");
		assertEquals(200, other.statusCode(), "Any path should return the HTML");
		stopServing();
	}

	@Test
	void assetPathDoesNotExposeParentDirectories() throws Exception {
		writeHtml("<html><body>dash</body></html>");

		runServeInBackground();
		waitForBound();

		// Traversal attempt — server must not 500; must return 200
		// (dashboard HTML) or 404
		HttpResponse<String> resp = get("/assets/../index.html");
		assertTrue(resp.statusCode() == 404 || resp.statusCode() == 200, "Traversal attempt must not 500");
		stopServing();
	}

	@Test
	void servesCustomOutputFilename() throws Exception {
		htmlFile = tempDir.resolve("target/test-order-dashboard/custom-dashboard.html");
		inject(mojo, "dashboardOutput", htmlFile.toString());
		String marker = "CUSTOM_OUTPUT_FILE_MARKER";
		writeHtml("<html><body>" + marker + "</body></html>");

		runServeInBackground();
		waitForBound();

		HttpResponse<String> resp = get("/");
		assertEquals(200, resp.statusCode(), "Server should return HTTP 200 for custom output filename");
		assertTrue(resp.body().contains(marker), "Server should serve the configured dashboard output file");
		stopServing();
	}

	// ── regenerate=true with index → generates then would serve ──────────────

	@Test
	void regenerateTrueGeneratesFileEvenIfExists() throws Exception {
		// Write a stale placeholder; after regenerate=true it should be replaced
		writeHtml("<html>STALE</html>");
		inject(mojo, "regenerate", "true");

		// Need a real index for the regeneration step
		var depMap = new me.bechberger.testorder.DependencyMap();
		depMap.put("com.example.RegenTest", Set.of("com.app.X"));
		depMap.save(tempDir.resolve("index.lz4"));
		mojo.overrideState = new me.bechberger.testorder.TestOrderState();

		runServeInBackground();
		waitForBound();

		// Check the file was regenerated (no longer STALE)
		String body = get("/").body();
		assertFalse(body.contains("STALE"), "File should be regenerated, not the stale placeholder");
		assertTrue(body.contains("dashboard-data"), "Regenerated file should contain the dashboard script tag");
		stopServing();
	}

	@Test
	void serveSecondsStopsServerAutomatically() throws Exception {
		writeHtml("<html><body>timed</body></html>");
		inject(mojo, "serveSeconds", 1);

		long startNanos = System.nanoTime();
		mojo.execute();
		long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

		assertNotEquals(0, mojo.boundPort, "Server should bind before timed shutdown");
		assertTrue(elapsedMillis >= 800, "Timed serve should stay up long enough to be useful");
		assertTrue(elapsedMillis < 5_000, "Timed serve should stop on its own");
	}

	@Test
	void negativeServeSecondsFailsFast() throws Exception {
		writeHtml("<html><body>timed</body></html>");
		inject(mojo, "serveSeconds", -1);

		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains("serveSeconds"), "Error should mention the invalid property");
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private void writeHtml(String content) throws IOException {
		Files.createDirectories(htmlFile.getParent());
		Files.writeString(htmlFile, content);
	}

	private void runAndVerify(String expectedContent) throws Exception {
		runServeInBackground();
		waitForBound();

		HttpResponse<String> resp = getEventuallyOk("/");
		assertEquals(200, resp.statusCode());
		assertTrue(resp.body().contains(expectedContent.substring(0, 20)));
		stopServing();
	}

	private Thread serverThread;

	private void runServeInBackground() {
		serverThread = new Thread(() -> {
			try {
				mojo.execute();
			} catch (MojoExecutionException e) {
				// expected after interrupt
			}
		});
		serverThread.setDaemon(true);
		serverThread.start();
	}

	/** Waits up to 3 s for the server to bind a port. */
	private void waitForBound() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 3_000;
		while (mojo.boundPort == 0 && System.currentTimeMillis() < deadline) {
			TimeUnit.MILLISECONDS.sleep(20);
		}
		assertNotEquals(0, mojo.boundPort, "Server should have bound a port within 3 s");
	}

	private void stopServing() throws InterruptedException {
		if (serverThread != null) {
			serverThread.interrupt();
			serverThread.join(1000);
		}
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + mojo.boundPort + path)).GET()
				.build();
		// Retry once on connection refused (server may not be fully ready)
		try {
			return client.send(req, HttpResponse.BodyHandlers.ofString());
		} catch (java.net.ConnectException e) {
			TimeUnit.MILLISECONDS.sleep(200);
			return client.send(req, HttpResponse.BodyHandlers.ofString());
		}
	}

	private HttpResponse<String> getEventuallyOk(String path) throws Exception {
		HttpResponse<String> last = null;
		for (int i = 0; i < 5; i++) {
			last = get(path);
			if (last.statusCode() == 200) {
				return last;
			}
			TimeUnit.MILLISECONDS.sleep(100);
		}
		return last;
	}

	// ── Testable subclass ─────────────────────────────────────────────────────

	static final class TestableServeDashboardMojo extends ServeDashboardMojo {
		me.bechberger.testorder.TestOrderState overrideState;
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
		protected me.bechberger.testorder.TestOrderState loadState() {
			return overrideState != null ? overrideState : new me.bechberger.testorder.TestOrderState();
		}
		@Override
		protected void tryOpenBrowser(java.net.URI uri) {
			// suppress browser opening in tests
		}
	}
}
