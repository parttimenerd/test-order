package me.bechberger.testorder.agent.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests the load → grow → save pattern that {@code PrepareMojo} runs under a
 * {@link FileLock}, simulating the way parallel Maven module builds (Maven
 * {@code -T}) interact with the shared reactor class-id-map.
 *
 * <p>
 * Each "thread" represents one module's prepare goal: it acquires the file
 * lock, loads the current map into a fresh ClassIdMap, registers its own
 * classes (which may include new inner-class FQNs not in the pre-pass), then
 * saves the grown map back atomically.
 *
 * <p>
 * The invariant: after all parallel preparers finish, every class registered by
 * any preparer must be in the final saved map with a unique ID, and IDs
 * assigned by the reactor pre-pass must be unchanged.
 */
class ReactorClassIdMapConcurrencyTest {

	@TempDir
	Path tempDir;

	private Path mappingFile() {
		return tempDir.resolve(".test-order").resolve("class-id-map.bin");
	}

	private Path lockFile() {
		Path m = mappingFile();
		return m.resolveSibling(m.getFileName() + ".lock");
	}

	/** Per-JVM monitor mirroring PrepareMojo.REACTOR_MAP_INTRA_JVM_LOCK. */
	private static final Object INTRA_JVM_LOCK = new Object();

	/**
	 * Mimics PrepareMojo's load-grow-save under (intra-JVM monitor + FileLock). The
	 * intra-JVM monitor is necessary because FileLock is process-level, not
	 * thread-level — multiple threads in one Maven JVM (mvn -T) would otherwise hit
	 * OverlappingFileLockException.
	 */
	private void preparerSimulate(List<String> classNames) throws IOException {
		Path mapping = mappingFile();
		Path lock = lockFile();
		Files.createDirectories(mapping.getParent());
		synchronized (INTRA_JVM_LOCK) {
			try (RandomAccessFile raf = new RandomAccessFile(lock.toFile(), "rw");
					FileLock fl = raf.getChannel().lock()) {
				ClassIdMap local = ClassIdMap.createForBenchmark();
				if (Files.exists(mapping)) {
					ClassIdMapping existing = ClassIdMapping.load(mapping);
					local.bulkLoadClasses(existing.toClassMap());
				}
				for (String c : classNames) {
					local.getOrRegisterClass(c);
				}
				ClassIdMapping fresh = ClassIdMapping.fromClassIdMap(local, local.getNextClassId(),
						local.getNextMemberId());
				fresh.save(mapping);
			}
		}
	}

	@Test
	void parallelPreparersAllPreserveTheirClassesAndUniqueIds() throws Exception {
		// Pre-seed: reactor pre-pass writes top-level classes for every module.
		preparerSimulate(
				List.of("com.a.Library", "com.a.LibraryTest", "com.b.Service", "com.b.ServiceTest", "com.c.Helper"));

		Map<String, Integer> baseline = ClassIdMapping.load(mappingFile()).toClassMap();
		// Each "preparer" registers a few extra inner-class FQNs the pre-pass missed.
		List<List<String>> perPreparer = List.of(List.of("com.a.Library", "com.a.Library$Inner", "com.a.Library$1"),
				List.of("com.b.Service", "com.b.Service$Builder", "com.b.Service$Builder$Step"),
				List.of("com.c.Helper", "com.c.Helper$Listener"));

		ExecutorService pool = Executors.newFixedThreadPool(perPreparer.size());
		try {
			List<Future<Void>> futures = new java.util.ArrayList<>();
			for (List<String> names : perPreparer) {
				futures.add(pool.submit((Callable<Void>) () -> {
					preparerSimulate(names);
					return null;
				}));
			}
			for (Future<Void> f : futures) {
				f.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
			pool.awaitTermination(5, TimeUnit.SECONDS);
		}

		Map<String, Integer> finalMap = ClassIdMapping.load(mappingFile()).toClassMap();

		// Baseline IDs preserved
		for (Map.Entry<String, Integer> e : baseline.entrySet()) {
			assertEquals(e.getValue(), finalMap.get(e.getKey()),
					"baseline ID for " + e.getKey() + " must be unchanged");
		}
		// Every inner-class FQN any preparer registered survived
		for (List<String> names : perPreparer) {
			for (String c : names) {
				assertNotNull(finalMap.get(c), c + " missing from final map");
			}
		}
		// All IDs unique
		Set<Integer> ids = new HashSet<>(finalMap.values());
		assertEquals(finalMap.size(), ids.size(), "every class must have a unique ID");
	}

	@Test
	void sequentialPreparersGrowMonotonically() throws Exception {
		// Sequential equivalent — a regression check for the basic pattern.
		preparerSimulate(List.of("p.Top"));
		int sizeAfterFirst = ClassIdMapping.load(mappingFile()).toClassMap().size();
		preparerSimulate(List.of("p.Top", "p.Top$A"));
		int sizeAfterSecond = ClassIdMapping.load(mappingFile()).toClassMap().size();
		preparerSimulate(List.of("p.Top", "p.Top$A", "p.Top$B"));
		int sizeAfterThird = ClassIdMapping.load(mappingFile()).toClassMap().size();

		assertEquals(1, sizeAfterFirst);
		assertEquals(2, sizeAfterSecond);
		assertEquals(3, sizeAfterThird);
	}

	@Test
	void manyPreparersWithOverlapDontDuplicate() throws Exception {
		// All preparers register the SAME class — the result must contain it
		// exactly once, not N copies.
		String shared = "common.Shared";
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Void>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < 8; i++) {
				futures.add(pool.submit((Callable<Void>) () -> {
					preparerSimulate(List.of(shared));
					return null;
				}));
			}
			for (Future<Void> f : futures) {
				f.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
			pool.awaitTermination(5, TimeUnit.SECONDS);
		}

		Map<String, Integer> finalMap = ClassIdMapping.load(mappingFile()).toClassMap();
		assertEquals(1, finalMap.size(), "shared class must appear exactly once");
		assertEquals(0, (int) finalMap.get(shared));
	}

