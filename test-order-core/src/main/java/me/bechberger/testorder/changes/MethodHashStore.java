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
 * Compressed hash table mapping {@code className#methodName} to SHA-256 hashes.
 * Used for detecting which individual test methods have changed between runs.
 * <p>
 * Format: LZ4-compressed lines of {@code className#methodName\thexHash}. Lines
 * prefixed with {@code #FILE:} store per-file content fingerprints for
 * incremental scanning.
 */
public class MethodHashStore {

	private final Map<String, String> hashes; // className#methodName → hex SHA-256
	private final Map<String, String> fileFingerprints; // relative path → raw content SHA-256

	public MethodHashStore(Map<String, String> hashes) {
		this.hashes = new TreeMap<>(hashes);
		this.fileFingerprints = new TreeMap<>();
	}

	private MethodHashStore(Map<String, String> hashes, Map<String, String> fileFingerprints) {
		this.hashes = new TreeMap<>(hashes);
		this.fileFingerprints = new TreeMap<>(fileFingerprints);
	}

	public MethodHashStore() {
		this.hashes = new TreeMap<>();
		this.fileFingerprints = new TreeMap<>();
	}

	private static final HexFormat HEX_FORMAT = HexFormat.of();
	private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	});

	private static String rawSha256(byte[] content) {
		MessageDigest md = SHA256.get();
		md.reset();
		md.update(content);
		return HEX_FORMAT.formatHex(md.digest());
	}

	/**
	 * Scans a test source root directory and computes per-method hashes for all
	 * Java/Kotlin source files.
	 */
	public static MethodHashStore scan(Path testSourceRoot) throws IOException {
		Map<String, String> hashes = new TreeMap<>();
		Map<String, String> fingerprints = new TreeMap<>();
		if (!Files.isDirectory(testSourceRoot)) {
			return new MethodHashStore(hashes);
		}
		List<Path> files;
		try (Stream<Path> walk = Files.walk(testSourceRoot)) {
			files = walk.filter(p -> Files.isRegularFile(p) && SourceFileModel.isSourceFile(p.toString())).toList();
		}
		// Parse files in parallel for I/O and CPU overlap
		record FileResult(String relPath, String fingerprint, Map<String, String> methods) {
		}
		List<FileResult> results = files.parallelStream().map(file -> {
			try {
				byte[] raw = Files.readAllBytes(file);
				String fp = rawSha256(raw);
				String source = new String(raw, StandardCharsets.UTF_8);
				String pkg = SourceFileModel.extractPackageName(source);
				Map<String, String> methods = SourceFileModel.parse(source, pkg, SourceFileModel.Detail.METHODS)
						.methodHashes();
				String relPath = testSourceRoot.relativize(file).toString().replace('\\', '/');
				return new FileResult(relPath, fp, methods);
			} catch (Exception e) {
				return null;
			}
		}).filter(Objects::nonNull).toList();
		for (FileResult r : results) {
			fingerprints.put(r.relPath(), r.fingerprint());
			hashes.putAll(r.methods());
		}
		return new MethodHashStore(hashes, fingerprints);
	}

	/**
	 * Incrementally scans a test source root, re-parsing only files whose content
	 * has changed since the previous snapshot. For unchanged files, method hashes
	 * are carried over from the previous store.
	 */
	public static MethodHashStore scanIncremental(Path testSourceRoot, MethodHashStore previous) throws IOException {
		if (previous.fileFingerprints.isEmpty()) {
			// No fingerprints in previous store — fall back to full scan
			return scan(testSourceRoot);
		}
		Map<String, String> hashes = new TreeMap<>();
		Map<String, String> fingerprints = new TreeMap<>();
		if (!Files.isDirectory(testSourceRoot)) {
			return new MethodHashStore(hashes);
		}
		List<Path> files;
		try (Stream<Path> walk = Files.walk(testSourceRoot)) {
			files = walk.filter(p -> Files.isRegularFile(p) && SourceFileModel.isSourceFile(p.toString())).toList();
		}

		// Build a map of relative path → file for current files
		Map<String, Path> currentFiles = new TreeMap<>();
		for (Path file : files) {
			String relPath = testSourceRoot.relativize(file).toString().replace('\\', '/');
			currentFiles.put(relPath, file);
		}

		// Build a reverse index: className prefix → relative path (from previous)
		// Method keys are className#methodName; we need to know which file owns each
		// class
		Map<String, String> classToFile = new HashMap<>();
		for (String relPath : previous.fileFingerprints.keySet()) {
			// Convert path to class name: com/example/FooTest.java → com.example.FooTest
			String className = relPath.replaceFirst("\\.(java|kt)$", "").replace('/', '.');
			classToFile.put(className, relPath);
		}

		// Separate files into changed and unchanged (parallel fingerprinting)
		record FileCheck(String relPath, Path file, String fingerprint, boolean changed) {
		}
		List<FileCheck> checks = currentFiles.entrySet().parallelStream().map(entry -> {
			try {
				byte[] raw = Files.readAllBytes(entry.getValue());
				String fp = rawSha256(raw);
				String prevFp = previous.fileFingerprints.get(entry.getKey());
				boolean changed = prevFp == null || !prevFp.equals(fp);
				return new FileCheck(entry.getKey(), entry.getValue(), fp, changed);
			} catch (IOException e) {
				return new FileCheck(entry.getKey(), entry.getValue(), "", true);
			}
		}).toList();

		// Process unchanged files: carry over method hashes
		Set<String> unchangedFiles = new HashSet<>();
		for (FileCheck check : checks) {
			fingerprints.put(check.relPath(), check.fingerprint());
			if (!check.changed()) {
				unchangedFiles.add(check.relPath());
			}
		}

		// Carry over method hashes for unchanged files
		for (var entry : previous.hashes.entrySet()) {
			String methodKey = entry.getKey();
			int hash = methodKey.indexOf('#');
			if (hash > 0) {
				String className = methodKey.substring(0, hash);
				// Strip inner class suffix ($Inner) to find the owning source file
				String topLevelClass = className.contains("$")
						? className.substring(0, className.indexOf('$'))
						: className;
				String owningFile = classToFile.get(topLevelClass);
				if (owningFile != null && unchangedFiles.contains(owningFile)) {
					hashes.put(methodKey, entry.getValue());
				}
			}
		}

		// Re-parse changed files in parallel
		List<FileCheck> changedFiles = checks.stream().filter(FileCheck::changed).toList();
		if (!changedFiles.isEmpty()) {
			Map<String, String> newMethods = changedFiles.parallelStream().flatMap(check -> {
				try {
					String source = Files.readString(check.file());
					String pkg = SourceFileModel.extractPackageName(source);
					return SourceFileModel.parse(source, pkg, SourceFileModel.Detail.METHODS).methodHashes().entrySet()
							.stream();
				} catch (Exception e) {
					return java.util.stream.Stream.empty();
				}
			}).collect(
					java.util.stream.Collectors.toConcurrentMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b));
			hashes.putAll(newMethods);
		}

		return new MethodHashStore(hashes, fingerprints);
	}

	/** Saves the hash store as an LZ4-compressed text file. */
	public void save(Path hashFile) throws IOException {
		Path parent = hashFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tempFile = PersistenceSupport.temporarySibling(hashFile);
		try (LZ4FrameOutputStream lz4os = LZ4Support.frameOutputStreamHC(Files.newOutputStream(tempFile));
				PrintWriter pw = new PrintWriter(new OutputStreamWriter(lz4os))) {
			// Write file fingerprints first (prefixed with #FILE:)
			for (var entry : fileFingerprints.entrySet()) {
				pw.print("#FILE:");
				pw.print(entry.getKey());
				pw.print('\t');
				pw.println(entry.getValue());
			}
			for (var entry : hashes.entrySet()) {
				pw.print(entry.getKey());
				pw.print('\t');
				pw.println(entry.getValue());
			}
		}
		PersistenceSupport.moveIntoPlace(tempFile, hashFile);
	}

	/** Loads a previously saved method hash store. */
	public static MethodHashStore load(Path hashFile) throws IOException {
		Map<String, String> hashes = new TreeMap<>();
		Map<String, String> fingerprints = new TreeMap<>();
		Path loadPath = PersistenceSupport.resolveLoadPath(hashFile);
		try (LZ4FrameInputStream lz4is = LZ4Support.frameInputStream(Files.newInputStream(loadPath));
				BufferedReader br = new BufferedReader(new InputStreamReader(lz4is))) {
			String line;
			while ((line = br.readLine()) != null) {
				if (line.startsWith("#FILE:")) {
					int tab = line.indexOf('\t', 6);
					if (tab > 6) {
						fingerprints.put(line.substring(6, tab), line.substring(tab + 1));
					}
				} else {
					int tab = line.indexOf('\t');
					if (tab > 0) {
						hashes.put(line.substring(0, tab), line.substring(tab + 1));
					}
				}
			}
		}
		return new MethodHashStore(hashes, fingerprints);
	}

	/**
	 * Returns method keys ({@code className#methodName}) that have changed, been
	 * added, or been deleted compared to a previous snapshot.
	 */
	public Set<String> getChangedMethods(MethodHashStore previous) {
		Set<String> changed = new TreeSet<>();
		for (var entry : hashes.entrySet()) {
			String prevHash = previous.hashes.get(entry.getKey());
			if (prevHash == null || !prevHash.equals(entry.getValue())) {
				changed.add(entry.getKey());
			}
		}
		for (String prevKey : previous.hashes.keySet()) {
			if (!hashes.containsKey(prevKey)) {
				changed.add(prevKey);
			}
		}
		return changed;
	}

	public Map<String, String> getHashes() {
		return Collections.unmodifiableMap(hashes);
	}

	public boolean containsMethod(String methodKey) {
		return hashes.containsKey(methodKey);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof MethodHashStore other))
			return false;
		return hashes.equals(other.hashes);
	}

	@Override
	public int hashCode() {
		return hashes.hashCode();
	}
}
