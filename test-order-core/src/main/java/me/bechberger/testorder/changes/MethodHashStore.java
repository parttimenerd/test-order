package me.bechberger.testorder.changes;

import java.io.*;
import java.nio.file.*;
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
 * Format: LZ4-compressed lines of {@code className#methodName\thexHash}.
 */
public class MethodHashStore {

	private final Map<String, String> hashes; // className#methodName → hex SHA-256

	public MethodHashStore(Map<String, String> hashes) {
		this.hashes = new TreeMap<>(hashes);
	}

	public MethodHashStore() {
		this.hashes = new TreeMap<>();
	}

	/**
	 * Scans a test source root directory and computes per-method hashes for all
	 * Java/Kotlin source files.
	 */
	public static MethodHashStore scan(Path testSourceRoot) throws IOException {
		Map<String, String> hashes = new TreeMap<>();
		if (!Files.isDirectory(testSourceRoot)) {
			return new MethodHashStore(hashes);
		}
		try (Stream<Path> walk = Files.walk(testSourceRoot)) {
			for (Path file : walk.filter(p -> Files.isRegularFile(p) && SourceFileModel.isSourceFile(p.toString()))
					.toList()) {
				try {
					String source = Files.readString(file);
					String pkg = SourceFileModel.extractPackageName(source);
					hashes.putAll(SourceFileModel.parse(source, pkg, SourceFileModel.Detail.METHODS).methodHashes());
				} catch (Exception e) {
					// best-effort: skip files that can't be parsed
				}
			}
		}
		return new MethodHashStore(hashes);
	}

	/** Saves the hash store as an LZ4-compressed text file. */
	public void save(Path hashFile) throws IOException {
		Path parent = hashFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tempFile = PersistenceSupport.temporarySibling(hashFile);
		try (LZ4FrameOutputStream lz4os = LZ4Support.frameOutputStream(Files.newOutputStream(tempFile));
				PrintWriter pw = new PrintWriter(new OutputStreamWriter(lz4os))) {
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
		Path loadPath = PersistenceSupport.resolveLoadPath(hashFile);
		try (LZ4FrameInputStream lz4is = LZ4Support.frameInputStream(Files.newInputStream(loadPath));
				BufferedReader br = new BufferedReader(new InputStreamReader(lz4is))) {
			String line;
			while ((line = br.readLine()) != null) {
				int tab = line.indexOf('\t');
				if (tab > 0) {
					hashes.put(line.substring(0, tab), line.substring(tab + 1));
				}
			}
		}
		return new MethodHashStore(hashes);
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
