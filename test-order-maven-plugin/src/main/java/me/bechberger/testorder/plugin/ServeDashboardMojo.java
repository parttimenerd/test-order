package me.bechberger.testorder.plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Serves the test-order dashboard via a local HTTP server and opens it in the
 * browser.
 *
 * <p>
 * If the dashboard HTML has not been generated yet (or
 * {@code regenerate=true}), the dashboard is (re-)generated first. The server
 * runs until the Maven build is interrupted (Ctrl+C).
 *
 * <p>
 * Usage: {@code mvn test-order:serve}
 *
 * <p>
 * Custom port: {@code mvn test-order:serve -Dtestorder.dashboard.port=8080}
 */
@Mojo(name = "serve", requiresProject = true)
public class ServeDashboardMojo extends DashboardMojo {

	/**
	 * TCP port for the embedded HTTP server. {@code 0} (default) picks a free
	 * ephemeral port automatically.
	 */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_PORT, defaultValue = "0")
	private int port;

	/**
	 * Controls when the dashboard HTML is regenerated before serving:
	 * <ul>
	 * <li>{@code auto} (default) — regenerate only if the file does not exist</li>
	 * <li>{@code true} — always regenerate</li>
	 * <li>{@code false} — never regenerate; fail if the file is missing</li>
	 * </ul>
	 */
	@Parameter(property = MavenPluginConfigKeys.DASHBOARD_REGENERATE, defaultValue = "auto")
	private String regenerate;

	@Override
	public void execute() throws MojoExecutionException {
		Path htmlPath = resolveOutputPath();

		boolean shouldGenerate = switch (regenerate.toLowerCase()) {
			case "true", "yes", "always" -> true;
			case "false", "no", "never" -> false;
			default -> !Files.exists(htmlPath); // "auto"
		};

		if (shouldGenerate) {
			getLog().info("[test-order] Generating dashboard before serving…");
			super.execute();
		} else if (!Files.exists(htmlPath)) {
			throw new MojoExecutionException("[test-order] Dashboard not found at " + htmlPath
					+ " — run 'mvn test-order:dashboard' first, or set " + MavenPluginConfigKeys.DASHBOARD_REGENERATE
					+ "=auto");
		}

		startServer(htmlPath);
	}

	/** Bound port after the server starts; 0 until then. Accessible for testing. */
	public volatile int boundPort = 0;

	private void startServer(Path htmlPath) throws MojoExecutionException {
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(port), /* backlog */ 10);
		} catch (IOException e) {
			throw new MojoExecutionException("Failed to bind HTTP server on port " + port, e);
		}

		server.createContext("/", exchange -> handleRequest(exchange, htmlPath.getParent()));
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.start();

		boundPort = server.getAddress().getPort();
		String url = "http://localhost:" + boundPort;

		getLog().info("[test-order] Dashboard served at: " + url);
		getLog().info("[test-order] Press Ctrl+C to stop.");

		tryOpenBrowser(URI.create(url));

		CountDownLatch latch = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			server.stop(0);
			latch.countDown();
		}));

		try {
			latch.await();
		} catch (InterruptedException e) {
			server.stop(0);
			Thread.currentThread().interrupt();
		}
	}

	private void handleRequest(HttpExchange exchange, Path dir) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
				&& !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(405, -1);
			return;
		}

		// The generated HTML is fully self-contained (assets inlined), so serve
		// index.html for every request — no separate /assets/ needed.
		Path target = dir.resolve("index.html");
		if (!Files.isRegularFile(target)) {
			exchange.sendResponseHeaders(404, -1);
			return;
		}

		byte[] body = Files.readAllBytes(target);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-cache");

		if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(200, -1);
			return;
		}

		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(body);
		}
	}
}
