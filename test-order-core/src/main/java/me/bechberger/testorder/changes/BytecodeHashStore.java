package me.bechberger.testorder.changes;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import me.bechberger.testorder.LZ4Support;
import me.bechberger.testorder.PersistenceSupport;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;

/**
 * Compressed hash table over compiled {@code .class} files. Mirrors
 * {@link FileHashStore} but operates on bytecode, holding both per-class and
 * per-method SHA-256 fingerprints in a single file.
 *
 * <p>
 * Hash basis: a class is parsed with ASM and re-emitted via {@link ClassWriter}
 * after stripping debug info, stack frames, and the {@code SourceFile}
 * attribute. This means comment-only, line-number-only and debug-only source
 * edits do not change the bytecode hash. Per-method hashes cover the method's
 * opcodes + operands so a localised body change only invalidates that one
 * method's key.
 *
 * <p>
 * On-disk format (one file): LZ4-compressed lines.
 * <ul>
 * <li>{@code #CLASS:<fqcn>\t<hex-sha256>} — one per scanned class</li>
 * <li>{@code <methodKey>\t<hex-sha256>} — one per method, where
 * {@code methodKey} is {@code fqcn#methodName+descriptor}</li>
 * </ul>
 * Mirrors the {@code #FILE:} convention used by {@link MethodHashStore}.
 */
public class BytecodeHashStore {

