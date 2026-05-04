package me.bechberger.testorder.dashboard.ui;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
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

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.plugin.ServeDashboardMojo;

/**
 * Test fixture that starts a {@link ServeDashboardMojo} with realistic
 * synthetic data and exposes the bound HTTP URL for Playwright tests.
 *
 * <p>
 * Usage:
 *
 * <pre>
 * try (DashboardServerFixture f = new DashboardServerFixture(tempDir)) {
 * 	f.start();
 * 	// use f.url()
 * }
 * </pre>
 */
class DashboardServerFixture implements AutoCloseable {

	private final Path tempDir;
	private final TestableServeDashboardMojo mojo;
	private Thread serverThread;

	DashboardServerFixture(Path tempDir) throws Exception {
		this.tempDir = tempDir;
		mojo = new TestableServeDashboardMojo();
		setupMojo();
	}

	// ── Rich test data ────────────────────────────────────────────────────────

	/** Populates a realistic dependency map and run history. */
	DashboardServerFixture withRichTestData() throws Exception {
		DependencyMap map = new DependencyMap();
		map.put("com.example.UserServiceTest",
				Set.of("com.example.UserService", "com.example.UserRepository", "com.example.ValidationService"));
		map.put("com.example.OrderServiceTest",
				Set.of("com.example.OrderService", "com.example.OrderRepository", "com.example.UserService"));
		map.put("com.example.PaymentServiceTest",
				Set.of("com.example.PaymentService", "com.example.UserService", "com.example.BillingClient"));
		map.put("com.example.CartServiceTest", Set.of("com.example.CartService", "com.example.ProductService"));
		map.put("com.example.ProductServiceTest",
				Set.of("com.example.ProductService", "com.example.ProductRepository"));
		map.put("com.example.EmailServiceTest", Set.of("com.example.EmailService", "com.example.SmtpClient"));
		map.put("com.example.AuthServiceTest",
				Set.of("com.example.AuthService", "com.example.UserService", "com.example.TokenManager"));
		map.put("com.example.DatabaseMigrationTest", Set.of("com.example.MigrationRunner", "com.example.DataSource"));
		map.put("com.example.IntegrationTest", Set.of("com.example.UserService", "com.example.OrderService",
				"com.example.PaymentService", "com.example.CartService"));
		map.put("com.example.ReportServiceTest",
				Set.of("com.example.ReportService", "com.example.OrderRepository", "com.example.UserRepository"));
		map.save(tempDir.resolve("index.lz4"));

		// Create a "new" test class (not in dep map) so the newTest weight slider
		// has visible effect in the simulation — DashboardMojo discovers it by
		// scanning target/test-classes for .class files.
		Path testClassesDir = tempDir.resolve("target/test-classes/com/example");
		Files.createDirectories(testClassesDir);
		Files.createFile(testClassesDir.resolve("NewFeatureTest.class"));

		TestOrderState state = new TestOrderState();

		// Duration history
		state.recordDuration("com.example.UserServiceTest", 150L);
		state.recordDuration("com.example.OrderServiceTest", 220L);
		state.recordDuration("com.example.PaymentServiceTest", 180L);
		state.recordDuration("com.example.CartServiceTest", 90L);
		state.recordDuration("com.example.ProductServiceTest", 110L);
		state.recordDuration("com.example.EmailServiceTest", 75L);
		state.recordDuration("com.example.AuthServiceTest", 200L);
		state.recordDuration("com.example.DatabaseMigrationTest", 500L);
		state.recordDuration("com.example.IntegrationTest", 800L);
		state.recordDuration("com.example.ReportServiceTest", 320L);

		// Run 1: all pass
		state.addRunRecord(TestOrderState.buildRunRecord(List.of("com.example.AuthServiceTest",
				"com.example.UserServiceTest", "com.example.PaymentServiceTest", "com.example.OrderServiceTest",
				"com.example.CartServiceTest", "com.example.ProductServiceTest", "com.example.EmailServiceTest",
				"com.example.ReportServiceTest", "com.example.DatabaseMigrationTest", "com.example.IntegrationTest"),
				Set.of()));

		// Run 2: two failures
		state.addRunRecord(TestOrderState.buildRunRecord(
				List.of("com.example.UserServiceTest", "com.example.PaymentServiceTest", "com.example.AuthServiceTest",
						"com.example.OrderServiceTest", "com.example.CartServiceTest", "com.example.ProductServiceTest",
						"com.example.EmailServiceTest", "com.example.ReportServiceTest",
						"com.example.DatabaseMigrationTest", "com.example.IntegrationTest"),
				Set.of("com.example.UserServiceTest", "com.example.PaymentServiceTest")));

		mojo.overrideState = state;

		// Mark UserService as changed → boosts overlapping tests
		mojo.stubbedChangedClasses = Set.of("com.example.UserService");

		return this;
	}

	// ── Lifecycle ─────────────────────────────────────────────────────────────

	void start() throws Exception {
		inject(mojo, "regenerate", "true");
		serverThread = new Thread(() -> {
			try {
				mojo.execute();
			} catch (MojoExecutionException ignored) {
				// expected on interrupt
			}
		});
		serverThread.setDaemon(true);
		serverThread.start();

		long deadline = System.currentTimeMillis() + 5_000;
		while (mojo.boundPort == 0 && System.currentTimeMillis() < deadline) {
			TimeUnit.MILLISECONDS.sleep(20);
		}
		if (mojo.boundPort == 0) {
			throw new IllegalStateException("Dashboard HTTP server did not start within 5 s");
		}
	}

	String url() {
		return "http://localhost:" + mojo.boundPort;
	}

	@Override
	public void close() {
		if (serverThread != null) {
			serverThread.interrupt();
		}
	}

	// ── Mojo wiring ───────────────────────────────────────────────────────────

	private void setupMojo() throws Exception {
		MavenProject project = mock(MavenProject.class);
		when(project.getArtifactId()).thenReturn("ui-test-project");
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
		inject(mojo, "port", 0);
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
		throw new NoSuchFieldException("Field not found in hierarchy: " + fieldName);
	}

	// ── Testable subclass ─────────────────────────────────────────────────────

	static final class TestableServeDashboardMojo extends ServeDashboardMojo {
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
		@Override
		protected void tryOpenBrowser(URI uri) {
			/* suppress in tests */ }
	}
}
