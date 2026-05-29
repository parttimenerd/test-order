package me.bechberger.testorder.ops;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * <li>{@code GET /api/classinfo?class=com.example.Foo} — return first Javadoc
 * summary and public method signatures for a source class</li>
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
	 * Starts the dashboard HTTP server and blocks until interrupted (Ctrl+C). The
	 * optional {@code portCallback} is invoked with the bound port before blocking,
	 * allowing callers to observe the port while the server is running.
	 */
	public static int start(Path htmlPath, Path statePath, int port, PluginLog log,
			java.util.function.IntConsumer portCallback) throws IOException {
		return start(htmlPath, statePath, port, log, portCallback, 0, false);
	}

	/**
	 * Starts the dashboard HTTP server. When {@code serveSeconds > 0}, the server
	 * stops automatically after the configured duration; otherwise it runs until
	 * interrupted (Ctrl+C).
	 */
	public static int start(Path htmlPath, Path statePath, int port, PluginLog log,
			java.util.function.IntConsumer portCallback, long serveSeconds) throws IOException {
		return start(htmlPath, statePath, port, log, portCallback, serveSeconds, false);
	}

	/**
	 * Starts the dashboard HTTP server. When {@code serveSeconds > 0}, the server
	 * stops automatically after the configured duration; otherwise it runs until
	 * interrupted (Ctrl+C).
	 *
	 * @param openBrowser
	 *            if {@code true}, attempt to open the served URL in the default
	 *            browser after the server starts
	 */
	public static int start(Path htmlPath, Path statePath, int port, PluginLog log,
			java.util.function.IntConsumer portCallback, long serveSeconds, boolean openBrowser) throws IOException {
		return start(htmlPath, statePath, port, log, portCallback, serveSeconds, openBrowser, List.of());
	}

	/**
	 * Starts the dashboard HTTP server with source root paths for the
	 * {@code /api/classinfo} endpoint.
	 *
	 * @param sourceRoots
	 *            directories to search for {@code .java} source files (e.g.
	 *            {@code src/main/java}). May be empty — in that case
	 *            {@code /api/classinfo} returns {@code 404}.
	 */
	public static int start(Path htmlPath, Path statePath, int port, PluginLog log,
			java.util.function.IntConsumer portCallback, long serveSeconds, boolean openBrowser, List<Path> sourceRoots)
			throws IOException {
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
		server.createContext("/api/classinfo/bulk", exchange -> handleClassInfoBulk(exchange, sourceRoots));
		server.createContext("/api/classinfo", exchange -> handleClassInfo(exchange, sourceRoots));
		server.createContext("/favicon.ico", exchange -> exchange.sendResponseHeaders(204, -1));
		server.createContext("/", exchange -> handleHtml(exchange, htmlPath));
		server.setExecutor(executor);

		try {
			server.start();
		} catch (RuntimeException e) {
			executor.shutdownNow();
			throw e;
		}

		int boundPort = server.getAddress().getPort();
		String url = "http://localhost:" + boundPort;

		log.info("[test-order] Dashboard served at: " + url);
		if (serveSeconds > 0) {
			log.info("[test-order] Server will stop automatically after " + serveSeconds + " s.");
		} else {
			// Note: no message here — callers (ServeDashboardMojo, Gradle) print their own
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
		// CSRF protection: reject cross-origin POST requests
		String origin = exchange.getRequestHeaders().getFirst("Origin");
		if (origin != null && !origin.startsWith("http://localhost:") && !origin.startsWith("http://127.0.0.1:")) {
			exchange.sendResponseHeaders(403, -1);
			return;
		}
		try {
			TestOrderState.OptimizeResult result = PersistenceSupport.withFileLock(statePath, () -> {
				TestOrderState s = Files.exists(statePath) ? TestOrderState.load(statePath) : new TestOrderState();
				TestOrderState.OptimizeResult r = s.optimize();
				if (r == null)
					return null;
				s.setWeights(r.weights());
				s.save(statePath);
				return r;
			});
			if (result == null) {
				sendJson(exchange, "{\"error\":\"Not enough failure runs for optimization (need at least 3)\"}");
				return;
			}
			TestOrderState.ScoringWeights w = result.weights();
			String json = String.format("{\"weights\":{\"newTest\":%d,\"changedTest\":%d,\"maxFailure\":%d,"
					+ "\"speed\":%d,\"speedPenalty\":%d,\"depOverlap\":%d,"
					+ "\"changeComplexity\":%d,\"staticFieldBonus\":%d,\"coverageBonus\":%d,\"killRateBonus\":%d},"
					+ "\"trainScore\":%.4f,\"validationScore\":%.4f,\"overfit\":%b,\"folds\":%d}", w.newTest(),
					w.changedTest(), w.maxFailure(), w.speed(), w.speedPenalty(), w.depOverlap(), w.changeComplexity(),
					w.staticFieldBonus(), w.coverageBonus(), w.killRateBonus(), result.trainScore(),
					result.validationScore(), result.overfit(), result.folds());
			sendJson(exchange, json);
		} catch (Exception e) {
			String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
			// Escape for JSON: backslashes, quotes, and control characters
			msg = msg.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
					.replace("\t", "\\t");
			sendJson(exchange, "{\"error\":\"" + msg + "\"}");
		}
	}

	private static void handleClassInfo(HttpExchange exchange, List<Path> sourceRoots) throws IOException {
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(405, -1);
			return;
		}
		String query = exchange.getRequestURI().getQuery();
		String className = null;
		if (query != null) {
			for (String part : query.split("&")) {
				if (part.startsWith("class=")) {
					className = URLDecoder.decode(part.substring(6), StandardCharsets.UTF_8);
					break;
				}
			}
		}
		if (className == null || className.isBlank()) {
			exchange.sendResponseHeaders(400, -1);
			return;
		}
		// Validate: only allow fully-qualified Java identifiers
		if (!className.matches("[a-zA-Z_$][a-zA-Z0-9_$.]*")) {
			exchange.sendResponseHeaders(400, -1);
			return;
		}
		String relPath = className.replace('.', '/') + ".java";
		// Inner class: strip $Inner → use outer class file
		int dollarIdx = relPath.indexOf('$');
		if (dollarIdx >= 0) {
			relPath = relPath.substring(0, dollarIdx) + ".java";
		}
		Path sourceFile = null;
		for (Path root : sourceRoots) {
			Path candidate = root.resolve(relPath);
			if (Files.isRegularFile(candidate)) {
				sourceFile = candidate;
				break;
			}
		}
		if (sourceFile == null) {
			exchange.sendResponseHeaders(404, -1);
			return;
		}
		String source;
		try {
			source = Files.readString(sourceFile, StandardCharsets.UTF_8);
		} catch (IOException e) {
			exchange.sendResponseHeaders(500, -1);
			return;
		}
		String javadoc = extractFirstJavadoc(source);
		List<String> methods = extractPublicMethods(source);
		StringBuilder json = new StringBuilder();
		json.append("{\"className\":\"").append(jsonEsc(className)).append('"');
		if (javadoc != null) {
			json.append(",\"javadoc\":\"").append(jsonEsc(javadoc)).append('"');
		}
		json.append(",\"methods\":[");
		for (int i = 0; i < methods.size(); i++) {
			if (i > 0)
				json.append(',');
			json.append('"').append(jsonEsc(methods.get(i))).append('"');
		}
		json.append("]}");
		sendJson(exchange, json.toString());
	}

	/**
	 * POST /api/classinfo/bulk — accepts a JSON array of class names, returns a
	 * JSON object mapping each name to its ClassInfo (or null if not found).
	 */
	private static void handleClassInfoBulk(HttpExchange exchange, List<Path> sourceRoots) throws IOException {
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			exchange.sendResponseHeaders(405, -1);
			return;
		}
		String origin = exchange.getRequestHeaders().getFirst("Origin");
		if (origin != null && !origin.startsWith("http://localhost:") && !origin.startsWith("http://127.0.0.1:")) {
			exchange.sendResponseHeaders(403, -1);
			return;
		}
		String body;
		try {
			body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();
		} catch (IOException e) {
			exchange.sendResponseHeaders(400, -1);
			return;
		}
		// Parse JSON array of strings: ["a.B","c.D",...]
		if (!body.startsWith("[")) {
			exchange.sendResponseHeaders(400, -1);
			return;
		}
		List<String> classNames = new ArrayList<>();
		// Simple extraction: find all quoted strings inside the array
		Matcher qm = Pattern.compile("\"([^\"\\\\]*)\"").matcher(body);
		while (qm.find()) {
			String cn = qm.group(1);
			if (cn.matches("[a-zA-Z_$][a-zA-Z0-9_$.]*")) {
				classNames.add(cn);
			}
		}
		StringBuilder json = new StringBuilder("{");
		boolean first = true;
		for (String className : classNames) {
			String relPath = className.replace('.', '/') + ".java";
			int dollarIdx = relPath.indexOf('$');
			if (dollarIdx >= 0)
				relPath = relPath.substring(0, dollarIdx) + ".java";
			Path sourceFile = null;
			for (Path root : sourceRoots) {
				Path candidate = root.resolve(relPath);
				if (Files.isRegularFile(candidate)) {
					sourceFile = candidate;
					break;
				}
			}
			if (!first)
				json.append(',');
			first = false;
			json.append('"').append(jsonEsc(className)).append("\":");
			if (sourceFile == null) {
				json.append("null");
				continue;
			}
			String source;
			try {
				source = Files.readString(sourceFile, StandardCharsets.UTF_8);
			} catch (IOException e) {
				json.append("null");
				continue;
			}
			String javadoc = extractFirstJavadoc(source);
			List<String> methods = extractPublicMethods(source);
			json.append("{\"className\":\"").append(jsonEsc(className)).append('"');
			if (javadoc != null)
				json.append(",\"javadoc\":\"").append(jsonEsc(javadoc)).append('"');
			json.append(",\"methods\":[");
			for (int i = 0; i < methods.size(); i++) {
				if (i > 0)
					json.append(',');
				json.append('"').append(jsonEsc(methods.get(i))).append('"');
			}
			json.append("]}");
		}
		json.append("}");
		sendJson(exchange, json.toString());
	}

	/** Extracts the first sentence of the first class-level Javadoc comment. */
	private static String extractFirstJavadoc(String source) {
		// Find /** ... */ before the class/interface/enum declaration
		Pattern javadocPat = Pattern.compile(
				"/\\*\\*(.*?)\\*/\\s*(?:@\\w[^\\n]*\\n)*\\s*(?:public|protected|private|abstract|final|class|interface|enum|record|@interface)",
				Pattern.DOTALL);
		Matcher m = javadocPat.matcher(source);
		if (!m.find())
			return null;
		String raw = m.group(1);
		// Strip leading * from each line
		raw = raw.replaceAll("(?m)^\\s*\\*\\s?", " ").trim();
		// Replace inline tags like {@link Foo} → Foo, {@code x} → x
		raw = raw.replaceAll("\\{@(?:link|linkplain|code|value)\\s+([^}]*)\\}", "$1");
		// Strip any remaining {@...} tags
		raw = raw.replaceAll("\\{@[^}]*\\}", "");
		// Remove block @tags (must come after inline tag removal)
		int tagIdx = raw.indexOf('@');
		if (tagIdx > 0)
			raw = raw.substring(0, tagIdx).trim();
		// Collapse whitespace
		raw = raw.replaceAll("\\s+", " ").trim();
		if (raw.isEmpty())
			return null;
		// First sentence only (up to '. ' or end)
		int dot = raw.indexOf(". ");
		if (dot > 0)
			raw = raw.substring(0, dot + 1);
		return raw.length() > 200 ? raw.substring(0, 197) + "…" : raw;
	}

	private static final Pattern METHOD_PAT = Pattern.compile(
			"(?:^|\\n)[ \\t]*(?:(?:public|protected)(?:\\s+(?:static|final|synchronized|default|abstract|native))*\\s+)"
					+ "(?:<[^>]*>\\s*)?" // generics
					+ "(?:[A-Za-z_$][A-Za-z0-9_$.<>\\[\\]?,\\s]*?\\s+)" // return type
					+ "([A-Za-z_$][A-Za-z0-9_$]*)\\s*" // method name
					+ "\\(([^)]*)\\)"); // params

	// Also matches @Test/@ParameterizedTest/@BeforeEach etc. annotated
	// package-private methods
	private static final Pattern TEST_METHOD_PAT = Pattern.compile(
			"(?:^|\\n)[ \\t]*@(?:Test|ParameterizedTest|RepeatedTest|BeforeEach|AfterEach|BeforeAll|AfterAll)[^\\n]*\\n"
					+ "[ \\t]*(?:(?:static|final|synchronized)\\s+)*" + "void\\s+" + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*" // method
																														// name
					+ "\\(([^)]*)\\)");

	/**
	 * Extracts up to 8 public/protected method signatures, plus @Test methods for
	 * test classes.
	 */
	private static List<String> extractPublicMethods(String source) {
		List<String> result = new ArrayList<>();
		Matcher m = METHOD_PAT.matcher(source);
		while (m.find() && result.size() < 8) {
			String name = m.group(1);
			if (name.equals("class") || name.equals("interface") || name.equals("enum"))
				continue;
			String params = m.group(2).replaceAll("\\s+", " ").trim();
			params = simplifyParams(params);
			result.add(name + "(" + params + ")");
		}
		// For test classes (few/no public methods), also collect annotated test methods
		if (result.size() < 4) {
			Matcher tm = TEST_METHOD_PAT.matcher(source);
			while (tm.find() && result.size() < 8) {
				String name = tm.group(1);
				String params = tm.group(2).replaceAll("\\s+", " ").trim();
				params = simplifyParams(params);
				String sig = name + "(" + params + ")";
				if (!result.contains(sig))
					result.add(sig);
			}
		}
		return result;
	}

	private static String simplifyParams(String params) {
		if (params.isEmpty())
			return "";
		String[] parts = params.split(",");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0)
				sb.append(", ");
			String p = parts[i].trim();
			// Take last word before any annotation: the type is everything except final
			// identifier
			String[] tokens = p.split("\\s+");
			if (tokens.length >= 2) {
				// type is all but last token (variable name)
				StringBuilder type = new StringBuilder();
				for (int j = 0; j < tokens.length - 1; j++) {
					if (j > 0)
						type.append(' ');
					String t = tokens[j];
					if (t.startsWith("@"))
						continue; // skip annotations
					if (t.equals("final"))
						continue;
					type.append(t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t);
				}
				String ts = type.toString().trim();
				sb.append(ts.isEmpty() ? tokens[tokens.length - 1] : ts);
			} else {
				sb.append(p);
			}
		}
		return sb.toString();
	}

	private static String jsonEsc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t",
				"\\t");
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
		// Restrict CORS to localhost origins — server is loopback-only
		String origin = exchange.getRequestHeaders().getFirst("Origin");
		if (origin != null && (origin.startsWith("http://localhost:") || origin.startsWith("http://127.0.0.1:"))) {
			exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
		}
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
