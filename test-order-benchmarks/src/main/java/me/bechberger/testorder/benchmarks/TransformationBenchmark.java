package me.bechberger.testorder.benchmarks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.objectweb.asm.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import me.bechberger.testorder.agent.AsmClassTransformer;
import me.bechberger.testorder.agent.IntelligentClassFilter;
import me.bechberger.testorder.agent.runtime.ClassIdMap;

/**
 * JMH benchmark measuring class transformation overhead.
 *
 * Measures: 1. Raw ASM parsing (ClassReader + empty ClassVisitor) 2. ASM
 * parsing + ClassWriter (full copy) 3. Full instrumentation (our
 * AsmClassTransformer) 4. Filter decision cost
 *
 * This isolates whether the bottleneck is: - ASM parsing of bytecode - ASM
 * bytecode generation (ClassWriter) - Our instrumentation injection - Filter
 * evaluation
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 2, jvmArgs = {"-Xmx512m"})
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
public class TransformationBenchmark {

	@State(Scope.Benchmark)
	public static class ClassFileState {
		/** Real .class file bytes from the classpath */
		byte[][] classFiles;
		String[] classNames;

		/** A larger set for "bulk" benchmarks simulating a real project */
		byte[][] bulkClassFiles;
		String[] bulkClassNames;

		ClassIdMap classIdMap;
		IntelligentClassFilter filter;

		@Setup(Level.Trial)
		public void setup() throws Exception {
			classIdMap = ClassIdMap.createForBenchmark();

			// Build a filter that accepts "com/sap/cloud/sdk" classes (simulating real
			// project)
			filter = new IntelligentClassFilter.Builder().strategy(IntelligentClassFilter.Strategy.SMART)
					.explicitInclude("com.example").explicitInclude("me.bechberger").useHeuristics(true).build();

			// Load some real class files from classpath
			List<byte[]> files = new ArrayList<>();
			List<String> names = new ArrayList<>();

			// Use classes from our own project as representative workload
			String[] classesToLoad = {"org.objectweb.asm.ClassReader", "org.objectweb.asm.ClassWriter",
					"org.objectweb.asm.ClassVisitor", "org.objectweb.asm.MethodVisitor",
					"org.openjdk.jmh.annotations.Benchmark", "me.bechberger.testorder.agent.runtime.ClassIdMap",
					"me.bechberger.testorder.agent.runtime.BitsetTracker",
					"me.bechberger.testorder.agent.runtime.UsageStore",
					"me.bechberger.testorder.agent.IntelligentClassFilter",
					"me.bechberger.testorder.agent.AsmClassTransformer",};

			for (String cls : classesToLoad) {
				byte[] bytes = loadClassBytes(cls);
				if (bytes != null) {
					files.add(bytes);
					names.add(cls.replace('.', '/'));
				}
			}

			classFiles = files.toArray(new byte[0][]);
			classNames = names.toArray(new String[0]);

			// For bulk benchmarks: duplicate to simulate 200 classes
			bulkClassFiles = new byte[200][];
			bulkClassNames = new String[200];
			for (int i = 0; i < 200; i++) {
				bulkClassFiles[i] = classFiles[i % classFiles.length];
				bulkClassNames[i] = classNames[i % classNames.length] + "_" + i;
			}
		}

		private byte[] loadClassBytes(String className) {
			String path = className.replace('.', '/') + ".class";
			try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
				if (is == null)
					return null;
				return is.readAllBytes();
			} catch (IOException e) {
				return null;
			}
		}
	}

	@State(Scope.Thread)
	public static class ThreadState {
		int index;

		@Setup(Level.Iteration)
		public void reset() {
			index = 0;
		}
	}

	// ─── Individual class benchmarks ───────────────────────────────────

	/**
	 * Baseline: Just parse the class (ClassReader creation + accept with empty
	 * visitor)
	 */
	@Benchmark
	public void asmParseOnly(ClassFileState state, ThreadState ts, Blackhole bh) {
		int i = ts.index++ % state.classFiles.length;
		ClassReader cr = new ClassReader(state.classFiles[i]);
		cr.accept(new ClassVisitor(Opcodes.ASM9) {
		}, 0);
		bh.consume(cr);
	}

	/**
	 * ASM parse + write (copy without modification)
	 */
	@Benchmark
	public void asmParsePlusWrite(ClassFileState state, ThreadState ts, Blackhole bh) {
		int i = ts.index++ % state.classFiles.length;
		ClassReader cr = new ClassReader(state.classFiles[i]);
		ClassWriter cw = new ClassWriter(cr, 0);
		cr.accept(cw, 0);
		bh.consume(cw.toByteArray());
	}

	/**
	 * ASM parse + write with COMPUTE_MAXS (expensive stack map computation)
	 */
	@Benchmark
	public void asmParsePlusWriteComputeMaxs(ClassFileState state, ThreadState ts, Blackhole bh) {
		int i = ts.index++ % state.classFiles.length;
		ClassReader cr = new ClassReader(state.classFiles[i]);
		ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
		cr.accept(cw, 0);
		bh.consume(cw.toByteArray());
	}

	/**
	 * Full instrumentation: our visitor that adds recordUsageIdFast calls
	 */
	@Benchmark
	public void fullInstrumentation(ClassFileState state, ThreadState ts, Blackhole bh) {
		int i = ts.index++ % state.classFiles.length;
		byte[] classFile = state.classFiles[i];
		String className = state.classNames[i];

		ClassReader cr = new ClassReader(classFile);
		int classId = state.classIdMap.getOrRegisterClass(className.replace('/', '.'));
		int[] ids = {classId};

		ClassWriter cw = new ClassWriter(cr, 0);
		cr.accept(new InstrumentingVisitor(cw, ids), 0);
		bh.consume(cw.toByteArray());
	}

	/**
	 * Filter evaluation only (no transformation)
	 */
	@Benchmark
	public void filterEvaluation(ClassFileState state, ThreadState ts, Blackhole bh) {
		int i = ts.index++ % state.bulkClassNames.length;
		bh.consume(state.filter.shouldInstrument(state.bulkClassNames[i]));
	}

	/**
	 * ClassIdMap registration (simulating first-time class encounter)
	 */
	@Benchmark
	public void classIdRegistration(ClassFileState state, ThreadState ts, Blackhole bh) {
		int i = ts.index++;
		bh.consume(state.classIdMap.getOrRegisterClass("com.example.bench.Class" + i));
	}

	// ─── Bulk benchmarks (simulating a fork loading 200 classes) ───────

	/**
	 * Bulk: transform 200 classes (simulates one fork's class loading)
	 */
	@Benchmark
	@BenchmarkMode(Mode.SingleShotTime)
	@OutputTimeUnit(TimeUnit.MILLISECONDS)
	@Fork(value = 10)
	@Warmup(iterations = 0)
	@Measurement(iterations = 1)
	public void bulkTransform200Classes(ClassFileState state, Blackhole bh) {
		for (int i = 0; i < state.bulkClassFiles.length; i++) {
			ClassReader cr = new ClassReader(state.bulkClassFiles[i]);
			int classId = state.classIdMap.getOrRegisterClass(state.bulkClassNames[i].replace('/', '.'));
			int[] ids = {classId};
			ClassWriter cw = new ClassWriter(cr, 0);
			cr.accept(new InstrumentingVisitor(cw, ids), 0);
			bh.consume(cw.toByteArray());
		}
	}

	/**
	 * Bulk: parse-only 200 classes (baseline for bulk)
	 */
	@Benchmark
	@BenchmarkMode(Mode.SingleShotTime)
	@OutputTimeUnit(TimeUnit.MILLISECONDS)
	@Fork(value = 10)
	@Warmup(iterations = 0)
	@Measurement(iterations = 1)
	public void bulkParseOnly200Classes(ClassFileState state, Blackhole bh) {
		for (int i = 0; i < state.bulkClassFiles.length; i++) {
			ClassReader cr = new ClassReader(state.bulkClassFiles[i]);
			ClassWriter cw = new ClassWriter(cr, 0);
			cr.accept(cw, 0);
			bh.consume(cw.toByteArray());
		}
	}

	// ─── Helper: minimal instrumenting visitor ─────────────────────────

	/**
	 * Simulates what AsmClassTransformer does: inject recordUsageIdFast at method
	 * entry
	 */
	private static class InstrumentingVisitor extends ClassVisitor {
		private static final String USAGE_STORE = "me/bechberger/testorder/agent/runtime/UsageStore";
		private final int[] classIds;

		InstrumentingVisitor(ClassVisitor cv, int[] classIds) {
			super(Opcodes.ASM9, cv);
			this.classIds = classIds;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
			MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
			if (mv == null)
				return null;
			if ((access
					& (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) {
				return mv;
			}
			return new MethodVisitor(Opcodes.ASM9, mv) {
				@Override
				public void visitCode() {
					super.visitCode();
					for (int classId : classIds) {
						if (classId >= -1 && classId <= 5) {
							mv.visitInsn(Opcodes.ICONST_0 + classId);
						} else if (classId >= Byte.MIN_VALUE && classId <= Byte.MAX_VALUE) {
							mv.visitIntInsn(Opcodes.BIPUSH, classId);
						} else if (classId >= Short.MIN_VALUE && classId <= Short.MAX_VALUE) {
							mv.visitIntInsn(Opcodes.SIPUSH, classId);
						} else {
							mv.visitLdcInsn(classId);
						}
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, USAGE_STORE, "recordUsageIdFast", "(I)V", false);
					}
				}

				@Override
				public void visitMaxs(int maxStack, int maxLocals) {
					super.visitMaxs(maxStack + 1, maxLocals);
				}
			};
		}
	}
}
