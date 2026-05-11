package me.bechberger.testorder;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.roaringbitmap.RoaringBitmap;

import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

/**
 * Maps test class FQCNs to the set of application class FQCNs they depend on.
 * <p>
 * The on-disk format is a section-based binary format (version 1) inside an
 * LZ4-compressed stream. The header is {@code TORD} (4 bytes) followed by a
 * format version (2-byte big-endian short). Each payload section has a type
 * tag, length prefix, and payload — unknown section types are skipped on read,
 * enabling forward-compatible extensibility. {@link #save(Path)} writes v1.
 * {@link #load(Path)} reads v1.
 */
public class DependencyMap {

	/** LZ4 frame magic bytes (big-endian read of 04 22 4D 18). */
	private static final int LZ4_MAGIC = 0x04224D18;
	static final long MAX_COMPRESSED_FILE_SIZE = 1_000_000_000L;

	/** Magic marker inside the LZ4 payload: ASCII "TORD". */
	private static final byte[] FORMAT_MAGIC = { 'T', 'O', 'R', 'D' };

	/** Current binary format version. */
	public static final short FORMAT_VERSION = 1;

	// ── Section type constants ────────────────────────────────────────

	/** Section type: radix trie dictionary of class names. */
	static final short SECTION_TRIE = 1;
	/** Section type: ordered list of test class IDs. */
	static final short SECTION_TEST_CLASSES = 2;
	/** Section type: row-deduplicated dependency groups. */
	static final short SECTION_DEP_GROUPS = 3;
	/** Section type: per-method dependency bitmaps. */
	static final short SECTION_METHOD_DEPS = 4;
	/** Section type: per-test-class member-level dependencies. */
	static final short SECTION_MEMBER_DEPS = 5;
	/** Section type: per-test-method member-level dependencies. */
	static final short SECTION_METHOD_MEMBER_DEPS = 6;

	private final Map<String, Set<String>> dependencies;

	/**
	 * Per-method dependencies: className#methodName → deps. Only populated in
	 * FULL_METHOD or FULL_MEMBER mode.
	 */
	private final Map<String, Set<String>> methodDependencies;

	/**
	 * Per-test-class member-level deps: testClass → Set<"depClass#member">. Only
	 * populated in FULL_MEMBER mode.
	 */
	private final Map<String, Set<String>> memberDependencies;

	/**
	 * Per-test-method member-level deps: testClass#method → Set<"depClass#member">.
	 * Only populated in FULL_MEMBER mode.
	 */
	private final Map<String, Set<String>> methodMemberDependencies;

	public DependencyMap() {
		this.dependencies = new LinkedHashMap<>();
		this.methodDependencies = new LinkedHashMap<>();
		this.memberDependencies = new LinkedHashMap<>();
		this.methodMemberDependencies = new LinkedHashMap<>();
	}

	public DependencyMap(Map<String, Set<String>> dependencies) {
		this.dependencies = new LinkedHashMap<>();
		for (var e : dependencies.entrySet()) {
			this.dependencies.put(e.getKey(), Collections.unmodifiableSet(new HashSet<>(e.getValue())));
		}
		this.methodDependencies = new LinkedHashMap<>();
		this.memberDependencies = new LinkedHashMap<>();
		this.methodMemberDependencies = new LinkedHashMap<>();
	}

	/**
	 * Stores the dependency set for the given test class, replacing any previous
	 * entry.
	 */
	public void put(String testClass, Set<String> deps) {
		dependencies.put(testClass, Collections.unmodifiableSet(new HashSet<>(deps)));
	}

	/**
	 * Returns an unmodifiable view of the dependency set for the given test class,
	 * or an empty set if unknown.
	 */
	public Set<String> get(String testClass) {
		return dependencies.getOrDefault(testClass, Collections.emptySet());
	}

	/**
	 * Store deps directly without copying — for use by loadBinary where sets are
	 * already constructed.
	 */
	void putDirect(String testClass, Set<String> deps) {
		dependencies.put(testClass, deps);
	}

	/**
	 * Returns an unmodifiable view of all test class names in the dependency index.
	 */
	public Set<String> testClasses() {
		return Collections.unmodifiableSet(dependencies.keySet());
	}

	public int size() {
		return dependencies.size();
	}

	// ── Method-level dependencies ─────────────────────────────────────

	/** Store per-method dependencies. Key format: "className#methodName" */
	public void putMethodDeps(String methodKey, Set<String> deps) {
		methodDependencies.put(methodKey, Collections.unmodifiableSet(new HashSet<>(deps)));
	}

	/** Get per-method dependencies. Returns empty set if not available. */
	public Set<String> getMethodDeps(String className, String methodName) {
		return methodDependencies.getOrDefault(className + "#" + methodName, Collections.emptySet());
	}

	/** Get per-method dependencies by composite key (className#methodName). */
	public Set<String> getMethodDeps(String methodKey) {
		return methodDependencies.getOrDefault(methodKey, Collections.emptySet());
	}

	/** Returns all method keys (className#methodName) that have dependency data. */
	public Set<String> methodKeys() {
		return Collections.unmodifiableSet(methodDependencies.keySet());
	}