	@Test
	void preparersWritingDisjointClassesAllSurvive() throws Exception {
		// Stress: 16 preparers each registers 5 disjoint classes; final map must
		// have all 80 entries with unique IDs.
		int preparerCount = 16;
		int classesPerPreparer = 5;

		ExecutorService pool = Executors.newFixedThreadPool(preparerCount);
		try {
			List<Future<Void>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < preparerCount; i++) {
				final int idx = i;
				List<String> names = new java.util.ArrayList<>();
				for (int j = 0; j < classesPerPreparer; j++) {
					names.add("mod" + idx + ".Cls" + j);
				}
				futures.add(pool.submit((Callable<Void>) () -> {
					preparerSimulate(names);
					return null;
				}));
			}
			for (Future<Void> f : futures) {
				f.get(60, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
			pool.awaitTermination(5, TimeUnit.SECONDS);
		}

		Map<String, Integer> finalMap = ClassIdMapping.load(mappingFile()).toClassMap();
		assertEquals(preparerCount * classesPerPreparer, finalMap.size());
		Set<Integer> ids = new HashSet<>(finalMap.values());
		assertEquals(finalMap.size(), ids.size());

		// Sanity: every name we expected is present
		for (int i = 0; i < preparerCount; i++) {
			for (int j = 0; j < classesPerPreparer; j++) {
				assertNotNull(finalMap.get("mod" + i + ".Cls" + j));
			}
		}
	}

	@Test
	void lockReleaseHappensEvenOnException() throws Exception {
		// Preparer 1 acquires the lock and throws; preparer 2 must still be
		// able to take it. The try-with-resources on FileLock must release
		// even on exceptional exit.
		Path mapping = mappingFile();
		Files.createDirectories(mapping.getParent());

		try {
			synchronized (INTRA_JVM_LOCK) {
				try (RandomAccessFile raf = new RandomAccessFile(lockFile().toFile(), "rw");
						FileLock fl = raf.getChannel().lock()) {
					throw new RuntimeException("simulated mid-prepare failure");
				}
			}
		} catch (RuntimeException expected) {
			// expected
		}

		// Now another preparer takes the lock — must succeed
		preparerSimulate(List.of("after.Recovery"));

		Map<String, Integer> finalMap = ClassIdMapping.load(mapping).toClassMap();
		assertEquals(1, finalMap.size());
		assertNotNull(finalMap.get("after.Recovery"));
	}

	@Test
	void atomicSaveSurvivesPartialFile() throws Exception {
		// Simulate a corrupt class-id-map.bin (e.g. interrupted save from a
		// previous build with a non-atomic-move filesystem). The next preparer
		// must NOT crash; it should overwrite cleanly.
		Path mapping = mappingFile();
		Files.createDirectories(mapping.getParent());
		Files.write(mapping, new byte[]{0x01, 0x02, 0x03}); // garbage

		// Preparer wraps load in try/catch — but the simulator above rethrows
		// any IOException. PrepareMojo logs and continues. Verify by running
		// a preparer that doesn't pre-load (corrupt file is a non-fatal warning).
		assertTrue(Files.exists(mapping));
		try {
			ClassIdMapping.load(mapping);
		} catch (IOException expected) {
			// Confirms file is genuinely corrupt
		}

		// PrepareMojo's load-or-skip pattern. Repeat without pre-load:
		synchronized (INTRA_JVM_LOCK) {
			try (RandomAccessFile raf = new RandomAccessFile(lockFile().toFile(), "rw");
					FileLock fl = raf.getChannel().lock()) {
				ClassIdMap local = ClassIdMap.createForBenchmark();
				// (skip the load — corrupt file)
				local.getOrRegisterClass("recovery.Class");
				ClassIdMapping fresh = ClassIdMapping.fromClassIdMap(local, local.getNextClassId(),
						local.getNextMemberId());
				fresh.save(mapping);
			}
		}

		Map<String, Integer> finalMap = ClassIdMapping.load(mapping).toClassMap();
		assertNotNull(finalMap.get("recovery.Class"));
	}

	@Test
	void interleavedGrowsWithOverlappingFqnsNeverReassign() throws Exception {
		// Stress: 12 preparers, each registering a mix of (a) pre-existing
		// baseline FQNs that should keep their IDs, and (b) module-unique inner
		// class FQNs. Even under heavy concurrent load, no baseline ID may shift
		// — that would corrupt already-instrumented bytecode.
		preparerSimulate(List.of("base.X", "base.Y", "base.Z"));
		Map<String, Integer> baseline = ClassIdMapping.load(mappingFile()).toClassMap();

		int preparerCount = 12;
		ExecutorService pool = Executors.newFixedThreadPool(preparerCount);
		try {
			List<Future<Void>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < preparerCount; i++) {
				final int idx = i;
				futures.add(pool.submit((Callable<Void>) () -> {
					// Mix of baseline (overlap) and unique (new) FQNs.
					List<String> names = List.of("base.X", // baseline overlap
							"base.Y", // baseline overlap
							"mod" + idx + ".Inner$1", // unique anon
							"mod" + idx + ".Inner$Helper", // unique inner
							"mod" + idx + ".Top" // unique top
					);
					preparerSimulate(names);
					return null;
				}));
			}
			for (Future<Void> f : futures) {
				f.get(60, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
			pool.awaitTermination(5, TimeUnit.SECONDS);
		}

		Map<String, Integer> finalMap = ClassIdMapping.load(mappingFile()).toClassMap();

		// Baseline IDs unchanged.
		for (Map.Entry<String, Integer> e : baseline.entrySet()) {
			assertEquals(e.getValue(), finalMap.get(e.getKey()),
					"baseline ID for " + e.getKey() + " must not be reassigned under concurrent overlap");
		}
		// All 12 preparers' uniques present (3 each = 36).
		for (int i = 0; i < preparerCount; i++) {
			assertNotNull(finalMap.get("mod" + i + ".Inner$1"));
			assertNotNull(finalMap.get("mod" + i + ".Inner$Helper"));
			assertNotNull(finalMap.get("mod" + i + ".Top"));
		}
		// Total: 3 baseline + 36 unique = 39, all unique IDs.
		assertEquals(39, finalMap.size());
		Set<Integer> ids = new HashSet<>(finalMap.values());
		assertEquals(finalMap.size(), ids.size(), "every class must have a unique ID");
	}

	@Test
	void preparerOnlyOverlapsDoesNotGrowMap() throws Exception {
		// If every preparer registers ONLY classes already in the baseline, the
		// final map must have exactly the baseline size — no spurious growth
		// from re-registration churn.
		preparerSimulate(List.of("z.Alpha", "z.Beta", "z.Gamma"));
		int baselineSize = ClassIdMapping.load(mappingFile()).toClassMap().size();

		int preparerCount = 8;
		ExecutorService pool = Executors.newFixedThreadPool(preparerCount);
		try {
			List<Future<Void>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < preparerCount; i++) {
				futures.add(pool.submit((Callable<Void>) () -> {
					preparerSimulate(List.of("z.Alpha", "z.Beta", "z.Gamma"));
					return null;
				}));
			}
			for (Future<Void> f : futures) {
				f.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
			pool.awaitTermination(5, TimeUnit.SECONDS);
		}

		Map<String, Integer> finalMap = ClassIdMapping.load(mappingFile()).toClassMap();
		assertEquals(baselineSize, finalMap.size(), "all-overlap concurrent prepares must not grow the map");
	}

	@Test
	void hundredPreparersMixedOverlapAndUnique() throws Exception {
		// Heavier stress: 100 preparers, each adds 2 inner classes; first
		// preparer also seeds 50 baseline classes. Final map has 50 baseline +
		// 100 × 2 = 250 entries with unique IDs and stable baseline.
		List<String> baseline = new java.util.ArrayList<>();
		for (int i = 0; i < 50; i++) {
			baseline.add("base.B" + i);
		}
		preparerSimulate(baseline);
		Map<String, Integer> baselineMap = ClassIdMapping.load(mappingFile()).toClassMap();

		int preparerCount = 100;
		ExecutorService pool = Executors.newFixedThreadPool(8);
		try {
			List<Future<Void>> futures = new java.util.ArrayList<>();
			for (int i = 0; i < preparerCount; i++) {
				final int idx = i;
				futures.add(pool.submit((Callable<Void>) () -> {
					preparerSimulate(List.of("p" + idx + ".X$Inner", "p" + idx + ".X$$Lambda$0x" + idx));
					return null;
				}));
			}
			for (Future<Void> f : futures) {
				f.get(120, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}

		Map<String, Integer> finalMap = ClassIdMapping.load(mappingFile()).toClassMap();
		assertEquals(50 + preparerCount * 2, finalMap.size());
		Set<Integer> ids = new HashSet<>(finalMap.values());
		assertEquals(finalMap.size(), ids.size());
		// Baseline IDs intact.
		for (Map.Entry<String, Integer> e : baselineMap.entrySet()) {
			assertEquals(e.getValue(), finalMap.get(e.getKey()));
		}
	}

	@Test
	void preparerWithEmptyClassListPreservesMap() throws Exception {
		// A pom-only module's prepare goal might run with zero classes to
		// register (no compile output). The load-save cycle must be a no-op.
		preparerSimulate(List.of("seed.A", "seed.B"));
		Map<String, Integer> before = ClassIdMapping.load(mappingFile()).toClassMap();

		preparerSimulate(java.util.Collections.emptyList());
		Map<String, Integer> after = ClassIdMapping.load(mappingFile()).toClassMap();

		assertEquals(before.size(), after.size());
		for (Map.Entry<String, Integer> e : before.entrySet()) {
			assertEquals(e.getValue(), after.get(e.getKey()));
		}
	}

	@Test
	void veryLongFqnsRoundTripUnderConcurrency() throws Exception {
		// Boundary: nested generics + deeply-nested inner classes can produce
		// FQN strings hundreds of chars long. writeUTF caps at 65535 bytes;
		// realistic FQNs stay well below that, but verify no truncation.
		StringBuilder pkg = new StringBuilder("very.deeply.nested.package");
		for (int i = 0; i < 20; i++) {
			pkg.append(".sub").append(i);
		}
		String basePkg = pkg.toString();
		List<String> longFqns = new java.util.ArrayList<>();
		for (int i = 0; i < 5; i++) {
			longFqns.add(basePkg + ".Outer" + i + "$Inner$Deeper$Deepest$$Lambda$0x000000080" + i + "c01000");
		}

		ExecutorService pool = Executors.newFixedThreadPool(longFqns.size());
		try {
			List<Future<Void>> futures = new java.util.ArrayList<>();
			for (String fqn : longFqns) {
				futures.add(pool.submit((Callable<Void>) () -> {
					preparerSimulate(List.of(fqn));
					return null;
				}));
			}
			for (Future<Void> f : futures) {
				f.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
			pool.awaitTermination(5, TimeUnit.SECONDS);
		}

		Map<String, Integer> finalMap = ClassIdMapping.load(mappingFile()).toClassMap();
		for (String fqn : longFqns) {
			assertNotNull(finalMap.get(fqn), "long FQN survived round-trip: " + fqn);
		}
	}
}
