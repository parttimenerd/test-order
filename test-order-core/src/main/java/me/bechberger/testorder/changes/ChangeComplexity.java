package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.Deflater;

/**
 * Computes a normalised "change complexity" score for each changed class using
 * Deflate-compressed file size as a proxy for information content.
 * <p>
 * Larger compressed sizes indicate more complex / information-dense source
 * files, which are more likely to harbour subtle bugs after modification. The
 * scores are normalised to [0.0, 1.0] relative to the largest changed file.
 * <p>
 * This is intentionally simple — a Kolmogorov-complexity-like heuristic that
 * requires no external tools and adds negligible overhead.
 */
public class ChangeComplexity {

	private ChangeComplexity() {
	}

	/**
	 * Computes normalized complexity scores for the given changed classes.
	 *
	 * @param changedClasses
	 *            FQCNs of changed application classes
	 * @param sourceRoots
	 *            source root directories to search for source files
	 * @return FQCN → normalised complexity (0.0–1.0); classes whose source cannot
	 *         be found are omitted
	 */
	public static Map<String, Double> compute(Set<String> changedClasses, List<Path> sourceRoots) {
		return compute(changedClasses, sourceRoots, null);
	}

	/**
	 * Computes normalized complexity scores, optionally refined by structural
	 * change data.
	 * <p>
	 * When {@code changedMembers} is provided, the score blends compressed file
	 * size (information density) with the number of changed members in each class
	 * (change breadth). This avoids over-scoring a 1-line change in a huge file,
	 * and under-scoring a multi-method rewrite in a small file.
	 *
	 * @param changedClasses
	 *            FQCNs of changed application classes
	 * @param sourceRoots
	 *            source root directories to search for source files
	 * @param changedMembers
	 *            structural analysis result (may be null for backward compat)
	 * @return FQCN → normalised complexity (0.0–1.0)
	 */
	public static Map<String, Double> compute(Set<String> changedClasses, List<Path> sourceRoots,
			StructuralChangeAnalyzer.ChangedMembers changedMembers) {
		if (changedClasses.isEmpty() || sourceRoots.isEmpty()) {
			return Map.of();
		}

		Map<String, Integer> rawSizes = new LinkedHashMap<>();
		int maxSize = 0;

		for (String fqcn : changedClasses) {
			Path sourceFile = findSourceFile(fqcn, sourceRoots);
			if (sourceFile == null)
				continue;

			try {
				byte[] content = Files.readAllBytes(sourceFile);
				int compressed = deflateSize(content);
				rawSizes.put(fqcn, compressed);
				if (compressed > maxSize)
					maxSize = compressed;
			} catch (IOException ignored) {
				// skip files we can't read
			}
		}

		if (rawSizes.isEmpty() || maxSize == 0) {
			return Map.of();
		}

		// Base score: file-level compressed size
		Map<String, Double> result = new LinkedHashMap<>();
		for (var entry : rawSizes.entrySet()) {
			result.put(entry.getKey(), (double) entry.getValue() / maxSize);
		}

		// Refine with structural member count when available
		if (changedMembers != null && !changedMembers.membersByClass().isEmpty()) {
			int maxMembers = 0;
			for (String fqcn : result.keySet()) {
				Set<String> members = changedMembers.membersByClass().get(fqcn);
				if (members != null && members.size() > maxMembers) {
					maxMembers = members.size();
				}
			}
			if (maxMembers > 0) {
				for (var entry : result.entrySet()) {
					Set<String> members = changedMembers.membersByClass().get(entry.getKey());
					double memberRatio = (members != null) ? (double) members.size() / maxMembers : 0.0;
					// Blend: 50% file complexity + 50% member change breadth
					entry.setValue((entry.getValue() + memberRatio) / 2.0);
				}
			}
		}
		return result;
	}

	/**
	 * Computes normalized complexity scores from structural diff body changes,
	 * using Deflate-compressed diff text as a proxy for information complexity.
	 * <p>
	 * For each changed class, collects the LCS-based diff text (insertions +
	 * deletions) across all its modified/added/removed members, compresses it with
	 * Deflate, and uses the compressed size as the raw complexity value. More
	 * complex, information-dense diffs compress less well and thus score higher.
	 * <p>
	 * Normalised to [0.0, 1.0] relative to the most-complex changed class.
	 *
	 * @param diffs
	 *            the structural diffs (must include body changes)
	 * @return FQCN → normalised compressed diff complexity (0.0–1.0)
	 */
	public static Map<String, Double> computeFromDiffs(List<StructuralDiff.FileDiff> diffs) {
		if (diffs == null || diffs.isEmpty())
			return Map.of();

		// Accumulate diff text per class
		Map<String, StringBuilder> diffTextPerClass = new LinkedHashMap<>();
		for (StructuralDiff.FileDiff diff : diffs) {
			for (StructuralDiff.BodyChange bc : diff.bodyChanges()) {
				String diffText = LineDiff.diffText(bc.oldBody(), bc.newBody());
				if (!diffText.isEmpty()) {
					diffTextPerClass.computeIfAbsent(bc.fqcn(), k -> new StringBuilder()).append(diffText).append("\n");
				}
			}
		}

		if (diffTextPerClass.isEmpty())
			return Map.of();

		// Compress each class's diff text
		Map<String, Integer> compressedSizes = new LinkedHashMap<>();
		int maxSize = 0;
		for (var entry : diffTextPerClass.entrySet()) {
			byte[] diffBytes = entry.getValue().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
			int compressed = deflateSize(diffBytes);
			compressedSizes.put(entry.getKey(), compressed);
			if (compressed > maxSize)
				maxSize = compressed;
		}

		if (maxSize == 0)
			return Map.of();

		Map<String, Double> result = new LinkedHashMap<>();
		for (var entry : compressedSizes.entrySet()) {
			result.put(entry.getKey(), (double) entry.getValue() / maxSize);
		}
		return result;
	}