	/** Whether this map has any per-method dependency data. */
	public boolean hasMethodDeps() {
		return !methodDependencies.isEmpty();
	}

	// ── Member-level dependencies (FULL_MEMBER mode) ────────────────

	/**
	 * Store per-test-class member deps. Key: testClass, value: set of
	 * "depClass#memberName".
	 */
	public void putMemberDeps(String testClass, Set<String> memberDeps) {
		memberDependencies.put(testClass, Collections.unmodifiableSet(new HashSet<>(memberDeps)));
	}

	/** Get per-test-class member deps. Returns empty set if not available. */
	public Set<String> getMemberDeps(String testClass) {
		return memberDependencies.getOrDefault(testClass, Collections.emptySet());
	}

	/** Whether this map has any member-level dependency data. */
	public boolean hasMemberDeps() {
		return !memberDependencies.isEmpty();
	}

	/**
	 * Store per-test-method member deps. Key: "testClass#method", value: set of
	 * "depClass#memberName".
	 */
	public void putMethodMemberDeps(String methodKey, Set<String> memberDeps) {
		methodMemberDependencies.put(methodKey, Collections.unmodifiableSet(new HashSet<>(memberDeps)));
	}

	/** Get per-test-method member deps. Returns empty set if not available. */
	public Set<String> getMethodMemberDeps(String methodKey) {
		return methodMemberDependencies.getOrDefault(methodKey, Collections.emptySet());
	}

	/** Get per-test-method member deps by class and method name. */
	public Set<String> getMethodMemberDeps(String className, String methodName) {
		return methodMemberDependencies.getOrDefault(className + "#" + methodName, Collections.emptySet());
	}

	/**
	 * Returns all test classes whose dependency set intersects with the given
	 * changed classes.
	 */
	public Set<String> getAffectedTests(Set<String> changedClasses) {
		Set<String> affected = new LinkedHashSet<>();
		if (changedClasses.isEmpty())
			return affected;
		for (var entry : dependencies.entrySet()) {
			Set<String> deps = entry.getValue();
			// check the smaller set against the larger for best performance
			if (changedClasses.size() <= deps.size()) {
				for (String cc : changedClasses) {
					if (deps.contains(cc)) {
						affected.add(entry.getKey());
						break;
					}
				}
			} else {
				for (String dep : deps) {
					if (changedClasses.contains(dep)) {
						affected.add(entry.getKey());
						break;
					}
				}
			}
		}
		return affected;
	}

	/**
	 * Returns the number of unique application classes across all test mappings.
	 */
	public long totalUniqueClasses() {
		return dependencies.values().stream().flatMap(Set::stream).distinct().count();
	}

	/**
	 * Average number of dependencies per test class.
	 */
	public double averageDeps() {
		if (dependencies.isEmpty())
			return 0;
		return dependencies.values().stream().mapToInt(Set::size).average().orElse(0);
	}

