package me.bechberger.testorder;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import me.bechberger.testorder.annotations.ThreadSafe;

/**
 * TCP server that collects dependency data from forked test JVMs via socket.
 * <p>
 * Runs in the build tool process (Maven plugin / Gradle plugin) and accepts
 * connections from {@code IndexCollectorClient} instances in each forked JVM.
 * After all tests complete, call {@link #stopAndMerge(Path)} to write the final
 * index.
 * <p>
 * This eliminates:
 * <ul>
 * <li>Writing hundreds of .deps files per fork</li>
 * <li>File locking for concurrent index merge</li>
 * <li>Reflective DependencyMap access in forked JVMs (classloader issues)</li>
 * </ul>
 *
 * <p>
 * Thread-safety: accept-loop runs on a single thread; per-connection handlers
 * run on a fixed thread pool ({@value #MAX_HANDLER_THREADS} threads). Shared
 * state uses {@link ConcurrentHashMap} for lock-free access. Lazy class-name
 * dictionary load is protected by {@code synchronized(this)} double-check.
 */
@ThreadSafe
public class IndexCollectorServer implements AutoCloseable {

	private static final int MAGIC = 0x54_4F_44_50; // "TODP"
	private static final byte PROTOCOL_VERSION_V1 = 1;
	private static final byte PROTOCOL_VERSION_V2 = 2;
	/** v3: v2 binary protocol + UTF-8 moduleId string after the version byte. */
	private static final byte PROTOCOL_VERSION_V3 = 3;
	/** v4: v1 string protocol + UTF-8 moduleId string after the version byte. */
	private static final byte PROTOCOL_VERSION_V4 = 4;
	private static final int MEMBER_ID_OFFSET = 8_000_000;

	/** Max concurrent handler threads to prevent resource exhaustion. */
	private static final int MAX_HANDLER_THREADS = 50;

	/**
	 * JVM-global registry of running servers keyed by port. Stored as a value in
	 * {@link System#getProperties()} so it is visible from all classloader realms
	 * in the same JVM (the extension and plugin Maven realms each load their own
	 * copy of this class, but they share the system Properties Hashtable).
	 */
	private static final String JVM_REGISTRY_KEY = "testorder.IndexCollectorServer.registry";

	@SuppressWarnings("unchecked")
	private static ConcurrentHashMap<Integer, Object> jvmRegistry() {
		return (ConcurrentHashMap<Integer, Object>) System.getProperties().computeIfAbsent(JVM_REGISTRY_KEY,
				k -> new ConcurrentHashMap<>());
	}

	/**
	 * Drain and stop the collector for the given port, writing the index file. Can
	 * be called from any classloader realm as long as the running server was
	 * registered in the JVM registry. Uses reflection if the server object was
	 * loaded by a different classloader realm.
	 */
	public static void drainByPort(int port, Path indexFile) {
		Object server = jvmRegistry().remove(port);
		if (server == null) {
			return;
		}
		try {
			// Direct cast works when called from the same realm that registered the server.
			int merged = ((IndexCollectorServer) server).stopAndMerge(indexFile);
			if (merged > 0) {
				System.out
						.println("[test-order] IndexCollectorServer merged " + merged + " test classes via port drain");
			}
		} catch (ClassCastException e) {
			// Cross-realm cast: use reflection to invoke stopAndMerge(Path) on the
			// server object which belongs to a different ClassRealm.
			try {
				java.lang.reflect.Method m = server.getClass().getMethod("stopAndMerge", Path.class);
				Object result = m.invoke(server, indexFile);
				int merged = result instanceof Integer ? (Integer) result : 0;
				if (merged > 0) {
					System.out.println("[test-order] IndexCollectorServer merged " + merged
							+ " test classes via cross-realm port drain");
				}
			} catch (Exception re) {
				Throwable cause = (re instanceof java.lang.reflect.InvocationTargetException ite
						&& ite.getCause() != null) ? ite.getCause() : re;
				System.err.println("[test-order] drainByPort reflective call failed for port " + port + ": "
						+ cause.getClass().getSimpleName() + ": " + cause.getMessage());
			}
		} catch (Exception | NoClassDefFoundError ex) {
			System.err.println("[test-order] drainByPort failed for port " + port + ": " + ex.getMessage());
		}
	}

	private final ServerSocket serverSocket;
	private final Thread acceptThread;
	private final ExecutorService handlerExecutor;
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final Path indexFile;
	private final Path mappingFile; // ClassIdMapping file for v2 protocol
	/**
	 * Package prefixes for classes that have source; deps outside these are
	 * dropped. Null means keep all.
	 */
	private volatile String[] sourcePackagePrefixes; // set via setIncludePackages
	private volatile String[] classNames; // lazily loaded from mappingFile
	private volatile String[] memberNames; // lazily loaded from mappingFile
	private final Thread shutdownHook;

