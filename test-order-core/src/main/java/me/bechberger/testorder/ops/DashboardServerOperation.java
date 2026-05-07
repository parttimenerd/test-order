package me.bechberger.testorder.ops;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderState;

/**
 * Serves the test-order dashboard via a local HTTP server. Framework-agnostic —
 * used by both the Maven {@code serve} mojo and the Gradle
 * {@code testOrderServe} task.
 *
 * <p>
 * Endpoints:
 * <ul>
 * <li>{@code GET /api/ping} — health check</li>
 * <li>{@code POST /api/optimize} — trigger weight optimisation, persist
 * result</li>
 * <li>{@code GET /} — serve the self-contained dashboard HTML</li>
 * </ul>
 */
public final class DashboardServerOperation {

	private DashboardServerOperation() {
	}

	/**
	 * Starts the dashboard HTTP server and blocks until interrupted (Ctrl+C).
	 *
	 * @param htmlPath
	 *            path to the self-contained dashboard HTML file
	 * @param statePath
	 *            path to the test-order state file (for optimize endpoint)
	 * @param port
	 *            TCP port; 0 picks an ephemeral port
	 * @param log
	 *            logger
	 * @return the port the server bound to (useful when port=0)
	 * @throws IOException
	 *             if the server cannot bind
	 */
	public static int start(Path htmlPath, Path statePath, int port, PluginLog log) throws IOException {
		return start(htmlPath, statePath, port, log, null, 0, false);
	}

	/**
	 * Starts the dashboard HTTP server and blocks until interrupted (Ctrl+C).
	 * The optional {@code portCallback} is invoked with the bound port before
	 * blocking, allowing callers to observe the port while the server is running.
	 */
	public static int start(Path htmlPath, Path statePath, int port, PluginLog log,
			java.util.function.IntConsumer portCallback) throws IOException {
		return start(htmlPath, statePath, port, log, portCallback, 0, false);
	}

	/**
	 * Starts the dashboard HTTP server. When {@code serveSeconds > 0}, the
	 * server stops automatically after the configured duration; otherwise it runs
	 * until interrupted (Ctrl+C).
	 */
	public static int start(Path htmlPath, Path statePath, int port, PluginLog log,
			java.util.function.IntConsumer portCallback, long serveSeconds) throws IOException {
		return start(htmlPath, statePath, port, log, portCallback, serveSeconds, false);
	}

