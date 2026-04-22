package me.bechberger.testorder.benchmarks;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.openjdk.jmh.annotations.*;

import me.bechberger.testorder.agent.runtime.BitsetTracker;
import me.bechberger.testorder.agent.runtime.ClassIdMap;

/**
 * JMH Benchmarks for the runtime hot path.
 *
 * Uses the runtime ClassIdMap implementation (VarHandle-backed counters). Run
 * with: mvn clean install && java -jar target/benchmarks.jar
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
public class HotPathBenchmark {

	// Pre-registered class names for testing
	private static final String[] TEST_CLASSES = { "java.lang.Object", "java.lang.String", "java.util.ArrayList",
			"java.util.HashMap", "com.example.UserService", "com.example.Database", "com.example.NetworkClient",
			"org.springframework.context.ApplicationContext" };

	@State(Scope.Benchmark)
	public static class MapState {
		ClassIdMap classIdMap;
		BitsetTracker tracker;
		String[] memberKeys;
		int[] classIds;

		@Setup(Level.Trial)
		public void setup() {
			classIdMap = ClassIdMap.createForBenchmark();
			tracker = new BitsetTracker();
			memberKeys = new String[TEST_CLASSES.length];
			classIds = new int[TEST_CLASSES.length];

			for (int i = 0; i < TEST_CLASSES.length; i++) {
				memberKeys[i] = TEST_CLASSES[i] + "#field";
			}

			// Pre-register all test classes/members for hit-path benchmarks.
			for (int i = 0; i < TEST_CLASSES.length; i++) {
				classIds[i] = classIdMap.getOrRegisterClass(TEST_CLASSES[i]);
			}
			for (String memberKey : memberKeys) {
				classIdMap.getOrRegisterMember(memberKey);
			}
		}
	}

	@State(Scope.Thread)
	public static class RegistrationState {
		AtomicInteger sequence;

		@Setup(Level.Trial)
		public void setup() {
			sequence = new AtomicInteger();
		}

		int next() {
			return sequence.getAndIncrement();
		}
	}

	/**
	 * Benchmark ClassIdMap class-ID lookup hit-path performance.
	 */
	@Benchmark
	public int benchmarkClassIdMapLookup(MapState state) {
		int sum = 0;
		for (String className : TEST_CLASSES) {
			sum += state.classIdMap.getOrRegisterClass(className);
		}
		return sum;
	}

	/**
	 * Benchmark member ID lookup hit-path performance.
	 */
	@Benchmark
	public int benchmarkMemberIdLookup(MapState state) {
		int sum = 0;
		for (String memberKey : state.memberKeys) {
			sum += state.classIdMap.getOrRegisterMember(memberKey);
		}
		return sum;
	}

	/**
	 * Benchmark class-ID registration miss path (forces new IDs each invocation).
	 */
	@Benchmark
	public int benchmarkClassRegistrationMiss(MapState state, RegistrationState registrationState) {
		String className = "bench.dynamic.Class" + registrationState.next();
		return state.classIdMap.getOrRegisterClass(className);
	}

	/**
	 * Benchmark member-ID registration miss path (forces new IDs each invocation).
	 */
	@Benchmark
	public int benchmarkMemberRegistrationMiss(MapState state, RegistrationState registrationState) {
		int n = registrationState.next();
		String memberKey = "bench.dynamic.Class" + n + "#field" + n;
		return state.classIdMap.getOrRegisterMember(memberKey);
	}

	/**
	 * Benchmark BitsetTracker recording (lock-free bitset operations).
	 */
	@Benchmark
	public void benchmarkBitsetRecording(MapState state) {
		for (int i = 0; i < TEST_CLASSES.length; i++) {
			state.tracker.recordClass(i);
		}
	}

	/**
	 * Benchmark the combined hot path: ID lookup + recording.
	 */
	@Benchmark
	public int benchmarkCombinedHotPath(MapState state) {
		int sum = 0;
		BitsetTracker localTracker = new BitsetTracker();
		for (String className : TEST_CLASSES) {
			int classId = state.classIdMap.getOrRegisterClass(className);
			if (classId >= 0) {
				localTracker.recordClass(classId);
				sum += classId;
			}
		}
		return sum;
	}

	/**
	 * Benchmark upper-bound hot path with instrumentation-time pre-resolved IDs.
	 */
	@Benchmark
	public int benchmarkCombinedPreResolvedIds(MapState state) {
		int sum = 0;
		BitsetTracker localTracker = new BitsetTracker();
		for (int classId : state.classIds) {
			if (classId >= 0) {
				localTracker.recordClass(classId);
				sum += classId;
			}
		}
		return sum;
	}

	/**
	 * Benchmark converting bitset back to class names (called only at flush time).
	 */
	@Benchmark
	public int benchmarkBitsetConversion(MapState state) {
		BitsetTracker localTracker = new BitsetTracker();
		// Pre-register all test classes
		for (String className : TEST_CLASSES) {
			int classId = state.classIdMap.getOrRegisterClass(className);
			if (classId >= 0) {
				localTracker.recordClass(classId);
			}
		}
		return localTracker.toClassNames().size();
	}
}
