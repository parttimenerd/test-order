package me.bechberger.testorder.changes;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

/**
 * Compressed hash table mapping source file relative paths to their SHA-256 hashes.
 * Used for "since-last-run" change detection by comparing file snapshots.
 */
public class FileHashStore {

    private final Map<String, String> hashes; // relative path → hex SHA-256

    public FileHashStore(Map<String, String> hashes) {
        this.hashes = new TreeMap<>(hashes);
    }

    public FileHashStore() {
        this.hashes = new TreeMap<>();
    }

    /**
     * Scans a source root directory for {@code *.java} and {@code *.kt} files and computes SHA-256 hashes.
     */
    public static FileHashStore scan(Path sourceRoot) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        if (!Files.isDirectory(sourceRoot)) {
            return new FileHashStore(hashes);
        }
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            for (Path file : walk.filter(p -> Files.isRegularFile(p) && SourceFileModel.isSourceFile(p.toString())).toList()) {
                String relativePath = sourceRoot.relativize(file).toString();
                String hash = sha256(file);
                hashes.put(relativePath, hash);
            }
        }
        return new FileHashStore(hashes);
    }

    /**
     * Saves the hash store as an LZ4-compressed text file.
     */
    public void save(Path hashFile) throws IOException {
        Path parent = hashFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (LZ4FrameOutputStream lz4os = new LZ4FrameOutputStream(Files.newOutputStream(hashFile));
             PrintWriter pw = new PrintWriter(new OutputStreamWriter(lz4os))) {
            for (var entry : hashes.entrySet()) {
                pw.print(entry.getKey());
                pw.print('\t');
                pw.println(entry.getValue());
            }
        }
    }

    /**
     * Loads a previously saved hash store.
     */
    public static FileHashStore load(Path hashFile) throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        try (LZ4FrameInputStream lz4is = new LZ4FrameInputStream(Files.newInputStream(hashFile));
             BufferedReader br = new BufferedReader(new InputStreamReader(lz4is))) {
            String line;
            while ((line = br.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    hashes.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        return new FileHashStore(hashes);
    }

    /**
     * Returns relative paths of files that have changed, been added, or been deleted
     * compared to a previous snapshot.
     */
    public Set<String> getChangedFiles(FileHashStore previous) {
        Set<String> changed = new TreeSet<>();
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
        MessageDigest md = SHA256.get();
        md.reset();
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                md.update(buf, 0, n);
            }
        }
        return HEX_FORMAT.formatHex(md.digest());
    }

    public Map<String, String> getHashes() {
        return Collections.unmodifiableMap(hashes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileHashStore other)) return false;
        return hashes.equals(other.hashes);
    }

    @Override
    public int hashCode() {
        return hashes.hashCode();
    }
}