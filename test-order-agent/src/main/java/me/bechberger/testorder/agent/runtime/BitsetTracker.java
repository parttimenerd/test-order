package me.bechberger.testorder.agent.runtime;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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
				if ((w[index] & mask) != 0)
					return; // already set — most common case
				LONG_ARRAY.getAndBitwiseOr(w, index, mask);
				// If a concurrent grow published a new array between the snap and the OR,
				// our write landed on a now-orphaned array — replay against the live ref.
				// Grow is monotone so this terminates after at most one retry per grow.
				if (words != w) {
					record(index, mask);
					return;
				}
				advanceHighWater(index);
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
			// Snap words first, then highWater, so a concurrent grow can't make the
			// limit exceed the snapshot's array length.
			long[] w = words;
			int hw = highWater;
			if (hw < 0)
				return 0;
			int n = 0;
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
	// non-MEMBER modes.
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
	 * Derives class bits from recorded member bits. For each set member bit, looks
	 * up the owning classId and sets it in classWords. Called at flush time so the
	 * hot path only needs to record memberIds.
	 *
	 * <p>
	 * <b>Precondition:</b> recording must be quiesced before calling. The reads of
	 * {@code mw.highWater} and {@code mw.words} are not atomic, so a concurrent
	 * grow can leave bits past {@code highWater} unobserved.
	 */
	public void deriveClassBitsFromMembers() {
		WordArray mw = memberWords;
		if (mw == null)
			return;
		// Snap words first, then highWater: if a grow races, we keep an under-estimate
		// of valid range (limit clamps to w.length) instead of indexing past the end.
		long[] w = mw.words;
		int hw = mw.highWater;
		if (hw < 0)
			return;
		int limit = Math.min(hw + 1, w.length);
		ClassIdMap classIdMap = ClassIdMap.getInstance();
		for (int wi = 0; wi < limit; wi++) {
			long word = w[wi];
			if (word == 0)
				continue;
			int baseAdjusted = wi << 6;
			for (long bits = word; bits != 0; bits &= bits - 1) {
				int adjusted = baseAdjusted + Long.numberOfTrailingZeros(bits);
				int classId = classIdMap.getClassIdForMember(adjusted + MEMBER_ID_OFFSET);
				if (classId >= 0) {
					classWords.record(classId >>> 6, 1L << classId);
				}
			}
		}
	}

	/**
	 * Convert recorded class IDs to class names. Single-pass: iterates only up to
	 * the high-water mark and skips zero words.
	 */
	public Set<String> toClassNames() {
		int hw = classWords.highWater;
		if (hw < 0)
			return Collections.emptySet();
		ClassIdMap classIdMap = ClassIdMap.getInstance();
		long[] w = classWords.words;
		int limit = Math.min(hw + 1, w.length);
		// Pre-count bits to right-size the set and avoid resizes
		int estimated = 0;
		for (int wi = 0; wi < limit; wi++)
			estimated += Long.bitCount(w[wi]);
		Set<String> result = new HashSet<>(Math.max(16, estimated + (estimated >>> 2)));
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
	public Set<String> toMemberNames() {
		WordArray mw = memberWords;
		if (mw == null)
			return Collections.emptySet();
		int hw = mw.highWater;
		if (hw < 0)
			return Collections.emptySet();
		ClassIdMap classIdMap = ClassIdMap.getInstance();
		long[] w = mw.words;
		int limit = Math.min(hw + 1, w.length);
		int estimated = 0;
		for (int wi = 0; wi < limit; wi++)
			estimated += Long.bitCount(w[wi]);
		Set<String> result = new HashSet<>(Math.max(16, estimated + (estimated >>> 2)));
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

	/**
	 * Merge all bits from {@code other} into this tracker (bitwise OR). Used at
	 * flush time to fold per-method deps into the per-class tracker. Not
	 * thread-safe — call only when recording is stopped.
	 */
	public void mergeFrom(BitsetTracker other) {
		mergeWordArrays(classWords, other.classWords);
		WordArray otherMw = other.memberWords;
		if (otherMw != null) {
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
			mergeWordArrays(mw, otherMw);
		}
	}

	private static void mergeWordArrays(WordArray dst, WordArray src) {
		int hw = src.highWater;
		if (hw < 0)
			return;
		long[] srcW = src.words;
		int limit = Math.min(hw + 1, srcW.length);
		long[] dstW = dst.words;
		if (limit > dstW.length) {
			// grow dst — use growAndRecord with mask=0 to trigger resize without setting
			// bits
			dst.growAndRecord(limit - 1, 0L);
			dstW = dst.words;
		}
		// Atomic OR + replay-on-grow keeps merge correct even if a future caller
		// violates the "recording stopped" precondition (P3-M1 defence-in-depth).
		for (int i = 0; i < limit; i++) {
			long bits = srcW[i];
			if (bits == 0)
				continue;
			LONG_ARRAY.getAndBitwiseOr(dstW, i, bits);
			if (dst.words != dstW) {
				dstW = dst.words;
				LONG_ARRAY.getAndBitwiseOr(dstW, i, bits);
			}
		}
		// advance dst highWater via CAS so the merge is also safe under racy writers
		dst.advanceHighWater(hw);
	}

	// ── Raw bitset access (for binary protocol) ──────────────────────

	/**
	 * Returns the class-words array trimmed to highWater+1, or empty array if
	 * nothing recorded. For sending raw bitset data over the wire.
	 */
	public long[] getClassWordsRaw() {
		// Snap words first, then highWater — guarantees limit ≤ w.length even under
		// a concurrent grow.
		long[] w = classWords.words;
		int hw = classWords.highWater;
		if (hw < 0)
			return EMPTY_LONGS;
		int limit = Math.min(hw + 1, w.length);
		// If the live array is already the right size, return it directly
		// (safe at flush time when recording is stopped).
		return (w.length == limit) ? w : Arrays.copyOf(w, limit);
	}

	/** Effective length of class words (highWater + 1), or 0 if empty. */
	public int getClassWordsLength() {
		long[] w = classWords.words;
		int hw = classWords.highWater;
		if (hw < 0)
			return 0;
		return Math.min(hw + 1, w.length);
	}

	/**
	 * Direct reference to the class words backing array. Use only at flush time.
	 *
	 * <p>
	 * <b>Warning:</b> not paired with a length read — concurrent grows can leave
	 * this reference and {@link #getClassWordsLength()} pointing to different
	 * arrays. Prefer {@link #getClassWordsRaw()} which atomically clamps both.
	 */
	public long[] getClassWordsArray() {
		return classWords.words;
	}

	/**
	 * Returns the member-words array trimmed to highWater+1, or empty array if
	 * nothing recorded. For sending raw bitset data over the wire.
	 */
	public long[] getMemberWordsRaw() {
		WordArray mw = memberWords;
		if (mw == null)
			return EMPTY_LONGS;
		// Snap words first, then highWater — see getClassWordsRaw().
		long[] w = mw.words;
		int hw = mw.highWater;
		if (hw < 0)
			return EMPTY_LONGS;
		int limit = Math.min(hw + 1, w.length);
		return (w.length == limit) ? w : Arrays.copyOf(w, limit);
	}

	/** Effective length of member words (highWater + 1), or 0 if empty. */
	public int getMemberWordsLength() {
		WordArray mw = memberWords;
		if (mw == null)
			return 0;
		long[] w = mw.words;
		int hw = mw.highWater;
		if (hw < 0)
			return 0;
		return Math.min(hw + 1, w.length);
	}

	/**
	 * Direct reference to the member words backing array. Use only at flush time.
	 *
	 * <p>
	 * <b>Warning:</b> not paired with a length read — concurrent grows can leave
	 * this reference and {@link #getMemberWordsLength()} pointing to different
	 * arrays. Prefer {@link #getMemberWordsRaw()} which atomically clamps both.
	 */
	public long[] getMemberWordsArray() {
		WordArray mw = memberWords;
		return (mw != null) ? mw.words : EMPTY_LONGS;
	}

	private static final long[] EMPTY_LONGS = new long[0];
}
