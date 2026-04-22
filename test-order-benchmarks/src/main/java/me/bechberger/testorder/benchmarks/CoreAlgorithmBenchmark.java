package me.bechberger.testorder.benchmarks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.SetCoverComputer;
import me.bechberger.testorder.TestOrderState;
import me.bechberger.testorder.TestScorer;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 4, time = 1)
public class CoreAlgorithmBenchmark {

	@State(Scope.Benchmark)
	public static class ScoringState {
		DependencyMap depMap;
		TestOrderState state;
		TestScorer scorer;
		String targetTest;
		Map<String, Set<String>> coverage;
		Set<String> changedClasses;

		@Setup(Level.Trial)
		public void setup() {
			depMap = new DependencyMap();
			state = new TestOrderState();
			changedClasses = new LinkedHashSet<>();
			for (int i = 0; i < 12; i++) {
				changedClasses.add("com.example.app.Component" + i);
			}

			List<String> testNames = new ArrayList<>();
			coverage = new LinkedHashMap<>();
			for (int i = 0; i < 200; i++) {
				String testClass = "com.example.tests.Test" + i;
				testNames.add(testClass);
				Set<String> deps = new LinkedHashSet<>();
				for (int j = 0; j < 12; j++) {
					deps.add("com.example.app.Component" + ((i + j) % 48));
				}
				depMap.put(testClass, deps);
				coverage.put(testClass, new LinkedHashSet<>(deps));
				state.recordDuration(testClass, 40 + (i % 20) * 10L);
				if (i % 11 == 0) {
					state.recordFailure(testClass);
				}
			}
			targetTest = "com.example.tests.Test42";
			scorer = new TestScorer.Builder(TestOrderState.ScoringWeights.DEFAULT, depMap, state, changedClasses,
					Set.of()).testClassNames(testNames).build();
		}
	}

	@State(Scope.Benchmark)
	public static class SerializationState {
		DependencyMap depMap;
		TestOrderState state;
		Path tempDir;
		Path indexFile;
		Path stateFile;

		@Setup(Level.Trial)
		public void setup() throws IOException {
			tempDir = Files.createTempDirectory("test-order-bench");
			depMap = new DependencyMap();
			state = new TestOrderState();
			for (int i = 0; i < 150; i++) {
				String testClass = "com.example.tests.SerializationTest" + i;
				depMap.put(testClass, Set.of("com.example.app.Service" + (i % 20),
						"com.example.app.Repository" + (i % 15), "com.example.app.Util" + (i % 10)));
				state.recordDuration(testClass, 25 + i);
				if (i % 9 == 0) {
					state.recordFailure(testClass);
				}
			}
			for (int run = 0; run < 8; run++) {
				List<TestOrderState.TestOutcome> outcomes = List
						.of(new TestOrderState.TestOutcome("com.example.tests.SerializationTest" + run, 8, false,
								run % 2 == 0, 2, 3, 1.0, true, false, run % 3 == 0, 0.2));
				state.addRunRecord(new TestOrderState.RunRecord(run, 150, run % 3 == 0 ? 1 : 0, run % 3 == 0 ? 0 : -1,
						0.8, outcomes));
			}
			indexFile = tempDir.resolve("deps.lz4");
			stateFile = tempDir.resolve("state.lz4");
			depMap.save(indexFile);
			state.save(stateFile);
		}
	}

	@Benchmark
	public void scoreTestClass(ScoringState state, Blackhole blackhole) {
		blackhole.consume(state.scorer.score(state.targetTest));
	}

	@Benchmark
	public void loadDependencyMap(SerializationState state, Blackhole blackhole) throws IOException {
		blackhole.consume(DependencyMap.load(state.indexFile));
	}

	@Benchmark
	public void computeSetCover(ScoringState state, Blackhole blackhole) {
		blackhole.consume(new SetCoverComputer<>(state.coverage, state.changedClasses).compute());
	}

	@Benchmark
	public void stateRoundTrip(SerializationState state, Blackhole blackhole) throws IOException {
		state.state.save(state.stateFile);
		blackhole.consume(TestOrderState.load(state.stateFile));
	}
}