	/**
	 * Computes normalized complexity scores, preferring diff-based computation when
	 * structural diffs with body changes are available, falling back to file-level
	 * compressed size.
	 *
	 * @param changedClasses
	 *            FQCNs of changed application classes
	 * @param sourceRoots
	 *            source root directories to search for source files
	 * @param changedMembers
	 *            structural analysis result (may be null)
	 * @param diffs
	 *            structural diffs with body changes (may be null)
	 * @return FQCN → normalised complexity (0.0–1.0)
	 */
	public static Map<String, Double> compute(Set<String> changedClasses, List<Path> sourceRoots,
			StructuralChangeAnalyzer.ChangedMembers changedMembers, List<StructuralDiff.FileDiff> diffs) {
		// Prefer diff-based scoring when body changes are available
		if (diffs != null && !diffs.isEmpty()) {
			Map<String, Double> diffBased = computeFromDiffs(diffs);
			if (!diffBased.isEmpty()) {
				return diffBased;
			}
		}
		// Fall back to file-level complexity
		return compute(changedClasses, sourceRoots, changedMembers);
	}

	/**
	 * Computes normalized complexity scores from pre-computed compressed sizes.
	 * Useful when the caller already has the raw values (e.g. from config
	 * properties).
	 */
	public static Map<String, Double> fromRawSizes(Map<String, Integer> rawSizes) {
		if (rawSizes.isEmpty())
			return Map.of();
		int maxSize = 0;
		for (int v : rawSizes.values()) {
			if (v > maxSize)
				maxSize = v;
		}
		if (maxSize == 0)
			return Map.of();

		Map<String, Double> result = new LinkedHashMap<>();
		for (var entry : rawSizes.entrySet()) {
			result.put(entry.getKey(), (double) entry.getValue() / maxSize);
		}
		return result;
	}

	/**
	 * Returns the Deflate-compressed size of the given bytes.
	 */
	static int deflateSize(byte[] data) {
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
		try {
			deflater.setInput(data);
			deflater.finish();
			// Worst-case DEFLATE expansion for incompressible data is data.length + ~6 +
			// ceil(data.length/32767)*5 bytes. Using data.length*2 + 128 is safe for all
			// inputs and avoids the infinite-loop when the buffer is too small for one
			// pass.
			byte[] buf = new byte[data.length * 2 + 128];
			int totalOut = 0;
			while (!deflater.finished()) {
				totalOut += deflater.deflate(buf, totalOut, buf.length - totalOut);
			}
			return totalOut;
		} finally {
			deflater.end();
		}
	}

	/**
	 * Resolves a FQCN to a source file under the given roots. Searches for both
	 * .java and .kt files. Returns {@code null} if not found.
	 */
	static Path findSourceFile(String fqcn, List<Path> sourceRoots) {
		// Top-level class name: strip inner class (e.g. "com.Foo$Bar" → "com.Foo")
		int dollar = fqcn.indexOf('$');
		String topLevel = (dollar >= 0) ? fqcn.substring(0, dollar) : fqcn;
		String relativePath = topLevel.replace('.', '/');

		for (Path root : sourceRoots) {
			Path java = root.resolve(relativePath + ".java");
			if (Files.isRegularFile(java))
				return java;
			Path kt = root.resolve(relativePath + ".kt");
			if (Files.isRegularFile(kt))
				return kt;
		}
		return null;
	}

	/**
	 * Serialises a complexity map to a property-style string:
	 * {@code fqcn:score,fqcn:score,...} Stores normalised values (0.0–1.0) as
	 * produced by {@link #compute}.
	 */
	public static String serialise(Map<String, Double> complexity) {
		if (complexity.isEmpty())
			return "";
		StringBuilder sb = new StringBuilder();
		for (var entry : complexity.entrySet()) {
			if (!sb.isEmpty())
				sb.append(',');
			sb.append(entry.getKey()).append(':').append(entry.getValue());
		}
		return sb.toString();
	}

	/**
	 * Deserialises a property-style complexity string back to a map.
	 */
	public static Map<String, Double> deserialise(String value) {
		if (value == null || value.isBlank())
			return Map.of();
		Map<String, Double> result = new LinkedHashMap<>();
		for (String pair : value.split(",")) {
			int colon = pair.lastIndexOf(':');
			if (colon > 0 && colon < pair.length() - 1) {
				try {
					result.put(pair.substring(0, colon), Double.parseDouble(pair.substring(colon + 1)));
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return result;
	}
}