	// Shared maps for incremental merge — ConcurrentHashMap for lock-free access
	private final ConcurrentHashMap<String, Set<String>> mergedClassDeps = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Set<String>> mergedMethodDeps = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Set<String>> mergedMemberDeps = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Set<String>> mergedMethodMemberDeps = new ConcurrentHashMap<>();
	/** Maps each test FQCN to the moduleId of the fork that recorded it. */
	private final ConcurrentHashMap<String, String> mergedTestToModule = new ConcurrentHashMap<>();
	private final AtomicInteger receivedCount = new AtomicInteger();
	private final AtomicInteger activeHandlers = new AtomicInteger();

	/**
	 * Start the collector server on a random available port on localhost.
	 *
	 * @param indexFile
	 *            path to the binary dependency index; used by the shutdown hook to
	 *            auto-merge if {@link #stopAndMerge} isn't called explicitly.
	 */
	public IndexCollectorServer(Path indexFile) throws IOException {
		this(indexFile, null);
	}

	/**
	 * Start the collector server with ClassIdMapping support for v2 binary
	 * protocol.
	 *
	 * @param indexFile
	 *            path to the binary dependency index
	 * @param mappingFile
	 *            path to the class-id-map.bin file (for v2 protocol); null to
	 *            disable v2 support
	 */
	public IndexCollectorServer(Path indexFile, Path mappingFile) throws IOException {
		this.indexFile = indexFile;
		this.mappingFile = mappingFile;
		// Pre-load classes needed by stopAndMerge so they're available even if
		// the plugin classloader is torn down before the shutdown hook fires.
		// Without this, Maven's classloader cleanup causes NoClassDefFoundError.
		preloadMergeClasses();

		serverSocket = new ServerSocket();
		try {
			serverSocket.setReuseAddress(true);
			serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
			serverSocket.setSoTimeout(500); // 500ms accept timeout for responsive shutdown
		} catch (IOException e) {
			try {
				serverSocket.close();
			} catch (IOException ignored) {
			}
			throw e;
		}

		handlerExecutor = Executors.newFixedThreadPool(MAX_HANDLER_THREADS,
				r -> new Thread(r, "test-order-collector-handler"));

		acceptThread = new Thread(this::acceptLoop, "test-order-index-collector");
		acceptThread.setDaemon(true);
		acceptThread.start();

		// Register in JVM-global registry so afterSessionEnd() (extension realm) can
		// drain the server even when the extension and plugin realms are separate.
		jvmRegistry().put(serverSocket.getLocalPort(), this);

		// Safety net: when CollectorLifecycleParticipant.afterSessionEnd() is not
		// called (e.g. plugin lacks <extensions>true</extensions>), write deps to a
		// text-based fallback file. The next build run will merge it via
		// processFallbackFile(). Using only JDK classes here avoids
		// NoClassDefFoundError from classloader teardown that affects RoaringBitmap
		// / LZ4 serialization.
		shutdownHook = new Thread(() -> {
			if (running.get()) {
				writeFallbackPayloads();
			}
		}, "test-order-collector-shutdown");
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	/**
	 * Eagerly load all classes needed by the merge path so they survive classloader
	 * teardown during JVM shutdown.
	 */
	private static void preloadMergeClasses() {
		try {
			DependencyMap.preloadSaveClasses();
		} catch (NoClassDefFoundError | Exception e) {
			// Non-fatal: if classes aren't on the classpath, the
			// explicit stopAndMerge call from the plugin will handle it.
		}
	}

	/**
	 * Returns the port the server is listening on. Pass this to forked JVMs via
	 * {@code -Dtestorder.collector.port=<port>}.
	 */
	public int getPort() {
		return serverSocket.getLocalPort();
	}

	/**
	 * Returns the number of payloads received so far.
	 */
	public int getReceivedCount() {
		return receivedCount.get();
	}

	/**
	 * Set the package prefixes that have source available (comma-separated, e.g.
	 * {@code "com.example,org.myapp"}). Dependency entries outside these prefixes
	 * are dropped from the index — they belong to libraries without source and
	 * contribute nothing to change detection.
	 */
	public void setIncludePackages(String includePackages) {
		if (includePackages == null || includePackages.isBlank()) {
			this.sourcePackagePrefixes = null;
		} else {
			this.sourcePackagePrefixes = java.util.Arrays.stream(includePackages.split("[,;]+"))
					.map(p -> p.endsWith(".") ? p.substring(0, p.length() - 1) : p).toArray(String[]::new);
		}
	}

	/**
	 * Stop accepting connections and merge all received data into the index file.
	 *
	 * @return the number of test classes merged
	 */
	public int stopAndMerge() {
		return stopAndMerge(this.indexFile);
	}

	/**
	 * Stop accepting connections and merge all received data into the given index
	 * file.
	 *
	 * @param targetIndexFile
	 *            path to the binary dependency index (test-dependencies.lz4)
	 * @return the number of test classes merged
	 */
	public int stopAndMerge(Path targetIndexFile) {
		if (!running.compareAndSet(true, false)) {
			return 0; // already stopped
		}
		// Remove from registry immediately before merge to prevent concurrent drain
		// attempts
		// from using stale state if merge fails partway through
		jvmRegistry().remove(serverSocket.getLocalPort());
		try {
			acceptThread.join(5000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		// Wait for any in-progress handler threads to finish writing to the maps.
		// Use exponential backoff instead of fixed 10ms polling to reduce CPU spin.
		long deadline = System.currentTimeMillis() + 3000;
		long delay = 1;
		while (activeHandlers.get() > 0 && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(Math.min(delay, 100));
				delay = Math.min(delay * 2, 100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		closeServerSocket();
		unregisterShutdownHook();

		if (mergedClassDeps.isEmpty()) {
			return 0;
		}

		Map<String, Set<String>> classDepsToWrite = applyFrequencyThreshold(mergedClassDeps);
		Map<String, Set<String>> memberDepsToWrite = classDepsToWrite == mergedClassDeps
				? mergedMemberDeps
				: filterMemberDeps(mergedMemberDeps, classDepsToWrite);
		Map<String, Set<String>> methodMemberDepsToWrite = classDepsToWrite == mergedClassDeps
				? mergedMethodMemberDeps
				: filterMemberDeps(mergedMethodMemberDeps, classDepsToWrite);

		// Write the already-merged index
		try {
			DependencyMap.mergeFromAgent(targetIndexFile, classDepsToWrite, mergedMethodDeps, memberDepsToWrite,
					methodMemberDepsToWrite, mergedTestToModule);
		} catch (IOException e) {
			System.err.println("[test-order] IndexCollectorServer: failed to write index: " + e.getMessage());
			return 0;
		}
		logIndexSize(targetIndexFile, classDepsToWrite.size());
		return classDepsToWrite.size();
	}

	/**
	 * Applies the {@code testorder.deps.dropFrequencyThreshold} filter. If the
	 * property is set to a value in (0, 1), any dep class that appears in more than
	 * {@code threshold * totalTests} test entries is removed from every test's dep
	 * set. This reduces index size and prevents near-universal deps from flooding
	 * the scorer. Returns the input map unchanged if the property is not set or the
	 * threshold is not in the valid range.
	 */
	private static Map<String, Set<String>> applyFrequencyThreshold(Map<String, Set<String>> classDeps) {
		double threshold = parseDropFrequencyThreshold();
		if (Double.isNaN(threshold)) {
			return classDeps;
		}
		int total = classDeps.size();
		if (total == 0) {
			return classDeps;
		}
		int maxCount = (int) Math.ceil(threshold * total);

		// Count how many tests each dep appears in
		Map<String, Integer> depFreq = new HashMap<>();
		for (Set<String> deps : classDeps.values()) {
			for (String dep : deps) {
				depFreq.merge(dep, 1, Integer::sum);
			}
		}

		Set<String> toDrop = new java.util.HashSet<>();
		for (Map.Entry<String, Integer> e : depFreq.entrySet()) {
			if (e.getValue() > maxCount) {
				toDrop.add(e.getKey());
			}
		}
		if (toDrop.isEmpty()) {
			return classDeps;
		}

		int droppedDeps = toDrop.size();
		Map<String, Set<String>> filtered = new java.util.LinkedHashMap<>(classDeps.size());
		for (Map.Entry<String, Set<String>> e : classDeps.entrySet()) {
			Set<String> orig = e.getValue();
			Set<String> kept = new java.util.LinkedHashSet<>(orig);
			kept.removeAll(toDrop);
			filtered.put(e.getKey(), kept);
		}
		System.out.println("[test-order] Frequency filter (threshold=" + threshold + "): dropped " + droppedDeps
				+ " high-frequency dep(s) present in >" + (int) (threshold * 100) + "% of " + total + " tests.");
		return filtered;
	}

	/**
	 * Removes member-dep entries ({@code "fqcn#member"}) for any class that was
	 * dropped from the class-level deps. This keeps member deps consistent with the
	 * filtered class deps so the scorer doesn't see member deps for classes it
	 * won't score against.
	 */
	private static Map<String, Set<String>> filterMemberDeps(Map<String, Set<String>> memberDeps,
			Map<String, Set<String>> filteredClassDeps) {
		// Build the set of all dep classes remaining after the frequency filter
		Set<String> keptClasses = new java.util.HashSet<>();
		for (Set<String> deps : filteredClassDeps.values()) {
			keptClasses.addAll(deps);
		}
		Map<String, Set<String>> result = new java.util.LinkedHashMap<>(memberDeps.size());
		for (Map.Entry<String, Set<String>> e : memberDeps.entrySet()) {
			Set<String> orig = e.getValue();
			Set<String> kept = new java.util.LinkedHashSet<>();
			for (String memberKey : orig) {
				int hash = memberKey.lastIndexOf('#');
				String cls = hash > 0 ? memberKey.substring(0, hash) : memberKey;
				if (keptClasses.contains(cls)) {
					kept.add(memberKey);
				}
			}
			if (!kept.isEmpty()) {
				result.put(e.getKey(), kept);
			}
		}
		return result;
	}

	private static double parseDropFrequencyThreshold() {
		String prop = System.getProperty("testorder.deps.dropFrequencyThreshold");
		if (prop == null || prop.isBlank()) {
			return Double.NaN;
		}
		try {
			double v = Double.parseDouble(prop);
			if (v > 0.0 && v < 1.0) {
				return v;
			}
		} catch (NumberFormatException ignored) {
		}
		System.err.println("[test-order] Invalid testorder.deps.dropFrequencyThreshold value: " + prop
				+ " (must be in (0, 1)) — frequency filter disabled.");
		return Double.NaN;
	}

	private static void logIndexSize(Path indexFile, int testCount) {
		try {
			long bytes = java.nio.file.Files.size(indexFile);
			double mb = bytes / (1024.0 * 1024.0);
			String sizeStr = mb >= 1.0 ? String.format("%.1f MB", mb) : String.format("%d KB", bytes / 1024);
			System.out.println("[test-order] Index written: " + sizeStr + " (" + testCount + " tests)");
			if (mb > 20) {
				System.out.println("[test-order] WARNING: index is large (" + sizeStr + "). "
						+ "Consider setting -Dtestorder.deps.dropFrequencyThreshold=0.8 to drop near-universal deps.");
			}
		} catch (IOException e) {
			// size logging is best-effort
		}
	}

	/**
	 * Stop the server without merging (e.g., on build failure).
	 */
	@Override
	public void close() {
		running.set(false);
		jvmRegistry().remove(serverSocket.getLocalPort());
		closeServerSocket();
		unregisterShutdownHook();
		try {
			acceptThread.join(2000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		handlerExecutor.shutdown();
	}

	private void unregisterShutdownHook() {
		try {
			Runtime.getRuntime().removeShutdownHook(shutdownHook);
		} catch (IllegalStateException ignored) {
			// JVM already shutting down
		}
	}

	private void closeServerSocket() {
		try {
			serverSocket.close();
		} catch (IOException ignored) {
		}
	}

	private void acceptLoop() {
		while (running.get()) {
			try {
				Socket client = serverSocket.accept();
				activeHandlers.incrementAndGet();
				try {
					handlerExecutor.execute(() -> {
						try {
							handleClient(client);
						} catch (Throwable t) {
							System.err.println("[test-order] Unhandled exception in client handler: " + t);
						} finally {
							READ_BUF.remove();
							activeHandlers.decrementAndGet();
						}
					});
				} catch (RejectedExecutionException e) {
					activeHandlers.decrementAndGet();
					try {
						client.close();
					} catch (IOException ignored) {
					}
					System.err.println("[test-order] Handler thread pool full, rejecting connection");
				}
			} catch (SocketTimeoutException e) {
				// Normal: accept timed out, loop back to check running flag
			} catch (IOException e) {
				if (running.get()) {
					System.err.println("[test-order] IndexCollectorServer accept error: " + e.getMessage());
					try {
						Thread.sleep(100);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
					}
				}
			}
		}
	}

	private void handleClient(Socket client) {
		try (client;
				DataInputStream in = new DataInputStream(new BufferedInputStream(client.getInputStream(), 65536));
				OutputStream rawOut = client.getOutputStream()) {
			client.setSoTimeout(30_000); // 30s read timeout per client
			// Read and validate header
			int magic = in.readInt();
			if (magic != MAGIC) {
				System.err.println("[test-order] IndexCollectorServer: invalid magic from client");
				rawOut.write(0); // NACK
				return;
			}
			byte version = in.readByte();
			if (version == PROTOCOL_VERSION_V3) {
				String moduleId = readString(in);
				Set<String> payloadKeys = handleBinaryPayload(in);
				if (payloadKeys == null) {
					rawOut.write(0);
					rawOut.flush();
					return;
				}
				stampTestsWithModule(payloadKeys, moduleId);
			} else if (version == PROTOCOL_VERSION_V4) {
				String moduleId = readString(in);
				Set<String> payloadKeys = handleStringPayload(in);
				stampTestsWithModule(payloadKeys, moduleId);
			} else if (version == PROTOCOL_VERSION_V2) {
				Set<String> payloadKeys = handleBinaryPayload(in);
				if (payloadKeys == null) {
					// No ClassIdMapping loaded — NACK so client falls back to v1
					rawOut.write(0);
					rawOut.flush();
					return;
				}
			} else if (version == PROTOCOL_VERSION_V1) {
				handleStringPayload(in);
			} else {
				System.err.println("[test-order] IndexCollectorServer: unsupported protocol version " + version);
				rawOut.write(0); // NACK
				return;
			}
			receivedCount.incrementAndGet();

			// Send ACK
			rawOut.write(1);
			rawOut.flush();
		} catch (IOException e) {
			System.err.println("[test-order] IndexCollectorServer: error reading from client: " + e.getMessage());
		}
	}

	private Set<String> handleStringPayload(DataInputStream in) throws IOException {
		Map<String, Set<String>> classDeps = readMap(in);
		Map<String, Set<String>> methodDeps = readMap(in);
		Map<String, Set<String>> memberDeps = readMap(in);
		Map<String, Set<String>> methodMemberDeps = readMap(in);

		// Merge into concurrent maps
		mergeMaps(mergedClassDeps, classDeps);
		mergeMaps(mergedMethodDeps, methodDeps);
		mergeMaps(mergedMemberDeps, memberDeps);
		mergeMaps(mergedMethodMemberDeps, methodMemberDeps);
		return classDeps.keySet();
	}

	/**
	 * Handle a v2/v3 binary payload. Returns the set of test-class keys that were
	 * in the payload (regardless of whether they were new), or {@code null} if no
	 * ClassIdMapping was available (caller should NACK so the client falls back to
	 * v1 string protocol).
	 */
	private Set<String> handleBinaryPayload(DataInputStream in) throws IOException {
		String[] cn = ensureClassNames();
		String[] mn = ensureMemberNames();
		if (cn == null) {
			// No mapping loaded — can't decode v2. Signal caller to NACK.
			System.err.println("[test-order] IndexCollectorServer: v2 payload received but no ClassIdMapping loaded"
					+ " — sending NACK so client retries with v1 string protocol");
			return null;
		}

		Set<String> payloadTestKeys = new HashSet<>();

		// Read test-class trackers
		int testCount = in.readInt();
		if (testCount < 0 || testCount > 100_000) {
			throw new IOException("Invalid testCount from client: " + testCount);
		}
		for (int i = 0; i < testCount; i++) {
			String key = readString(in);
			payloadTestKeys.add(key);
			long[] classWords = readLongArray(in);
			long[] memberWords = readLongArray(in);
			Set<String> classDeps = resolveClassIds(classWords, cn);
			Set<String> memberDeps = resolveMemberIds(memberWords, mn);
			if (!classDeps.isEmpty()) {
				mergedClassDeps.merge(key, classDeps, (existing, incoming) -> {
					Set<String> combined = new java.util.HashSet<>(existing.size() + incoming.size());
					combined.addAll(existing);
					combined.addAll(incoming);
					return combined;
				});
			}
			if (!memberDeps.isEmpty()) {
				mergedMemberDeps.merge(key, memberDeps, (existing, incoming) -> {
					Set<String> combined = new java.util.HashSet<>(existing.size() + incoming.size());
					combined.addAll(existing);
					combined.addAll(incoming);
					return combined;
				});
			}
		}

		// Read method trackers
		int methodCount = in.readInt();
		if (methodCount < 0 || methodCount > 1_000_000) {
			throw new IOException("Invalid methodCount from client: " + methodCount);
		}
		for (int i = 0; i < methodCount; i++) {
			String key = readString(in);
			long[] classWords = readLongArray(in);
			long[] memberWords = readLongArray(in);
			Set<String> classDeps = resolveClassIds(classWords, cn);
			Set<String> memberDeps = resolveMemberIds(memberWords, mn);
			if (!classDeps.isEmpty()) {
				mergedMethodDeps.merge(key, classDeps, (existing, incoming) -> {
					Set<String> combined = new java.util.HashSet<>(existing.size() + incoming.size());
					combined.addAll(existing);
					combined.addAll(incoming);
					return combined;
				});
			}
			if (!memberDeps.isEmpty()) {
				mergedMethodMemberDeps.merge(key, memberDeps, (existing, incoming) -> {
					Set<String> combined = new java.util.HashSet<>(existing.size() + incoming.size());
					combined.addAll(existing);
					combined.addAll(incoming);
					return combined;
				});
			}
		}
		return payloadTestKeys;
	}

	private static final long[] EMPTY_LONGS = new long[0];

	/**
	 * Per-handler-thread scratch buffer for decoding longs and strings from the
	 * wire. Starts at 512 bytes and grows on demand. Shared between
	 * {@link #readLongArray} and {@link #readString} — both are always called
	 * sequentially on the same thread, so sharing is safe.
	 */
	private static final ThreadLocal<byte[]> READ_BUF = ThreadLocal.withInitial(() -> new byte[512]);

	private long[] readLongArray(DataInputStream in) throws IOException {
		int len = in.readInt();
		if (len == 0)
			return EMPTY_LONGS;
		if (len < 0 || len > 1_000_000)
			throw new IOException("Invalid long array length: " + len);
		long[] arr = new long[len];
		int needed = len * 8;
		byte[] buf = READ_BUF.get();
		if (buf.length < needed) {
			buf = new byte[needed];
			READ_BUF.set(buf);
		}
		in.readFully(buf, 0, needed);
		for (int i = 0, off = 0; i < len; i++, off += 8) {
			arr[i] = ((long) (buf[off] & 0xFF) << 56) | ((long) (buf[off + 1] & 0xFF) << 48)
					| ((long) (buf[off + 2] & 0xFF) << 40) | ((long) (buf[off + 3] & 0xFF) << 32)
					| ((long) (buf[off + 4] & 0xFF) << 24) | ((long) (buf[off + 5] & 0xFF) << 16)
					| ((long) (buf[off + 6] & 0xFF) << 8) | ((long) (buf[off + 7] & 0xFF));
		}
		return arr;
	}

	private Set<String> resolveClassIds(long[] words, String[] names) {
		if (words.length == 0)
			return Set.of();
		int estimated = 0;
		for (long w : words)
			estimated += Long.bitCount(w);
		Set<String> result = new HashSet<>(estimated + (estimated >>> 2));
		for (int wi = 0; wi < words.length; wi++) {
			long word = words[wi];
			if (word == 0)
				continue;
			int baseId = wi << 6;
			for (long bits = word; bits != 0; bits &= bits - 1) {
				int id = baseId + Long.numberOfTrailingZeros(bits);
				if (id < names.length && names[id] != null) {
					result.add(names[id]);
				}
			}
		}
		return result;
	}

	private Set<String> resolveMemberIds(long[] words, String[] names) {
		if (words.length == 0 || names == null)
			return Set.of();
		int estimated = 0;
		for (long w : words)
			estimated += Long.bitCount(w);
		Set<String> result = new HashSet<>(estimated + (estimated >>> 2));
		for (int wi = 0; wi < words.length; wi++) {
			long word = words[wi];
			if (word == 0)
				continue;
			int baseAdj = wi << 6;
			for (long bits = word; bits != 0; bits &= bits - 1) {
				int adj = baseAdj + Long.numberOfTrailingZeros(bits);
				if (adj < names.length && names[adj] != null) {
					result.add(names[adj]);
				}
			}
		}
		return result;
	}

	/**
	 * Lazily load class names from the mapping file. Thread-safe via volatile +
	 * double-check.
	 */
	private String[] ensureClassNames() {
		String[] cn = classNames;
		if (cn != null)
			return cn;
		if (mappingFile == null || !java.nio.file.Files.exists(mappingFile))
			return null;
		synchronized (this) {
			cn = classNames;
			if (cn != null)
				return cn;
			loadMapping();
			return classNames;
		}
	}

	private String[] ensureMemberNames() {
		String[] mn = memberNames;
		if (mn != null)
			return mn;
		if (mappingFile == null || !java.nio.file.Files.exists(mappingFile))
			return null;
		synchronized (this) {
			mn = memberNames;
			if (mn != null)
				return mn;
			loadMapping();
			return memberNames;
		}
	}

	private void loadMapping() {
		try {
			// Use reflection to avoid compile-time dependency on agent module
			// ClassIdMapping is in test-order-agent which may not be on our classpath
			// directly; but in the Maven plugin's classpath it usually is.
			var mappingClass = Class.forName("me.bechberger.testorder.agent.runtime.ClassIdMapping");
			var loadMethod = mappingClass.getMethod("load", java.nio.file.Path.class);
			Object mapping = loadMethod.invoke(null, mappingFile);

			var classCountMethod = mappingClass.getMethod("classCount");
			int classCount = (int) classCountMethod.invoke(mapping);
			var getClassNameMethod = mappingClass.getMethod("getClassName", int.class);
			String[] cn = new String[classCount];
			for (int i = 0; i < classCount; i++) {
				cn[i] = (String) getClassNameMethod.invoke(mapping, i);
			}
			var memberCountMethod = mappingClass.getMethod("memberCount");
			int memberCount = (int) memberCountMethod.invoke(mapping);
			var getMemberNameMethod = mappingClass.getMethod("getMemberName", int.class);
			String[] mn = new String[memberCount];
			for (int i = 0; i < memberCount; i++) {
				mn[i] = (String) getMemberNameMethod.invoke(mapping, i + MEMBER_ID_OFFSET);
			}
			memberNames = mn;
			classNames = cn;
		} catch (Exception e) {
			System.err.println("[test-order] IndexCollectorServer: failed to load ClassIdMapping from " + mappingFile
					+ ": " + e.getMessage());
			classNames = new String[0];
			memberNames = new String[0];
		}
	}

	/** Maximum number of entries allowed in a single map (safety limit). */
	private static final int MAX_MAP_ENTRIES = 500_000;
	/** Maximum string length in the wire protocol (64 KB). */
	private static final int MAX_STRING_LENGTH = 65535;

	private static Map<String, Set<String>> readMap(DataInputStream in) throws IOException {
		int entryCount = in.readInt();
		if (entryCount == 0) {
			return Map.of();
		}
		if (entryCount < 0 || entryCount > MAX_MAP_ENTRIES) {
			throw new IOException("Invalid map entry count: " + entryCount);
		}
		Map<String, Set<String>> map = new HashMap<>(entryCount);
		for (int i = 0; i < entryCount; i++) {
			String key = readString(in);
			int valueCount = in.readInt();
			if (valueCount < 0 || valueCount > MAX_MAP_ENTRIES) {
				throw new IOException("Invalid value set size: " + valueCount);
			}
			Set<String> values = new HashSet<>(valueCount);
			for (int j = 0; j < valueCount; j++) {
				values.add(readString(in));
			}
			map.put(key, values);
		}
		return map;
	}

	private static String readString(DataInputStream in) throws IOException {
		int len = in.readUnsignedShort();
		if (len > MAX_STRING_LENGTH) {
			throw new IOException("String length exceeds maximum: " + len);
		}
		byte[] buf = READ_BUF.get();
		if (buf.length < len) {
			buf = new byte[Math.max(len, 512)];
			READ_BUF.set(buf);
		}
		in.readFully(buf, 0, len);
		return new String(buf, 0, len, StandardCharsets.UTF_8);
	}

	private void mergeMaps(ConcurrentHashMap<String, Set<String>> target, Map<String, Set<String>> source) {
		String[] prefixes = this.sourcePackagePrefixes;
		for (var entry : source.entrySet()) {
			Set<String> filtered = new java.util.HashSet<>();
			for (String dep : entry.getValue()) {
				if (!isSyntheticClass(dep) && hasSource(dep, prefixes)) {
					filtered.add(dep);
				}
			}
			if (filtered.isEmpty()) {
				continue;
			}
			target.merge(entry.getKey(), filtered, (existing, incoming) -> {
				// merge() serializes per-key access already; build a new combined Set
				// rather than mutating `existing` so the value semantics stay immutable
				// from the perspective of concurrent readers iterating the map.
				Set<String> combined = new java.util.HashSet<>(existing.size() + incoming.size());
				combined.addAll(existing);
				combined.addAll(incoming);
				return combined;
			});
		}
	}

	/** Returns true if {@code className} should be kept as a dependency entry. */
	private static boolean hasSource(String className, String[] prefixes) {
		if (prefixes == null || prefixes.length == 0) {
			return true;
		}
		for (String prefix : prefixes) {
			if (className.startsWith(prefix) && (className.length() == prefix.length()
					|| className.charAt(prefix.length()) == '.' || className.charAt(prefix.length()) == '$')) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns true for runtime-generated synthetic class names that should not
	 * appear in the dependency index: proxies, cglib enhancers, lambda forms, etc.
	 * These are generated at runtime and carry no stable source-level identity.
	 */
	static boolean isSyntheticClass(String className) {
		if (className == null) {
			return true;
		}
		// cglib: Foo$$EnhancerByCGLIB$$abc123
		if (className.contains("$$EnhancerByCGLIB$$") || className.contains("$$FastClassByCGLIB$$")) {
			return true;
		}
		// JDK dynamic proxies: com.sun.proxy.$Proxy0, jdk.proxy1.$Proxy2
		if (className.startsWith("com.sun.proxy.") || className.startsWith("jdk.proxy")) {
			return true;
		}
		// Lambda form classes: java.lang.invoke.LambdaForm$MH, Foo$$Lambda$123
		if (className.contains("$$Lambda$") || className.startsWith("java.lang.invoke.LambdaForm$")) {
			return true;
		}
		// Hibernate/Spring runtime repackaged classes
		if (className.startsWith("org.hibernate.repackage.") || className.startsWith("org.springframework.cglib.")) {
			return true;
		}
		return false;
	}

	/**
	 * After a payload has been merged, record this fork's moduleId for every
	 * test-class key in {@code mergedClassDeps} that was either added by this
	 * payload or hasn't yet had a moduleId recorded. Empty/null moduleIds are
	 * skipped so older agents (which don't send a moduleId) don't pollute the map.
	 */
	private void stampNewTestsWithModule(Set<String> beforeKeys, String moduleId) {
		if (moduleId == null || moduleId.isEmpty()) {
			return;
		}
		for (String testKey : mergedClassDeps.keySet()) {
			if (!beforeKeys.contains(testKey)) {
				mergedTestToModule.putIfAbsent(testKey, moduleId);
			}
		}
	}

	private void stampTestsWithModule(Set<String> payloadKeys, String moduleId) {
		if (moduleId == null || moduleId.isEmpty() || payloadKeys == null || payloadKeys.isEmpty()) {
			return;
		}
		for (String testKey : payloadKeys) {
			mergedTestToModule.putIfAbsent(testKey, moduleId);
		}
	}

	// ── Fallback file support ───────────────────────────────────────────

	/**
	 * Suffix for fallback payload files written when the shutdown hook can't merge.
	 */
	private static final String FALLBACK_SUFFIX = ".collector-fallback";

	/**
	 * Guards against two threads processing the same fallback file on
	 * non-atomic-move filesystems.
	 */
	private static final java.util.concurrent.ConcurrentHashMap<Path, Boolean> FALLBACK_PROCESSING = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * Write raw payloads to a fallback file next to the index file using only JDK
	 * classes. This is the last-resort path when the plugin classloader is torn
	 * down. Format: line-based text, "K\tV1\tV2\t..." per entry, sections separated
	 * by "---".
	 */
	private void writeFallbackPayloads() {
		if (mergedClassDeps.isEmpty() || indexFile == null) {
			return;
		}
		Path fallbackFile = indexFile.resolveSibling(indexFile.getFileName() + FALLBACK_SUFFIX);
		try {
			java.nio.file.Files.createDirectories(fallbackFile.getParent());
			try (java.io.PrintWriter pw = new java.io.PrintWriter(
					java.nio.file.Files.newBufferedWriter(fallbackFile, StandardCharsets.UTF_8))) {
				writeMapText(pw, mergedClassDeps);
				pw.println("---");
				writeMapText(pw, mergedMethodDeps);
				pw.println("---");
				writeMapText(pw, mergedMemberDeps);
				pw.println("---");
				writeMapText(pw, mergedMethodMemberDeps);
				pw.println("===");
				if (!mergedTestToModule.isEmpty()) {
					pw.println("===module-map");
					for (var entry : mergedTestToModule.entrySet()) {
						if (entry.getValue() != null && !entry.getValue().isEmpty()) {
							pw.println(entry.getKey() + "\t" + entry.getValue());
						}
					}
					pw.println("===end-module-map");
				}
			}
			System.err.println("[test-order] Wrote fallback payloads to " + fallbackFile
					+ " — will be merged on the next build run.");
			System.err.println("[test-order] TIP: If you see this every run, add <extensions>true</extensions> to the"
					+ " test-order-maven-plugin declaration in your pom.xml.");
		} catch (IOException e) {
			System.err.println("[test-order] Failed to write fallback payloads: " + e.getMessage());
		}
	}

	private static void writeMapText(java.io.PrintWriter pw, Map<String, Set<String>> map) {
		for (var entry : map.entrySet()) {
			pw.print(entry.getKey());
			for (String val : entry.getValue()) {
				pw.print('\t');
				pw.print(val);
			}
			pw.println();
		}
	}

	/**
	 * Load and process any fallback payload file for the given index, then delete
	 * it. Called by the Maven/Gradle plugin during aggregation.
	 *
	 * @param indexFile
	 *            the index file path (fallback file is sibling with
	 *            {@code .collector-fallback} suffix)
	 * @return true if a fallback file was found and processed
	 */
	public static boolean processFallbackFile(Path indexFile) throws IOException {
		return processFallbackFile(indexFile, null);
	}

	public static boolean processFallbackFile(Path indexFile, String includePackages) throws IOException {
		final String[] prefixes = (includePackages == null || includePackages.isBlank())
				? null
				: java.util.Arrays.stream(includePackages.split("[,;]+"))
						.map(p -> p.endsWith(".") ? p.substring(0, p.length() - 1) : p).toArray(String[]::new);
		Path fallbackFile = indexFile.resolveSibling(indexFile.getFileName() + FALLBACK_SUFFIX);
		if (!java.nio.file.Files.exists(fallbackFile)) {
			return false;
		}
		// Atomically claim the file by renaming it before parsing — prevents a second
		// concurrent module invocation from re-processing the same payloads.
		Path claimedFile = fallbackFile.resolveSibling(fallbackFile.getFileName() + ".processing");
		try {
			java.nio.file.Files.move(fallbackFile, claimedFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.NoSuchFileException | java.nio.file.AtomicMoveNotSupportedException e) {
			// Already claimed by another module, or atomic move not available.
			if (!java.nio.file.Files.exists(fallbackFile)) {
				return false;
			}
			// On non-atomic-move filesystems two threads can both see the file and reach
			// here. Use a JVM-level sentinel to ensure only one processes it.
			if (FALLBACK_PROCESSING.putIfAbsent(fallbackFile.toAbsolutePath().normalize(), Boolean.TRUE) != null) {
				return false; // another thread is already processing this file
			}
			claimedFile = fallbackFile;
		}
		List<String> lines;
		try {
			lines = java.nio.file.Files.readAllLines(claimedFile, StandardCharsets.UTF_8);
		} catch (IOException e) {
			java.nio.file.Files.deleteIfExists(claimedFile);
			throw e;
		}
		Map<String, Set<String>> classDeps = new HashMap<>();
		Map<String, Set<String>> methodDeps = new HashMap<>();
		Map<String, Set<String>> memberDeps = new HashMap<>();
		Map<String, Set<String>> methodMemberDeps = new HashMap<>();
		Map<String, String> testToModule = new HashMap<>();

		// Parse: 4 maps per payload, separated by "---", payloads separated by "==="
		// Optionally followed by "===module-map" ... "===end-module-map" section.
		int mapIndex = 0;
		Map<String, Set<String>> current = classDeps;
		boolean inModuleMap = false;
		for (String line : lines) {
			if ("===module-map".equals(line)) {
				inModuleMap = true;
				continue;
			}
			if ("===end-module-map".equals(line)) {
				inModuleMap = false;
				continue;
			}
			if (inModuleMap) {
				int tab = line.indexOf('\t');
				if (tab > 0) {
					testToModule.put(line.substring(0, tab), line.substring(tab + 1));
				}
				continue;
			}
			if ("===".equals(line)) {
				mapIndex = 0;
				current = classDeps;
				continue;
			}
			if ("---".equals(line)) {
				mapIndex++;
				current = switch (mapIndex) {
					case 1 -> methodDeps;
					case 2 -> memberDeps;
					case 3 -> methodMemberDeps;
					default -> current;
				};
				continue;
			}
			String[] parts = line.split("\t");
			if (parts.length >= 1 && !parts[0].isEmpty()) {
				Set<String> values = current.computeIfAbsent(parts[0], k -> new HashSet<>());
				for (int i = 1; i < parts.length; i++) {
					if (!isSyntheticClass(parts[i]) && hasSource(parts[i], prefixes)) {
						values.add(parts[i]);
					}
				}
			}
		}

		try {
			DependencyMap.mergeFromAgent(indexFile, classDeps, methodDeps, memberDeps, methodMemberDeps, testToModule);
		} finally {
			java.nio.file.Files.deleteIfExists(claimedFile);
			FALLBACK_PROCESSING.remove(fallbackFile.toAbsolutePath().normalize());
		}
		return true;
	}
}
