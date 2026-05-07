package me.bechberger.testorder.agent.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;

/**
 * Lock-free tracker for class and member IDs using two compact atomic bitsets.
 *
 * <h3>Data layout</h3> Class IDs (0 .. MEMBER_ID_OFFSET-1) and member IDs
 * (MEMBER_ID_OFFSET ..) are stored in <em>separate</em> {@code volatile long[]}
 * arrays. Keeping them apart means member IDs are stored at their
 * <em>adjusted</em> index (memberId - MEMBER_ID_OFFSET), so both arrays stay
 * small:
 * <ul>
 * <li>classWords — 128 words = 8 192 class-ID slots = 1 KB</li>
 * <li>memberWords — 512 words = 32 768 member-ID slots = 4 KB</li>
 * </ul>
 * The old single-array design stored member IDs at raw offset 8 000 000
 * (wordIndex 125 000), causing a ~1 MB allocation on the first
 * {@code recordMember} call per tracker.
 *
 * <h3>Hot-path cost per call</h3>
 * <ol>
 * <li>One {@code volatile} array-reference read ({@code words} field).</li>
 * <li>Plain array-element read (speculative pre-check).</li>
 * <li>If bit already set → return immediately (no atomic instruction).</li>
 * <li>Otherwise → one {@code lock or} via
 * {@code VarHandle.getAndBitwiseOr}.</li>
 * </ol>
 * The speculative check is safe because bits are <em>monotone</em> (0 → 1
 * only): seeing a stale "1" is impossible; seeing a stale "0" at most causes an
 * unnecessary but harmless {@code lock or}.
 */
public class BitsetTracker {

	private static final VarHandle LONG_ARRAY = MethodHandles.arrayElementVarHandle(long[].class);

	static final int MEMBER_ID_OFFSET = 8_000_000;

	// 128 words × 64 = 8 192 class-ID slots (1 KB)
	private static final int INITIAL_CLASS_WORDS = 128;
	// 512 words × 64 = 32 768 member-ID slots (4 KB); covers 4 096 classes × ~8
	// members
	private static final int INITIAL_MEMBER_WORDS = 512;

