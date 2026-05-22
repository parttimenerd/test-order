package me.bechberger.testorder.changes;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

import me.bechberger.testorder.LZ4Support;
import me.bechberger.testorder.PersistenceSupport;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

/**
 * Compressed hash table mapping source file relative paths to their SHA-256
 * hashes. Used for "since-last-run" change detection by comparing file
 * snapshots.
 */
public class FileHashStore {

	private final Map<String, String> hashes; // relative path → hex SHA-256

	public FileHashStore(Map<String, String> hashes) {
		this.hashes = new HashMap<>(hashes);
	}

	public FileHashStore() {
		this.hashes = new HashMap<>();
	}

	/**
	 * Scans a source root directory for {@code *.java} and {@code *.kt} files and
	 * computes SHA-256 hashes.
	 */
	public static FileHashStore scan(Path sourceRoot) throws IOException {
		Map<String, String> hashes = new TreeMap<>();
		if (!Files.isDirectory(sourceRoot)) {
			return new FileHashStore(hashes);
		}
		List<Path> files;
		try (Stream<Path> walk = Files.walk(sourceRoot)) {
			files = walk.filter(p -> Files.isRegularFile(p) && SourceFileModel.isSourceFile(p.toString())).toList();
		}
		// Hash files in parallel for I/O overlap
		Map<String, String> collected = files.parallelStream()
				.collect(java.util.stream.Collectors.toConcurrentMap(
						file -> sourceRoot.relativize(file).toString().replace('\\', '/'), FileHashStore::sha256Safe,
						(a, b) -> b));
		hashes = new HashMap<>();
		hashes.putAll(collected);
		return new FileHashStore(hashes);
	}

	private static String sha256Safe(Path file) {
		try {
			return sha256(file);
		} catch (IOException e) {
			return "";
		}
	}

	/**
	 * Saves the hash store as an LZ4-compressed text file.
	 */
	public void save(Path hashFile) throws IOException {
		Path parent = hashFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tempFile = PersistenceSupport.temporarySibling(hashFile);
		try (LZ4FrameOutputStream lz4os = LZ4Support.frameOutputStreamHC(Files.newOutputStream(tempFile));
				PrintWriter pw = new PrintWriter(new OutputStreamWriter(lz4os))) {
			// Sort entries for deterministic output
			hashes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
				pw.print(entry.getKey());
				pw.print('\t');
				pw.println(entry.getValue());
			});
		}
		PersistenceSupport.moveIntoPlace(tempFile, hashFile);
	}

	/**
	 * Loads a previously saved hash store.
	 */
	public static FileHashStore load(Path hashFile) throws IOException {
		Map<String, String> hashes = new HashMap<>();
		Path loadPath = PersistenceSupport.resolveLoadPath(hashFile);
		try (LZ4FrameInputStream lz4is = LZ4Support.frameInputStream(Files.newInputStream(loadPath));
				BufferedReader br = new BufferedReader(new InputStreamReader(lz4is))) {
			String line;
			while ((line = br.readLine()) != null) {
				int tab = line.indexOf('\t');
				if (tab > 0) {
					String key = line.substring(0, tab).replace('\\', '/');
					hashes.put(key, line.substring(tab + 1));
				}
			}
		}
		return new FileHashStore(hashes);
	}

	/**
	 * Returns relative paths of files that have changed, been added, or been
	 * deleted compared to a previous snapshot.
	 */
	public Set<String> getChangedFiles(FileHashStore previous) {
		Set<String> changed = new HashSet<>();
		// files changed or added
		for (var entry : hashes.entrySet()) {
			String prevHash = previous.hashes.get(entry.getKey());
			if (prevHash == null || !prevHash.equals(entry.getValue())) {
				changed.add(entry.getKey());
			}
		}
		// files deleted
		for (String prevPath : previous.hashes.keySet()) {
			if (!hashes.containsKey(prevPath)) {
				changed.add(prevPath);
			}
		}
		return changed;
	}

	private static final HexFormat HEX_FORMAT = HexFormat.of();
	private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	});

	private static String sha256(Path file) throws IOException {
		// Normalize source content so that comment-only and whitespace-only changes
		// do not produce different hashes. String literal changes are still detected.
		String source = Files.readString(file, StandardCharsets.UTF_8);
		String normalized = SourceFileModel.normalizeForHashing(source);
		MessageDigest md = SHA256.get();
		md.reset();

		// Use CharsetEncoder to avoid creating intermediate byte array from getBytes()
		java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(Math.min(normalized.length() * 4, 65536));
		java.nio.charset.CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
		java.nio.CharBuffer charBuf = java.nio.CharBuffer.wrap(normalized);
		encoder.reset();
		encoder.encode(charBuf, buffer, true);
		encoder.flush(buffer);
		buffer.flip();
		md.update(buffer);

		return HEX_FORMAT.formatHex(md.digest());
	}

	public Map<String, String> getHashes() {
		return Collections.unmodifiableMap(hashes);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof FileHashStore other))
			return false;
		return hashes.equals(other.hashes);
	}

	@Override
	public int hashCode() {
		return hashes.hashCode();
	}
}