	/**
	 * Saves in section-based binary format v1 (LZ4-compressed, trie +
	 * RoaringBitmaps, row-deduped, per-method deps, and member-level deps). Each
	 * data block is written as a typed section with a length prefix so that future
	 * readers can skip unknown sections.
	 */
	public void save(Path indexFile) throws IOException {
		Path parent = indexFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tempFile = PersistenceSupport.temporarySibling(indexFile);
		try (OutputStream fos = Files.newOutputStream(tempFile);
				LZ4FrameOutputStream lz4 = LZ4Support.frameOutputStream(fos);
				DataOutputStream out = new DataOutputStream(lz4)) {

			// ── Header: magic + version ──────────────────────────────
			out.write(FORMAT_MAGIC);
			out.writeShort(FORMAT_VERSION);

			// build trie over all class names (test + dep + method dep class names)
			ClassNameTrie trie = new ClassNameTrie();
			for (var entry : dependencies.entrySet()) {
				trie.insert(entry.getKey());
				for (String dep : entry.getValue()) {
					trie.insert(dep);
				}
			}
			for (var entry : methodDependencies.entrySet()) {
				for (String dep : entry.getValue()) {
					trie.insert(dep);
				}
			}
			trie.assignIds();

			// ordered list of test class IDs (preserves insertion order)
			List<String> testList = new ArrayList<>(dependencies.keySet());
			int testCount = testList.size();

			// group tests by identical dependency set (row deduplication)
			Map<RoaringBitmap, List<Integer>> groups = new HashMap<>();
			List<RoaringBitmap> groupOrder = new ArrayList<>();
			for (int ti = 0; ti < testCount; ti++) {
				Set<String> deps = dependencies.get(testList.get(ti));
				RoaringBitmap depBitmap = new RoaringBitmap();
				for (String dep : deps) {
					depBitmap.add(trie.getId(dep));
				}
				List<Integer> members = groups.get(depBitmap);
				if (members != null) {
					members.add(ti);
				} else {
					members = new ArrayList<>();
					members.add(ti);
					groups.put(depBitmap, members);
					groupOrder.add(depBitmap);
				}
			}

			// Count sections to write
			int sectionCount = 3; // trie + test classes + dep groups (always present)
			if (!methodDependencies.isEmpty())
				sectionCount++;
			if (!memberDependencies.isEmpty())
				sectionCount++;
			if (!methodMemberDependencies.isEmpty())
				sectionCount++;
			out.writeInt(sectionCount);

			// ── Section: TRIE ────────────────────────────────────────
			{
				ByteArrayOutputStream trieBuf = new ByteArrayOutputStream();
				trie.writeTo(new DataOutputStream(trieBuf));
				byte[] trieBytes = trieBuf.toByteArray();
				writeSection(out, SECTION_TRIE, trieBytes);
			}

			// ── Section: TEST_CLASSES ────────────────────────────────
			{
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				DataOutputStream s = new DataOutputStream(buf);
				s.writeInt(testCount);
				for (String tc : testList) {
					s.writeInt(trie.getId(tc));
				}
				s.flush();
				writeSection(out, SECTION_TEST_CLASSES, buf.toByteArray());
			}

			// ── Section: DEP_GROUPS ──────────────────────────────────
			{
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				DataOutputStream s = new DataOutputStream(buf);
				s.writeInt(groupOrder.size());
				for (RoaringBitmap depBitmap : groupOrder) {
					List<Integer> memberIndices = groups.get(depBitmap);

					depBitmap.runOptimize();
					int depSize = depBitmap.serializedSizeInBytes();
					s.writeInt(depSize);
					depBitmap.serialize(s);

					RoaringBitmap memberBitmap = new RoaringBitmap();
					for (int idx : memberIndices) {
						memberBitmap.add(idx);
					}
					memberBitmap.runOptimize();
					int memberSize = memberBitmap.serializedSizeInBytes();
					s.writeInt(memberSize);
					memberBitmap.serialize(s);
				}
				s.flush();
				writeSection(out, SECTION_DEP_GROUPS, buf.toByteArray());
			}

			// ── Section: METHOD_DEPS (optional) ──────────────────────
			if (!methodDependencies.isEmpty()) {
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				DataOutputStream s = new DataOutputStream(buf);
				List<String> methodKeys = new ArrayList<>(methodDependencies.keySet());
				s.writeInt(methodKeys.size());
				for (String methodKey : methodKeys) {
					s.writeUTF(methodKey);
					Set<String> deps = methodDependencies.get(methodKey);
					RoaringBitmap depBitmap = new RoaringBitmap();
					for (String dep : deps) {
						depBitmap.add(trie.getId(dep));
					}
					depBitmap.runOptimize();
					int depSize = depBitmap.serializedSizeInBytes();
					s.writeInt(depSize);
					depBitmap.serialize(s);
				}
				s.flush();
				writeSection(out, SECTION_METHOD_DEPS, buf.toByteArray());
			}

			// ── Section: MEMBER_DEPS (optional) ──────────────────────
			if (!memberDependencies.isEmpty()) {
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				DataOutputStream s = new DataOutputStream(buf);
				List<String> memberKeys = new ArrayList<>(memberDependencies.keySet());
				s.writeInt(memberKeys.size());
				for (String testClass : memberKeys) {
					s.writeUTF(testClass);
					Set<String> members = memberDependencies.get(testClass);
					s.writeInt(members.size());
					for (String memberKey : members) {
						s.writeUTF(memberKey);
					}
				}
				s.flush();
				writeSection(out, SECTION_MEMBER_DEPS, buf.toByteArray());
			}

			// ── Section: METHOD_MEMBER_DEPS (optional) ───────────────
			if (!methodMemberDependencies.isEmpty()) {
				ByteArrayOutputStream buf = new ByteArrayOutputStream();
				DataOutputStream s = new DataOutputStream(buf);
				List<String> methodMemberKeys = new ArrayList<>(methodMemberDependencies.keySet());
				s.writeInt(methodMemberKeys.size());
				for (String methodKey : methodMemberKeys) {
					s.writeUTF(methodKey);
					Set<String> members = methodMemberDependencies.get(methodKey);
					s.writeInt(members.size());
					for (String memberKey : members) {
						s.writeUTF(memberKey);
					}
				}
				s.flush();
				writeSection(out, SECTION_METHOD_MEMBER_DEPS, buf.toByteArray());
			}
		}
		PersistenceSupport.moveIntoPlace(tempFile, indexFile);
	}

	/** Writes a section: type (short) + length (int) + payload bytes. */
	private static void writeSection(DataOutputStream out, short type, byte[] payload) throws IOException {
		out.writeShort(type);
		out.writeInt(payload.length);
		out.write(payload);
	}

