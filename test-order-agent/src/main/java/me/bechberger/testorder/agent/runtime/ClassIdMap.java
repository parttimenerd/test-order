package me.bechberger.testorder.agent.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ConcurrentHashMap;

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
	}

	private static final ClassIdMap INSTANCE = new ClassIdMap();

	private static final int MEMBER_ID_OFFSET = 8_000_000; // members start at 8M
	private static final int CAPACITY_LIMIT = 16_000_000; // total capacity: 16K classes + 8M members
	// Medium project: ~2 000 instrumented application classes; 4 096 avoids any
	// rehash below that.
	private static final int INITIAL_CLASS_MAP_CAPACITY = 4_096 * 2;
	// Methods + fields per class ~8 on average → 4 096 classes × 8 = ~32 768; 32
	// 768 avoids resize.
	private static final int INITIAL_MEMBER_MAP_CAPACITY = 32_768 * 4;

	private final ConcurrentHashMap<String, Integer> classToId;
	private final ConcurrentHashMap<String, Integer> memberToId;
	private final VarHandleIntCounter nextClassId;
	private final VarHandleIntCounter nextMemberId;

	// Reverse maps built lazily on first lookup (flush time only).
	// Using plain String[] arrays indexed by ID avoids Integer autoboxing
	// and is faster than ConcurrentHashMap<Integer, String>.
	private volatile String[] reverseClassNames;
	private volatile String[] reverseMemberNames;

	private ClassIdMap() {
		this.classToId = new ConcurrentHashMap<>(INITIAL_CLASS_MAP_CAPACITY);
		this.memberToId = new ConcurrentHashMap<>(INITIAL_MEMBER_MAP_CAPACITY);
		this.nextClassId = createCounter(0);
		this.nextMemberId = createCounter(MEMBER_ID_OFFSET);
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
			reverseClassNames = null; // invalidate cached reverse array
			return newId;
		});
		return id != null ? id : -1;
	}

	/**
	 * Get or register a member name (format: "className#memberName"). Lock-free via
	 * {@link ConcurrentHashMap#computeIfAbsent}.
	 */
	public int getOrRegisterMember(String memberKey) {
		Integer id = memberToId.computeIfAbsent(memberKey, k -> {
			int newId = nextMemberId.getAndIncrement();
			if (newId >= CAPACITY_LIMIT) {
				nextMemberId.decrementAndGet();
				AgentLogger.log("[ClassIdMap] WARNING: Member ID capacity exceeded: " + k);
				return null;
			}
			reverseMemberNames = null; // invalidate cached reverse array
			return newId;
		});
		return id != null ? id : -1;
	}

	/**
	 * Get the class name for an ID via lazy reverse array. The reverse array is
	 * built on first call and invalidated when new classes register.
	 */
	public String getClassNameForId(int id) {
		if (id < 0 || id >= MEMBER_ID_OFFSET) {
			return null;
		}
		String[] rc = reverseClassNames;
		if (rc == null || id >= rc.length) {
			rc = rebuildClassReverse();
		}
		return (id < rc.length) ? rc[id] : null;
	}

	private synchronized String[] rebuildClassReverse() {
		// Double-check: another thread may have rebuilt while we waited
		String[] existing = reverseClassNames;
		if (existing != null && nextClassId.get() <= existing.length) {
			return existing;
		}
		// Add safety margin: a concurrent registration may have incremented
		// nextClassId after we snapshot the map, so over-size the array.
		int size = nextClassId.get() + 64;
		String[] arr = new String[size];
		for (var e : classToId.entrySet()) {
			int idx = e.getValue();
			if (idx >= 0 && idx < size)
				arr[idx] = e.getKey();
		}
		reverseClassNames = arr;
		return arr;
	}

	/**
	 * Get the member name for an ID via lazy reverse array.
	 */
	public String getMemberNameForId(int id) {
		if (id < MEMBER_ID_OFFSET) {
			return null;
		}
		int adjusted = id - MEMBER_ID_OFFSET;
		String[] rm = reverseMemberNames;
		if (rm == null || adjusted >= rm.length) {
			rm = rebuildMemberReverse();
		}
		return (adjusted < rm.length) ? rm[adjusted] : null;
	}

	private synchronized String[] rebuildMemberReverse() {
		// Double-check: another thread may have rebuilt while we waited
		String[] existing = reverseMemberNames;
		int currentCount = nextMemberId.get() - MEMBER_ID_OFFSET;
		if (existing != null && currentCount <= existing.length) {
			return existing;
		}
		int size = currentCount + 64; // safety margin for concurrent registrations
		if (size <= 0)
			return new String[0];
		String[] arr = new String[size];
		for (var e : memberToId.entrySet()) {
			int idx = e.getValue() - MEMBER_ID_OFFSET;
			if (idx >= 0 && idx < size)
				arr[idx] = e.getKey();
		}
		reverseMemberNames = arr;
		return arr;
	}

}
