package me.bechberger.testorder.changes;

import static org.objectweb.asm.Opcodes.ASM9;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Builds a reverse call graph from compiled {@code .class} files and uses it to
 * expand a set of {@link StructuralChangeAnalyzer.ChangedMembers} transitively.
 *
 * <p>
 * When a method that was not covered at learn time now calls a changed method
 * (or reads a changed static field, or references it via lambda/method
 * reference), this expander finds the caller and adds it (and its owning class)
 * to the changed set so dependent tests are not missed.
 *
 * <p>
 * Edge sources captured:
 * <ul>
 * <li>{@code INVOKEVIRTUAL / STATIC / INTERFACE / SPECIAL} (regular calls and
 * constructors)</li>
 * <li>{@code INVOKEDYNAMIC} bootstrap target (lambdas and method
 * references)</li>
 * <li>{@code GETSTATIC / PUTSTATIC} (static field reads/writes)</li>
 * <li>{@code GETFIELD / PUTFIELD} (instance field reads/writes; recorded as
 * class-level edges to {@code owner#<class>})</li>
 * <li>{@code NEW / ANEWARRAY / CHECKCAST / INSTANCEOF / MULTIANEWARRAY} (type
 * references)</li>
 * <li>{@code LDC} of a class literal ({@code Foo.class})</li>
 * <li>try/catch handler exception types</li>
 * <li>annotations on class / method / field / parameter (edge to the annotation
 * type)</li>
 * <li>method descriptor: every non-library type appearing in argument and
 * return positions, plus declared exceptions</li>
 * <li>generic signatures on methods and fields (best-effort)</li>
 * <li>declared field types on each class</li>
 * <li>direct supertype and interfaces — every method of a subclass becomes
 * reachable from its base type's class marker</li>
 * </ul>
 *
 * <p>
 * The expander also models <b>virtual dispatch</b>:
 * <ul>
 * <li>If {@code T#m} is changed, every override {@code S#m} on a subtype
 * {@code S extends T} is treated as changed too.</li>
 * <li>When a caller invokes {@code B#m} and {@code B} does not declare
 * {@code m} but inherits it from {@code Super}, the edge to {@code Super#m} is
 * also recorded so the BFS reaches the caller from a changed superclass
 * method.</li>
 * </ul>
 *
 * <p>
 * The analysis is purely additive: the returned {@code ChangedMembers} is a
 * superset of the input — no entries are removed.
 */
public class StaticCallGraphAnalyzer {

	/**
	 * Marker member name used for class-level changes that have no specific member.
	 */
	public static final String CLASS_MARKER = "<class>";

	/**
	 * Default ratio: when expansion produces more than {@code 10×} the seed size,
	 * we treat the result as "degraded" and fall back to class-level matching.
	 */
	public static final int DEFAULT_DEGRADATION_RATIO = 10;

	/**
	 * Report describing the outcome of an expansion run.
	 *
	 * @param expanded
	 *            the resulting changed members (either truly expanded, or a
	 *            class-level fallback when {@code degraded == true})
	 * @param degraded
	 *            {@code true} if the expansion was abandoned because it produced
	 *            too many results (exceeded the ratio threshold)
	 * @param seedSize
	 *            number of seed member keys before expansion
	 * @param expandedSize
	 *            number of member keys after expansion (before any degradation
	 *            fallback)
	 * @param reason
	 *            short human-readable description of why we degraded (or
	 *            {@code null} if not degraded)
	 */
	public record Report(StructuralChangeAnalyzer.ChangedMembers expanded, boolean degraded, int seedSize,
			int expandedSize, String reason) {
	}

	/**
	 * Bundle of structural information extracted from a single bytecode scan pass.
	 * Public so reusing components ({@link BytecodeDependencyAugmenter}) can run a
	 * single scan and inspect the resulting reverse call graph + class metadata
	 * without re-walking every {@code .class} file.
	 *
	 * <p>
	 * Mutation is package-private — outside callers should treat this as a read-
	 * only result.
	 */
	public static final class ScanResult {
		/** {@code calleeKey → set of callerKey}. */
		final Map<String, Set<String>> reverseCallGraph = new HashMap<>();
		/** {@code className → superClassName} (null for j.l.Object). */
		final Map<String, String> superClasses = new HashMap<>();
		/** {@code className → declared interface names}. */
		final Map<String, Set<String>> interfaces = new HashMap<>();
		/** {@code className → set of methods declared directly on that class}. */
		final Map<String, Set<String>> declaredMethods = new HashMap<>();
		/**
		 * {@code className → set of direct subtype names} (extends + implements
		 * inverted).
		 */
		final Map<String, Set<String>> directSubtypes = new HashMap<>();
		/** number of class files successfully scanned. */
		int classCount;

		/** {@code calleeKey → set of callerKey}. */
		public Map<String, Set<String>> reverseCallGraph() {
			return reverseCallGraph;
		}

		/** {@code className → set of methods declared directly on that class}. */
		public Map<String, Set<String>> declaredMethods() {
			return declaredMethods;
		}

		/** Number of class files successfully scanned. */
		public int classCount() {
			return classCount;
		}
	}

	/**
	 * Public wrapper around the package-private {@link #scan(List)} so callers
	 * outside this package (e.g. {@link BytecodeDependencyAugmenter}) can run a
	 * single scan and inspect its full {@link ScanResult}.
	 */
	public static ScanResult scanPublic(List<Path> classDirs) {
		return scan(classDirs);
	}

	/**
	 * Expands {@code original} by following call-graph edges up to {@code maxDepth}
	 * hops in the reverse direction (callee → caller) using bytecode from
	 * {@code classDirs}.
	 *
	 * <p>
	 * If {@code classDirs} is empty or {@code original} has no changed member keys
	 * the original instance is returned unchanged.
	 *
	 * @param original
	 *            the initially detected changed members
	 * @param classDirs
	 *            directories containing compiled {@code .class} files to scan
	 * @param maxDepth
	 *            maximum number of BFS hops (1 = direct callers only)
	 * @return expanded changed members, or {@code original} if nothing changed
	 */
	public static StructuralChangeAnalyzer.ChangedMembers expand(StructuralChangeAnalyzer.ChangedMembers original,
			List<Path> classDirs, int maxDepth) {
		return expandWithReport(original, classDirs, maxDepth, DEFAULT_DEGRADATION_RATIO).expanded();
	}

	/**
	 * Like {@link #expand} but returns a {@link Report} that includes a
	 * {@code degraded} flag. When the BFS produces more than
	 * {@code degradationRatio × seed} member keys, the result is replaced with a
	 * class-level fallback (every changed class flagged with the
	 * {@link #CLASS_MARKER} member, no methods) — this signals to downstream
	 * scoring that the call graph drift is too large to be precise, and the user
	 * should re-run learn mode.
	 *
	 * @param original
	 *            the initially detected changed members
	 * @param classDirs
	 *            directories containing compiled {@code .class} files to scan
	 * @param maxDepth
	 *            maximum number of BFS hops (1 = direct callers only)
	 * @param degradationRatio
	 *            threshold ratio (e.g. 10 = degrade when expanded &gt; 10× seed);
	 *            pass {@code 0} or a negative value to disable degradation.
	 * @return expansion report
	 */
	public static Report expandWithReport(StructuralChangeAnalyzer.ChangedMembers original, List<Path> classDirs,
			int maxDepth, int degradationRatio) {

		if (original == null || classDirs.isEmpty() || maxDepth <= 0) {
			return new Report(original, false, 0, 0, null);
		}
		if (original.changedMemberKeys().isEmpty() && original.changedStaticFieldKeys().isEmpty()) {
			return new Report(original, false, 0, 0, null);
		}

		ScanResult scan = scan(classDirs);
		if (scan.classCount == 0) {
			return new Report(original, false, 0, 0, null);
		}

		// ── Seed: original changed keys, plus any subtype overrides of those keys ──
		Set<String> seed = new LinkedHashSet<>(original.changedMemberKeys());
		seed.addAll(original.changedStaticFieldKeys());
		seed.addAll(propagateToOverrides(seed, scan));

		int seedSize = seed.size();
		Set<String> allChangedMemberKeys = new LinkedHashSet<>(seed);
		Set<String> frontier = new LinkedHashSet<>(seed);

		for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
			Set<String> nextFrontier = new LinkedHashSet<>();
			for (String callee : frontier) {
				Set<String> callers = scan.reverseCallGraph.get(callee);
				if (callers != null) {
					for (String caller : callers) {
						if (allChangedMemberKeys.add(caller)) {
							nextFrontier.add(caller);
						}
					}
				}
			}
			Set<String> overrides = propagateToOverrides(nextFrontier, scan);
			for (String o : overrides) {
				if (allChangedMemberKeys.add(o)) {
					nextFrontier.add(o);
				}
			}
			frontier = nextFrontier;
		}

		int expandedSize = allChangedMemberKeys.size();

		// ── Degradation check: too many discovered members ⇒ fall back to class-level
		// ──
		if (degradationRatio > 0 && seedSize > 0 && expandedSize > (long) degradationRatio * seedSize) {
			StructuralChangeAnalyzer.ChangedMembers fallback = classLevelFallback(original, allChangedMemberKeys);
			String reason = "expansion produced " + expandedSize + " members from " + seedSize + " seeds (>"
					+ degradationRatio + "× threshold) — falling back to class-level matching";
			return new Report(fallback, true, seedSize, expandedSize, reason);
		}

		if (allChangedMemberKeys.equals(original.changedMemberKeys())) {
			return new Report(original, false, seedSize, expandedSize, null);
		}

		Set<String> allChangedClasses = new LinkedHashSet<>(original.changedClasses());
		Map<String, Set<String>> membersByClass = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> e : original.membersByClass().entrySet()) {
			membersByClass.put(e.getKey(), new LinkedHashSet<>(e.getValue()));
		}

		for (String memberKey : allChangedMemberKeys) {
			if (!original.changedMemberKeys().contains(memberKey)) {
				int hash = memberKey.lastIndexOf('#');
				if (hash > 0) {
					String cls = memberKey.substring(0, hash);
					String member = memberKey.substring(hash + 1);
					allChangedClasses.add(cls);
					membersByClass.computeIfAbsent(cls, k -> new LinkedHashSet<>()).add(member);
				}
			}
		}

		StructuralChangeAnalyzer.ChangedMembers result = new StructuralChangeAnalyzer.ChangedMembers(
				Collections.unmodifiableSet(allChangedClasses), Collections.unmodifiableSet(allChangedMemberKeys),
				Collections.unmodifiableMap(membersByClass), original.classesWithTypeChanges(),
				original.changedStaticFieldKeys());
		return new Report(result, false, seedSize, expandedSize, null);
	}

	/**
	 * Builds a coarse class-level fallback: every class touched by the expansion is
	 * flagged with a single {@link #CLASS_MARKER} member, and methodsByClass is
	 * cleared. This intentionally drops the per-member granularity so that scoring
	 * falls back to dependency-map class matching (which is exactly what
	 * pre-static-analysis behavior was).
	 */
	private static StructuralChangeAnalyzer.ChangedMembers classLevelFallback(
			StructuralChangeAnalyzer.ChangedMembers original, Set<String> allMemberKeys) {
		Set<String> classes = new LinkedHashSet<>(original.changedClasses());
		for (String k : allMemberKeys) {
			int hash = k.lastIndexOf('#');
			if (hash > 0) {
				classes.add(k.substring(0, hash));
			}
		}
		Set<String> keys = new LinkedHashSet<>();
		Map<String, Set<String>> byClass = new LinkedHashMap<>();
		for (String cls : classes) {
			keys.add(cls + "#" + CLASS_MARKER);
			byClass.put(cls, Collections.singleton(CLASS_MARKER));
		}
		return new StructuralChangeAnalyzer.ChangedMembers(Collections.unmodifiableSet(classes),
				Collections.unmodifiableSet(keys), Collections.unmodifiableMap(byClass),
				original.classesWithTypeChanges(), original.changedStaticFieldKeys());
	}

	/**
	 * For each {@code T#m} in {@code keys}, returns all overrides on declared
	 * subtypes ({@code S#m} where {@code S extends T} or {@code S implements T} and
	 * {@code S} declares {@code m}). Walks the subtype graph transitively.
	 */
	static Set<String> propagateToOverrides(Set<String> keys, ScanResult scan) {
		if (keys.isEmpty() || scan.directSubtypes.isEmpty()) {
			return Set.of();
		}
		Set<String> result = new LinkedHashSet<>();
		for (String key : keys) {
			int hash = key.lastIndexOf('#');
			if (hash <= 0)
				continue;
			String type = key.substring(0, hash);
			String member = key.substring(hash + 1);
			if (CLASS_MARKER.equals(member))
				continue;

			// BFS through subtypes
			Deque<String> queue = new ArrayDeque<>();
			Set<String> visited = new HashSet<>();
			Set<String> direct = scan.directSubtypes.get(type);
			if (direct != null) {
				queue.addAll(direct);
				visited.addAll(direct);
			}
			while (!queue.isEmpty()) {
				String sub = queue.pop();
				Set<String> declared = scan.declaredMethods.get(sub);
				if (declared != null && declared.contains(member)) {
					result.add(sub + "#" + member);
				}
				Set<String> next = scan.directSubtypes.get(sub);
				if (next != null) {
					for (String s : next) {
						if (visited.add(s)) {
							queue.push(s);
						}
					}
				}
			}
		}
		return result;
	}

	/**
	 * Single-pass scan that builds the reverse call graph plus class hierarchy
	 * metadata. Visible for testing.
	 */
	static ScanResult scan(List<Path> classDirs) {
		ScanResult result = new ScanResult();
		// Collect all class file paths first so we can do two passes:
		// pass 1: class headers + declared methods (so the upward walk in addCallEdge
		// sees a complete map)
		// pass 2: method bodies (call/field/invokedynamic edges)
		List<Path> allClassFiles = new ArrayList<>();
		for (Path dir : classDirs) {
			if (dir == null || !Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> walk = Files.walk(dir)) {
				walk.filter(p -> p.toString().endsWith(".class")).forEach(allClassFiles::add);
			} catch (IOException e) {
				// ignore unreadable directories
			}
		}

		Map<Path, byte[]> bytecodeCache = new HashMap<>();
		for (Path cf : allClassFiles) {
			try (InputStream is = Files.newInputStream(cf)) {
				bytecodeCache.put(cf, is.readAllBytes());
			} catch (IOException ignored) {
			}
		}

		for (Map.Entry<Path, byte[]> e : bytecodeCache.entrySet()) {
			scanClassMetadata(e.getValue(), result);
		}
		for (Map.Entry<Path, byte[]> e : bytecodeCache.entrySet()) {
			scanClassBodies(e.getValue(), result);
		}

		// Build the inverted (subtype) map after all classes are seen.
		for (Map.Entry<String, String> e : result.superClasses.entrySet()) {
			String sub = e.getKey();
			String sup = e.getValue();
			if (sup != null) {
				result.directSubtypes.computeIfAbsent(sup, k -> new HashSet<>()).add(sub);
			}
		}
		for (Map.Entry<String, Set<String>> e : result.interfaces.entrySet()) {
			String sub = e.getKey();
			for (String iface : e.getValue()) {
				result.directSubtypes.computeIfAbsent(iface, k -> new HashSet<>()).add(sub);
			}
		}

		// Class supertype / interface edges: when a base type's <class> changes,
		// every method of every subtype should be flagged. We add edges from the
		// supertype's class marker to each declared method of the subtype (and to
		// the subtype's class marker so synthetic class-level seeds reach it too).
		for (Map.Entry<String, String> e : result.superClasses.entrySet()) {
			String sub = e.getKey();
			String sup = e.getValue();
			if (sup == null || isLibraryType(sup)) {
				continue;
			}
			addSupertypeEdges(result, sub, sup);
		}
		for (Map.Entry<String, Set<String>> e : result.interfaces.entrySet()) {
			String sub = e.getKey();
			for (String iface : e.getValue()) {
				if (isLibraryType(iface)) {
					continue;
				}
				addSupertypeEdges(result, sub, iface);
			}
		}
		return result;
	}

	/**
	 * For a single (subtype, supertype) pair, records edges from
	 * {@code supertype#<class>} to each declared method of {@code subtype} (so that
	 * a base-class change reaches every subclass method) and to
	 * {@code subtype#<class>} (so coarse class-level seeds also reach the subtype).
	 */
	private static void addSupertypeEdges(ScanResult result, String subtype, String supertype) {
		String classKey = supertype + "#" + CLASS_MARKER;
		result.reverseCallGraph.computeIfAbsent(classKey, k -> new HashSet<>()).add(subtype + "#" + CLASS_MARKER);
		Set<String> declared = result.declaredMethods.get(subtype);
		if (declared != null) {
			for (String m : declared) {
				result.reverseCallGraph.computeIfAbsent(classKey, k -> new HashSet<>()).add(subtype + "#" + m);
			}
		}
	}

	/** Kept for backward compatibility with the original API. */
	static Map<String, Set<String>> buildReverseCallGraph(List<Path> classDirs) {
		return scan(classDirs).reverseCallGraph;
	}

	/** Pass 1: extract class header + declared method names. */
	private static void scanClassMetadata(byte[] bytes, ScanResult result) {
		try {
			ClassReader cr = new ClassReader(bytes);
			String[] className = new String[1];
			cr.accept(new ClassVisitor(ASM9) {
				@Override
				public void visit(int version, int access, String name, String signature, String superName,
						String[] interfaces) {
					className[0] = name.replace('/', '.');
					result.classCount++;
					if (superName != null && !"java/lang/Object".equals(superName)) {
						result.superClasses.put(className[0], superName.replace('/', '.'));
					} else {
						result.superClasses.put(className[0], null);
					}
					if (interfaces != null && interfaces.length > 0) {
						Set<String> ifaceSet = new HashSet<>();
						for (String i : interfaces) {
							ifaceSet.add(i.replace('/', '.'));
						}
						result.interfaces.put(className[0], ifaceSet);
					}
				}

				@Override
				public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature,
						String[] exceptions) {
					if (className[0] != null) {
						result.declaredMethods.computeIfAbsent(className[0], k -> new HashSet<>()).add(methodName);
					}
					return null; // skip method body in pass 1
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		} catch (Exception ignored) {
		}
	}

	/**
	 * Returns {@code true} if {@code fqcn} belongs to a JDK or known
	 * standard-library namespace whose classes we never expect to appear in the
	 * project's changed-classes set. Delegates to {@link ClassNameFilter} so the
	 * skip list stays in sync with the agent's instrumentation filter.
	 */
	private static boolean isLibraryType(String fqcn) {
		return ClassNameFilter.isLibraryType(fqcn);
	}

	/**
	 * Pass 2: walk method bodies and record call / invokedynamic / field-access
	 * edges, plus all type references reachable from the class header (annotations,
	 * field types, method signatures).
	 */
	private static void scanClassBodies(byte[] bytes, ScanResult result) {
		try {
			ClassReader cr = new ClassReader(bytes);
			String[] className = new String[1];
			cr.accept(new ClassVisitor(ASM9) {
				@Override
				public void visit(int version, int access, String name, String signature, String superName,
						String[] interfaces) {
					className[0] = name.replace('/', '.');
				}

				@Override
				public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
					if (className[0] != null) {
						addClassRefFromDescriptor(result, descriptor, className[0] + "#" + CLASS_MARKER);
					}
					return null;
				}

				@Override
				public FieldVisitor visitField(int access, String name, String descriptor, String signature,
						Object value) {
					if (className[0] == null) {
						return null;
					}
					String classKey = className[0] + "#" + CLASS_MARKER;
					addClassRefFromDescriptor(result, descriptor, classKey);
					if (signature != null) {
						addClassRefsFromGenericSignature(result, signature, classKey);
					}
					return new FieldVisitor(ASM9) {
						@Override
						public AnnotationVisitor visitAnnotation(String annDesc, boolean v) {
							addClassRefFromDescriptor(result, annDesc, classKey);
							return null;
						}
					};
				}

				@Override
				public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature,
						String[] exceptions) {
					if (className[0] == null) {
						return null;
					}
					String callerKey = className[0] + "#" + methodName;

					// Method param + return types from descriptor.
					addClassRefsFromMethodDescriptor(result, descriptor, callerKey);
					if (signature != null) {
						addClassRefsFromGenericSignature(result, signature, callerKey);
					}
					// Declared exceptions.
					if (exceptions != null) {
						for (String ex : exceptions) {
							addClassRefEdge(result, ex.replace('/', '.'), callerKey);
						}
					}

					return new MethodVisitor(ASM9) {
						@Override
						public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
							addClassRefFromDescriptor(result, annDesc, callerKey);
							return null;
						}

						@Override
						public AnnotationVisitor visitParameterAnnotation(int parameter, String annDesc,
								boolean visible) {
							addClassRefFromDescriptor(result, annDesc, callerKey);
							return null;
						}

						@Override
						public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
								boolean isInterface) {
							String ownerFqcn = owner.replace('/', '.');
							if (isLibraryType(ownerFqcn))
								return;
							addCallEdge(result, ownerFqcn, name, callerKey);
						}

						@Override
						public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle,
								Object... bootstrapMethodArguments) {
							for (Object arg : bootstrapMethodArguments) {
								if (arg instanceof Handle h) {
									int tag = h.getTag();
									if (tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKESTATIC
											|| tag == Opcodes.H_INVOKESPECIAL || tag == Opcodes.H_INVOKEINTERFACE
											|| tag == Opcodes.H_NEWINVOKESPECIAL) {
										String ownerFqcn = h.getOwner().replace('/', '.');
										if (isLibraryType(ownerFqcn))
											continue;
										addCallEdge(result, ownerFqcn, h.getName(), callerKey);
									}
								}
							}
						}

						@Override
						public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
							String ownerFqcn = owner.replace('/', '.');
							if (isLibraryType(ownerFqcn))
								return;
							if (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC) {
								String fieldKey = ownerFqcn + "#" + name;
								result.reverseCallGraph.computeIfAbsent(fieldKey, k -> new HashSet<>()).add(callerKey);
							} else {
								// GETFIELD / PUTFIELD: instance field — record an edge to owner#<class>
								// so that any class-level change to the owner reaches this caller.
								addClassRefEdge(result, ownerFqcn, callerKey);
							}
							// Field's declared type.
							addClassRefFromDescriptor(result, descriptor, callerKey);
						}

						@Override
						public void visitTypeInsn(int opcode, String type) {
							// NEW, ANEWARRAY, CHECKCAST, INSTANCEOF — all reference a type by name.
							addClassRefEdge(result, type.replace('/', '.'), callerKey);
						}

						@Override
						public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
							addClassRefFromDescriptor(result, descriptor, callerKey);
						}

						@Override
						public void visitLdcInsn(Object value) {
							// Class literals (Foo.class) appear as LDC of a Type constant.
							if (value instanceof Type t) {
								String fqcn = null;
								if (t.getSort() == Type.OBJECT) {
									fqcn = t.getClassName();
								} else if (t.getSort() == Type.ARRAY) {
									Type elem = t.getElementType();
									if (elem.getSort() == Type.OBJECT) {
										fqcn = elem.getClassName();
									}
								}
								if (fqcn != null) {
									addClassRefEdge(result, fqcn, callerKey);
								}
							}
						}

						@Override
						public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
								org.objectweb.asm.Label handler, String type) {
							// `type` is the internal name of the caught exception class, or null for
							// "any" (finally blocks).
							if (type == null)
								return;
							addClassRefEdge(result, type.replace('/', '.'), callerKey);
						}
					};
				}
			}, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		} catch (Exception ignored) {
		}
	}

	/**
	 * Records a class-level reverse-call edge {@code targetFqcn#<class> →
	 * callerKey}. Library types are filtered.
	 */
	private static void addClassRefEdge(ScanResult result, String targetFqcn, String callerKey) {
		if (isLibraryType(targetFqcn)) {
			return;
		}
		String classKey = targetFqcn + "#" + CLASS_MARKER;
		result.reverseCallGraph.computeIfAbsent(classKey, k -> new HashSet<>()).add(callerKey);
	}

	/**
	 * Parses a single field/type descriptor (e.g. {@code Lcom/foo/Bar;} or
	 * {@code [[Lcom/foo/Bar;}) and emits a class-level edge for each non-library
	 * object type it references. Primitives and arrays of primitives are ignored.
	 */
	private static void addClassRefFromDescriptor(ScanResult result, String descriptor, String callerKey) {
		if (descriptor == null || descriptor.isEmpty()) {
			return;
		}
		try {
			Type t = Type.getType(descriptor);
			collectTypeRefs(t, result, callerKey);
		} catch (Exception ignored) {
		}
	}

	/**
	 * Parses a method descriptor and emits class-level edges for every non-library
	 * object type appearing in its argument and return types.
	 */
	private static void addClassRefsFromMethodDescriptor(ScanResult result, String descriptor, String callerKey) {
		if (descriptor == null || descriptor.isEmpty() || descriptor.charAt(0) != '(') {
			return;
		}
		try {
			Type mt = Type.getMethodType(descriptor);
			for (Type arg : mt.getArgumentTypes()) {
				collectTypeRefs(arg, result, callerKey);
			}
			collectTypeRefs(mt.getReturnType(), result, callerKey);
		} catch (Exception ignored) {
		}
	}

	private static void collectTypeRefs(Type t, ScanResult result, String callerKey) {
		if (t == null) {
			return;
		}
		if (t.getSort() == Type.OBJECT) {
			addClassRefEdge(result, t.getClassName(), callerKey);
		} else if (t.getSort() == Type.ARRAY) {
			Type elem = t.getElementType();
			if (elem.getSort() == Type.OBJECT) {
				addClassRefEdge(result, elem.getClassName(), callerKey);
			}
		}
	}

	/**
	 * Best-effort scan of a generic signature attribute string (e.g.
	 * {@code Ljava/util/List<Lcom/foo/Bar;>;}). Pulls out every internal-form type
	 * reference {@code Lpkg/Cls;} and emits a class-level edge for it. This avoids
	 * pulling in {@code SignatureReader} (which would require more boilerplate)
	 * while still capturing the common cases.
	 */
	private static void addClassRefsFromGenericSignature(ScanResult result, String signature, String callerKey) {
		if (signature == null || signature.isEmpty()) {
			return;
		}
		int n = signature.length();
		int i = 0;
		while (i < n) {
			char c = signature.charAt(i);
			if (c == 'L') {
				int end = signature.indexOf(';', i + 1);
				int lt = signature.indexOf('<', i + 1);
				int boundary;
				if (end < 0) {
					return;
				}
				if (lt > 0 && lt < end) {
					boundary = lt;
				} else {
					boundary = end;
				}
				String internal = signature.substring(i + 1, boundary);
				// Strip any '.' (inner class separators in signature form).
				String fqcn = internal.replace('/', '.').replace('$', '.');
				addClassRefEdge(result, fqcn, callerKey);
				i = boundary;
			} else {
				i++;
			}
		}
	}

	/**
	 * Records {@code caller → owner#name} and additionally {@code caller →
	 * Super#name} for every ancestor of {@code owner} that <em>declares</em>
	 * {@code name} but {@code owner} itself does not (i.e. {@code owner} inherits
	 * the method). This way a BFS seeded from a changed {@code Super#name} reaches
	 * callers that referenced the method via a subclass receiver type.
	 *
	 * <p>
	 * Note: at scan time we may not yet have seen {@code owner}'s class file (it
	 * may be in a JAR / outside {@code classDirs}). In that case
	 * {@link #propagateToOverrides} handles the symmetric direction (subtype
	 * propagation from a changed supertype).
	 */
	private static void addCallEdge(ScanResult result, String ownerFqcn, String methodName, String callerKey) {
		String key = ownerFqcn + "#" + methodName;
		result.reverseCallGraph.computeIfAbsent(key, k -> new HashSet<>()).add(callerKey);

		Set<String> ownerDeclared = result.declaredMethods.get(ownerFqcn);
		if (ownerDeclared != null && ownerDeclared.contains(methodName)) {
			return; // owner declares the method directly — no need to walk up
		}
		String sup = result.superClasses.get(ownerFqcn);
		while (sup != null) {
			Set<String> supDeclared = result.declaredMethods.get(sup);
			if (supDeclared != null && supDeclared.contains(methodName)) {
				result.reverseCallGraph.computeIfAbsent(sup + "#" + methodName, k -> new HashSet<>()).add(callerKey);
				return;
			}
			sup = result.superClasses.get(sup);
		}
	}
}
