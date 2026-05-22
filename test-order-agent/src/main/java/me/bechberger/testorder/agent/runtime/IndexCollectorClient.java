package me.bechberger.testorder.agent.runtime;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Client that sends collected dependency data to the IndexCollectorServer
 * running in the build process (Maven/Gradle plugin) via a local TCP socket.
 * <p>
 * This avoids writing intermediary .deps files and eliminates the need for
 * reflective DependencyMap access in the forked JVM. The server handles merging
 * into the binary index.
 * <p>
 * Supports two wire protocols:
 * <ul>
 * <li><b>v1 (string-based)</b>: sends dependency names as UTF-8 strings. Used
 * as fallback when raw bitset data is not available (agent mode).</li>
 * <li><b>v2 (binary IDs)</b>: sends raw long[] bitset words per tracker. Much
 * more compact — avoids ID→string conversion in the forked JVM entirely. The
 * server resolves IDs to names using the ClassIdMapping file.</li>
 * </ul>
 *
 * <h3>v2 wire format</h3>
 *
 * <pre>
 * [4 bytes] magic: 0x54_4F_44_50 ("TODP")
 * [1 byte]  version: 2
 * [4 bytes] test-class tracker count
 * per tracker:
 *   [2 bytes] key length (UTF-8)
 *   [N bytes] key (test class name)
 *   [4 bytes] classWords length (number of longs)
 *   [M×8 bytes] classWords data
 *   [4 bytes] memberWords length (number of longs)
 *   [K×8 bytes] memberWords data
 * [4 bytes] method tracker count
 * per tracker:
 *   [2 bytes] key length (UTF-8)
 *   [N bytes] key (className#methodName)
 *   [4 bytes] classWords length
 *   [M×8 bytes] classWords data
 *   [4 bytes] memberWords length
 *   [K×8 bytes] memberWords data
 * </pre>
 */
public final class IndexCollectorClient {

	static final int MAGIC = 0x54_4F_44_50; // "TODP"
	static final byte PROTOCOL_VERSION_V1 = 1;
	static final byte PROTOCOL_VERSION_V2 = 2;

	private IndexCollectorClient() {
	}

	/**
	 * Send raw bitset data (protocol v2) — avoids all string conversion in the
	 * forked JVM. The server resolves IDs→names using ClassIdMapping.
	 *
	 * @param port
	 *            TCP port of the IndexCollectorServer on localhost
	 * @param perTestTrackers
	 *            test-class name → BitsetTracker
	 * @param perMethodTrackers
	 *            method key → BitsetTracker
	 * @return true if data was sent successfully
	 */
	public static boolean sendBinary(int port, Map<String, BitsetTracker> perTestTrackers,
			Map<String, BitsetTracker> perMethodTrackers) {
		int maxRetries = 2;
		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
				socket.setSoTimeout(10000);
				try (DataOutputStream out = new DataOutputStream(
						new BufferedOutputStream(socket.getOutputStream(), 65536));
						DataInputStream in = new DataInputStream(socket.getInputStream())) {
					out.writeInt(MAGIC);
					out.writeByte(PROTOCOL_VERSION_V2);
					writeTrackerMap(out, perTestTrackers);
					writeTrackerMap(out, perMethodTrackers);
					out.flush();
					int ack = in.read();
					if (ack == 1) {
						return true;
					}
					AgentLogger.log("[IndexCollectorClient] Server NACK'd v2, attempt " + (attempt + 1));
				}
			} catch (IOException e) {
				if (attempt < maxRetries) {
					AgentLogger.log("[IndexCollectorClient] v2 attempt " + (attempt + 1) + " failed: " + e.getMessage()
							+ ", retrying...");
					try {
						Thread.sleep(100L * (attempt + 1));
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						break;
					}
				} else {
					AgentLogger.log("[IndexCollectorClient] Failed to send v2 after " + (maxRetries + 1) + " attempts: "
							+ e.getMessage());
				}
			}
		}
		return false;
	}

	private static void writeTrackerMap(DataOutputStream out, Map<String, BitsetTracker> trackers) throws IOException {
		if (trackers == null || trackers.isEmpty()) {
			out.writeInt(0);
			return;
		}
		out.writeInt(trackers.size());
		for (var entry : trackers.entrySet()) {
			writeString(out, entry.getKey());
			BitsetTracker bt = entry.getValue();
			// Bulk-write: convert longs to byte[] and write in one call.
			// Avoids per-element writeLong() which does 8 individual writes through
			// BufferedOutputStream's bounds-check path.
			long[] cwArr = bt.getClassWordsArray();
			int cwLen = bt.getClassWordsLength();
			out.writeInt(cwLen);
			writeLongsBulk(out, cwArr, cwLen);
			long[] mwArr = bt.getMemberWordsArray();
			int mwLen = bt.getMemberWordsLength();
			out.writeInt(mwLen);
			writeLongsBulk(out, mwArr, mwLen);
		}
	}

	// Reusable scratch buffer for writeLongsBulk. Flush runs in the JVM shutdown
	// hook (single-threaded), so a static field avoids per-call allocation.
	private static byte[] writeBuf = new byte[1024];

	/**
	 * Write a long[] as a single bulk byte[] write. 8x fewer write calls than
	 * per-element writeLong(), and the BufferedOutputStream only needs one bounds
	 * check per call.
	 */
	private static void writeLongsBulk(DataOutputStream out, long[] arr, int len) throws IOException {
		if (len == 0)
			return;
		int needed = len * 8;
		if (writeBuf.length < needed)
			writeBuf = new byte[needed];
		byte[] buf = writeBuf;
		for (int i = 0, off = 0; i < len; i++, off += 8) {
			long v = arr[i];
			buf[off] = (byte) (v >>> 56);
			buf[off + 1] = (byte) (v >>> 48);
			buf[off + 2] = (byte) (v >>> 40);
			buf[off + 3] = (byte) (v >>> 32);
			buf[off + 4] = (byte) (v >>> 24);
			buf[off + 5] = (byte) (v >>> 16);
			buf[off + 6] = (byte) (v >>> 8);
			buf[off + 7] = (byte) v;
		}
		out.write(buf, 0, needed);
	}

	/**
	 * Send dependency data as strings (protocol v1 — fallback for agent mode).
	 */
	public static boolean send(int port, Map<String, Set<String>> classDeps, Map<String, Set<String>> methodDeps,
			Map<String, Set<String>> memberDeps, Map<String, Set<String>> methodMemberDeps) {
		int maxRetries = 2;
		for (int attempt = 0; attempt <= maxRetries; attempt++) {
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
				socket.setSoTimeout(10000);
				try (DataOutputStream out = new DataOutputStream(
						new BufferedOutputStream(socket.getOutputStream(), 65536));
						DataInputStream in = new DataInputStream(socket.getInputStream())) {
					out.writeInt(MAGIC);
					out.writeByte(PROTOCOL_VERSION_V1);
					writeMap(out, classDeps);
					writeMap(out, methodDeps);
					writeMap(out, memberDeps);
					writeMap(out, methodMemberDeps);
					out.flush();
					int ack = in.read();
					if (ack == 1) {
						return true;
					}
					AgentLogger.log("[IndexCollectorClient] Server NACK'd, attempt " + (attempt + 1));
				}
			} catch (IOException e) {
				if (attempt < maxRetries) {
					AgentLogger.log("[IndexCollectorClient] Attempt " + (attempt + 1) + " failed: " + e.getMessage()
							+ ", retrying...");
					try {
						Thread.sleep(100L * (attempt + 1));
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						break;
					}
				} else {
					AgentLogger.log("[IndexCollectorClient] Failed to send deps after " + (maxRetries + 1)
							+ " attempts: " + e.getMessage());
				}
			}
		}
		return false;
	}

	private static void writeMap(DataOutputStream out, Map<String, Set<String>> map) throws IOException {
		if (map == null) {
			out.writeInt(0);
			return;
		}
		out.writeInt(map.size());
		for (var entry : map.entrySet()) {
			writeString(out, entry.getKey());
			Set<String> values = entry.getValue();
			out.writeInt(values.size());
			for (String value : values) {
				writeString(out, value);
			}
		}
	}

	private static void writeString(DataOutputStream out, String s) throws IOException {
		byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
		out.writeShort(bytes.length);
		out.write(bytes);
	}
}
