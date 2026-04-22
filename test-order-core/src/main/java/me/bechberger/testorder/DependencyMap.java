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
 *
 * Supports two on-disk formats:
 * <ul>
 * <li><b>V1 (text)</b> — plain-text, one test per line, tab-separated
 * ({@code # test-order dependency index v1} header)</li>
 * <li><b>V2 (binary)</b> — LZ4-compressed binary with a radix-trie class name
 * dictionary, RoaringBitmap dependency sets, and row deduplication
 * (default)</li>
 * </ul>
 * {@link #save(Path)} writes V2. {@link #saveText(Path)} writes V1.
 * {@link #load(Path)} auto-detects the format.
 */
public class DependencyMap {

	private static final String HEADER_V1 = "# test-order dependency index v1";

	/** LZ4 frame magic bytes (big-endian read of 04 22 4D 18). */
	private static final int LZ4_MAGIC = 0x04224D18;
	static final long MAX_COMPRESSED_FILE_SIZE = 1_000_000_000L;

	/** Magic marker inside the LZ4 payload. */
	private static final byte[] MAGIC_V2 = { 'T', 'O', '2', '\n' };
	private static final byte[] MAGIC_V3 = { 'T', 'O', '3', '\n' };
	private static final byte[] MAGIC_V4 = { 'T', 'O', '4', '\n' };

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
			this.dependencies.put(e.getKey(), new TreeSet<>(e.getValue()));
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
		dependencies.put(testClass, new TreeSet<>(deps));
	}

	/**
	 * Returns an unmodifiable view of the dependency set for the given test class,
	 * or an empty set if unknown.
	 */
	public Set<String> get(String testClass) {
		return Collections.unmodifiableSet(dependencies.getOrDefault(testClass, Collections.emptySet()));
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
		methodDependencies.put(methodKey, new TreeSet<>(deps));
	}

	/** Get per-method dependencies. Returns empty set if not available. */
	public Set<String> getMethodDeps(String className, String methodName) {
		return Collections
				.unmodifiableSet(methodDependencies.getOrDefault(className + "#" + methodName, Collections.emptySet()));
	}

	/** Get per-method dependencies by composite key (className#methodName). */
	public Set<String> getMethodDeps(String methodKey) {
		return Collections.unmodifiableSet(methodDependencies.getOrDefault(methodKey, Collections.emptySet()));
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
		memberDependencies.put(testClass, new TreeSet<>(memberDeps));
	}

	/** Get per-test-class member deps. Returns empty set if not available. */
	public Set<String> getMemberDeps(String testClass) {
		return Collections.unmodifiableSet(memberDependencies.getOrDefault(testClass, Collections.emptySet()));
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
		methodMemberDependencies.put(methodKey, new TreeSet<>(memberDeps));
	}

	/** Get per-test-method member deps. Returns empty set if not available. */
	public Set<String> getMethodMemberDeps(String methodKey) {
		return Collections.unmodifiableSet(methodMemberDependencies.getOrDefault(methodKey, Collections.emptySet()));
	}

	/** Get per-test-method member deps by class and method name. */
	public Set<String> getMethodMemberDeps(String className, String methodName) {
		return Collections.unmodifiableSet(
				methodMemberDependencies.getOrDefault(className + "#" + methodName, Collections.emptySet()));
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

	// ---- V2 binary save (default) ----

	/**
	 * Saves in V3 binary format (LZ4-compressed, trie + RoaringBitmaps,
	 * row-deduped, plus optional per-method dependency section). Falls back to V2
	 * if no method deps.
	 */
	public void save(Path indexFile) throws IOException {
		Path parent = indexFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tempFile = PersistenceSupport.temporarySibling(indexFile);
		try (OutputStream fos = Files.newOutputStream(tempFile);
				LZ4FrameOutputStream lz4 = new LZ4FrameOutputStream(fos);
				DataOutputStream out = new DataOutputStream(lz4)) {

			boolean hasMethodData = !methodDependencies.isEmpty();
			boolean hasMemberData = !memberDependencies.isEmpty() || !methodMemberDependencies.isEmpty();
			out.write(hasMemberData ? MAGIC_V4 : hasMethodData ? MAGIC_V3 : MAGIC_V2);
			// build trie over all class names (test + dep + method dep class names)
			ClassNameTrie trie = new ClassNameTrie();
			for (var entry : dependencies.entrySet()) {
				trie.insert(entry.getKey());
				for (String dep : entry.getValue()) {
					trie.insert(dep);
				}
			}
			for (var entry : methodDependencies.entrySet()) {
				// method keys are className#methodName — we don't insert these as trie entries,
				// but we do insert the dep class names
				for (String dep : entry.getValue()) {
					trie.insert(dep);
				}
			}
			trie.assignIds();

			// ordered list of test class IDs (preserves insertion order)
			List<String> testList = new ArrayList<>(dependencies.keySet());
			int testCount = testList.size();

			// group tests by identical dependency set (row deduplication)
			// Use a HashMap for O(1) lookup instead of linear scan over all groups
			Map<RoaringBitmap, List<Integer>> groups = new HashMap<>();
			// Preserve insertion order for deterministic output
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

			// write trie
			ByteArrayOutputStream trieBuf = new ByteArrayOutputStream();
			trie.writeTo(new DataOutputStream(trieBuf));
			byte[] trieBytes = trieBuf.toByteArray();
			out.writeInt(trieBytes.length);
			out.write(trieBytes);

			// write test class IDs (in insertion order)
			out.writeInt(testCount);
			for (String tc : testList) {
				out.writeInt(trie.getId(tc));
			}

			// write dependency groups
			out.writeInt(groupOrder.size());
			for (RoaringBitmap depBitmap : groupOrder) {
				List<Integer> memberIndices = groups.get(depBitmap);

				// dep set bitmap
				depBitmap.runOptimize();
				int depSize = depBitmap.serializedSizeInBytes();
				out.writeInt(depSize);
				depBitmap.serialize(out);

				// member test indices bitmap
				RoaringBitmap memberBitmap = new RoaringBitmap();
				for (int idx : memberIndices) {
					memberBitmap.add(idx);
				}
				memberBitmap.runOptimize();
				int memberSize = memberBitmap.serializedSizeInBytes();
				out.writeInt(memberSize);
				memberBitmap.serialize(out);
			}

			// V3/V4: write per-method dependency section
			if (hasMethodData || hasMemberData) {
				List<String> methodKeys = new ArrayList<>(methodDependencies.keySet());
				out.writeInt(methodKeys.size());
				for (String methodKey : methodKeys) {
					// write method key as UTF string
					out.writeUTF(methodKey);
					// write dependency bitmap
					Set<String> deps = methodDependencies.get(methodKey);
					RoaringBitmap depBitmap = new RoaringBitmap();
					for (String dep : deps) {
						depBitmap.add(trie.getId(dep));
					}
					depBitmap.runOptimize();
					int depSize = depBitmap.serializedSizeInBytes();
					out.writeInt(depSize);
					depBitmap.serialize(out);
				}
			}

			// V4: write member-level dependency sections
			if (hasMemberData) {
				// Per-test-class member deps
				List<String> memberKeys = new ArrayList<>(memberDependencies.keySet());
				out.writeInt(memberKeys.size());
				for (String testClass : memberKeys) {
					out.writeUTF(testClass);
					Set<String> members = memberDependencies.get(testClass);
					out.writeInt(members.size());
					for (String memberKey : members) {
						out.writeUTF(memberKey);
					}
				}
				// Per-test-method member deps
				List<String> methodMemberKeys = new ArrayList<>(methodMemberDependencies.keySet());
				out.writeInt(methodMemberKeys.size());
				for (String methodKey : methodMemberKeys) {
					out.writeUTF(methodKey);
					Set<String> members = methodMemberDependencies.get(methodKey);
					out.writeInt(members.size());
					for (String memberKey : members) {
						out.writeUTF(memberKey);
					}
				}
			}
		}
		PersistenceSupport.moveIntoPlace(tempFile, indexFile);
	}

	// ---- V1 text save ----

	/**
	 * Saves in V1 text format (human-readable, one test per line).
	 */
	public void saveText(Path indexFile) throws IOException {
		Path parent = indexFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tempFile = PersistenceSupport.temporarySibling(indexFile);
		try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(tempFile))) {
			pw.println(HEADER_V1);
			for (var entry : dependencies.entrySet()) {
				pw.print(entry.getKey());
				pw.print('\t');
				pw.println(String.join(",", entry.getValue()));
			}
		}
		PersistenceSupport.moveIntoPlace(tempFile, indexFile);
	}

	// ---- auto-detecting load ----

	/**
	 * Loads a dependency index, auto-detecting V1 (text) vs V2 (LZ4 binary) format.
	 */
	public static DependencyMap load(Path indexFile) throws IOException {
		Path loadPath = PersistenceSupport.resolveLoadPath(indexFile);
		try {
			return loadDetected(loadPath);
		} catch (IOException primaryFailure) {
			Path tempFile = PersistenceSupport.temporarySibling(indexFile);
			if (!loadPath.equals(tempFile) && Files.exists(tempFile)) {
				return loadDetected(tempFile);
			}
			throw primaryFailure;
		}
	}

	private static DependencyMap loadDetected(Path indexFile) throws IOException {
		validateCompressedFileSize(indexFile);
		int magic;
		try (DataInputStream peek = new DataInputStream(Files.newInputStream(indexFile))) {
			if (Files.size(indexFile) < 4) {
				return loadText(indexFile);
			}
			magic = peek.readInt();
		}
		return magic == LZ4_MAGIC ? loadBinary(indexFile) : loadText(indexFile);
	}

	private static DependencyMap loadText(Path indexFile) throws IOException {
		DependencyMap map = new DependencyMap();
		List<String> lines = Files.readAllLines(indexFile);
		for (String line : lines) {
			if (line.startsWith("#") || line.isBlank())
				continue;
			int tab = line.indexOf('\t');
			if (tab < 0)
				continue;
			String testClass = line.substring(0, tab);
			String depsStr = line.substring(tab + 1);
			Set<String> deps = depsStr.isEmpty()
					? new TreeSet<>()
					: Arrays.stream(depsStr.split(",")).map(String::trim).filter(s -> !s.isEmpty())
							.collect(Collectors.toCollection(TreeSet::new));
			map.put(testClass, deps);
		}
		return map;
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
				LZ4FrameInputStream lz4 = new LZ4FrameInputStream(fis);
				DataInputStream in = new DataInputStream(lz4)) {

			// verify magic (V2, V3, or V4)
			byte[] magicBuf = new byte[4];
			in.readFully(magicBuf);
			boolean isV4 = Arrays.equals(magicBuf, MAGIC_V4);
			boolean isV3 = !isV4 && Arrays.equals(magicBuf, MAGIC_V3);
			if (!isV4 && !isV3 && !Arrays.equals(magicBuf, MAGIC_V2)) {
				throw new IOException("Invalid binary magic in " + indexFile);
			}

			// read trie
			int trieSize = in.readInt();
			checkSize(trieSize, "Trie", indexFile);
			byte[] trieBytes = new byte[trieSize];
			in.readFully(trieBytes);
			ClassNameTrie trie = ClassNameTrie.readFrom(new DataInputStream(new ByteArrayInputStream(trieBytes)));

			// read test class IDs
			int testCount = in.readInt();
			String[] testNames = new String[testCount];
			for (int i = 0; i < testCount; i++) {
				testNames[i] = trie.getName(in.readInt());
			}

			// read dependency groups
			int groupCount = in.readInt();
			// pre-fill map with empty sets in insertion order
			DependencyMap map = new DependencyMap();
			@SuppressWarnings("unchecked")
			Set<String>[] depSets = new Set[testCount];

			for (int g = 0; g < groupCount; g++) {
				// dep set bitmap
				int depSize = in.readInt();
				checkSize(depSize, "Dependency bitmap", indexFile);
				byte[] depBytes = new byte[depSize];
				in.readFully(depBytes);
				RoaringBitmap depBitmap = new RoaringBitmap();
				depBitmap.deserialize(new DataInputStream(new ByteArrayInputStream(depBytes)));

				// convert bitmap to class name set (HashSet for O(1) contains at runtime)
				Set<String> deps = new HashSet<>((int) (depBitmap.getLongCardinality() * 2));
				depBitmap.forEach((int id) -> deps.add(trie.getName(id)));
				Set<String> sharedDeps = Collections.unmodifiableSet(deps);

				// member test indices bitmap
				int memberSize = in.readInt();
				checkSize(memberSize, "Member bitmap", indexFile);
				byte[] memberBytes = new byte[memberSize];
				in.readFully(memberBytes);
				RoaringBitmap memberBitmap = new RoaringBitmap();
				memberBitmap.deserialize(new DataInputStream(new ByteArrayInputStream(memberBytes)));

				memberBitmap.forEach((int ti) -> depSets[ti] = sharedDeps);
			}

			// build map preserving test insertion order (putDirect avoids re-copying)
			for (int i = 0; i < testCount; i++) {
				map.putDirect(testNames[i], depSets[i] != null ? depSets[i] : Collections.emptySet());
			}

			// V3/V4: read per-method dependency section
			if (isV3 || isV4) {
				int methodCount = in.readInt();
				for (int m = 0; m < methodCount; m++) {
					String methodKey = in.readUTF();
					int depSize = in.readInt();
					checkSize(depSize, "Method dependency bitmap", indexFile);
					byte[] depBytes = new byte[depSize];
					in.readFully(depBytes);
					RoaringBitmap depBitmap = new RoaringBitmap();
					depBitmap.deserialize(new DataInputStream(new ByteArrayInputStream(depBytes)));
					Set<String> deps = new HashSet<>((int) (depBitmap.getLongCardinality() * 2));
					depBitmap.forEach((int id) -> deps.add(trie.getName(id)));
					map.methodDependencies.put(methodKey, deps);
				}
			}

			// V4: read member-level dependency sections
			if (isV4) {
				// Per-test-class member deps
				int memberEntryCount = in.readInt();
				validateCount(memberEntryCount, "memberEntryCount");
				for (int i = 0; i < memberEntryCount; i++) {
					String testClass = in.readUTF();
					int memberCount = in.readInt();
					validateCount(memberCount, "memberCount");
					Set<String> members = new HashSet<>(memberCount * 2);
					for (int j = 0; j < memberCount; j++) {
						members.add(in.readUTF());
					}
					map.memberDependencies.put(testClass, members);
				}
				// Per-test-method member deps
				int methodMemberCount = in.readInt();
				validateCount(methodMemberCount, "methodMemberCount");
				for (int i = 0; i < methodMemberCount; i++) {
					String methodKey = in.readUTF();
					int memberCount = in.readInt();
					validateCount(memberCount, "memberCount");
					Set<String> members = new HashSet<>(memberCount * 2);
					for (int j = 0; j < memberCount; j++) {
						members.add(in.readUTF());
					}
					map.methodMemberDependencies.put(methodKey, members);
				}
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
		DependencyMap map = new DependencyMap();
		try (Stream<Path> files = Files.list(depsDir)) {
			for (Path file : files.toList()) {
				String fileName = file.getFileName().toString();
				if (fileName.endsWith(".deps")) {
					String testClass = fileName.substring(0, fileName.length() - 5); // strip .deps
					Set<String> deps = Files.readAllLines(file).stream().map(String::trim).filter(s -> !s.isEmpty())
							.collect(Collectors.toCollection(TreeSet::new));
					map.put(testClass, deps);
				} else if (fileName.endsWith(".mdeps")) {
					List<String> lines = Files.readAllLines(file);
					if (!lines.isEmpty() && lines.get(0).startsWith("# ")) {
						String methodKey = lines.get(0).substring(2).trim();
						Set<String> deps = lines.stream().skip(1).map(String::trim)
								.filter(s -> !s.isEmpty() && !s.startsWith("#"))
								.collect(Collectors.toCollection(TreeSet::new));
						map.putMethodDeps(methodKey, deps);
					}
				} else if (fileName.endsWith(".members")) {
					String testClass = fileName.substring(0, fileName.length() - 8); // strip .members
					Set<String> members = Files.readAllLines(file).stream().map(String::trim)
							.filter(s -> !s.isEmpty() && !s.startsWith("#"))
							.collect(Collectors.toCollection(TreeSet::new));
					map.putMemberDeps(testClass, members);
				} else if (fileName.endsWith(".mmembers")) {
					List<String> lines = Files.readAllLines(file);
					if (!lines.isEmpty() && lines.get(0).startsWith("# ")) {
						String methodKey = lines.get(0).substring(2).trim();
						Set<String> members = lines.stream().skip(1).map(String::trim)
								.filter(s -> !s.isEmpty() && !s.startsWith("#"))
								.collect(Collectors.toCollection(TreeSet::new));
						map.putMethodMemberDeps(methodKey, members);
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
		if (deps.isEmpty() && methodDeps.isEmpty() && memberDeps.isEmpty()) {
			return; // nothing to merge
		}

		DependencyMap map;
		if (Files.exists(indexFile)) {
			map = load(indexFile);
		} else {
			map = new DependencyMap();
		}

		// Merge incrementally, deduplicating to save space
		for (var entry : deps.entrySet()) {
			Set<String> existing = map.dependencies.getOrDefault(entry.getKey(), new TreeSet<>());
			existing.addAll(entry.getValue());
			map.dependencies.put(entry.getKey(), existing);
		}
		for (var entry : methodDeps.entrySet()) {
			Set<String> existing = map.methodDependencies.getOrDefault(entry.getKey(), new TreeSet<>());
			existing.addAll(entry.getValue());
			map.methodDependencies.put(entry.getKey(), existing);
		}
		for (var entry : memberDeps.entrySet()) {
			Set<String> existing = map.memberDependencies.getOrDefault(entry.getKey(), new TreeSet<>());
			existing.addAll(entry.getValue());
			map.memberDependencies.put(entry.getKey(), existing);
		}
		for (var entry : methodMemberDeps.entrySet()) {
			Set<String> existing = map.methodMemberDependencies.getOrDefault(entry.getKey(), new TreeSet<>());
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
						.collect(Collectors.toCollection(TreeSet::new));
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
					Set<String> existing = map.dependencies.getOrDefault(entry.getKey(), new TreeSet<>());
					existing.addAll(entry.getValue());
					map.dependencies.put(entry.getKey(), existing);
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
				Set<String> deps = new TreeSet<>();
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
					Set<String> existing = map.methodDependencies.getOrDefault(entry.getKey(), new TreeSet<>());
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
						.collect(Collectors.toCollection(TreeSet::new));
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
					Set<String> existing = map.memberDependencies.getOrDefault(entry.getKey(), new TreeSet<>());
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
				Set<String> members = new TreeSet<>();
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
					Set<String> existing = map.methodMemberDependencies.getOrDefault(entry.getKey(), new TreeSet<>());
					existing.addAll(entry.getValue());
					map.methodMemberDependencies.put(entry.getKey(), existing);
					methodMemberDepCount++;
				}
			} catch (java.util.concurrent.ExecutionException | InterruptedException e) {
				System.err.println("[test-order] Error loading .mmembers file: " + e.getMessage());
			}
		}

		// Save aggregated index
		Files.createDirectories(indexFile.getParent());
		map.save(indexFile);

		long duration = System.currentTimeMillis() - startTime;
		System.out.println("[test-order] Aggregated " + depCount + " test classes + " + methodDepCount
				+ " test methods + " + memberDepCount + " class-member sets + " + methodMemberDepCount
				+ " method-member sets from deps files in " + duration + "ms");
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
