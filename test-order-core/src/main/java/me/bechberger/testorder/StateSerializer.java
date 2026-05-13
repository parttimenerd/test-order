package me.bechberger.testorder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.logging.Logger;

import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.PrettyPrinter;

final class StateSerializer {

	private static final Logger LOG = Logger.getLogger(StateSerializer.class.getName());

	private StateSerializer() {
	}

	static void save(Path file, TestOrderState state) throws IOException {
		PersistenceSupport.withFileLock(file, () -> {
			Path parent = file.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Path tempFile = PersistenceSupport.temporarySibling(file);
			try (var writer = new java.io.OutputStreamWriter(LZ4Support.blockOutputStream(
					Files.newOutputStream(tempFile), 1 << 16, LZ4Support.highCompressor(17)), StandardCharsets.UTF_8)) {
				writer.write(PrettyPrinter.compactPrint(state.toPersistedRoot()));
			}
			PersistenceSupport.moveIntoPlace(tempFile, file);
			state.afterSave();
			return null;
		});
	}

	static TestOrderState load(Path file) throws IOException {
		return PersistenceSupport.withFileLock(file, () -> loadUnlocked(file));
	}

	private static TestOrderState loadUnlocked(Path file) throws IOException {
		Path loadPath = PersistenceSupport.resolveLoadPath(file);
		if (!Files.exists(loadPath)) {
			return new TestOrderState();
		}
		byte[] raw = readRaw(file, loadPath);
		if (raw.length == 0) {
			return new TestOrderState();
		}
		String json = decode(raw);
		if (json.isEmpty()) {
			return new TestOrderState();
		}
		try {
			return TestOrderState.fromPersistedRoot(TestOrderState.safeMap(JSONParser.parse(json), "root"));
		} catch (StateDowngradeException downgrade) {
			// R10-4: Create a timestamped backup so the user can recover by upgrading back
			Path backup = createTimestampedBackup(loadPath);
			LOG.warning("State file was written by a newer plugin version. " + "Created backup at " + backup
					+ ". Starting fresh. " + "To recover, upgrade the plugin or restore the backup.");
			return new TestOrderState();
		} catch (IOException | RuntimeException primaryFailure) {
			Path tempFile = PersistenceSupport.temporarySibling(file);
			if (!loadPath.equals(tempFile) && Files.exists(tempFile)) {
				try {
					return TestOrderState.fromPersistedRoot(
							TestOrderState.safeMap(JSONParser.parse(decode(Files.readAllBytes(tempFile))), "root"));
				} catch (StateDowngradeException tempDowngrade) {
					Path backup = createTimestampedBackup(tempFile);
					LOG.warning(
							"Temp state file was also from a newer version. Backup at " + backup + ". Starting fresh.");
					return new TestOrderState();
				} catch (IOException | RuntimeException tempFailure) {
					tempFailure.addSuppressed(primaryFailure);
					if (tempFailure instanceof IOException ioe)
						throw ioe;
					throw new IOException(
							"Failed to load state from both primary and temp: " + tempFailure.getMessage(),
							tempFailure);
				}
			}
			if (primaryFailure instanceof IOException ioe)
				throw ioe;
			throw new IOException("Failed to load state: " + primaryFailure.getMessage(), primaryFailure);
		}
	}

	private static byte[] readRaw(Path file, Path loadPath) throws IOException {
		try {
			return Files.readAllBytes(loadPath);
		} catch (IOException primaryFailure) {
			Path tempFile = PersistenceSupport.temporarySibling(file);
			if (!loadPath.equals(tempFile) && Files.exists(tempFile)) {
				return Files.readAllBytes(tempFile);
			}
			throw primaryFailure;
		}
	}

	private static String decode(byte[] raw) throws IOException {
		if (raw.length == 0) {
			return "";
		}
		if (raw[0] == '{' || raw[0] == ' ' || raw[0] == '\n' || raw[0] == '\r' || raw[0] == '\t') {
			return new String(raw, StandardCharsets.UTF_8).strip();
		}
		try (var reader = new java.io.InputStreamReader(LZ4Support.blockInputStream(new ByteArrayInputStream(raw)),
				StandardCharsets.UTF_8)) {
			StringBuilder sb = new StringBuilder(raw.length * 3);
			char[] buf = new char[8192];
			int n;
			while ((n = reader.read(buf)) >= 0) {
				sb.append(buf, 0, n);
			}
			return sb.toString().strip();
		}
	}

	/**
	 * Creates a timestamped backup of the state file so users can recover after a
	 * plugin downgrade (R10-4).
	 */
	private static Path createTimestampedBackup(Path stateFile) throws IOException {
		String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
		String fileName = stateFile.getFileName().toString();
		Path backup = stateFile.resolveSibling(fileName + ".backup-" + timestamp);
		Files.copy(stateFile, backup, StandardCopyOption.REPLACE_EXISTING);
		return backup;
	}
}