	/**
	 * Atomic bitset backed by a volatile long[]. Growth replaces the array under a
	 * lock; reads and bit-sets are lock-free.
	 */
	private static final VarHandle HIGHWATER;
	static {
		try {
			HIGHWATER = MethodHandles.lookup().findVarHandle(WordArray.class, "highWater", int.class);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private static final class WordArray {
		volatile long[] words;
		/**
		 * High-water mark: highest word index written to. Updated atomically via CAS to
		 * prevent races where a smaller index overwrites a larger one. Used at flush
		 * time to limit iteration range.
		 */
		volatile int highWater = -1;

		WordArray(int initialSize) {
			words = new long[initialSize];
		}

		void record(int index, long mask) {
			long[] w = words; // volatile acquire-load
			if (index < w.length) {
				// Speculative: plain read first; skip lock-or if bit already set.
				// Safe because bits are monotone 0→1: seeing "set" is never a false positive.
				if ((w[index] & mask) == 0) {
					LONG_ARRAY.getAndBitwiseOr(w, index, mask);
					advanceHighWater(index);
				} else if (index > highWater) {
					advanceHighWater(index);
				}
				return;
			}
			growAndRecord(index, mask);
		}

		/** CAS loop to advance high-water mark; never regresses. */
		private void advanceHighWater(int index) {
			int cur;
			while (index > (cur = highWater)) {
				if (HIGHWATER.compareAndSet(this, cur, index))
					return;
			}
		}

		private synchronized void growAndRecord(int index, long mask) {
			long[] w = words;
			if (index < w.length) { // another thread grew it while we waited
				if ((w[index] & mask) == 0) {
					LONG_ARRAY.getAndBitwiseOr(w, index, mask);
				}
			} else {
				int newLen = Math.max(index + 1, w.length * 2);
				long[] grown = Arrays.copyOf(w, newLen);
				LONG_ARRAY.getAndBitwiseOr(grown, index, mask);
				words = grown; // volatile release-store: publishes new array
			}
			advanceHighWater(index);
		}

		int count() {
			int hw = highWater;
			if (hw < 0)
				return 0;
			int n = 0;
			long[] w = words;
			int limit = Math.min(hw + 1, w.length);
			for (int i = 0; i < limit; i++)
				n += Long.bitCount(w[i]);
			return n;
		}

		void clear() {
			Arrays.fill(words, 0L);
			highWater = -1;
		}
	}

	private final WordArray classWords = new WordArray(INITIAL_CLASS_WORDS);
	// Lazy: only allocated on first recordMember() call to save 4KB per tracker in
	// non-FULL_MEMBER modes.
	private volatile WordArray memberWords;

	// ── Recording ────────────────────────────────────────────────────

	public void recordClass(int classId) {
		if (classId < 0)
			return;
		classWords.record(classId >>> 6, 1L << classId);
	}

	public void recordMember(int memberId) {
		if (memberId < 0)
			return;
		WordArray mw = memberWords;
		if (mw == null) {
			synchronized (this) {
				mw = memberWords;
				if (mw == null) {
					mw = new WordArray(INITIAL_MEMBER_WORDS);
					memberWords = mw;
				}
			}
		}
		int adjusted = memberId - MEMBER_ID_OFFSET;
		mw.record(adjusted >>> 6, 1L << adjusted);
	}

	// ── Flush-time helpers ────────────────────────────────────────────

	/**
	 * Convert recorded class IDs to class names. Single-pass: iterates only up to
	 * the high-water mark and skips zero words.
	 */
	public java.util.Set<String> toClassNames() {
		int hw = classWords.highWater;
		if (hw < 0)
			return java.util.Collections.emptySet();
		ClassIdMap classIdMap = ClassIdMap.getInstance();
		long[] w = classWords.words;
		int limit = Math.min(hw + 1, w.length);
		// Pre-count bits to right-size the set and avoid resizes
		int estimated = 0;
		for (int wi = 0; wi < limit; wi++)
			estimated += Long.bitCount(w[wi]);
		java.util.Set<String> result = new java.util.LinkedHashSet<>(Math.max(16, estimated + (estimated >>> 2)));
		for (int wi = 0; wi < limit; wi++) {
			long word = w[wi];
			if (word == 0)
				continue;
			int baseId = wi << 6;
			for (long bits = word; bits != 0; bits &= bits - 1) {
				int id = baseId + Long.numberOfTrailingZeros(bits);
				String name = classIdMap.getClassNameForId(id);
				if (name != null)
					result.add(name);
			}
		}
		return result;
	}

	/**
	 * Convert recorded member IDs to member names. Single-pass: iterates only up to
	 * the high-water mark and skips zero words.
	 */
	public java.util.Set<String> toMemberNames() {
		WordArray mw = memberWords;
		if (mw == null)
			return java.util.Collections.emptySet();
		int hw = mw.highWater;
		if (hw < 0)
			return java.util.Collections.emptySet();
		ClassIdMap classIdMap = ClassIdMap.getInstance();
		long[] w = mw.words;
		int limit = Math.min(hw + 1, w.length);
		int estimated = 0;
		for (int wi = 0; wi < limit; wi++)
			estimated += Long.bitCount(w[wi]);
		java.util.Set<String> result = new java.util.LinkedHashSet<>(Math.max(16, estimated + (estimated >>> 2)));
		for (int wi = 0; wi < limit; wi++) {
			long word = w[wi];
			if (word == 0)
				continue;
			int baseAdjusted = wi << 6;
			for (long bits = word; bits != 0; bits &= bits - 1) {
				int adjusted = baseAdjusted + Long.numberOfTrailingZeros(bits);
				String name = classIdMap.getMemberNameForId(adjusted + MEMBER_ID_OFFSET);
				if (name != null)
					result.add(name);
			}
		}
		return result;
	}

	/** Population count of all recorded class and member IDs. */
	public int count() {
		WordArray mw = memberWords;
		return classWords.count() + (mw != null ? mw.count() : 0);
	}

	/** Clear all recorded IDs (for reuse). */
	public void clear() {
		classWords.clear();
		WordArray mw = memberWords;
		if (mw != null)
			mw.clear();
	}
}
