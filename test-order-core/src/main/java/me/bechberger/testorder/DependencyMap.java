package me.bechberger.testorder;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
	private static final byte[] FORMAT_MAGIC = {'T', 'O', 'R', 'D'};

	/** Current binary format version. */
	public static final short FORMAT_VERSION = 1;

	// ── H12: per-JVM load cache keyed by (absolutePath, mtime, size) ─────────
	// Avoids re-deserializing the same index file N times in a Maven reactor or
	// multi-command CLI session. Invalidated whenever save() rewrites the file.
	// IMPORTANT: callers that mutate the returned instance (e.g. mergeWith) must
	// call save() immediately after — save() evicts the stale cache entry so the
	// next load() picks up the updated file. Do not hold a reference to a cached
	// instance across a save() call.
	private record CacheKey(Path path, long mtime, long size) {
	}
	private static final ConcurrentHashMap<CacheKey, DependencyMap> LOAD_CACHE = new ConcurrentHashMap<>();

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
	/**
	 * Section type: per-test-class owning module id (testClass FQCN → moduleId).
	 */
	static final short SECTION_TEST_MODULE_MAP = 7;
	/**
	 * Section type: dictionary of member keys, encoded as (classTrieId, suffix
	 * index) varint pairs. The suffix table is inlined at the front. Member ids in
	 * MEMBER_DEPS / METHOD_MEMBER_DEPS index into this table.
	 */
	static final short SECTION_MEMBER_KEY_TABLE = 8;

	private final Map<String, Set<String>> dependencies;

	/** Cached unmodifiable view of dependency keys — live view, never stale. */
	private final Set<String> testClassesView;

	/**
	 * Inverted index: depClass → Set of test classes that depend on it. Built
	 * lazily on first call to {@link #getAffectedTests(Set)}. Includes entries for
	 * top-level class names derived from nested class deps (e.g. if testA depends
	 * on com.Foo$Bar, the inverted index has both "com.Foo$Bar" → {testA} and
	 * "com.Foo" → {testA}).
	 */
	private volatile Map<String, Set<String>> invertedIndex;

	/**
	 * Document frequency cache: depClass → number of test classes that depend on
	 * it. Built lazily alongside invertedIndex; invalidated on put/mergeWith.
	 */
	private volatile Map<String, Integer> depFrequencies;

	/**
	 * Per-method dependencies: className#methodName → deps. Only populated in
	 * METHOD or MEMBER mode.
	 */
	private final Map<String, Set<String>> methodDependencies;

	/**
	 * Dense integer dictionary for member keys. Index = memberKeyId, value =
	 * "ClassName#member". Shared across memberDepsMap and methodMemberDepsMap. New
	 * keys are appended on first use.
	 */
	private final List<String> memberKeyDictionary;

	/**
	 * Reverse lookup: "ClassName#member" → memberKeyId. Kept in sync with
	 * memberKeyDictionary at all times.
	 */
	private final Map<String, Integer> memberKeyIndex;

	/**
	 * Per-test-class member-level deps: testClass → RoaringBitmap of memberKeyIds.
	 * Only populated in MEMBER mode. Materialized to Set<String> on demand via
	 * getMemberDeps().
	 */
	private final Map<String, RoaringBitmap> memberDepsMap;

	/**
	 * Per-test-method member-level deps: testClass#method → RoaringBitmap of
	 * memberKeyIds. Only populated in MEMBER mode.
	 */
	private final Map<String, RoaringBitmap> methodMemberDepsMap;

	/**
	 * Per-test-class owning module id: testClass FQCN → "groupId:artifactId" (or
	 * "artifactId" if groupId is empty). Populated during learn in multi-module
	 * builds; used by select to filter the index to only the current module's
	 * tests, avoiding cross-module surefire failures when a single shared index
	 * sits at the reactor root. Tests with no recorded module are treated as
	 * unknown and pass through every per-module filter for backward compatibility.
	 */
	private final Map<String, String> testToModule;

	public DependencyMap() {
		this.dependencies = new HashMap<>();
		this.methodDependencies = new HashMap<>();
		this.memberKeyDictionary = new ArrayList<>();
		this.memberKeyIndex = new LinkedHashMap<>();
		this.memberDepsMap = new HashMap<>();
		this.methodMemberDepsMap = new HashMap<>();
		this.testToModule = new HashMap<>();
		this.testClassesView = Collections.unmodifiableSet(dependencies.keySet());
	}

	/**
	 * Force-load all classes transitively needed by {@link #save(Path)} and
	 * {@link #mergeFromAgent}. Call this while the plugin classloader is still
	 * alive so the JVM shutdown hook can use them after Maven tears down its class
	 * realms.
	 * <p>
	 * This performs a dummy save/load cycle on a temp file to force every class in
	 * the serialization path (RoaringBitmap internals, LZ4 codecs, ClassNameTrie,
	 * etc.) to be loaded. Once loaded, classes survive classloader closure.
	 */
	@SuppressWarnings("unused")
	public static void preloadSaveClasses() {
		try {
			// Exercise the full serialization path purely in memory.
			// This forces the JVM to load every class transitively reachable from
			// the save path (RoaringBitmap internals, LZ4 codecs, ClassNameTrie,
			// etc.) while the plugin classloader is still alive — no disk I/O.
			DependencyMap dummy = new DependencyMap();
			dummy.put("a.PreloadTest", Set.of("a.Dep"));
			ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
			try (LZ4FrameOutputStream lz4 = LZ4Support.frameOutputStream(buf, LZ4Support.Compression.FAST);
					DataOutputStream out = new DataOutputStream(lz4)) {
				dummy.writePayload(out);
			}
		} catch (Exception | NoClassDefFoundError e) {
			// Best-effort — if this fails, the explicit stopAndMerge call handles it
		}
	}

	public DependencyMap(Map<String, Set<String>> dependencies) {
		this.dependencies = new HashMap<>();
		for (var e : dependencies.entrySet()) {
			this.dependencies.put(e.getKey(), Collections.unmodifiableSet(new HashSet<>(e.getValue())));
		}
		this.methodDependencies = new HashMap<>();
		this.memberKeyDictionary = new ArrayList<>();
		this.memberKeyIndex = new LinkedHashMap<>();
		this.memberDepsMap = new HashMap<>();
		this.methodMemberDepsMap = new HashMap<>();
		this.testToModule = new HashMap<>();
		this.testClassesView = Collections.unmodifiableSet(this.dependencies.keySet());
	}

	/**
	 * Stores the dependency set for the given test class, replacing any previous
	 * entry.
	 */
	public void put(String testClass, Set<String> deps) {
		dependencies.put(testClass, Collections.unmodifiableSet(new HashSet<>(deps)));
		invertedIndex = null;
		depFrequencies = null;
	}

	/**
	 * Checks whether {@code dep} is contained in {@code changedClasses}, falling
	 * back to the top-level class if {@code dep} is a nested class. This handles
	 * the "reverse" nested class case: a test depends on
	 * {@code com.Service$Builder} but the changed set only contains
	 * {@code com.Service}.
	 */
	public static boolean changedClassesContains(Set<String> changedClasses, String dep) {
		if (changedClasses.contains(dep)) {
			return true;
		}
		int dollar = dep.indexOf('$');
		return dollar > 0 && changedClasses.contains(dep.substring(0, dollar));
	}

	/**
	 * Returns an unmodifiable view of the dependency set for the given test class,
	 * or an empty set if unknown. Falls back to the top-level class entry for
	 * nested test classes.
	 */
	public Set<String> get(String testClass) {
		Set<String> result = dependencies.getOrDefault(testClass, Collections.emptySet());
		if (result.isEmpty()) {
			int dollar = testClass.indexOf('$');
			if (dollar > 0) {
				result = dependencies.getOrDefault(testClass.substring(0, dollar), Collections.emptySet());
			}
		}
		return result;
	}

	/**
	 * Store deps directly without copying — for use by loadBinary where sets are
	 * already constructed.
	 */
	void putDirect(String testClass, Set<String> deps) {
		dependencies.put(testClass, deps);
		invertedIndex = null;
		depFrequencies = null;
	}

	/**
	 * Returns an unmodifiable view of all test class names in the dependency index.
	 */
	public Set<String> testClasses() {
		return testClassesView;
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
		String key = className + "#" + methodName;
		Set<String> deps = methodDependencies.get(key);
		if (deps != null) {
			return deps;
		}
		int dollar = className.indexOf('$');
		if (dollar > 0) {
			String parentKey = className.substring(0, dollar) + "#" + methodName;
			deps = methodDependencies.get(parentKey);
			if (deps != null) {
				return deps;
			}
		}
		return Collections.emptySet();
	}

	/** Get per-method dependencies by composite key (className#methodName). */
	public Set<String> getMethodDeps(String methodKey) {
		Set<String> deps = methodDependencies.get(methodKey);
		if (deps != null) {
			return deps;
		}
		int hash = methodKey.indexOf('#');
		if (hash > 0) {
			String className = methodKey.substring(0, hash);
			int dollar = className.indexOf('$');
			if (dollar > 0) {
				String parentKey = className.substring(0, dollar) + methodKey.substring(hash);
				deps = methodDependencies.get(parentKey);
				if (deps != null) {
					return deps;
				}
			}
		}
		return Collections.emptySet();
	}

	/** Returns all method keys (className#methodName) that have dependency data. */
	public Set<String> methodKeys() {
		return Collections.unmodifiableSet(methodDependencies.keySet());
	}

	/** Whether this map has any per-method dependency data. */
	public boolean hasMethodDeps() {
		return !methodDependencies.isEmpty();
	}

	// ── Member-level dependencies (MEMBER mode) ────────────────────

	/**
	 * Sentinel returned by {@link #memberKeyId} when the key is too long to be
	 * serialised via {@link DataOutputStream#writeUTF} (which is limited to 65535
	 * UTF-8 bytes). Callers must not add this value to a {@link RoaringBitmap}.
	 */
	private static final int INVALID_MEMBER_KEY_ID = -1;

	/** Logged at most once to avoid flooding stderr with the same message. */
	private boolean warnedOversizedMemberKey = false;

	/**
	 * Returns the memberKeyId for the given key, inserting it into the dictionary
	 * if it is new. Returns {@link #INVALID_MEMBER_KEY_ID} and logs a one-time
	 * warning when the key's UTF-8 encoding would exceed the 65535-byte limit
	 * imposed by {@link DataOutputStream#writeUTF}.
	 */
	private int memberKeyId(String memberKey) {
		if (memberKey.length() > 21844 && memberKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65535) {
			if (!warnedOversizedMemberKey) {
				warnedOversizedMemberKey = true;
				System.err.println("[test-order] WARNING: Ignoring member key whose UTF-8 encoding exceeds "
						+ "65535 bytes (key starts with: " + memberKey.substring(0, Math.min(120, memberKey.length()))
						+ "…). " + "This indicates corrupted or synthetic data reaching the index.");
			}
			return INVALID_MEMBER_KEY_ID;
		}
		return memberKeyIndex.computeIfAbsent(memberKey, k -> {
			int id = memberKeyDictionary.size();
			memberKeyDictionary.add(k);
			return id;
		});
	}

	/**
	 * Adds the memberKeyId for {@code mk} to {@code bm}, skipping the entry when
	 * the key is too long (i.e. {@link #memberKeyId} returns
	 * {@link #INVALID_MEMBER_KEY_ID}).
	 */
	private void addMemberKey(RoaringBitmap bm, String mk) {
		int id = memberKeyId(mk);
		if (id != INVALID_MEMBER_KEY_ID)
			bm.add(id);
	}

	/** Materializes a bitmap of memberKeyIds into an unmodifiable Set<String>. */
	private Set<String> materializeMembers(RoaringBitmap bm) {
		int size = (int) bm.getLongCardinality();
		Set<String> result = new HashSet<>((int) (size * 1.5) + 1);
		bm.forEach((int id) -> {
			if (id >= 0 && id < memberKeyDictionary.size())
				result.add(memberKeyDictionary.get(id));
		});
		return Collections.unmodifiableSet(result);
	}

	/**
	 * Store per-test-class member deps. Key: testClass, value: set of
	 * "depClass#memberName".
	 */
	public void putMemberDeps(String testClass, Set<String> memberDeps) {
		if (memberDeps.isEmpty()) {
			memberDepsMap.remove(testClass);
			return;
		}
		RoaringBitmap bm = new RoaringBitmap();
		for (String mk : memberDeps)
			addMemberKey(bm, mk);
		memberDepsMap.put(testClass, bm);
	}

	/** Get per-test-class member deps. Returns empty set if not available. */
	public Set<String> getMemberDeps(String testClass) {
		RoaringBitmap bm = memberDepsMap.get(testClass);
		if (bm == null || bm.isEmpty()) {
			int dollar = testClass.indexOf('$');
			if (dollar > 0) {
				bm = memberDepsMap.get(testClass.substring(0, dollar));
			}
		}
		if (bm == null || bm.isEmpty())
			return Collections.emptySet();
		return materializeMembers(bm);
	}

	/** Whether this map has any member-level dependency data. */
	public boolean hasMemberDeps() {
		return !memberDepsMap.isEmpty();
	}

	/**
	 * Returns the number of distinct tracked members (methods) per source class,
	 * derived from the member key dictionary. Only populated when member-level
	 * instrumentation is active (i.e. {@link #hasMemberDeps()} is true). Keys are
	 * fully-qualified class names; values are the count of tracked members.
	 */
	public Map<String, Integer> trackedMembersPerClass() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String key : memberKeyDictionary) {
			int hash = key.indexOf('#');
			if (hash < 0)
				continue;
			String cls = key.substring(0, hash);
			counts.merge(cls, 1, Integer::sum);
		}
		return counts;
	}

	/**
	 * Store per-test-method member deps. Key: "testClass#method", value: set of
	 * "depClass#memberName".
	 */
	public void putMethodMemberDeps(String methodKey, Set<String> memberDeps) {
		if (memberDeps.isEmpty()) {
			methodMemberDepsMap.remove(methodKey);
			return;
		}
		RoaringBitmap bm = new RoaringBitmap();
		for (String mk : memberDeps)
			addMemberKey(bm, mk);
		methodMemberDepsMap.put(methodKey, bm);
	}

	// ── Test-class → owning module ───────────────────────────────────

	/**
	 * Records the owning module of a test class. Used so the index can carry a
	 * single shared store across a multi-module reactor while still letting each
	 * module's select goal filter to only its own tests.
	 */
	public void putModule(String testClass, String moduleId) {
		if (testClass == null || moduleId == null || moduleId.isEmpty()) {
			return;
		}
		testToModule.put(testClass, moduleId);
	}

	/**
	 * Returns the owning module of {@code testClass}, or {@code null} if no module
	 * is recorded (e.g. older indexes built before the section existed).
	 */
	public String getModule(String testClass) {
		return testToModule.get(testClass);
	}

	/** Whether this map carries any test → module mapping. */
	public boolean hasModuleMap() {
		return !testToModule.isEmpty();
	}

	/** Set of all module ids referenced by the test → module map. */
	public Set<String> modules() {
		return Collections.unmodifiableSet(new HashSet<>(testToModule.values()));
	}

	/**
	 * Returns a view of this map restricted to tests that belong to the given
	 * module. Tests with no module stamp (from older indexes) are included so that
	 * backward compatibility is preserved. Tests whose module is different from
	 * {@code moduleId} are excluded.
	 * <p>
	 * This prevents the {@code affected} goal from selecting tests that live in a
	 * different Maven module and would cause Surefire "No tests matching pattern"
	 * errors.
	 */
	public DependencyMap filterForModule(String moduleId, me.bechberger.testorder.ops.PluginLog log) {
		if (moduleId == null || moduleId.isEmpty() || testToModule.isEmpty()) {
			return this;
		}
		DependencyMap filtered = new DependencyMap();
		int excluded = 0;
		for (var entry : dependencies.entrySet()) {
			String testClass = entry.getKey();
			String ownerModule = testToModule.get(testClass);
			if (ownerModule == null || ownerModule.equals(moduleId)) {
				filtered.dependencies.put(testClass, entry.getValue());
			} else {
				excluded++;
			}
		}
		if (excluded > 0 && log != null) {
			log.debug("[test-order] Module filter '" + moduleId + "': excluded " + excluded
					+ " tests from other modules");
		}
		filtered.methodDependencies.putAll(methodDependencies);
		filtered.memberKeyDictionary.addAll(memberKeyDictionary);
		filtered.memberKeyIndex.putAll(memberKeyIndex);
		for (var e : memberDepsMap.entrySet())
			filtered.memberDepsMap.put(e.getKey(), e.getValue().clone());
		for (var e : methodMemberDepsMap.entrySet())
			filtered.methodMemberDepsMap.put(e.getKey(), e.getValue().clone());
		filtered.testToModule.putAll(testToModule);
		return filtered;
	}

	/**
	 * Merge all entries from another DependencyMap into this one. For each key, the
	 * dependency sets are unioned (not replaced). Member deps are merged using
	 * RoaringBitmap OR after remapping the other instance's member key IDs.
	 */
	public void mergeWith(DependencyMap other) {
		for (var entry : other.dependencies.entrySet()) {
			Set<String> existing = dependencies.get(entry.getKey());
			if (existing == null) {
				dependencies.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
			} else {
				Set<String> merged = new HashSet<>(existing);
				merged.addAll(entry.getValue());
				dependencies.put(entry.getKey(), Collections.unmodifiableSet(merged));
			}
		}
		for (var entry : other.methodDependencies.entrySet()) {
			Set<String> existing = methodDependencies.get(entry.getKey());
			if (existing == null) {
				methodDependencies.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
			} else {
				Set<String> merged = new HashSet<>(existing);
				merged.addAll(entry.getValue());
				methodDependencies.put(entry.getKey(), Collections.unmodifiableSet(merged));
			}
		}
		mergeMemberDepsFrom(other.memberKeyDictionary, other.memberDepsMap, memberDepsMap);
		mergeMemberDepsFrom(other.memberKeyDictionary, other.methodMemberDepsMap, methodMemberDepsMap);
		// In a multi-module reactor each test FQCN belongs to exactly one module, so
		// last-writer-wins is fine. Only overwrite when the incoming entry is
		// non-empty so a freshly-built empty map can't erase known module data.
		for (var entry : other.testToModule.entrySet()) {
			if (entry.getValue() != null && !entry.getValue().isEmpty()) {
				testToModule.put(entry.getKey(), entry.getValue());
			}
		}
		invertedIndex = null; // invalidate cache
		depFrequencies = null;
	}

	/**
	 * Merges member-dep bitmaps from {@code srcMap} (whose IDs reference
	 * {@code srcDict}) into {@code dstMap} (whose IDs reference this instance's
	 * memberKeyDictionary). IDs are remapped on the fly.
	 */
	private void mergeMemberDepsFrom(List<String> srcDict, Map<String, RoaringBitmap> srcMap,
			Map<String, RoaringBitmap> dstMap) {
		if (srcMap.isEmpty())
			return;
		for (var entry : srcMap.entrySet()) {
			RoaringBitmap srcBm = entry.getValue();
			if (srcBm.isEmpty())
				continue;
			// Remap IDs from src dictionary to this instance's dictionary
			int[] remapped = new int[(int) srcBm.getLongCardinality()];
			int[] k = {0};
			srcBm.forEach((int srcId) -> {
				if (srcId >= 0 && srcId < srcDict.size()) {
					int id = memberKeyId(srcDict.get(srcId));
					if (id != INVALID_MEMBER_KEY_ID)
						remapped[k[0]++] = id;
				}
			});
			if (k[0] == 0)
				continue;
			int[] ids = k[0] == remapped.length ? remapped : Arrays.copyOf(remapped, k[0]);
			RoaringBitmap existing = dstMap.get(entry.getKey());
			if (existing == null) {
				Arrays.sort(ids);
				dstMap.put(entry.getKey(), RoaringBitmap.bitmapOf(ids));
			} else {
				for (int id : ids)
					existing.add(id);
			}
		}
	}

	/** Get per-test-method member deps. Returns empty set if not available. */
	public Set<String> getMethodMemberDeps(String methodKey) {
		RoaringBitmap bm = methodMemberDepsMap.get(methodKey);
		if (bm == null || bm.isEmpty()) {
			int dollar = methodKey.indexOf('$');
			if (dollar > 0) {
				int hash = methodKey.indexOf('#');
				if (hash > dollar) {
					String topLevel = methodKey.substring(0, dollar) + methodKey.substring(hash);
					bm = methodMemberDepsMap.get(topLevel);
				}
			}
		}
		if (bm == null || bm.isEmpty())
			return Collections.emptySet();
		return materializeMembers(bm);
	}

	/** Get per-test-method member deps by class and method name. */
	public Set<String> getMethodMemberDeps(String className, String methodName) {
		RoaringBitmap bm = methodMemberDepsMap.get(className + "#" + methodName);
		if (bm == null || bm.isEmpty()) {
			int dollar = className.indexOf('$');
			if (dollar > 0) {
				bm = methodMemberDepsMap.get(className.substring(0, dollar) + "#" + methodName);
			}
		}
		if (bm == null || bm.isEmpty())
			return Collections.emptySet();
		return materializeMembers(bm);
	}

	/**
	 * Returns a new {@code DependencyMap} whose test → prod dependency sets are
	 * augmented with the supplied {@code extra} edges. For each test class, the
	 * resulting dependency set is the union of the original set and any extras
	 * supplied for that test. <b>Augment-only</b>: existing edges are never
	 * removed; tests not present in {@code extra} are passed through unchanged.
	 *
	 * <p>
	 * Method-level, member-level, and method-member-level dependency maps pass
	 * through unchanged.
	 *
	 * <p>
	 * Returns {@code this} unchanged when {@code extra} is null or empty.
	 */
	public DependencyMap withAugmentation(Map<String, Set<String>> extra) {
		if (extra == null || extra.isEmpty()) {
			return this;
		}
		DependencyMap result = new DependencyMap();
		for (var entry : dependencies.entrySet()) {
			Set<String> existing = entry.getValue();
			Set<String> add = extra.get(entry.getKey());
			if (add == null || add.isEmpty()) {
				result.dependencies.put(entry.getKey(), existing);
			} else {
				Set<String> merged = new HashSet<>(existing.size() + add.size());
				merged.addAll(existing);
				merged.addAll(add);
				result.dependencies.put(entry.getKey(), Collections.unmodifiableSet(merged));
			}
		}
		// Carry over the auxiliary maps unchanged. Member bitmaps reference the same
		// dictionary IDs — share the dictionary and copy bitmap references.
		result.methodDependencies.putAll(methodDependencies);
		result.memberKeyDictionary.addAll(memberKeyDictionary);
		result.memberKeyIndex.putAll(memberKeyIndex);
		for (var e : memberDepsMap.entrySet())
			result.memberDepsMap.put(e.getKey(), e.getValue().clone());
		for (var e : methodMemberDepsMap.entrySet())
			result.methodMemberDepsMap.put(e.getKey(), e.getValue().clone());
		result.testToModule.putAll(testToModule);
		return result;
	}

	/**
	 * Returns all test classes whose dependency set intersects with the given
	 * changed classes.
	 */
	public Set<String> getAffectedTests(Set<String> changedClasses) {
		Set<String> affected = new LinkedHashSet<>();
		if (changedClasses.isEmpty())
			return affected;
		Map<String, Set<String>> idx = getInvertedIndex();
		for (String changed : changedClasses) {
			Set<String> tests = idx.get(changed);
			if (tests != null)
				affected.addAll(tests);
		}
		return affected;
	}

	private Map<String, Set<String>> getInvertedIndex() {
		Map<String, Set<String>> idx = invertedIndex;
		if (idx == null) {
			synchronized (this) {
				idx = invertedIndex;
				if (idx == null) {
					idx = buildInvertedIndex();
					invertedIndex = idx;
				}
			}
		}
		return idx;
	}

	private Map<String, Set<String>> buildInvertedIndex() {
		Map<String, Set<String>> idx = new HashMap<>((int) (dependencies.size() / 0.75f) + 1);
		for (var entry : dependencies.entrySet()) {
			String testClass = entry.getKey();
			for (String dep : entry.getValue()) {
				idx.computeIfAbsent(dep, k -> new HashSet<>()).add(testClass);
				// Also index by top-level class for nested class deps
				int dollar = dep.indexOf('$');
				if (dollar > 0) {
					String topLevel = dep.substring(0, dollar);
					idx.computeIfAbsent(topLevel, k -> new HashSet<>()).add(testClass);
				}
			}
		}
		// Derive df from the inverted index so each test class is counted once per dep,
		// even when multiple inner-class deps (Foo$Bar, Foo$Baz) share the same
		// top-level.
		Map<String, Integer> df = new HashMap<>((int) (idx.size() / 0.75f) + 1);
		for (var e : idx.entrySet()) {
			df.put(e.getKey(), e.getValue().size());
		}
		depFrequencies = df;
		return idx;
	}

	/**
	 * Returns the document-frequency map: depClass → how many test classes depend
	 * on it. Built lazily (shares a pass with the inverted index).
	 */
	public Map<String, Integer> documentFrequencies() {
		Map<String, Integer> df = depFrequencies;
		if (df != null)
			return df;
		// trigger the shared build — afterwards depFrequencies is set
		getInvertedIndex();
		return depFrequencies;
	}

	/**
	 * Returns {@code idf(dep) = ln(N / df(dep))}, where {@code N} is the total
	 * number of test classes. A dep present in every test gets idf ≈ 0; a dep
	 * present in only one test gets idf = ln(N). Returns {@code 1.0} when the dep
	 * is not found in the index or the map has fewer than 10 tests (neutral weight
	 * — IDF requires enough tests to produce meaningful frequency statistics).
	 */
	public double idf(String dep) {
		int n = dependencies.size();
		if (n < 10)
			return 1.0;
		Map<String, Integer> df = documentFrequencies();
		int count = df.getOrDefault(dep, 0);
		if (count == 0)
			return 1.0;
		return Math.log((double) n / count);
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
		save(indexFile, LZ4Support.Compression.HC);
	}

	/**
	 * Save with optional fast compression. Fast mode uses LZ4 fast compressor
	 * (10-50x faster writes, ~5-15% larger files) — ideal for learn-mode where the
	 * index is rewritten frequently. Normal mode uses HC level 9 for archival.
	 */
	public void save(Path indexFile, boolean fast) throws IOException {
		save(indexFile, fast ? LZ4Support.Compression.FAST : LZ4Support.Compression.HC);
	}

	/**
	 * Save with explicit compression level.
	 *
	 * @param indexFile
	 *            target path for the binary index
	 * @param compression
	 *            LZ4 compression level (FAST or HC)
	 */
	public void save(Path indexFile, LZ4Support.Compression compression) throws IOException {
		Path parent = indexFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tempFile = PersistenceSupport.temporarySibling(indexFile);
		try (OutputStream fos = Files.newOutputStream(tempFile);
				LZ4FrameOutputStream lz4 = LZ4Support.frameOutputStream(fos, compression);
				DataOutputStream out = new DataOutputStream(lz4)) {
			writePayload(out);
		}
		PersistenceSupport.moveIntoPlace(tempFile, indexFile);
		// Invalidate any cached entry for this path so the next load() re-reads it.
		evictCache(indexFile);
	}

	/** Removes all cache entries for the given path (any mtime/size). */
	static void evictCache(Path indexFile) {
		Path abs = indexFile.toAbsolutePath();
		LOAD_CACHE.keySet().removeIf(k -> k.path().equals(abs));
	}

	/**
	 * Writes the binary index payload (header + all sections) to the given stream.
	 * Separated from {@link #save(Path, LZ4Support.Compression)} so it can be used
	 * for in-memory round-trips (e.g. class preloading) without disk I/O.
	 */
	void writePayload(DataOutputStream out) throws IOException {

		// ── Header: magic + version ──────────────────────────────
		out.write(FORMAT_MAGIC);
		out.writeShort(FORMAT_VERSION);

		// build trie over all class names (test + dep + method dep + member-key class
		// parts)
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
		// member key dictionary already has all unique class parts
		for (String mk : memberKeyDictionary)
			trie.insert(memberClass(mk));
		trie.assignIds();

		// Build the suffix deduplication table for MEMBER_KEY_TABLE serialization.
		// Suffixes ("#member") are deduplicated to avoid repeating them for every
		// class that has a member with the same name.
		boolean hasMemberDeps = !memberDepsMap.isEmpty() || !methodMemberDepsMap.isEmpty();
		String[] memberSuffixTable = new String[0];
		int[] memberSuffixIds = new int[0]; // parallel to memberKeyDictionary
		if (hasMemberDeps) {
			Map<String, Integer> suffixIds = new LinkedHashMap<>();
			memberSuffixIds = new int[memberKeyDictionary.size()];
			for (int i = 0; i < memberKeyDictionary.size(); i++) {
				String mk = memberKeyDictionary.get(i);
				int hash = mk.indexOf('#');
				String suffix = hash >= 0 ? mk.substring(hash) : mk;
				memberSuffixIds[i] = suffixIds.computeIfAbsent(suffix, s -> suffixIds.size());
			}
			memberSuffixTable = suffixIds.keySet().toArray(new String[0]);
		}

		// ordered list of test class IDs (preserves insertion order)
		List<String> testList = new ArrayList<>(dependencies.keySet());
		int testCount = testList.size();

		// group tests by identical dependency set (row deduplication)
		Map<RoaringBitmap, List<Integer>> groups = new HashMap<>();
		List<RoaringBitmap> groupOrder = new ArrayList<>();
		for (int ti = 0; ti < testCount; ti++) {
			Set<String> deps = dependencies.get(testList.get(ti));
			// Collect IDs and sort directly — avoids stream pipeline allocations
			int[] depIds = new int[deps.size()];
			int di = 0;
			for (String dep : deps)
				depIds[di++] = trie.getId(dep);
			java.util.Arrays.sort(depIds);
			RoaringBitmap depBitmap = RoaringBitmap.bitmapOf(depIds);
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
		if (hasMemberDeps)
			sectionCount++; // SECTION_MEMBER_KEY_TABLE
		if (!memberDepsMap.isEmpty())
			sectionCount++;
		if (!methodMemberDepsMap.isEmpty())
			sectionCount++;
		if (!testToModule.isEmpty())
			sectionCount++;
		out.writeInt(sectionCount);

		// ── Section: TRIE ────────────────────────────────────────
		{
			ByteArrayOutputStream trieBuf = new ByteArrayOutputStream(4096);
			trie.writeTo(new DataOutputStream(trieBuf));
			byte[] trieBytes = trieBuf.toByteArray();
			writeSection(out, SECTION_TRIE, trieBytes);
		}

		// ── Section: TEST_CLASSES ────────────────────────────────
		{
			ByteArrayOutputStream buf = new ByteArrayOutputStream(testCount * 4 + 4);
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
			ByteArrayOutputStream buf = new ByteArrayOutputStream(groupOrder.size() * 24 + 8);
			DataOutputStream s = new DataOutputStream(buf);
			s.writeInt(groupOrder.size());
			for (RoaringBitmap depBitmap : groupOrder) {
				List<Integer> memberIndices = groups.get(depBitmap);

				depBitmap.runOptimize();
				int depSize = depBitmap.serializedSizeInBytes();
				s.writeInt(depSize);
				depBitmap.serialize(s);

				int[] sortedMemberIds = memberIndices.stream().mapToInt(Integer::intValue).sorted().toArray();
				RoaringBitmap memberBitmap = RoaringBitmap.bitmapOf(sortedMemberIds);
				int memberSize = memberBitmap.serializedSizeInBytes();
				s.writeInt(memberSize);
				memberBitmap.serialize(s);
			}
			s.flush();
			writeSection(out, SECTION_DEP_GROUPS, buf.toByteArray());
		}

		// ── Section: METHOD_DEPS (optional) ──────────────────────
		if (!methodDependencies.isEmpty()) {
			ByteArrayOutputStream buf = new ByteArrayOutputStream(methodDependencies.size() * 60);
			DataOutputStream s = new DataOutputStream(buf);
			List<String> methodKeys = new ArrayList<>(methodDependencies.keySet());
			s.writeInt(methodKeys.size());
			for (String methodKey : methodKeys) {
				s.writeUTF(methodKey);
				Set<String> deps = methodDependencies.get(methodKey);
				// Collect IDs and sort directly — avoids stream pipeline allocations
				int[] depIds = new int[deps.size()];
				int di = 0;
				for (String dep : deps)
					depIds[di++] = trie.getId(dep);
				java.util.Arrays.sort(depIds);
				RoaringBitmap depBitmap = RoaringBitmap.bitmapOf(depIds);
				depBitmap.runOptimize();
				int depSize = depBitmap.serializedSizeInBytes();
				s.writeInt(depSize);
				depBitmap.serialize(s);
			}
			s.flush();
			writeSection(out, SECTION_METHOD_DEPS, buf.toByteArray());
		}

		// ── Section: MEMBER_KEY_TABLE (optional) ─────────────────
		if (hasMemberDeps) {
			int keyCount = memberKeyDictionary.size();
			ByteArrayOutputStream buf = new ByteArrayOutputStream(keyCount * 6 + 4);
			DataOutputStream s = new DataOutputStream(buf);
			// Inlined suffix table. All suffixes are guaranteed to fit within the
			// 65535-byte writeUTF limit because memberKeyId() rejects oversized keys
			// before they enter memberKeyDictionary.
			s.writeInt(memberSuffixTable.length);
			for (String suffix : memberSuffixTable)
				s.writeUTF(suffix);
			// Member key entries: (classTrieId, suffixId) varint pairs
			s.writeInt(keyCount);
			for (int i = 0; i < keyCount; i++) {
				String mk = memberKeyDictionary.get(i);
				int hash = mk.indexOf('#');
				String className = hash >= 0 ? mk.substring(0, hash) : mk;
				ClassNameTrie.writeVarInt(s, trie.getId(className));
				ClassNameTrie.writeVarInt(s, memberSuffixIds[i]);
			}
			s.flush();
			writeSection(out, SECTION_MEMBER_KEY_TABLE, buf.toByteArray());
		}

		// ── Section: MEMBER_DEPS (optional) ──────────────────────
		if (!memberDepsMap.isEmpty()) {
			ByteArrayOutputStream buf = new ByteArrayOutputStream(memberDepsMap.size() * 20);
			DataOutputStream s = new DataOutputStream(buf);
			writeMemberSection(s, memberDepsMap);
			s.flush();
			writeSection(out, SECTION_MEMBER_DEPS, buf.toByteArray());
		}

		// ── Section: METHOD_MEMBER_DEPS (optional) ───────────────
		if (!methodMemberDepsMap.isEmpty()) {
			ByteArrayOutputStream buf = new ByteArrayOutputStream(methodMemberDepsMap.size() * 20);
			DataOutputStream s = new DataOutputStream(buf);
			writeMemberSection(s, methodMemberDepsMap);
			s.flush();
			writeSection(out, SECTION_METHOD_MEMBER_DEPS, buf.toByteArray());
		}
		// ── Section: TEST_MODULE_MAP (optional) ──────────────────
		if (!testToModule.isEmpty()) {
			ByteArrayOutputStream buf = new ByteArrayOutputStream(testToModule.size() * 64);
			DataOutputStream s = new DataOutputStream(buf);
			s.writeInt(testToModule.size());
			for (var entry : testToModule.entrySet()) {
				s.writeUTF(entry.getKey());
				s.writeUTF(entry.getValue());
			}
			s.flush();
			writeSection(out, SECTION_TEST_MODULE_MAP, buf.toByteArray());
		}
	}

	/** Writes a section: type (short) + length (int) + payload bytes. */
	private static void writeSection(DataOutputStream out, short type, byte[] payload) throws IOException {
		out.writeShort(type);
		out.writeInt(payload.length);
		out.write(payload);
	}

	/** Extracts the "className" prefix from a "className#memberName" key. */
	private static String memberClass(String memberKey) {
		int hash = memberKey.indexOf('#');
		return hash >= 0 ? memberKey.substring(0, hash) : memberKey;
	}

	/**
	 * Writes a member-dep map (bitmap values already use this instance's
	 * memberKeyDictionary IDs) as row-deduplicated RoaringBitmap rows.
	 *
	 * <pre>
	 * writeInt(rowCount)
	 * for each row: writeUTF(rowKey)
	 * writeInt(groupCount)
	 * for each group:
	 *   writeInt(memberBitmapBytes); memberBitmap   (RoaringBitmap of memberKeyIds)
	 *   writeInt(rowBitmapBytes);   rowBitmap       (which rows share this group)
	 * </pre>
	 */
	private static void writeMemberSection(DataOutputStream s, Map<String, RoaringBitmap> map) throws IOException {
		List<String> rowKeys = new ArrayList<>(map.keySet());
		int rowCount = rowKeys.size();

		Map<RoaringBitmap, List<Integer>> groups = new LinkedHashMap<>();
		List<RoaringBitmap> groupOrder = new ArrayList<>();
		for (int ri = 0; ri < rowCount; ri++) {
			RoaringBitmap bm = map.get(rowKeys.get(ri));
			List<Integer> rows = groups.get(bm);
			if (rows != null) {
				rows.add(ri);
			} else {
				rows = new ArrayList<>();
				rows.add(ri);
				groups.put(bm, rows);
				groupOrder.add(bm);
			}
		}

		s.writeInt(rowCount);
		for (String key : rowKeys)
			s.writeUTF(key);
		s.writeInt(groupOrder.size());
		for (RoaringBitmap memberBm : groupOrder) {
			// Fetch row list BEFORE runOptimize — runOptimize changes hashCode()
			// and would break the map lookup.
			int[] rowIdxs = groups.get(memberBm).stream().mapToInt(Integer::intValue).sorted().toArray();
			memberBm.runOptimize();
			s.writeInt(memberBm.serializedSizeInBytes());
			memberBm.serialize(s);

			RoaringBitmap rowBm = RoaringBitmap.bitmapOf(rowIdxs);
			s.writeInt(rowBm.serializedSizeInBytes());
			rowBm.serialize(s);
		}
	}

	/** Reads a member section written by {@link #writeMemberSection}. */
	private static void readMemberSection(DataInputStream s, Map<String, RoaringBitmap> dest, int keyTableSize,
			Path indexFile) throws IOException {
		int rowCount = s.readInt();
		validateCount(rowCount, "memberRowCount");
		String[] rowKeys = new String[rowCount];
		for (int i = 0; i < rowCount; i++)
			rowKeys[i] = s.readUTF();
		int groupCount = s.readInt();
		validateCount(groupCount, "memberGroupCount");
		@SuppressWarnings("unchecked")
		RoaringBitmap[] rowBitmaps = new RoaringBitmap[rowCount];
		for (int g = 0; g < groupCount; g++) {
			int memberBmSize = s.readInt();
			checkSize(memberBmSize, "member bitmap", indexFile);
			byte[] memberBmBytes = new byte[memberBmSize];
			s.readFully(memberBmBytes);
			RoaringBitmap memberBm = new RoaringBitmap();
			memberBm.deserialize(new DataInputStream(new ByteArrayInputStream(memberBmBytes)));

			// Validate IDs against the key table size
			int finalKeyTableSize = keyTableSize;
			memberBm.forEach((int id) -> {
				if (id >= finalKeyTableSize)
					throw new IllegalStateException("member key id " + id + " >= table size " + finalKeyTableSize);
			});

			int rowBmSize = s.readInt();
			checkSize(rowBmSize, "row bitmap", indexFile);
			byte[] rowBmBytes = new byte[rowBmSize];
			s.readFully(rowBmBytes);
			RoaringBitmap rowBm = new RoaringBitmap();
			rowBm.deserialize(new DataInputStream(new ByteArrayInputStream(rowBmBytes)));
			rowBm.forEach((int ri) -> {
				if (ri >= 0 && ri < rowCount)
					rowBitmaps[ri] = memberBm.clone();
			});
		}
		for (int i = 0; i < rowCount; i++) {
			if (rowBitmaps[i] != null)
				dest.put(rowKeys[i], rowBitmaps[i]);
		}
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
			dependencies.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
				pw.print(entry.getKey());
				pw.print('\t');
				pw.println(entry.getValue().stream().sorted().collect(java.util.stream.Collectors.joining(",")));
			});
		}
		PersistenceSupport.moveIntoPlace(tempFile, indexFile);
	}

	/**
	 * Loads a binary dependency index. Validates LZ4 framing and the inner
	 * {@code TORD} magic + version header.
	 * <p>
	 * Results are cached by (absolutePath, lastModifiedTime, fileSize). The cache
	 * is invalidated whenever {@link #save} rewrites the file, so re-reading after
	 * a write always returns a fresh instance.
	 */
	public static DependencyMap load(Path indexFile) throws IOException {
		Path loadPath = PersistenceSupport.resolveLoadPath(indexFile);
		validateCompressedFileSize(loadPath);

		// H12: check the mtime/size cache before doing any decompression
		FileTime mtime = Files.getLastModifiedTime(loadPath);
		long size = Files.size(loadPath);
		CacheKey key = new CacheKey(loadPath.toAbsolutePath(), mtime.toMillis(), size);
		DependencyMap cached = LOAD_CACHE.get(key);
		if (cached != null) {
			return cached;
		}

		int magic;
		try (DataInputStream peek = new DataInputStream(Files.newInputStream(loadPath))) {
			magic = peek.readInt();
		} catch (EOFException e) {
			throw new IOException("Index file is too small to be a valid binary index: " + loadPath);
		}
		if (magic != LZ4_MAGIC) {
			throw new IOException("Not a valid binary index (wrong magic bytes): " + loadPath);
		}
		DependencyMap result = loadBinary(loadPath);
		LOAD_CACHE.put(key, result);
		return result;
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
	/** Max size for legacy member-dep sections (old format can be very large). */
	private static final int MAX_LEGACY_MEMBER_BLOCK_SIZE = 256 * 1024 * 1024;

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
			// true when MEMBER_KEY_TABLE has not been seen yet and we've encountered
			// MEMBER_DEPS in old string-based format (pre-RoaringBitmap)
			boolean legacyMemberFormat = false;

			for (int si = 0; si < sectionCount; si++) {
				short sectionType = in.readShort();
				int sectionLength = in.readInt();
				// Use a larger limit for legacy member sections (pre-RoaringBitmap format
				// stored all member keys as UTF strings, producing very large sections).
				if (sectionType == SECTION_MEMBER_DEPS || sectionType == SECTION_METHOD_MEMBER_DEPS) {
					if (sectionLength < 0 || sectionLength > MAX_LEGACY_MEMBER_BLOCK_SIZE) {
						throw new IOException(
								"Section[" + sectionType + "] size " + sectionLength + " out of range in " + indexFile);
					}
				} else {
					checkSize(sectionLength, "Section[" + sectionType + "]", indexFile);
				}

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
									throw new IllegalStateException("Invalid test index " + ti
											+ " in member bitmap (valid: 0–" + (testCount - 1) + ")");
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
					case SECTION_MEMBER_KEY_TABLE -> {
						byte[] payload = new byte[sectionLength];
						in.readFully(payload);
						DataInputStream s = new DataInputStream(new ByteArrayInputStream(payload));
						int suffixCount = s.readInt();
						validateCount(suffixCount, "memberSuffixCount");
						String[] suffixes = new String[suffixCount];
						for (int i = 0; i < suffixCount; i++)
							suffixes[i] = s.readUTF();
						int keyCount = s.readInt();
						validateCount(keyCount, "memberKeyCount");
						ClassNameTrie finalTrie = trie;
						for (int i = 0; i < keyCount; i++) {
							int classId = ClassNameTrie.readVarInt(s);
							int suffixId = ClassNameTrie.readVarInt(s);
							if (classId < 0 || classId >= finalTrie.size())
								throw new IOException("Invalid class id " + classId + " in " + indexFile);
							if (suffixId < 0 || suffixId >= suffixCount)
								throw new IOException("Invalid suffix id " + suffixId + " in " + indexFile);
							// populate the dictionary in order — id must equal current size
							String memberKey = finalTrie.getName(classId) + suffixes[suffixId];
							map.memberKeyIndex.put(memberKey, map.memberKeyDictionary.size());
							map.memberKeyDictionary.add(memberKey);
						}
					}
					case SECTION_MEMBER_DEPS -> {
						byte[] payload = new byte[sectionLength];
						in.readFully(payload);
						DataInputStream s = new DataInputStream(new ByteArrayInputStream(payload));
						if (map.memberKeyDictionary.isEmpty()) {
							// Legacy format (pre-RoaringBitmap): testClass → Set<memberKey> as UTF strings.
							legacyMemberFormat = true;
							int memberEntryCount = s.readInt();
							validateCount(memberEntryCount, "memberEntryCount");
							for (int i = 0; i < memberEntryCount; i++) {
								String testClass = s.readUTF();
								int memberCount = s.readInt();
								validateCount(memberCount, "memberCount");
								Set<String> members = new HashSet<>(memberCount * 2);
								for (int j = 0; j < memberCount; j++)
									members.add(s.readUTF());
								map.putMemberDeps(testClass, members);
							}
						} else {
							readMemberSection(s, map.memberDepsMap, map.memberKeyDictionary.size(), indexFile);
						}
					}
					case SECTION_METHOD_MEMBER_DEPS -> {
						byte[] payload = new byte[sectionLength];
						in.readFully(payload);
						DataInputStream s = new DataInputStream(new ByteArrayInputStream(payload));
						if (legacyMemberFormat) {
							// Legacy format: methodKey → Set<memberKey> as UTF strings.
							int methodMemberCount = s.readInt();
							validateCount(methodMemberCount, "methodMemberCount");
							for (int i = 0; i < methodMemberCount; i++) {
								String methodKey = s.readUTF();
								int memberCount = s.readInt();
								validateCount(memberCount, "memberCount");
								Set<String> members = new HashSet<>(memberCount * 2);
								for (int j = 0; j < memberCount; j++)
									members.add(s.readUTF());
								map.putMethodMemberDeps(methodKey, members);
							}
						} else {
							readMemberSection(s, map.methodMemberDepsMap, map.memberKeyDictionary.size(), indexFile);
						}
					}
					case SECTION_TEST_MODULE_MAP -> {
						byte[] payload = new byte[sectionLength];
						in.readFully(payload);
						DataInputStream s = new DataInputStream(new ByteArrayInputStream(payload));
						int entryCount = s.readInt();
						validateCount(entryCount, "moduleMapEntryCount");
						for (int i = 0; i < entryCount; i++) {
							String testClass = s.readUTF();
							String moduleId = s.readUTF();
							map.testToModule.put(testClass, moduleId);
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
		// Sidecar: optional module.id file lets the offline learn path stamp every
		// .deps-derived test class with the owning moduleId, mirroring what the
		// online IndexCollectorServer path already does for v3/v4 payloads.
		Path moduleIdFile = depsDir.resolve("module.id");
		String fileModuleId = null;
		if (Files.exists(moduleIdFile)) {
			try {
				fileModuleId = Files.readString(moduleIdFile, java.nio.charset.StandardCharsets.UTF_8).trim();
				if (fileModuleId.isEmpty()) {
					fileModuleId = null;
				}
			} catch (IOException e) {
				if (log != null) {
					log.warn("[test-order] Could not read " + moduleIdFile + ": " + e.getMessage());
				}
			}
		}
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
						Set<String> deps = Files.readAllLines(file).stream().map(String::trim)
								.filter(s -> !s.isEmpty() && !s.startsWith("#"))
								.collect(Collectors.toCollection(HashSet::new));
						map.put(testClass, deps);
						if (fileModuleId != null) {
							map.putModule(testClass, fileModuleId);
						}
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
	// Not thread-safe: IndexCollectorServer and lifecycle shutdown each call this
	// from a single thread only.
	public static void mergeFromAgent(Path indexFile, Map<String, Set<String>> deps,
			Map<String, Set<String>> methodDeps, Map<String, Set<String>> memberDeps,
			Map<String, Set<String>> methodMemberDeps) throws IOException {
		mergeFromAgent(indexFile, deps, methodDeps, memberDeps, methodMemberDeps, Map.of());
	}

	/**
	 * Same as the four-map overload, but also records a
	 * {@code testFQCN -> moduleId} mapping so the index can later be filtered to
	 * only the tests owned by the current module. Empty/null moduleIds are ignored.
	 * Existing module entries are preserved unless overridden by a non-empty
	 * incoming value.
	 */
	public static void mergeFromAgent(Path indexFile, Map<String, Set<String>> deps,
			Map<String, Set<String>> methodDeps, Map<String, Set<String>> memberDeps,
			Map<String, Set<String>> methodMemberDeps, Map<String, String> testToModule) throws IOException {
		if (deps.isEmpty() && methodDeps.isEmpty() && memberDeps.isEmpty() && methodMemberDeps.isEmpty()
				&& (testToModule == null || testToModule.isEmpty())) {
			return; // nothing to merge
		}

		Path indexParent = indexFile.toAbsolutePath().getParent();
		if (indexParent != null) {
			Files.createDirectories(indexParent);
		}

		// Hold the file lock for the entire load+merge+save cycle so concurrent
		// stopAndMerge calls from parallel Gradle test tasks don't produce a torn
		// index.
		PersistenceSupport.withFileLock(indexFile, () -> {
			final DependencyMap map = Files.exists(indexFile) ? loadOrCreateFresh(indexFile) : new DependencyMap();

			// Invalidate derived caches before mutating dependencies.
			map.invertedIndex = null;
			map.depFrequencies = null;

			// Merge in-place without redundant copying; preserve unmodifiable-set invariant
			for (var entry : deps.entrySet()) {
				map.dependencies.merge(entry.getKey(), entry.getValue(), (existing, incoming) -> {
					Set<String> merged = new HashSet<>(existing.size() + incoming.size(), 1.0f);
					merged.addAll(existing);
					merged.addAll(incoming);
					return Collections.unmodifiableSet(merged);
				});
			}
			for (var entry : methodDeps.entrySet()) {
				map.methodDependencies.merge(entry.getKey(), entry.getValue(), (existing, incoming) -> {
					Set<String> merged = new HashSet<>(existing.size() + incoming.size(), 1.0f);
					merged.addAll(existing);
					merged.addAll(incoming);
					return Collections.unmodifiableSet(merged);
				});
			}
			for (var entry : memberDeps.entrySet()) {
				RoaringBitmap existing = map.memberDepsMap.get(entry.getKey());
				if (existing == null) {
					RoaringBitmap bm = new RoaringBitmap();
					for (String mk : entry.getValue())
						map.addMemberKey(bm, mk);
					map.memberDepsMap.put(entry.getKey(), bm);
				} else {
					for (String mk : entry.getValue())
						map.addMemberKey(existing, mk);
				}
			}
			for (var entry : methodMemberDeps.entrySet()) {
				RoaringBitmap existing = map.methodMemberDepsMap.get(entry.getKey());
				if (existing == null) {
					RoaringBitmap bm = new RoaringBitmap();
					for (String mk : entry.getValue())
						map.addMemberKey(bm, mk);
					map.methodMemberDepsMap.put(entry.getKey(), bm);
				} else {
					for (String mk : entry.getValue())
						map.addMemberKey(existing, mk);
				}
			}

			if (testToModule != null && !testToModule.isEmpty()) {
				for (var e : testToModule.entrySet()) {
					if (e.getKey() != null && e.getValue() != null && !e.getValue().isEmpty()) {
						map.putModule(e.getKey(), e.getValue());
					}
				}
			}

			// Use configured compression (system property), defaulting to FAST for
			// learn-mode
			LZ4Support.Compression compression = LZ4Support.Compression
					.fromString(System.getProperty("testorder.compression"));
			map.save(indexFile, compression);

			// warn if no actual dependencies were captured (common with groupId/package
			// mismatch)
			boolean allEmpty = map.dependencies.values().stream()
					.allMatch(d -> d.isEmpty() || (d.size() == 1 && map.dependencies.containsKey(d.iterator().next())));
			if (!map.dependencies.isEmpty() && allEmpty) {
				System.err.println("[test-order] WARNING: All test classes have zero non-self dependencies. "
						+ "If your source packages differ from the Maven groupId, "
						+ "set -Dtestorder.includePackages=your.package.prefix");
			}
			return null;
		});
	}

	/**
	 * Backward-compatible overload for older agent versions without member deps.
	 */
	public static void mergeFromAgent(Path indexFile, Map<String, Set<String>> deps,
			Map<String, Set<String>> methodDeps) throws IOException {
		mergeFromAgent(indexFile, deps, methodDeps, Map.of(), Map.of());
	}

	/** Backward-compatible overload for oldest agent versions. */
	public static void mergeFromAgent(Path indexFile, Map<String, Set<String>> deps) throws IOException {
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
	public static int aggregateFromDepsDirectory(Path depsDir, Path indexFile) throws IOException {
		return aggregateFromDepsDirectory(depsDir, indexFile, null);
	}

	/**
	 * Aggregates .deps files from a directory into a single index file. Uses file
	 * locking to prevent corruption in concurrent builds.
	 *
	 * @param depsDir
	 *            directory containing .deps files
	 * @param indexFile
	 *            target index file
	 * @param log
	 *            optional logger (null = use System.out)
	 * @return number of test classes in the aggregated map after merge, or 0 if
	 *         nothing was aggregated
	 */
	public static int aggregateFromDepsDirectory(Path depsDir, Path indexFile,
			me.bechberger.testorder.ops.PluginLog log) throws IOException {
		if (!Files.isDirectory(depsDir)) {
			return 0; // empty directory, skip
		}

		// Collect all dependency files to merge
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
			// Nothing to merge — still need to return current index size
			return Files.exists(indexFile) ? load(indexFile).size() : 0;
		}

		Path aggParent = indexFile.toAbsolutePath().getParent();
		if (aggParent != null) {
			Files.createDirectories(aggParent);
		}

		// Hold the file lock for the entire load+merge+save cycle so concurrent builds
		// (e.g. Maven -T N) don't produce a torn index.
		return PersistenceSupport.withFileLock(indexFile, () -> {
			// Load existing index as base, or start fresh — inside the lock so we read
			// the latest committed state.
			// Evict the cache entry first so any concurrent load() call re-reads from disk
			// rather than seeing the partially-mutated object during merge.
			evictCache(indexFile);
			DependencyMap map;
			if (Files.exists(indexFile)) {
				map = load(indexFile);
			} else {
				map = new DependencyMap();
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
					Set<String> deps;
					try (Stream<String> lines = Files.lines(depFile)) {
						deps = lines.filter(line -> !line.trim().isEmpty())
								.collect(Collectors.toCollection(HashSet::new));
					}
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
							map.dependencies.put(entry.getKey(),
									Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
						} else {
							// existing may be an unmodifiable set from loadBinary — copy into mutable set
							Set<String> merged = new HashSet<>(existing);
							merged.addAll(entry.getValue());
							map.dependencies.put(entry.getKey(), Collections.unmodifiableSet(merged));
						}
						depCount++;
					}
				} catch (java.util.concurrent.ExecutionException e) {
					System.err.println("[test-order] Error loading .deps file: " + e.getMessage());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					System.err.println("[test-order] Interrupted loading .deps file: " + e.getMessage());
				}
			}

			// Load .mdeps files (each represents one test method → deps)
			var mdepTasks = mdepsFiles.parallelStream().map(mdepFile -> pool.submit(() -> {
				try {
					java.util.List<String> lines = new ArrayList<>();
					try (Stream<String> s = Files.lines(mdepFile)) {
						s.forEach(lines::add);
					}
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
						Set<String> existing = new HashSet<>(
								map.methodDependencies.getOrDefault(entry.getKey(), Set.of()));
						existing.addAll(entry.getValue());
						map.methodDependencies.put(entry.getKey(), Collections.unmodifiableSet(existing));
						methodDepCount++;
					}
				} catch (java.util.concurrent.ExecutionException e) {
					System.err.println("[test-order] Error loading .mdeps file: " + e.getMessage());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					System.err.println("[test-order] Interrupted loading .mdeps file: " + e.getMessage());
				}
			}

			// Load .members files (each represents one test class -> member deps)
			var memberTasks = memberFiles.parallelStream().map(memberFile -> pool.submit(() -> {
				String testClass = memberFile.getFileName().toString();
				testClass = testClass.substring(0, testClass.length() - 8); // remove .members
				try {
					Set<String> members;
					try (Stream<String> lines = Files.lines(memberFile)) {
						members = lines.map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#"))
								.collect(Collectors.toCollection(HashSet::new));
					}
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
						RoaringBitmap existing = map.memberDepsMap.get(entry.getKey());
						if (existing == null) {
							map.putMemberDeps(entry.getKey(), entry.getValue());
						} else {
							for (String mk : entry.getValue())
								map.addMemberKey(existing, mk);
						}
						memberDepCount++;
					}
				} catch (java.util.concurrent.ExecutionException e) {
					System.err.println("[test-order] Error loading .members file: " + e.getMessage());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					System.err.println("[test-order] Interrupted loading .members file: " + e.getMessage());
				}
			}

			// Load .mmembers files (each represents one test method -> member deps)
			var mmemberTasks = methodMemberFiles.parallelStream().map(mmemberFile -> pool.submit(() -> {
				try {
					java.util.List<String> lines = new ArrayList<>();
					try (Stream<String> s = Files.lines(mmemberFile)) {
						s.forEach(lines::add);
					}
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
						RoaringBitmap existing = map.methodMemberDepsMap.get(entry.getKey());
						if (existing == null) {
							map.putMethodMemberDeps(entry.getKey(), entry.getValue());
						} else {
							for (String mk : entry.getValue())
								map.addMemberKey(existing, mk);
						}
						methodMemberDepCount++;
					}
				} catch (java.util.concurrent.ExecutionException e) {
					System.err.println("[test-order] Error loading .mmembers file: " + e.getMessage());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					System.err.println("[test-order] Interrupted loading .mmembers file: " + e.getMessage());
				}
			}

			// Invalidate lazy caches since dependencies were mutated directly.
			map.invertedIndex = null;
			map.depFrequencies = null;

			// Save aggregated index — already inside the outer file lock.
			map.save(indexFile);

			// Archive .deps files that have been merged so they don't re-accumulate.
			// Archiving is best-effort: a failure to move one file never blocks the build.
			archiveMergedDepsFiles(depsDir, depFiles, mdepsFiles, memberFiles, methodMemberFiles);

			long duration = System.currentTimeMillis() - startTime;
			String msg = "[test-order] Aggregated " + depCount + " test classes + " + methodDepCount
					+ " test methods + " + memberDepCount + " class-member sets + " + methodMemberDepCount
					+ " method-member sets from deps files in " + duration + "ms";
			if (log != null) {
				log.info(msg);
			} else {
				System.out.println(msg);
			}
			return map.size();
		});
	}

	/**
	 * Archives merged .deps/.mdeps/.members/.mmembers files into a
	 * {@code .archived} subdirectory of {@code depsDir}, with a timestamp prefix.
	 * Caps the archive at 50 entries by deleting the oldest. All failures are
	 * swallowed so a move error never blocks the build.
	 */
	private static void archiveMergedDepsFiles(Path depsDir, java.util.List<Path> depFiles,
			java.util.List<Path> mdepsFiles, java.util.List<Path> memberFiles, java.util.List<Path> methodMemberFiles) {
		var allFiles = new java.util.ArrayList<Path>();
		allFiles.addAll(depFiles);
		allFiles.addAll(mdepsFiles);
		allFiles.addAll(memberFiles);
		allFiles.addAll(methodMemberFiles);
		if (allFiles.isEmpty())
			return;

		Path archiveDir = depsDir.resolve(".archived");
		try {
			Files.createDirectories(archiveDir);
		} catch (IOException e) {
			return; // can't create archive dir — leave files in place
		}

		String stamp = java.time.LocalDateTime.now()
				.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
		for (Path p : allFiles) {
			try {
				Files.move(p, archiveDir.resolve(stamp + "-" + p.getFileName()),
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException ignored) {
				// best-effort: leave file in place if move fails
			}
		}

		// Prune archive to at most 50 files (delete oldest).
		try (var stream = Files.list(archiveDir)) {
			var archived = stream.sorted(java.util.Comparator.comparingLong(p -> {
				try {
					return java.nio.file.Files.getLastModifiedTime(p).toMillis();
				} catch (IOException e) {
					return 0L;
				}
			})).collect(java.util.stream.Collectors.toList());
			if (archived.size() > 50) {
				for (int i = 0; i < archived.size() - 50; i++) {
					try {
						Files.deleteIfExists(archived.get(i));
					} catch (IOException ignored) {
					}
				}
			}
		} catch (IOException ignored) {
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
