package me.bechberger.testorder.ops;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

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
		return start(htmlPath, statePath, port, log, null);
	}

	/**
	 * Starts the dashboard HTTP server and blocks until interrupted (Ctrl+C).
	 * The optional {@code portCallback} is invoked with the bound port before
	 * blocking, allowing callers to observe the port while the server is running.
	 */
	public static int start(Path htmlPath, Path statePath, int port, PluginLog log,
			java.util.function.IntConsumer portCallback) throws IOException {
		ExecutorService executor = Executors.newCachedThreadPool();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(port), /* backlog */ 10);
		} catch (IOException e) {
			executor.shutdownNow();
			throw e;
		}

		server.createContext("/api/ping", DashboardServerOperation::handlePing);
		server.createContext("/api/optimize", exchange -> handleOptimize(exchange, statePath, log));
		server.createContext("/", exchange -> handleHtml(exchange, htmlPath));
		server.setExecutor(executor);
		server.start();

		int boundPort = server.getAddress().getPort();
		String url = "http://localhost:" + boundPort;

		log.info("[test-order] Dashboard served at: " + url);
		log.info("[test-order] Press Ctrl+C to stop.");

		if (portCallback != null) {
			portCallback.accept(boundPort);
		}

		tryOpenBrowser(URI.create(url));

		CountDownLatch latch = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			server.stop(0);
			executor.shutdownNow();
			latch.countDown();
		}));

		try {
			latch.await();
		} catch (InterruptedException e) {
			server.stop(0);
			executor.shutdownNow();
			Thread.currentThread().interrupt();
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
			TestOrderState state = Files.exists(statePath) ? TestOrderState.load(statePath) : new TestOrderState();
			TestOrderState.OptimizeResult result = state.optimize();
			if (result == null) {
				sendJson(exchange, "{\"error\":\"Not enough failure runs for optimization (need at least 3)\"}");
				return;
			}
			state.setWeights(result.weights());
			try {
				state.save(statePath);
			} catch (IOException saveEx) {
				log.warn("[test-order] Could not save optimized weights: " + saveEx.getMessage());
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
			sendJson(exchange, "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
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
		exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
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
