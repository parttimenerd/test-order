package me.bechberger.testorder.agent.runtime;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Serialization format for className→classId mappings used by offline
 * instrumentation. The format is LZ4-compressed sorted text where line index
 * equals the classId (className at line 0 has classId 0).
 * <p>
 * For member IDs (MEMBER mode), entries are stored as:
 * {@code M:className#memberName} on lines indexed by
 * {@code memberId - MEMBER_ID_OFFSET}.
 * <p>
 * File layout:
 *
 * <pre>
 *   [4 bytes] magic: "TOIM" (Test Order Id Mapping)
 *   [4 bytes] version: 1
 *   [4 bytes] classCount
 *   [4 bytes] memberCount
 *   [classCount lines] class names (sorted by assigned ID = line index)
 *   [memberCount lines] member keys (sorted by assigned ID offset)
 * </pre>
 *
 * All text is UTF-8. The file is NOT LZ4-compressed in v1 for simplicity
 * (typical size ~50-200KB for medium projects is acceptable).
 */
public final class ClassIdMapping {

	private static final byte[] MAGIC = {'T', 'O', 'I', 'M'};
	private static final int VERSION = 1;

	private final String[] classNames; // index = classId
	private final String[] memberNames; // index = memberId - MEMBER_ID_OFFSET

	private ClassIdMapping(String[] classNames, String[] memberNames) {
		this.classNames = classNames;
		this.memberNames = memberNames;
	}

	public int classCount() {
		return classNames.length;
	}

	public int memberCount() {
		return memberNames.length;
	}

	public String getClassName(int classId) {
		return (classId >= 0 && classId < classNames.length) ? classNames[classId] : null;
	}

	public String getMemberName(int memberId) {
		int idx = memberId - 8_000_000;
		return (idx >= 0 && idx < memberNames.length) ? memberNames[idx] : null;
	}

	/**
	 * Build a mapping from a ClassIdMap snapshot.
	 */
	public static ClassIdMapping fromClassIdMap(ClassIdMap classIdMap, int maxClassId, int maxMemberId) {
		String[] classes = new String[maxClassId];
		for (int i = 0; i < maxClassId; i++) {
			classes[i] = classIdMap.getClassNameForId(i);
		}
		String[] members;
		if (maxMemberId > 8_000_000) {
			int memberCount = maxMemberId - 8_000_000;
			members = new String[memberCount];
			for (int i = 0; i < memberCount; i++) {
				members[i] = classIdMap.getMemberNameForId(i + 8_000_000);
			}
		} else {
			members = new String[0];
		}
		return new ClassIdMapping(classes, members);
	}

	/**
	 * Save mapping to a binary file.
	 */
	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		// Pre-size buffer: header (16 bytes) + ~40 bytes average per name entry
		int estimatedSize = 16 + (classNames.length + memberNames.length) * 40;
		ByteArrayOutputStream baos = new ByteArrayOutputStream(estimatedSize);
		try (DataOutputStream dos = new DataOutputStream(baos)) {
			dos.write(MAGIC);
			dos.writeInt(VERSION);
			dos.writeInt(classNames.length);
			dos.writeInt(memberNames.length);
			for (String name : classNames) {
				dos.writeUTF(name != null ? name : "");
			}
			for (String name : memberNames) {
				dos.writeUTF(name != null ? name : "");
			}
		}
		// Single write + atomic move for crash safety
		Path tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(tmpFile, baos.toByteArray());
		try {
			Files.move(tmpFile, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException e) {
			Files.move(tmpFile, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/**
	 * Load mapping from a binary file.
	 */
	public static ClassIdMapping load(Path file) throws IOException {
		try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
			byte[] magic = new byte[4];
			dis.readFully(magic);
			if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1] || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
				throw new IOException("Invalid class-id-map file: bad magic bytes");
			}
			int version = dis.readInt();
			if (version != VERSION) {
				throw new IOException("Unsupported class-id-map version: " + version);
			}
			int classCount = dis.readInt();
			int memberCount = dis.readInt();
			String[] classes = new String[classCount];
			for (int i = 0; i < classCount; i++) {
				classes[i] = dis.readUTF();
			}
			String[] members = new String[memberCount];
			for (int i = 0; i < memberCount; i++) {
				members[i] = dis.readUTF();
			}
			return new ClassIdMapping(classes, members);
		}
	}

	/**
	 * Load as a map of className→classId (for bulkLoad into ClassIdMap at runtime).
	 */
	public Map<String, Integer> toClassMap() {
		Map<String, Integer> map = new HashMap<>(classNames.length * 2);
		for (int i = 0; i < classNames.length; i++) {
			if (classNames[i] != null && !classNames[i].isEmpty()) {
				map.put(classNames[i], i);
			}
		}
		return map;
	}

	/**
	 * Load as a map of memberKey→memberId (for bulkLoad into ClassIdMap at
	 * runtime).
	 */
	public Map<String, Integer> toMemberMap() {
		Map<String, Integer> map = new HashMap<>(memberNames.length * 2);
		for (int i = 0; i < memberNames.length; i++) {
			if (memberNames[i] != null && !memberNames[i].isEmpty()) {
				map.put(memberNames[i], i + 8_000_000);
			}
		}
		return map;
	}
}
