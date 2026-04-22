package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared helper for writing LZ4 hash snapshots from source directories.
 */
public final class HashSnapshotSupport {

	private HashSnapshotSupport() {
	}

	/**
	 * Scans {@code root} and writes a hash snapshot to {@code hashFile}.
	 *
	 * @return true when a snapshot was written, false when {@code root} is not a
	 *         directory
	 */
	public static boolean snapshotDirectory(Path root, Path hashFile) throws IOException {
		if (!Files.isDirectory(root)) {
			return false;
		}
		FileHashStore store = FileHashStore.scan(root);
		store.save(hashFile);
		return true;
	}
}