	/**
	 * Starts the dashboard HTTP server. When {@code serveSeconds > 0}, the
	 * server stops automatically after the configured duration; otherwise it runs
	 * until interrupted (Ctrl+C).
	 *
	 * @param openBrowser
	 *            if {@code true}, attempt to open the served URL in the default
	 *            browser after the server starts
	 */
	public static int start(Path htmlPath, Path statePath, int port, PluginLog log,
			java.util.function.IntConsumer portCallback, long serveSeconds, boolean openBrowser) throws IOException {
		ExecutorService executor = Executors.newCachedThreadPool();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), /* backlog */ 10);
		} catch (IOException e) {
			executor.shutdownNow();
			throw e;
		}

		server.createContext("/api/ping", DashboardServerOperation::handlePing);
		server.createContext("/api/optimize", exchange -> handleOptimize(exchange, statePath, log));
		server.createContext("/", exchange -> handleHtml(exchange, htmlPath));
		server.setExecutor(executor);
		
		// Register shutdown hook to ensure port is released on Ctrl+C
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			server.stop(0);
		}));
		
		server.start();

		int boundPort = server.getAddress().getPort();
		String url = "http://localhost:" + boundPort;

		log.info("[test-order] Dashboard served at: " + url);
		if (serveSeconds > 0) {
			log.info("[test-order] Server will stop automatically after " + serveSeconds + " s.");
		} else {
			log.info("[test-order] Press Ctrl+C to stop.");
		}

		if (portCallback != null) {
			portCallback.accept(boundPort);
		}

		if (openBrowser) {
			tryOpenBrowser(URI.create(url));
		}

		CountDownLatch latch = new CountDownLatch(1);
		AtomicBoolean stopped = new AtomicBoolean();
		Runnable stopServer = () -> {
			if (stopped.compareAndSet(false, true)) {
				server.stop(0);
				executor.shutdownNow();
				latch.countDown();
			}
		};
		Thread shutdownHook = new Thread(stopServer, "test-order-dashboard-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
		ScheduledExecutorService timeoutExecutor = null;
		if (serveSeconds > 0) {
			timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
			timeoutExecutor.schedule(stopServer, serveSeconds, TimeUnit.SECONDS);
		}

		try {
			latch.await();
		} catch (InterruptedException e) {
			stopServer.run();
			Thread.currentThread().interrupt();
		} finally {
			if (timeoutExecutor != null) {
				timeoutExecutor.shutdownNow();
			}
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
				// JVM is already shutting down.
			}
		}

		return boundPort;
	}

	// -------------------------------------------------------------------
	// Endpoint handlers
	// -------------------------------------------------------------------

	private static void handlePing(HttpExchange exchange) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(405, -1);
			return;
		}
		sendJson(exchange, "{\"status\":\"ok\",\"server\":\"test-order\"}");
	}

	private static void handleOptimize(HttpExchange exchange, Path statePath, PluginLog log) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(405, -1);
			return;
		}
		try {
			TestOrderState.OptimizeResult result = PersistenceSupport.withFileLock(statePath, () -> {
				TestOrderState s = Files.exists(statePath) ? TestOrderState.load(statePath) : new TestOrderState();
				TestOrderState.OptimizeResult r = s.optimize();
				if (r == null) return null;
				s.setWeights(r.weights());
				s.save(statePath);
				return r;
			});
			if (result == null) {
				sendJson(exchange, "{\"error\":\"Not enough failure runs for optimization (need at least 3)\"}");
				return;
			}
			TestOrderState.ScoringWeights w = result.weights();
			String json = String.format(
					"{\"weights\":{\"newTest\":%d,\"changedTest\":%d,\"maxFailure\":%d,"
							+ "\"speed\":%d,\"speedPenalty\":%d,\"depOverlap\":%d,"
							+ "\"changeComplexity\":%d,\"staticFieldBonus\":%d,\"coverageBonus\":%d},"
							+ "\"trainScore\":%.4f,\"validationScore\":%.4f,\"overfit\":%b,\"folds\":%d}",
					w.newTest(), w.changedTest(), w.maxFailure(), w.speed(), w.speedPenalty(), w.depOverlap(),
					w.changeComplexity(), w.staticFieldBonus(), w.coverageBonus(), result.trainScore(),
					result.validationScore(), result.overfit(), result.folds());
			sendJson(exchange, json);
		} catch (Exception e) {
			String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			// Escape for JSON: backslashes, quotes, and control characters
			msg = msg.replace("\\", "\\\\").replace("\"", "\\\"")
					.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
			sendJson(exchange, "{\"error\":\"" + msg + "\"}");
		}
	}

	private static void handleHtml(HttpExchange exchange, Path htmlPath) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())
				&& !"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(405, -1);
			return;
		}
		if (!Files.isRegularFile(htmlPath)) {
			exchange.sendResponseHeaders(404, -1);
			return;
		}
		byte[] body = Files.readAllBytes(htmlPath);
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

	// -------------------------------------------------------------------
	// Utilities
	// -------------------------------------------------------------------

	private static void sendJson(HttpExchange exchange, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		// Only allow same-origin requests for security (CORS origin check not needed for loopback-only binding)
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "http://localhost:*");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream os = exchange.getResponseBody()) {
			os.write(body);
		}
	}

	private static void tryOpenBrowser(URI uri) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(uri);
			}
		} catch (Exception ignored) {
		}
	}
}
