package me.bechberger.testorder.ml;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import me.bechberger.testorder.LZ4Support;
import me.bechberger.testorder.PersistenceSupport;
import me.bechberger.testorder.TestOrderLogger;

/**
 * Persistence for ML training history in LZ4-compressed binary format.
 * <p>
 * File layout: {@code [MAGIC 4B][VERSION 2B][runCount 4B][run...]}
 * <p>
 * Each run:
 * {@code [timestamp 8B][changedCount 4B][changedClasses...][changedTestCount 4B]
 * [changedTestClasses...][totalTests 4B][totalFailures 4B][outcomeCount 4B][outcomes...]}
 * <p>
 * Each outcome:
 * {@code [testClass UTF][failed 1B][durationMs 8B][hasFailureType 1B][failureType UTF?]}
 */
public final class MLHistoryPersistence {

	private static final byte[] FORMAT_MAGIC = { 'T', 'O', 'M', 'L' }; // Test Order ML
	private static final short FORMAT_VERSION = 1;

	private MLHistoryPersistence() {
	}

	/**
	 * Loads all ML run records from the history file.
	 *
	 * @param historyFile
	 *            path to the LZ4-compressed history file
	 * @return list of run records (empty if file does not exist)
	 */
	public static List<MLRunRecord> load(Path historyFile) throws IOException {
		if (!Files.exists(historyFile)) {
			return Collections.emptyList();
		}
		try (InputStream fis = Files.newInputStream(historyFile);
				InputStream lz4 = LZ4Support.frameInputStream(fis);
				DataInputStream in = new DataInputStream(lz4)) {
			byte[] magic = new byte[4];
			in.readFully(magic);
			if (magic[0] != FORMAT_MAGIC[0] || magic[1] != FORMAT_MAGIC[1] || magic[2] != FORMAT_MAGIC[2]
					|| magic[3] != FORMAT_MAGIC[3]) {
				throw new IOException("Invalid ML history magic: expected TOML");
			}
			short version = in.readShort();
			if (version != FORMAT_VERSION) {
				throw new IOException("Unsupported ML history version: " + version);
			}
			int runCount = in.readInt();
			List<MLRunRecord> runs = new ArrayList<>(runCount);
			for (int r = 0; r < runCount; r++) {
				runs.add(readRun(in));
			}
			return runs;
		}
	}

	/**
	 * Saves ML run records to the history file, atomically replacing the old file.
	 * Prunes to {@code maxRuns} if the list exceeds that size (keeps most recent).
	 *
	 * @param historyFile
	 *            path to the LZ4-compressed history file
	 * @param runs
	 *            all run records to persist
	 * @param maxRuns
	 *            maximum number of runs to keep (0 = unlimited)
	 */
	public static void save(Path historyFile, List<MLRunRecord> runs, int maxRuns) throws IOException {
		List<MLRunRecord> toWrite = runs;
		if (maxRuns > 0 && runs.size() > maxRuns) {
			toWrite = runs.subList(runs.size() - maxRuns, runs.size());
		}
		Files.createDirectories(historyFile.getParent());
		Path tempFile = historyFile.resolveSibling(historyFile.getFileName() + ".tmp");
		try (OutputStream fos = Files.newOutputStream(tempFile);
				OutputStream lz4 = LZ4Support.frameOutputStream(fos);
				DataOutputStream out = new DataOutputStream(lz4)) {
			out.write(FORMAT_MAGIC);
			out.writeShort(FORMAT_VERSION);
			out.writeInt(toWrite.size());
			for (MLRunRecord run : toWrite) {
				writeRun(out, run);
			}
		}
		Files.move(tempFile, historyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/**
	 * Appends a single run record to the existing history, pruning to maxRuns. Uses
	 * file locking to prevent concurrent Surefire forks from overwriting each
	 * other's data (TOCTOU race between load and save).
	 */
	public static void append(Path historyFile, MLRunRecord record, int maxRuns) throws IOException {
		Files.createDirectories(historyFile.getParent());
		PersistenceSupport.withFileLock(historyFile, () -> {
			List<MLRunRecord> existing;
			try {
				existing = new ArrayList<>(load(historyFile));
			} catch (IOException e) {
				TestOrderLogger.warn("[ml] Could not load ML history ({}), starting fresh: {}", historyFile,
						e.getMessage());
				existing = new ArrayList<>();
			}
			existing.add(record);
			save(historyFile, existing, maxRuns);
			return null;
		});
	}

	private static MLRunRecord readRun(DataInputStream in) throws IOException {
		long timestamp = in.readLong();
		int changedCount = in.readInt();
		List<String> changed = new ArrayList<>(changedCount);
		for (int i = 0; i < changedCount; i++) {
			changed.add(in.readUTF());
		}
		int changedTestCount = in.readInt();
		List<String> changedTests = new ArrayList<>(changedTestCount);
		for (int i = 0; i < changedTestCount; i++) {
			changedTests.add(in.readUTF());
		}
		int totalTests = in.readInt();
		int totalFailures = in.readInt();
		int outcomeCount = in.readInt();
		List<MLTestOutcome> outcomes = new ArrayList<>(outcomeCount);
		for (int i = 0; i < outcomeCount; i++) {
			outcomes.add(readOutcome(in));
		}
		return new MLRunRecord(timestamp, changed, changedTests, totalTests, totalFailures, outcomes);
	}

	private static void writeRun(DataOutputStream out, MLRunRecord run) throws IOException {
		out.writeLong(run.timestamp());
		out.writeInt(run.changedClasses().size());
		for (String s : run.changedClasses()) {
			out.writeUTF(s);
		}
		out.writeInt(run.changedTestClasses().size());
		for (String s : run.changedTestClasses()) {
			out.writeUTF(s);
		}
		out.writeInt(run.totalTests());
		out.writeInt(run.totalFailures());
		out.writeInt(run.outcomes().size());
		for (MLTestOutcome outcome : run.outcomes()) {
			writeOutcome(out, outcome);
		}
	}

	private static MLTestOutcome readOutcome(DataInputStream in) throws IOException {
		String testClass = in.readUTF();
		boolean failed = in.readBoolean();
		long durationMs = in.readLong();
		boolean hasFailureType = in.readBoolean();
		String failureType = hasFailureType ? in.readUTF() : null;
		return new MLTestOutcome(testClass, failed, durationMs, failureType);
	}

	private static void writeOutcome(DataOutputStream out, MLTestOutcome outcome) throws IOException {
		out.writeUTF(outcome.testClass());
		out.writeBoolean(outcome.failed());
		out.writeLong(outcome.durationMs());
		out.writeBoolean(outcome.failureType() != null);
		if (outcome.failureType() != null) {
			out.writeUTF(outcome.failureType());
		}
	}
}