	/**
	 * Saves in text format (human-readable, one test per line). For inspection and
	 * debugging only; use {@link #save(Path)} for the canonical binary format.
	 */
	public void saveText(Path indexFile) throws IOException {
		Path parent = indexFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tempFile = PersistenceSupport.temporarySibling(indexFile);
		try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(tempFile))) {
			for (var entry : dependencies.entrySet()) {
				pw.print(entry.getKey());
				pw.print('\t');
				pw.println(String.join(",", entry.getValue()));
			}
		}
		PersistenceSupport.moveIntoPlace(tempFile, indexFile);
	}

	/**
	 * Loads a binary dependency index. Validates LZ4 framing and the inner
	 * {@code TORD} magic + version header.
	 */
	public static DependencyMap load(Path indexFile) throws IOException {
		Path loadPath = PersistenceSupport.resolveLoadPath(indexFile);
		validateCompressedFileSize(loadPath);
		int magic;
		try (DataInputStream peek = new DataInputStream(Files.newInputStream(loadPath))) {
			magic = peek.readInt();
		} catch (EOFException e) {
			throw new IOException("Index file is too small to be a valid binary index: " + loadPath);
		}
		if (magic != LZ4_MAGIC) {
			throw new IOException("Not a valid binary index (wrong magic bytes): " + loadPath);
		}
		return loadBinary(loadPath);
	}

	/** Helper to load index or create fresh if corrupt */
	private static DependencyMap loadOrCreateFresh(Path indexFile) throws IOException {
		try {
			return load(indexFile);
		} catch (IOException e) {
			// Index is corrupt — start fresh instead of failing
			System.err.println("[test-order] Index file corrupt, creating fresh index: " + e.getMessage());
			return new DependencyMap();
		}
	}

	/** Maximum allowed size for a single serialized block (64 MB). */
	private static final int MAX_BLOCK_SIZE = 64 * 1024 * 1024;

	private static void checkSize(int size, String label, Path file) throws IOException {
		if (size < 0 || size > MAX_BLOCK_SIZE) {
			throw new IOException(label + " size " + size + " out of range in " + file);
		}
	}

	static void validateCompressedFileSize(Path file) throws IOException {
		validateCompressedFileSize(Files.size(file), file);
	}

	static void validateCompressedFileSize(long size, Path file) throws IOException {
		if (size > MAX_COMPRESSED_FILE_SIZE) {
			throw new IOException("Compressed dependency index exceeds safe size limit: " + size + " bytes in " + file);
		}
	}

	private static final int MAX_ENTRY_COUNT = 1_000_000;

	private static void validateCount(int count, String label) throws IOException {
		if (count < 0 || count > MAX_ENTRY_COUNT) {
			throw new IOException("Invalid " + label + " in dependency index: " + count);
		}
	}

	private static DependencyMap loadBinary(Path indexFile) throws IOException {
		try (InputStream fis = Files.newInputStream(indexFile);
				LZ4FrameInputStream lz4 = LZ4Support.frameInputStream(fis);
				DataInputStream in = new DataInputStream(lz4)) {

			// ── Verify header: magic + version ───────────────────────
			byte[] magicBuf = new byte[4];
			in.readFully(magicBuf);
			if (!Arrays.equals(magicBuf, FORMAT_MAGIC)) {
				throw new IOException("Unsupported index format (expected TORD magic) in " + indexFile);
			}
			short version = in.readShort();
			if (version != FORMAT_VERSION) {
				throw new IOException("Unsupported dependency index format version " + version + " (expected "
						+ FORMAT_VERSION + ") in " + indexFile + ". Please rebuild the dependency index.");
			}

			// ── Read section count and iterate ───────────────────────
			int sectionCount = in.readInt();
			if (sectionCount < 0 || sectionCount > 100) {
				throw new IOException("Invalid section count " + sectionCount + " in " + indexFile);
			}

			ClassNameTrie trie = null;
			String[] testNames = null;
			Set<String>[] depSets = null;
			DependencyMap map = new DependencyMap();

			for (int si = 0; si < sectionCount; si++) {
				short sectionType = in.readShort();
				int sectionLength = in.readInt();
				checkSize(sectionLength, "Section[" + sectionType + "]", indexFile);

				switch (sectionType) {
					case SECTION_TRIE -> {
						byte[] trieBytes = new byte[sectionLength];
						in.readFully(trieBytes);
						trie = ClassNameTrie.readFrom(new DataInputStream(new ByteArrayInputStream(trieBytes)));
					}
					case SECTION_TEST_CLASSES -> {
						byte[] payload = new byte[sectionLength];
						in.readFully(payload);
						DataInputStream s = new DataInputStream(new ByteArrayInputStream(payload));
						int testCount = s.readInt();
						validateCount(testCount, "testCount");
						testNames = new String[testCount];
						for (int i = 0; i < testCount; i++) {
							testNames[i] = Objects.requireNonNull(trie, "TRIE section must precede TEST_CLASSES")
									.getName(s.readInt());
						}
					}
					case SECTION_DEP_GROUPS -> {
						byte[] payload = new byte[sectionLength];
						in.readFully(payload);
						Objects.requireNonNull(trie, "TRIE section must precede DEP_GROUPS");
						Objects.requireNonNull(testNames, "TEST_CLASSES section must precede DEP_GROUPS");
						DataInputStream s = new DataInputStream(new ByteArrayInputStream(payload));
						int groupCount = s.readInt();
						validateCount(groupCount, "groupCount");
						@SuppressWarnings("unchecked")
						Set<String>[] ds = new Set[testNames.length];
						depSets = ds;
						for (int g = 0; g < groupCount; g++) {
							int depSize = s.readInt();
							checkSize(depSize, "Dependency bitmap", indexFile);
							byte[] depBytes = new byte[depSize];
							s.readFully(depBytes);
							RoaringBitmap depBitmap = new RoaringBitmap();
							depBitmap.deserialize(new DataInputStream(new ByteArrayInputStream(depBytes)));

							Set<String> deps = new HashSet<>((int) (depBitmap.getLongCardinality() * 2));
							ClassNameTrie finalTrie = trie;
							depBitmap.forEach((int id) -> deps.add(finalTrie.getName(id)));
							Set<String> sharedDeps = Collections.unmodifiableSet(deps);

							int memberSize = s.readInt();
							checkSize(memberSize, "Member bitmap", indexFile);
							byte[] memberBytes = new byte[memberSize];
							s.readFully(memberBytes);
							RoaringBitmap memberBitmap = new RoaringBitmap();
							memberBitmap.deserialize(new DataInputStream(new ByteArrayInputStream(memberBytes)));
							Set<String>[] finalDepSets = ds;
							int testCount = ds.length;
							memberBitmap.forEach((int ti) -> {
								if (ti < 0 || ti >= testCount) {
									throw new IllegalStateException(
											"Invalid test index " + ti + " in member bitmap (valid: 0–"
													+ (testCount - 1) + ")");
								}
								finalDepSets[ti] = sharedDeps;
							});
						}
						// build map preserving test insertion order
						for (int i = 0; i < testNames.length; i++) {
							map.putDirect(testNames[i], depSets[i] != null ? depSets[i] : Collections.emptySet());
						}
					}
					case SECTION_METHOD_DEPS -> {
						byte[] payload = new byte[sectionLength];
						in.readFully(payload);
						Objects.requireNonNull(trie, "TRIE section must precede METHOD_DEPS");
						DataInputStream s = new DataInputStream(new ByteArrayInputStream(payload));
						int methodCount = s.readInt();
						validateCount(methodCount, "methodCount");
						for (int m = 0; m < methodCount; m++) {
							String methodKey = s.readUTF();
							int depSize = s.readInt();
							checkSize(depSize, "Method dependency bitmap", indexFile);
							byte[] depBytes = new byte[depSize];
							s.readFully(depBytes);
							RoaringBitmap depBitmap = new RoaringBitmap();
							depBitmap.deserialize(new DataInputStream(new ByteArrayInputStream(depBytes)));
							Set<String> deps = new HashSet<>((int) (depBitmap.getLongCardinality() * 2));
							ClassNameTrie finalTrie = trie;
							depBitmap.forEach((int id) -> deps.add(finalTrie.getName(id)));
							map.methodDependencies.put(methodKey, Collections.unmodifiableSet(deps));
						}
					}
					case SECTION_MEMBER_DEPS -> {
						byte[] payload = new byte[sectionLength];
						in.readFully(payload);
						DataInputStream s = new DataInputStream(new ByteArrayInputStream(payload));
						int memberEntryCount = s.readInt();
						validateCount(memberEntryCount, "memberEntryCount");
						for (int i = 0; i < memberEntryCount; i++) {
							String testClass = s.readUTF();
							int memberCount = s.readInt();
							validateCount(memberCount, "memberCount");
							Set<String> members = new HashSet<>(memberCount * 2);
							for (int j = 0; j < memberCount; j++) {
								members.add(s.readUTF());
							}
							map.memberDependencies.put(testClass, Collections.unmodifiableSet(members));
						}
					}
					case SECTION_METHOD_MEMBER_DEPS -> {
						byte[] payload = new byte[sectionLength];
						in.readFully(payload);
						DataInputStream s = new DataInputStream(new ByteArrayInputStream(payload));
						int methodMemberCount = s.readInt();
						validateCount(methodMemberCount, "methodMemberCount");
						for (int i = 0; i < methodMemberCount; i++) {
							String methodKey = s.readUTF();
							int memberCount = s.readInt();
							validateCount(memberCount, "memberCount");
							Set<String> members = new HashSet<>(memberCount * 2);
							for (int j = 0; j < memberCount; j++) {
								members.add(s.readUTF());
							}
							map.methodMemberDependencies.put(methodKey, Collections.unmodifiableSet(members));
						}
					}
					default -> {
						// Unknown section type — skip for forward compatibility
						in.skipNBytes(sectionLength);
					}
				}
			}

			if (testNames == null) {
				throw new IOException("Missing required TEST_CLASSES section in " + indexFile);
			}

			return map;
		}
	}

	/**
	 * Aggregates all {@code .deps}, {@code .mdeps}, {@code .members}, and
	 * {@code .mmembers} files from a directory into a single DependencyMap. Each
	 * {@code .deps} file is named {@code <TestClass>.deps} and contains one class
	 * FQCN per line. Each {@code .mdeps} file contains per-method deps: first line
	 * is {@code # className#methodName}, remaining lines are dependency FQCNs.
	 */
	public static DependencyMap aggregate(Path depsDir) throws IOException {
		return aggregate(depsDir, null);
	}

	/**
	 * Aggregates all .deps, .mdeps, .members, and .mmembers files from the given
	 * directory into a single DependencyMap.
	 *
	 * @param depsDir
	 *            directory to scan for .deps files
	 * @param log
	 *            optional logger for progress reporting (null = no logging)
	 * @return aggregated dependency map
	 * @throws IOException
	 *             if reading files fails
	 */
	public static DependencyMap aggregate(Path depsDir, me.bechberger.testorder.ops.PluginLog log) throws IOException {
		DependencyMap map = new DependencyMap();
		try (Stream<Path> files = Files.list(depsDir)) {
			java.util.List<Path> fileList = files.toList();
			int totalFiles = fileList.size();
			for (int i = 0; i < totalFiles; i++) {
				Path file = fileList.get(i);
				// Log progress every 100 files on large projects
				if (log != null && i > 0 && i % 100 == 0) {
					log.info("[test-order] Aggregating... (" + i + "/" + totalFiles + " files)");
				}
				String fileName = file.getFileName().toString();
				try {
					if (fileName.endsWith(".deps")) {
						String testClass = fileName.substring(0, fileName.length() - 5); // strip .deps
						Set<String> deps = Files.readAllLines(file).stream().map(String::trim).filter(s -> !s.isEmpty())
								.collect(Collectors.toCollection(HashSet::new));
						map.put(testClass, deps);
					} else if (fileName.endsWith(".mdeps")) {
						List<String> lines = Files.readAllLines(file);
						if (!lines.isEmpty() && lines.get(0).startsWith("# ")) {
							String methodKey = lines.get(0).substring(2).trim();
							Set<String> deps = lines.stream().skip(1).map(String::trim)
									.filter(s -> !s.isEmpty() && !s.startsWith("#"))
									.collect(Collectors.toCollection(HashSet::new));
							map.putMethodDeps(methodKey, deps);
						}
					} else if (fileName.endsWith(".members")) {
						String testClass = fileName.substring(0, fileName.length() - 8); // strip .members
						Set<String> members = Files.readAllLines(file).stream().map(String::trim)
								.filter(s -> !s.isEmpty() && !s.startsWith("#"))
								.collect(Collectors.toCollection(HashSet::new));
						map.putMemberDeps(testClass, members);
					} else if (fileName.endsWith(".mmembers")) {
						List<String> lines = Files.readAllLines(file);
						if (!lines.isEmpty() && lines.get(0).startsWith("# ")) {
							String methodKey = lines.get(0).substring(2).trim();
							Set<String> members = lines.stream().skip(1).map(String::trim)
									.filter(s -> !s.isEmpty() && !s.startsWith("#"))
									.collect(Collectors.toCollection(HashSet::new));
							map.putMethodMemberDeps(methodKey, members);
						}
					}
				} catch (IOException e) {
					// R12-4: Skip files that are being written concurrently
					if (log != null) {
						log.warn("[test-order] Skipping " + fileName
								+ " (read error, possibly being written concurrently): " + e.getMessage());
					}
				}
			}
		}
		return map;
	}

	/**
	 * Merges dependency data from the agent directly into the binary index file.
	 * Called from UsageStore (on the bootstrap classpath) via reflection at JVM
	 * shutdown. Loads the existing index (if present), merges new entries, and
	 * saves back.
	 *
	 * <p>
	 * <b>Performance note:</b> This method is synchronized but called at most once
	 * per test fork. When the agent is configured with outputDir, UsageStore writes
	 * incremental .deps files instead, allowing multiple forks to run in parallel
	 * without contention.
	 */
	public static synchronized void mergeFromAgent(Path indexFile, Map<String, Set<String>> deps,
			Map<String, Set<String>> methodDeps, Map<String, Set<String>> memberDeps,
			Map<String, Set<String>> methodMemberDeps) throws IOException {
		if (deps.isEmpty() && methodDeps.isEmpty() && memberDeps.isEmpty() && methodMemberDeps.isEmpty()) {
			return; // nothing to merge
		}

		final DependencyMap map = Files.exists(indexFile) ? loadOrCreateFresh(indexFile) : new DependencyMap();

		// Merge incrementally, deduplicating to save space
		for (var entry : deps.entrySet()) {
			Set<String> existing = new HashSet<>(map.dependencies.getOrDefault(entry.getKey(), Set.of()));
			existing.addAll(entry.getValue());
			map.dependencies.put(entry.getKey(), existing);
		}
		for (var entry : methodDeps.entrySet()) {
			Set<String> existing = new HashSet<>(map.methodDependencies.getOrDefault(entry.getKey(), Set.of()));
			existing.addAll(entry.getValue());
			map.methodDependencies.put(entry.getKey(), existing);
		}
		for (var entry : memberDeps.entrySet()) {
			Set<String> existing = new HashSet<>(map.memberDependencies.getOrDefault(entry.getKey(), Set.of()));
			existing.addAll(entry.getValue());
			map.memberDependencies.put(entry.getKey(), existing);
		}
		for (var entry : methodMemberDeps.entrySet()) {
			Set<String> existing = new HashSet<>(map.methodMemberDependencies.getOrDefault(entry.getKey(), Set.of()));
			existing.addAll(entry.getValue());
			map.methodMemberDependencies.put(entry.getKey(), existing);
		}

		Files.createDirectories(indexFile.getParent());
		map.save(indexFile);

		// warn if no actual dependencies were captured (common with groupId/package
		// mismatch)
		boolean allEmpty = map.dependencies.values().stream()
				.allMatch(d -> d.isEmpty() || (d.size() == 1 && map.dependencies.containsKey(d.iterator().next())));
		if (!map.dependencies.isEmpty() && allEmpty) {
			System.err.println("[test-order] WARNING: All test classes have zero non-self dependencies. "
					+ "If your source packages differ from the Maven groupId, "
					+ "set -Dtestorder.includePackages=your.package.prefix");
		}
	}

	/**
	 * Backward-compatible overload for older agent versions without member deps.
	 */
	public static synchronized void mergeFromAgent(Path indexFile, Map<String, Set<String>> deps,
			Map<String, Set<String>> methodDeps) throws IOException {
		mergeFromAgent(indexFile, deps, methodDeps, Map.of(), Map.of());
	}

	/** Backward-compatible overload for oldest agent versions. */
	public static synchronized void mergeFromAgent(Path indexFile, Map<String, Set<String>> deps) throws IOException {
		mergeFromAgent(indexFile, deps, Map.of(), Map.of(), Map.of());
	}

	/**
	 * High-performance parallel aggregation from multiple .deps files. Scans a
	 * directory for *.deps and *.mdeps files, loads them in parallel, and merges
	 * into a single DependencyMap, finally saving to indexFile.
	 *
	 * <p>
	 * This method is optimized for the post-fork aggregation phase:
	 * <ul>
	 * <li>Loads existing index first (if present) as a base</li>
	 * <li>Discovers all .deps/.mdeps files in the directory</li>
	 * <li>Loads them in parallel using ForkJoinPool</li>
	 * <li>Merges results into a single map and saves</li>
	 * <li>Logs aggregation stats</li>
	 * </ul>
	 */
	public static void aggregateFromDepsDirectory(Path depsDir, Path indexFile) throws IOException {
		aggregateFromDepsDirectory(depsDir, indexFile, null);
	}

	/**
	 * Aggregates .deps files from a directory into a single index file.
	 * Uses file locking to prevent corruption in concurrent builds.
	 *
	 * @param depsDir   directory containing .deps files
	 * @param indexFile target index file
	 * @param log       optional logger (null = use System.out)
	 */
	public static void aggregateFromDepsDirectory(Path depsDir, Path indexFile, me.bechberger.testorder.ops.PluginLog log) throws IOException {
		if (!Files.isDirectory(depsDir)) {
			return; // empty directory, skip
		}

		// Load existing index as base, or start fresh
		DependencyMap map;
		if (Files.exists(indexFile)) {
			map = load(indexFile);
		} else {
			map = new DependencyMap();
		}

		// Collect all dependency files
		var depFiles = new java.util.ArrayList<Path>();
		var mdepsFiles = new java.util.ArrayList<Path>();
		var memberFiles = new java.util.ArrayList<Path>();
		var methodMemberFiles = new java.util.ArrayList<Path>();
		try (var stream = Files.list(depsDir)) {
			stream.forEach(path -> {
				String name = path.getFileName().toString();
				if (name.endsWith(".deps")) {
					depFiles.add(path);
				} else if (name.endsWith(".mdeps")) {
					mdepsFiles.add(path);
				} else if (name.endsWith(".members")) {
					memberFiles.add(path);
				} else if (name.endsWith(".mmembers")) {
					methodMemberFiles.add(path);
				}
			});
		}

		if (depFiles.isEmpty() && mdepsFiles.isEmpty() && memberFiles.isEmpty() && methodMemberFiles.isEmpty()) {
			return; // nothing to aggregate
		}

		long startTime = System.currentTimeMillis();
		long depCount = 0;
		long methodDepCount = 0;
		long memberDepCount = 0;
		long methodMemberDepCount = 0;

		// Load .deps files (each represents one test class → deps)
		var pool = java.util.concurrent.ForkJoinPool.commonPool();
		var depTasks = depFiles.parallelStream().map(depFile -> pool.submit(() -> {
			String testClass = depFile.getFileName().toString();
			testClass = testClass.substring(0, testClass.length() - 5); // remove .deps
			try {
				Set<String> deps = Files.readAllLines(depFile).stream().filter(line -> !line.trim().isEmpty())
						.collect(Collectors.toCollection(HashSet::new));
				return java.util.Map.entry(testClass, deps);
			} catch (IOException e) {
				System.err.println("[test-order] Failed to read " + depFile + ": " + e.getMessage());
				return null;
			}
		})).collect(Collectors.toList());

		// Merge results from dep tasks
		for (var task : depTasks) {
			try {
				var entry = task.get();
				if (entry != null) {
					Set<String> existing = map.dependencies.get(entry.getKey());
					if (existing == null) {
						map.dependencies.put(entry.getKey(), entry.getValue());
					} else {
						// existing may be an unmodifiable set from loadBinary — copy into mutable set
						Set<String> merged = new HashSet<>(existing);
						merged.addAll(entry.getValue());
						map.dependencies.put(entry.getKey(), merged);
					}
					depCount++;
				}
			} catch (java.util.concurrent.ExecutionException | InterruptedException e) {
				System.err.println("[test-order] Error loading .deps file: " + e.getMessage());
			}
		}

		// Load .mdeps files (each represents one test method → deps)
		var mdepTasks = mdepsFiles.parallelStream().map(mdepFile -> pool.submit(() -> {
			try {
				java.util.List<String> lines = Files.readAllLines(mdepFile);
				if (lines.isEmpty())
					return null;

				String methodKey = null;
				Set<String> deps = new HashSet<>();
				for (String line : lines) {
					if (line.startsWith("# ")) {
						methodKey = line.substring(2).trim();
					} else if (!line.trim().isEmpty()) {
						deps.add(line.trim());
					}
				}
				if (methodKey != null && !deps.isEmpty()) {
					return java.util.Map.entry(methodKey, deps);
				}
				return null;
			} catch (IOException e) {
				System.err.println("[test-order] Failed to read " + mdepFile + ": " + e.getMessage());
				return null;
			}
		})).collect(Collectors.toList());

		// Merge results from mdep tasks
		for (var task : mdepTasks) {
			try {
				var entry = task.get();
				if (entry != null) {
					// existing may be an unmodifiable set from loadBinary — copy into mutable set
					Set<String> existing = new HashSet<>(map.methodDependencies.getOrDefault(entry.getKey(), Set.of()));
					existing.addAll(entry.getValue());
					map.methodDependencies.put(entry.getKey(), existing);
					methodDepCount++;
				}
			} catch (java.util.concurrent.ExecutionException | InterruptedException e) {
				System.err.println("[test-order] Error loading .mdeps file: " + e.getMessage());
			}
		}

		// Load .members files (each represents one test class -> member deps)
		var memberTasks = memberFiles.parallelStream().map(memberFile -> pool.submit(() -> {
			String testClass = memberFile.getFileName().toString();
			testClass = testClass.substring(0, testClass.length() - 8); // remove .members
			try {
				Set<String> members = Files.readAllLines(memberFile).stream().map(String::trim)
						.filter(line -> !line.isEmpty() && !line.startsWith("#"))
						.collect(Collectors.toCollection(HashSet::new));
				return java.util.Map.entry(testClass, members);
			} catch (IOException e) {
				System.err.println("[test-order] Failed to read " + memberFile + ": " + e.getMessage());
				return null;
			}
		})).collect(Collectors.toList());

		for (var task : memberTasks) {
			try {
				var entry = task.get();
				if (entry != null) {
					// existing may be an unmodifiable set from loadBinary — copy into mutable set
					Set<String> existing = new HashSet<>(map.memberDependencies.getOrDefault(entry.getKey(), Set.of()));
					existing.addAll(entry.getValue());
					map.memberDependencies.put(entry.getKey(), existing);
					memberDepCount++;
				}
			} catch (java.util.concurrent.ExecutionException | InterruptedException e) {
				System.err.println("[test-order] Error loading .members file: " + e.getMessage());
			}
		}

		// Load .mmembers files (each represents one test method -> member deps)
		var mmemberTasks = methodMemberFiles.parallelStream().map(mmemberFile -> pool.submit(() -> {
			try {
				java.util.List<String> lines = Files.readAllLines(mmemberFile);
				if (lines.isEmpty())
					return null;

				String methodKey = null;
				Set<String> members = new HashSet<>();
				for (String line : lines) {
					if (line.startsWith("# ")) {
						methodKey = line.substring(2).trim();
					} else if (!line.trim().isEmpty()) {
						members.add(line.trim());
					}
				}
				if (methodKey != null && !members.isEmpty()) {
					return java.util.Map.entry(methodKey, members);
				}
				return null;
			} catch (IOException e) {
				System.err.println("[test-order] Failed to read " + mmemberFile + ": " + e.getMessage());
				return null;
			}
		})).collect(Collectors.toList());

		for (var task : mmemberTasks) {
			try {
				var entry = task.get();
				if (entry != null) {
					// existing may be an unmodifiable set from loadBinary — copy into mutable set
					Set<String> existing = new HashSet<>(map.methodMemberDependencies.getOrDefault(entry.getKey(), Set.of()));
					existing.addAll(entry.getValue());
					map.methodMemberDependencies.put(entry.getKey(), existing);
					methodMemberDepCount++;
				}
			} catch (java.util.concurrent.ExecutionException | InterruptedException e) {
				System.err.println("[test-order] Error loading .mmembers file: " + e.getMessage());
			}
		}

		// Save aggregated index under file lock (R7-6: prevent corruption in concurrent -T N builds)
		Files.createDirectories(indexFile.getParent());
		PersistenceSupport.withFileLock(indexFile, () -> {
			map.save(indexFile);
			return null;
		});

		long duration = System.currentTimeMillis() - startTime;
		String msg = "[test-order] Aggregated " + depCount + " test classes + " + methodDepCount
				+ " test methods + " + memberDepCount + " class-member sets + " + methodMemberDepCount
				+ " method-member sets from deps files in " + duration + "ms";
		if (log != null) {
			log.info(msg);
		} else {
			System.out.println(msg);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof DependencyMap other))
			return false;
		return dependencies.equals(other.dependencies);
	}

	@Override
	public int hashCode() {
		return dependencies.hashCode();
	}
}
