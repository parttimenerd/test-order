package me.bechberger.testorder.agent.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Maps class FQCNs and member names to small integer IDs for efficient
 * bitset-based tracking. Supports both class-level and member-level tracking
 * (className#memberName). Optimized for the common case where the map is stable
 * (no new classes being loaded).
 *
 * <p>
 * Read path is lock-free via {@link ConcurrentHashMap#get(Object)}; write path
 * synchronizes only when an entry is missing and needs registration.
 *
 * <p>
 * Supports two namespaces:
 * <ul>
 * <li><b>Classes</b> (IDs 0-8M): "com.example.Foo" → integer ID</li>
 * <li><b>Members</b> (IDs 8M+): "com.example.Foo#field" → integer ID
 * (offset)</li>
 * </ul>
 *
 * <p>
 * This enables atomic bitsets for recording test dependencies, eliminating
 * synchronization overhead in the hot path entirely.
 *
 * <p>
 * <b>Counter Strategy:</b> Uses VarHandle-backed counters.
 */
public class ClassIdMap {
	// VarHandle-backed counter implementation
	private static final class VarHandleIntCounter {
		private static final VarHandle VALUE_HANDLE;

		static {
			try {
				VALUE_HANDLE = MethodHandles.lookup().findVarHandle(VarHandleIntCounter.class, "value", int.class);
			} catch (ReflectiveOperationException e) {
				throw new ExceptionInInitializerError(e);
			}
		}

		@SuppressWarnings("unused")
		private volatile int value;

		private VarHandleIntCounter(int initialValue) {
			this.value = initialValue;
		}

		public int getAndIncrement() {
			return (int) VALUE_HANDLE.getAndAdd(this, 1);
		}

		public int decrementAndGet() {
			int previous = (int) VALUE_HANDLE.getAndAdd(this, -1);
			return previous - 1;
		}

		public int get() {
			return value;
		}

		public void set(int newValue) {
			VALUE_HANDLE.setVolatile(this, newValue);
		}
	}

	private static final ClassIdMap INSTANCE = new ClassIdMap();

	private static final int MEMBER_ID_OFFSET = 8_000_000; // members start at 8M
	private static final int CAPACITY_LIMIT = 16_000_000; // total capacity: 16K classes + 8M members
	// Medium project: ~2 000 instrumented application classes; 4 096 avoids any
	// rehash below that.
	private static final int INITIAL_CLASS_MAP_CAPACITY = 4_096 * 2;
	// Lazy member map: only allocated on first member registration to save ~8MB in
	// non-MEMBER modes.
	private static final int INITIAL_MEMBER_MAP_CAPACITY = 4_096;

	private final ConcurrentHashMap<String, Integer> classToId;
	private volatile ConcurrentHashMap<String, Integer> memberToId;
	private final VarHandleIntCounter nextClassId;
	private final VarHandleIntCounter nextMemberId;

	// AtomicReferenceArray reverse maps: written at registration time, O(1) lookup
	// with no rebuild. Grows by doubling when capacity is exceeded.
	private volatile AtomicReferenceArray<String> reverseClassNames;
	private volatile AtomicReferenceArray<String> reverseMemberNames;

	// Maps (memberId - MEMBER_ID_OFFSET) → classId. Populated at registration
	// time so flush can derive class bits from member bits without string parsing.
	private volatile int[] memberIdToClassId;

	private ClassIdMap() {
		this.classToId = new ConcurrentHashMap<>(INITIAL_CLASS_MAP_CAPACITY);
		// memberToId allocated lazily on first getOrRegisterMember() call
		this.memberToId = null;
		this.nextClassId = createCounter(0);
		this.nextMemberId = createCounter(MEMBER_ID_OFFSET);
		this.reverseClassNames = new AtomicReferenceArray<>(INITIAL_CLASS_MAP_CAPACITY);
		this.reverseMemberNames = null;
	}

	public static ClassIdMap getInstance() {
		return INSTANCE;
	}

	public static ClassIdMap createForBenchmark() {
		return new ClassIdMap();
	}

	private static VarHandleIntCounter createCounter(int initialValue) {
		return new VarHandleIntCounter(initialValue);
	}

	/**
	 * Get or register a class name lazily. Lock-free via
	 * {@link ConcurrentHashMap#computeIfAbsent}; no explicit synchronization
	 * needed.
	 */
	public int getOrRegisterClass(String className) {
		Integer id = classToId.computeIfAbsent(className, k -> {
			int newId = nextClassId.getAndIncrement();
			if (newId >= MEMBER_ID_OFFSET) {
				nextClassId.decrementAndGet();
				AgentLogger.log("[ClassIdMap] WARNING: Class ID capacity exceeded: " + k);
				return null; // don't store; caller gets -1
			}
			setReverseClass(newId, k);
			return newId;
		});
		return id != null ? id : -1;
	}

	private void setReverseClass(int id, String name) {
		AtomicReferenceArray<String> arr = reverseClassNames;
		if (id < arr.length()) {
			arr.set(id, name);
		} else {
			growAndSetReverseClass(id, name);
		}
	}

	private synchronized void growAndSetReverseClass(int id, String name) {
		AtomicReferenceArray<String> arr = reverseClassNames;
		if (id < arr.length()) {
			arr.set(id, name);
			return;
		}
		int newLen = Math.max(arr.length() * 2, id + 64);
		AtomicReferenceArray<String> grown = new AtomicReferenceArray<>(newLen);
		for (int i = 0; i < arr.length(); i++) {
			String v = arr.get(i);
			if (v != null)
				grown.set(i, v);
		}
		grown.set(id, name);
		reverseClassNames = grown;
	}

	/**
	 * Get or register a member name (format: "className#memberName"). Lock-free via
	 * {@link ConcurrentHashMap#computeIfAbsent}.
	 */
	public int getOrRegisterMember(String memberKey) {
		ConcurrentHashMap<String, Integer> map = memberToId;
		if (map == null) {
			synchronized (this) {
				map = memberToId;
				if (map == null) {
					map = new ConcurrentHashMap<>(INITIAL_MEMBER_MAP_CAPACITY);
					memberToId = map;
				}
			}
		}
		Integer id = map.computeIfAbsent(memberKey, k -> {
			int newId = nextMemberId.getAndIncrement();
			if (newId >= CAPACITY_LIMIT) {
				nextMemberId.decrementAndGet();
				AgentLogger.log("[ClassIdMap] WARNING: Member ID capacity exceeded: " + k);
				return null;
			}
			// Populate memberIdToClassId mapping
			int hashIdx = k.indexOf('#');
			if (hashIdx > 0) {
				String className = k.substring(0, hashIdx);
				int classId = getOrRegisterClass(className);
				if (classId >= 0) {
					ensureMemberToClassCapacity(newId - MEMBER_ID_OFFSET);
					memberIdToClassId[newId - MEMBER_ID_OFFSET] = classId;
				}
			}
			setReverseMember(newId - MEMBER_ID_OFFSET, k);
			return newId;
		});
		return id != null ? id : -1;
	}

	private synchronized void ensureMemberToClassCapacity(int index) {
		int[] arr = memberIdToClassId;
		if (arr == null) {
			arr = new int[Math.max(INITIAL_MEMBER_MAP_CAPACITY, index + 64)];
			java.util.Arrays.fill(arr, -1);
			memberIdToClassId = arr;
		} else if (index >= arr.length) {
			int newLen = Math.max(arr.length * 2, index + 64);
			int[] grown = java.util.Arrays.copyOf(arr, newLen);
			java.util.Arrays.fill(grown, arr.length, newLen, -1);
			memberIdToClassId = grown;
		}
	}

	private void setReverseMember(int adjustedId, String name) {
		AtomicReferenceArray<String> arr = reverseMemberNames;
		if (arr != null && adjustedId < arr.length()) {
			arr.set(adjustedId, name);
		} else {
			growAndSetReverseMember(adjustedId, name);
		}
	}

	private synchronized void growAndSetReverseMember(int adjustedId, String name) {
		AtomicReferenceArray<String> arr = reverseMemberNames;
		if (arr != null && adjustedId < arr.length()) {
			arr.set(adjustedId, name);
			return;
		}
		int newLen = arr == null
				? Math.max(INITIAL_MEMBER_MAP_CAPACITY, adjustedId + 64)
				: Math.max(arr.length() * 2, adjustedId + 64);
		AtomicReferenceArray<String> grown = new AtomicReferenceArray<>(newLen);
		if (arr != null) {
			for (int i = 0; i < arr.length(); i++) {
				String v = arr.get(i);
				if (v != null)
					grown.set(i, v);
			}
		}
		grown.set(adjustedId, name);
		reverseMemberNames = grown;
	}

	/**
	 * Get the class name for an ID. O(1) lookup via the incrementally maintained
	 * reverse array — no rebuild needed.
	 */
	public String getClassNameForId(int id) {
		if (id < 0 || id >= MEMBER_ID_OFFSET) {
			return null;
		}
		AtomicReferenceArray<String> rc = reverseClassNames;
		return (id < rc.length()) ? rc.get(id) : null;
	}

	/**
	 * Get the member name for an ID. O(1) lookup via the incrementally maintained
	 * reverse array — no rebuild needed.
	 */
	public String getMemberNameForId(int id) {
		if (id < MEMBER_ID_OFFSET) {
			return null;
		}
		AtomicReferenceArray<String> rm = reverseMemberNames;
		if (rm == null) {
			return null;
		}
		int adjusted = id - MEMBER_ID_OFFSET;
		return (adjusted < rm.length()) ? rm.get(adjusted) : null;
	}

	/**
	 * Pre-load class→id mapping from offline instrumentation. This bulk-loads all
	 * entries into the map and advances the next-ID counter past the highest loaded
	 * ID. Must be called before any tests run (single-threaded startup).
	 */
	public void bulkLoadClasses(java.util.Map<String, Integer> mapping) {
		int maxId = -1;
		for (var entry : mapping.entrySet()) {
			classToId.put(entry.getKey(), entry.getValue());
			setReverseClass(entry.getValue(), entry.getKey());
			if (entry.getValue() > maxId) {
				maxId = entry.getValue();
			}
		}
		// Advance counter past highest loaded ID (called once at startup, so loop is
		// fine)
		if (maxId >= 0) {
			while (nextClassId.get() <= maxId) {
				nextClassId.getAndIncrement();
			}
		}
	}

	/**
	 * Pre-load member→id mapping from offline instrumentation.
	 */
	public void bulkLoadMembers(java.util.Map<String, Integer> mapping) {
		if (mapping.isEmpty())
			return;
		// Ensure memberToId is allocated
		ConcurrentHashMap<String, Integer> map = memberToId;
		if (map == null) {
			synchronized (this) {
				map = memberToId;
				if (map == null) {
					map = new ConcurrentHashMap<>(mapping.size() * 2);
					memberToId = map;
				}
			}
		}
		int maxId = MEMBER_ID_OFFSET - 1;
		for (var entry : mapping.entrySet()) {
			map.put(entry.getKey(), entry.getValue());
			if (entry.getValue() > maxId) {
				maxId = entry.getValue();
			}
		}
		// Advance counter past highest loaded ID
		int target = maxId + 1;
		int current;
		while ((current = nextMemberId.get()) < target) {
			nextMemberId.getAndIncrement();
		}
		// Populate memberIdToClassId for all bulk-loaded members
		for (var entry : mapping.entrySet()) {
			int memberId = entry.getValue();
			String memberKey = entry.getKey();
			setReverseMember(memberId - MEMBER_ID_OFFSET, memberKey);
			int hashIdx = memberKey.indexOf('#');
			if (hashIdx > 0) {
				String className = memberKey.substring(0, hashIdx);
				int classId = getOrRegisterClass(className);
				if (classId >= 0) {
					ensureMemberToClassCapacity(memberId - MEMBER_ID_OFFSET);
					memberIdToClassId[memberId - MEMBER_ID_OFFSET] = classId;
				}
			}
		}
	}

	/**
	 * Get the classId that owns a given memberId. Returns -1 if not mapped. Used at
	 * flush time to derive class bits from member bits.
	 */
	public int getClassIdForMember(int memberId) {
		int[] arr = memberIdToClassId;
		if (arr == null)
			return -1;
		int idx = memberId - MEMBER_ID_OFFSET;
		if (idx < 0 || idx >= arr.length)
			return -1;
		return arr[idx];
	}

	/**
	 * Returns the next class ID to be assigned. The highest currently assigned
	 * class ID is {@code getNextClassId() - 1}. Useful for sizing snapshots that
	 * must capture every registered class (e.g. saving the reactor-wide map).
	 */
	public int getNextClassId() {
		return nextClassId.get();
	}

	/**
	 * Returns the next member ID to be assigned. The highest currently assigned
	 * member ID is {@code getNextMemberId() - 1}.
	 */
	public int getNextMemberId() {
		return nextMemberId.get();
	}

	/**
	 * Reset the map (for testing). Clears all registrations and resets counters.
	 */
	public void reset() {
		classToId.clear();
		if (memberToId != null) {
			memberToId.clear();
		}
		reverseClassNames = new AtomicReferenceArray<>(INITIAL_CLASS_MAP_CAPACITY);
		reverseMemberNames = null;
		memberIdToClassId = null;
		nextClassId.set(0);
		nextMemberId.set(MEMBER_ID_OFFSET);
	}

}