	private static final HexFormat HEX_FORMAT = HexFormat.of();
	private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	});

	private final Map<String, String> classHashes; // FQCN → hex SHA-256
	private final Map<String, String> methodHashes; // fqcn#name+desc → hex SHA-256

	public BytecodeHashStore() {
		this.classHashes = new TreeMap<>();
		this.methodHashes = new TreeMap<>();
	}

	private BytecodeHashStore(Map<String, String> classHashes, Map<String, String> methodHashes) {
		this.classHashes = new TreeMap<>(classHashes);
		this.methodHashes = new TreeMap<>(methodHashes);
	}

	/**
	 * Scans a directory of compiled class files and computes per-class and
	 * per-method SHA-256 hashes. Library types ({@link ClassNameFilter}) are
	 * skipped defensively. Returns an empty store if {@code classesDir} is null or
	 * not a directory.
	 */
	public static BytecodeHashStore scan(Path classesDir) throws IOException {
		if (classesDir == null || !Files.isDirectory(classesDir)) {
			return new BytecodeHashStore();
		}
		List<Path> classFiles;
		try (Stream<Path> walk = Files.walk(classesDir)) {
			classFiles = walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".class")).toList();
		}
		record FileResult(String className, String classHash, Map<String, String> methodHashes) {
		}
		List<FileResult> results = classFiles.parallelStream().map(file -> {
			try {
				byte[] bytes = Files.readAllBytes(file);
				return hashClass(bytes);
			} catch (Exception e) {
				return null;
			}
		}).filter(Objects::nonNull).map(r -> new FileResult(r.className, r.classHash, r.methodHashes)).toList();

		Map<String, String> classMap = new TreeMap<>();
		Map<String, String> methodMap = new TreeMap<>();
		for (FileResult r : results) {
			if (r.className == null || r.className.isEmpty() || ClassNameFilter.isLibraryType(r.className)) {
				continue;
			}
			classMap.put(r.className, r.classHash);
			methodMap.putAll(r.methodHashes);
		}
		return new BytecodeHashStore(classMap, methodMap);
	}

	private record HashResult(String className, String classHash, Map<String, String> methodHashes) {
	}

	/**
	 * Computes the normalized class hash plus per-method hashes for a single class
	 * file's bytes. Visible for tests.
	 */
	static HashResult hashClass(byte[] bytes) {
		ClassReader cr = new ClassReader(bytes);

		// Per-method digests: hash opcodes + operands as ASM walks the method body.
		Map<String, String> perMethod = new TreeMap<>();
		String[] classNameHolder = new String[1];

		ClassWriter cw = new ClassWriter(0);
		ClassVisitor stripper = new ClassVisitor(Opcodes.ASM9, cw) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName,
					String[] interfaces) {
				classNameHolder[0] = name == null ? null : name.replace('/', '.');
				// Drop the source-file value (we drop the SourceFile attribute below too).
				super.visit(version, access, name, signature, superName, interfaces);
			}

			@Override
			public void visitSource(String source, String debug) {
				// Strip SourceFile + SourceDebugExtension.
			}

			@Override
			public MethodVisitor visitMethod(int access, String mname, String descriptor, String signature,
					String[] exceptions) {
				MethodVisitor downstream = super.visitMethod(access, mname, descriptor, signature, exceptions);
				if (classNameHolder[0] == null) {
					return downstream;
				}
				return new MethodHashingVisitor(downstream, classNameHolder[0] + "#" + mname + descriptor, perMethod);
			}

			@Override
			public void visitAttribute(Attribute attribute) {
				// Drop unknown / debug attributes — they don't affect semantics.
			}
		};
		cr.accept(stripper, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		byte[] normalized = cw.toByteArray();
		MessageDigest md = SHA256.get();
		md.reset();
		md.update(normalized);
		String classHash = HEX_FORMAT.formatHex(md.digest());

		String fqcn = classNameHolder[0];
		return new HashResult(fqcn, classHash, perMethod);
	}

	/**
	 * Saves the store to an LZ4-compressed text file. Atomic via temp-file +
	 * rename.
	 */
	public void save(Path hashFile) throws IOException {
		Path parent = hashFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tempFile = PersistenceSupport.temporarySibling(hashFile);
		try (LZ4FrameOutputStream lz4os = LZ4Support.frameOutputStreamHC(Files.newOutputStream(tempFile));
				PrintWriter pw = new PrintWriter(new OutputStreamWriter(lz4os))) {
			for (var e : classHashes.entrySet()) {
				pw.print("#CLASS:");
				pw.print(e.getKey());
				pw.print('\t');
				pw.println(e.getValue());
			}
			for (var e : methodHashes.entrySet()) {
				pw.print(e.getKey());
				pw.print('\t');
				pw.println(e.getValue());
			}
		}
		PersistenceSupport.moveIntoPlace(tempFile, hashFile);
	}

	/** Loads a previously saved bytecode hash store. */
	public static BytecodeHashStore load(Path hashFile) throws IOException {
		Map<String, String> classMap = new TreeMap<>();
		Map<String, String> methodMap = new TreeMap<>();
		Path loadPath = PersistenceSupport.resolveLoadPath(hashFile);
		try (LZ4FrameInputStream lz4is = LZ4Support.frameInputStream(Files.newInputStream(loadPath));
				BufferedReader br = new BufferedReader(new InputStreamReader(lz4is))) {
			String line;
			while ((line = br.readLine()) != null) {
				if (line.startsWith("#CLASS:")) {
					int tab = line.indexOf('\t', 7);
					if (tab > 7) {
						classMap.put(line.substring(7, tab), line.substring(tab + 1));
					}
				} else {
					int tab = line.indexOf('\t');
					if (tab > 0) {
						methodMap.put(line.substring(0, tab), line.substring(tab + 1));
					}
				}
			}
		}
		return new BytecodeHashStore(classMap, methodMap);
	}

	/**
	 * Returns FQCNs whose bytecode hash differs from {@code prev}, plus FQCNs
	 * present in only one of the stores (added or deleted).
	 */
	public Set<String> getChangedClasses(BytecodeHashStore prev) {
		Set<String> changed = new TreeSet<>();
		for (var e : classHashes.entrySet()) {
			String prior = prev.classHashes.get(e.getKey());
			if (prior == null || !prior.equals(e.getValue())) {
				changed.add(e.getKey());
			}
		}
		for (String prevClass : prev.classHashes.keySet()) {
			if (!classHashes.containsKey(prevClass)) {
				changed.add(prevClass);
			}
		}
		return changed;
	}

	/**
	 * Returns method keys whose bytecode hash differs, plus added/deleted keys.
	 * Returned keys are stripped of their JVM descriptor — so {@code Foo#bar(I)V}
	 * and {@code Foo#bar()V} both yield {@code Foo#bar}. This matches the format
	 * used by {@link StructuralChangeAnalyzer.ChangedMembers#changedMemberKeys()}.
	 */
	public Set<String> getChangedMethodKeys(BytecodeHashStore prev) {
		Set<String> changed = new TreeSet<>();
		for (var e : methodHashes.entrySet()) {
			String prior = prev.methodHashes.get(e.getKey());
			if (prior == null || !prior.equals(e.getValue())) {
				changed.add(stripDescriptor(e.getKey()));
			}
		}
		for (String prevKey : prev.methodHashes.keySet()) {
			if (!methodHashes.containsKey(prevKey)) {
				changed.add(stripDescriptor(prevKey));
			}
		}
		return changed;
	}

	/**
	 * {@code com.foo.Bar#baz(I)V} → {@code com.foo.Bar#baz}. The descriptor lives
	 * after the first {@code (} that appears after the {@code #}. If no descriptor
	 * is present (already stripped) the original key is returned.
	 */
	private static String stripDescriptor(String methodKeyWithDescriptor) {
		int hash = methodKeyWithDescriptor.indexOf('#');
		if (hash < 0) {
			return methodKeyWithDescriptor;
		}
		int paren = methodKeyWithDescriptor.indexOf('(', hash);
		if (paren < 0) {
			return methodKeyWithDescriptor;
		}
		return methodKeyWithDescriptor.substring(0, paren);
	}

	public Map<String, String> getClassHashes() {
		return Collections.unmodifiableMap(classHashes);
	}

	public Map<String, String> getMethodHashes() {
		return Collections.unmodifiableMap(methodHashes);
	}

	public boolean isEmpty() {
		return classHashes.isEmpty() && methodHashes.isEmpty();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof BytecodeHashStore other))
			return false;
		return classHashes.equals(other.classHashes) && methodHashes.equals(other.methodHashes);
	}

	@Override
	public int hashCode() {
		return Objects.hash(classHashes, methodHashes);
	}

	/**
	 * MethodVisitor that streams every opcode + operand into a SHA-256 digest. On
	 * {@code visitEnd} it writes the resulting hex digest into the supplied map
	 * keyed by {@code fqcn#name+descriptor}.
	 */
	private static final class MethodHashingVisitor extends MethodVisitor {

		private final String key;
		private final Map<String, String> sink;
		private final MessageDigest md;
		private final DataOutputStream dos;
		private final ByteArrayOutputStream baos;

		MethodHashingVisitor(MethodVisitor downstream, String key, Map<String, String> sink) {
			super(Opcodes.ASM9, downstream);
			this.key = key;
			this.sink = sink;
			this.md = SHA256.get();
			this.md.reset();
			this.baos = new ByteArrayOutputStream();
			this.dos = new DataOutputStream(baos);
		}

		private void writeStr(String s) {
			try {
				dos.writeUTF(s == null ? "" : s);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		private void writeInt(int v) {
			try {
				dos.writeInt(v);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		@Override
		public void visitInsn(int opcode) {
			writeInt(opcode);
			super.visitInsn(opcode);
		}

		@Override
		public void visitIntInsn(int opcode, int operand) {
			writeInt(opcode);
			writeInt(operand);
			super.visitIntInsn(opcode, operand);
		}

		@Override
		public void visitVarInsn(int opcode, int var) {
			writeInt(opcode);
			writeInt(var);
			super.visitVarInsn(opcode, var);
		}

		@Override
		public void visitTypeInsn(int opcode, String type) {
			writeInt(opcode);
			writeStr(type);
			super.visitTypeInsn(opcode, type);
		}

		@Override
		public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
			writeInt(opcode);
			writeStr(owner);
			writeStr(name);
			writeStr(descriptor);
			super.visitFieldInsn(opcode, owner, name, descriptor);
		}

		@Override
		public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
			writeInt(opcode);
			writeStr(owner);
			writeStr(name);
			writeStr(descriptor);
			writeInt(isInterface ? 1 : 0);
			super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
		}

		@Override
		public void visitInvokeDynamicInsn(String name, String descriptor, org.objectweb.asm.Handle bsm,
				Object... bsmArgs) {
			writeStr("indy");
			writeStr(name);
			writeStr(descriptor);
			writeStr(bsm.getOwner());
			writeStr(bsm.getName());
			writeStr(bsm.getDesc());
			writeInt(bsm.getTag());
			for (Object a : bsmArgs) {
				writeStr(String.valueOf(a));
			}
			super.visitInvokeDynamicInsn(name, descriptor, bsm, bsmArgs);
		}

		@Override
		public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
			writeInt(opcode);
			// Don't hash label identity (it varies between compilations); the relative
			// jump target is implicit in the opcode sequence.
			super.visitJumpInsn(opcode, label);
		}

		@Override
		public void visitLdcInsn(Object value) {
			writeStr("ldc");
			writeStr(String.valueOf(value));
			super.visitLdcInsn(value);
		}

		@Override
		public void visitIincInsn(int var, int increment) {
			writeStr("iinc");
			writeInt(var);
			writeInt(increment);
			super.visitIincInsn(var, increment);
		}

		@Override
		public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt,
				org.objectweb.asm.Label... labels) {
			writeStr("tsw");
			writeInt(min);
			writeInt(max);
			writeInt(labels.length);
			super.visitTableSwitchInsn(min, max, dflt, labels);
		}

		@Override
		public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys, org.objectweb.asm.Label[] labels) {
			writeStr("lsw");
			for (int k : keys) {
				writeInt(k);
			}
			super.visitLookupSwitchInsn(dflt, keys, labels);
		}

		@Override
		public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
			writeStr("manewa");
			writeStr(descriptor);
			writeInt(numDimensions);
			super.visitMultiANewArrayInsn(descriptor, numDimensions);
		}

		@Override
		public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
				org.objectweb.asm.Label handler, String type) {
			writeStr("tc");
			writeStr(type);
			super.visitTryCatchBlock(start, end, handler, type);
		}

		@Override
		public void visitEnd() {
			try {
				dos.flush();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			md.update(baos.toByteArray());
			sink.put(key, HEX_FORMAT.formatHex(md.digest()));
			super.visitEnd();
		}
	}
}
