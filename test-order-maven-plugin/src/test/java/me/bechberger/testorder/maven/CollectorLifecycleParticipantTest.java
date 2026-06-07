package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.agent.runtime.ClassIdMapping;

/**
 * Unit tests for
 * {@link CollectorLifecycleParticipant#prepareReactorClassIdMap}.
 *
 * <p>
 * The reactor pre-pass scans every module's compile + test source roots and
 * pre-allocates a single {@code class-id-map.bin} at the reactor root. These
 * tests cover the multi-module flow plus weird edge cases: empty reactors,
 * pom-only modules, missing source roots, modules sharing class names.
 */
class CollectorLifecycleParticipantTest {

	@TempDir
	Path tempDir;

	private final CollectorLifecycleParticipant participant = new CollectorLifecycleParticipant();

	private MavenProject newProject(String artifactId, Path basedir, List<String> compileRoots,
			List<String> testRoots) {
		MavenProject p = mock(MavenProject.class);
		lenient().when(p.getArtifactId()).thenReturn(artifactId);
		lenient().when(p.getBasedir()).thenReturn(basedir.toFile());
		lenient().when(p.getCompileSourceRoots()).thenReturn(compileRoots);
		lenient().when(p.getTestCompileSourceRoots()).thenReturn(testRoots);
		return p;
	}

	private MavenSession newSession(Path reactorRoot, MavenProject topLevel, List<MavenProject> projects) {
		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(projects);
		when(session.getTopLevelProject()).thenReturn(topLevel);
		return session;
	}

	private Path reactorMapFile() {
		return tempDir.resolve(".test-order").resolve("class-id-map.bin");
	}

	private void writeJava(Path root, String relativePath) throws IOException {
		Path target = root.resolve(relativePath);
		Files.createDirectories(target.getParent());
		Files.writeString(target, "");
	}

	@Test
	void multiModuleReactorRegistersAllFqnsWithUniqueIds() throws IOException {
		// Three modules, each with main + test sources.
		Path modA = tempDir.resolve("module-a");
		Path modB = tempDir.resolve("module-b");
		Path modC = tempDir.resolve("module-c");
		writeJava(modA.resolve("src/main/java"), "com/a/Library.java");
		writeJava(modA.resolve("src/test/java"), "com/a/LibraryTest.java");
		writeJava(modB.resolve("src/main/java"), "com/b/Service.java");
		writeJava(modB.resolve("src/test/java"), "com/b/ServiceTest.java");
		writeJava(modC.resolve("src/main/java"), "com/c/Utility.java");

		MavenProject pA = newProject("module-a", modA, List.of(modA.resolve("src/main/java").toString()),
				List.of(modA.resolve("src/test/java").toString()));
		MavenProject pB = newProject("module-b", modB, List.of(modB.resolve("src/main/java").toString()),
				List.of(modB.resolve("src/test/java").toString()));
		MavenProject pC = newProject("module-c", modC, List.of(modC.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA, pB, pC));

		participant.prepareReactorClassIdMap(session);

		Path file = reactorMapFile();
		assertTrue(Files.exists(file), "class-id-map.bin should be written");
		ClassIdMapping mapping = ClassIdMapping.load(file);
		Map<String, Integer> classMap = mapping.toClassMap();

		assertEquals(5, classMap.size());
		assertNotNull(classMap.get("com.a.Library"));
		assertNotNull(classMap.get("com.a.LibraryTest"));
		assertNotNull(classMap.get("com.b.Service"));
		assertNotNull(classMap.get("com.b.ServiceTest"));
		assertNotNull(classMap.get("com.c.Utility"));

		// All IDs are unique
		Set<Integer> ids = new HashSet<>(classMap.values());
		assertEquals(classMap.size(), ids.size(), "every class must have a unique ID");
	}

	@Test
	void emptySessionWritesNoFile() {
		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(Collections.emptyList());
		participant.prepareReactorClassIdMap(session);
		assertFalse(Files.exists(reactorMapFile()));
	}

	@Test
	void nullSessionNoCrash() {
		participant.prepareReactorClassIdMap(null);
		assertFalse(Files.exists(reactorMapFile()));
	}

	@Test
	void sessionWithoutTopLevelProjectIsSkipped() {
		MavenSession session = mock(MavenSession.class);
		MavenProject p = mock(MavenProject.class);
		when(session.getProjects()).thenReturn(List.of(p));
		when(session.getTopLevelProject()).thenReturn(null);
		participant.prepareReactorClassIdMap(session);
		// Without a reactor root, we have no stable path; nothing to verify
		// except that it didn't throw.
	}

	@Test
	void modulesWithoutJavaSourcesAreNoop() {
		// pom-only or empty modules — getCompileSourceRoots returns empty.
		Path modA = tempDir.resolve("module-a");
		MavenProject pA = newProject("module-a", modA, Collections.emptyList(), Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA));

		participant.prepareReactorClassIdMap(session);

		// No FQNs, no prior file → nothing to write
		assertFalse(Files.exists(reactorMapFile()));
	}

	@Test
	void missingSourceRootDirectoryIsSkippedGracefully() {
		// compileRoots references a directory that doesn't exist on disk.
		Path modA = tempDir.resolve("module-a");
		MavenProject pA = newProject("module-a", modA, List.of(modA.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA));

		participant.prepareReactorClassIdMap(session);
		assertFalse(Files.exists(reactorMapFile()));
	}

	@Test
	void blankSourceRootStringIsSkipped() throws IOException {
		// Maven occasionally hands out blank source-root strings. Path.of("")
		// resolves to CWD; without the guard, we'd scan the entire CWD.
		Path modA = tempDir.resolve("module-a");
		writeJava(modA.resolve("src/main/java"), "com/a/Real.java");
		MavenProject pA = newProject("module-a", modA,
				java.util.Arrays.asList("", null, modA.resolve("src/main/java").toString()), Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA));

		participant.prepareReactorClassIdMap(session);

		ClassIdMapping mapping = ClassIdMapping.load(reactorMapFile());
		Map<String, Integer> classMap = mapping.toClassMap();
		assertEquals(1, classMap.size());
		assertNotNull(classMap.get("com.a.Real"));
	}

	@Test
	void existingMapIsPreservedAndIdsStable() throws IOException {
		// Run pre-pass once with module-a only, capture IDs. Then add module-b
		// and run again — module-a's IDs must be unchanged.
		Path modA = tempDir.resolve("module-a");
		writeJava(modA.resolve("src/main/java"), "com/a/A.java");
		writeJava(modA.resolve("src/main/java"), "com/a/B.java");

		MavenProject pA = newProject("module-a", modA, List.of(modA.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession s1 = newSession(tempDir, top, List.of(top, pA));
		participant.prepareReactorClassIdMap(s1);

		Map<String, Integer> firstMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		int idA = firstMap.get("com.a.A");
		int idB = firstMap.get("com.a.B");

		// Second run with an extra module
		Path modB = tempDir.resolve("module-b");
		writeJava(modB.resolve("src/main/java"), "com/b/Z.java");
		MavenProject pB = newProject("module-b", modB, List.of(modB.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenSession s2 = newSession(tempDir, top, List.of(top, pA, pB));
		participant.prepareReactorClassIdMap(s2);

		Map<String, Integer> secondMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(idA, (int) secondMap.get("com.a.A"), "previous ID for com.a.A must be reused");
		assertEquals(idB, (int) secondMap.get("com.a.B"), "previous ID for com.a.B must be reused");
		assertNotNull(secondMap.get("com.b.Z"), "new class must be registered");
	}

	@Test
	void modulesWithSameSimpleNameInDifferentPackagesGetUniqueIds() throws IOException {
		// Two modules each have a class called Util but in different packages.
		// With per-module ID maps these would BOTH be ID 0 — proving the
		// reactor-wide ID space is necessary.
		Path modA = tempDir.resolve("module-a");
		Path modB = tempDir.resolve("module-b");
		writeJava(modA.resolve("src/main/java"), "com/a/Util.java");
		writeJava(modB.resolve("src/main/java"), "com/b/Util.java");

		MavenProject pA = newProject("module-a", modA, List.of(modA.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject pB = newProject("module-b", modB, List.of(modB.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA, pB));

		participant.prepareReactorClassIdMap(session);

		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(2, classMap.size());
		int idAUtil = classMap.get("com.a.Util");
		int idBUtil = classMap.get("com.b.Util");
		assertFalse(idAUtil == idBUtil, "same-simple-name classes in different packages must NOT collide");
	}

	@Test
	void onlyTestSourcesWithoutMainSources() throws IOException {
		// Test-only modules (rare but valid) — the test sources alone get registered.
		Path modA = tempDir.resolve("module-a");
		writeJava(modA.resolve("src/test/java"), "com/a/IsolatedTest.java");

		MavenProject pA = newProject("module-a", modA, Collections.emptyList(),
				List.of(modA.resolve("src/test/java").toString()));
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA));

		participant.prepareReactorClassIdMap(session);

		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(1, classMap.size());
		assertNotNull(classMap.get("com.a.IsolatedTest"));
	}

	@Test
	void multipleCompileRootsArePickedUp() throws IOException {
		// Maven plugins like build-helper-maven-plugin add extra source roots.
		// All of them must be scanned.
		Path modA = tempDir.resolve("module-a");
		writeJava(modA.resolve("src/main/java"), "com/a/Std.java");
		writeJava(modA.resolve("target/generated-sources/annotations"), "com/a/Generated.java");

		MavenProject pA = newProject("module-a", modA, List.of(modA.resolve("src/main/java").toString(),
				modA.resolve("target/generated-sources/annotations").toString()), Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA));

		participant.prepareReactorClassIdMap(session);

		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(2, classMap.size());
		assertNotNull(classMap.get("com.a.Std"));
		assertNotNull(classMap.get("com.a.Generated"));
	}

	@Test
	void existingFileWithBadContentDoesNotCrash() throws IOException {
		// A truncated/garbage class-id-map.bin from a crashed run must not
		// break the pre-pass. It logs and starts fresh.
		Path file = reactorMapFile();
		Files.createDirectories(file.getParent());
		Files.write(file, new byte[]{0, 0, 0, 0, 0, 0});

		Path modA = tempDir.resolve("module-a");
		writeJava(modA.resolve("src/main/java"), "com/a/A.java");
		MavenProject pA = newProject("module-a", modA, List.of(modA.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA));

		participant.prepareReactorClassIdMap(session);

		// File should now have been overwritten with a valid mapping
		ClassIdMapping mapping = ClassIdMapping.load(file);
		assertNotNull(mapping.toClassMap().get("com.a.A"));
	}

	@Test
	void participantsExecutionsAreIdempotent() throws IOException {
		// Calling prepareReactorClassIdMap twice in a row produces the same map
		// (no growing IDs, no duplicates).
		Path modA = tempDir.resolve("module-a");
		writeJava(modA.resolve("src/main/java"), "com/a/A.java");
		writeJava(modA.resolve("src/main/java"), "com/a/B.java");
		MavenProject pA = newProject("module-a", modA, List.of(modA.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA));

		participant.prepareReactorClassIdMap(session);
		Map<String, Integer> first = ClassIdMapping.load(reactorMapFile()).toClassMap();
		participant.prepareReactorClassIdMap(session);
		Map<String, Integer> second = ClassIdMapping.load(reactorMapFile()).toClassMap();

		assertEquals(first, second);
	}

	@Test
	void packageInfoAndModuleInfoAreSkipped() throws IOException {
		Path modA = tempDir.resolve("module-a");
		writeJava(modA.resolve("src/main/java"), "com/a/package-info.java");
		writeJava(modA.resolve("src/main/java"), "module-info.java");
		writeJava(modA.resolve("src/main/java"), "com/a/Real.java");
		MavenProject pA = newProject("module-a", modA, List.of(modA.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA));

		participant.prepareReactorClassIdMap(session);

		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(1, classMap.size());
		assertNotNull(classMap.get("com.a.Real"));
		assertNull(classMap.get("com.a.package-info"));
		assertNull(classMap.get("module-info"));
	}

	@Test
	void mixOfPomOnlyAndSourceModulesNoFalseCount() throws IOException {
		// Realistic monorepo: aggregator parent + several pom-only intermediates
		// + a couple of leaf modules with sources. Pre-pass must register only
		// the leaf classes — no spurious empty-string entries from the pom-only
		// modules.
		Path leafA = tempDir.resolve("leaf-a");
		Path leafB = tempDir.resolve("leaf-b");
		writeJava(leafA.resolve("src/main/java"), "leaf/a/A.java");
		writeJava(leafB.resolve("src/main/java"), "leaf/b/B.java");

		MavenProject top = newProject("aggregator", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenProject pomOnly1 = newProject("intermediate-1", tempDir.resolve("im-1"), Collections.emptyList(),
				Collections.emptyList());
		MavenProject pomOnly2 = newProject("intermediate-2", tempDir.resolve("im-2"), Collections.emptyList(),
				Collections.emptyList());
		MavenProject pA = newProject("leaf-a", leafA, List.of(leafA.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject pB = newProject("leaf-b", leafB, List.of(leafB.resolve("src/main/java").toString()),
				Collections.emptyList());

		MavenSession session = newSession(tempDir, top, List.of(top, pomOnly1, pomOnly2, pA, pB));
		participant.prepareReactorClassIdMap(session);

		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(2, classMap.size());
		assertNotNull(classMap.get("leaf.a.A"));
		assertNotNull(classMap.get("leaf.b.B"));
	}

	@Test
	void modulesWithOverlappingPackageButDistinctClasses() throws IOException {
		// Two modules contributing classes to the SAME package (com.shared).
		// This is unusual but legal — the pre-pass must register them all
		// without confusing them. (split-package compilation is only blocked
		// at runtime by JPMS; classpath-only Maven is fine with it.)
		Path modA = tempDir.resolve("module-a");
		Path modB = tempDir.resolve("module-b");
		writeJava(modA.resolve("src/main/java"), "com/shared/AlphaFromA.java");
		writeJava(modA.resolve("src/main/java"), "com/shared/Common.java");
		writeJava(modB.resolve("src/main/java"), "com/shared/BetaFromB.java");
		writeJava(modB.resolve("src/main/java"), "com/shared/Helper.java");

		MavenProject pA = newProject("module-a", modA, List.of(modA.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject pB = newProject("module-b", modB, List.of(modB.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA, pB));

		participant.prepareReactorClassIdMap(session);

		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(4, classMap.size());
		Set<Integer> ids = new HashSet<>(classMap.values());
		assertEquals(4, ids.size(), "split-package classes still need unique IDs");
		assertNotNull(classMap.get("com.shared.AlphaFromA"));
		assertNotNull(classMap.get("com.shared.Common"));
		assertNotNull(classMap.get("com.shared.BetaFromB"));
		assertNotNull(classMap.get("com.shared.Helper"));
	}

	@Test
	void singleModuleReactorWritesAtModuleRoot() throws IOException {
		// Single-module reactor: top-level project IS the only module.
		// File should land at the top-level basedir (which is also the module).
		Path modRoot = tempDir;
		writeJava(modRoot.resolve("src/main/java"), "single/Only.java");

		MavenProject single = newProject("solo", modRoot, List.of(modRoot.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenSession session = newSession(modRoot, single, List.of(single));
		participant.prepareReactorClassIdMap(session);

		assertTrue(Files.exists(reactorMapFile()));
		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(1, classMap.size());
		assertNotNull(classMap.get("single.Only"));
	}

	@Test
	void manyModulesProducingHundredsOfFqns() throws IOException {
		// Stress: 20 modules × 10 classes each = 200 FQNs. Pre-pass must scale
		// without ID collisions or O(N²) blowup.
		int moduleCount = 20;
		int classesPerModule = 10;
		List<MavenProject> projects = new java.util.ArrayList<>();
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		projects.add(top);
		for (int m = 0; m < moduleCount; m++) {
			Path modDir = tempDir.resolve("mod-" + m);
			for (int c = 0; c < classesPerModule; c++) {
				writeJava(modDir.resolve("src/main/java"), "mod" + m + "/C" + c + ".java");
			}
			projects.add(newProject("mod-" + m, modDir, List.of(modDir.resolve("src/main/java").toString()),
					Collections.emptyList()));
		}

		MavenSession session = newSession(tempDir, top, projects);
		participant.prepareReactorClassIdMap(session);

		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(moduleCount * classesPerModule, classMap.size());
		Set<Integer> ids = new HashSet<>(classMap.values());
		assertEquals(classMap.size(), ids.size(), "all 200 IDs must be unique");
	}

	@Test
	void modulesWithSameSimpleNameInDIFFERENTPackagesAcrossManyModules() throws IOException {
		// Extreme version of the "Util in different packages" test: 5 modules
		// each have a class named Util in their own package. With per-module ID
		// space, all 5 would collide on ID 0. Reactor-wide → all 5 distinct.
		List<MavenProject> projects = new java.util.ArrayList<>();
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		projects.add(top);
		for (int i = 0; i < 5; i++) {
			Path modDir = tempDir.resolve("mod-" + i);
			writeJava(modDir.resolve("src/main/java"), "p" + i + "/Util.java");
			writeJava(modDir.resolve("src/main/java"), "p" + i + "/Helper.java");
			writeJava(modDir.resolve("src/test/java"), "p" + i + "/UtilTest.java");
			projects.add(newProject("mod-" + i, modDir, List.of(modDir.resolve("src/main/java").toString()),
					List.of(modDir.resolve("src/test/java").toString())));
		}

		MavenSession session = newSession(tempDir, top, projects);
		participant.prepareReactorClassIdMap(session);

		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(15, classMap.size(), "5 × (Util + Helper + UtilTest) = 15");

		// All 5 Util variants present and distinct
		Set<Integer> utilIds = new HashSet<>();
		for (int i = 0; i < 5; i++) {
			Integer id = classMap.get("p" + i + ".Util");
			assertNotNull(id, "p" + i + ".Util missing");
			utilIds.add(id);
		}
		assertEquals(5, utilIds.size(), "all 5 Util variants must have unique IDs");
	}

	/**
	 * BUG-5b regression: a standalone sub-project physically nested inside another
	 * Maven repo (one that has {@code .mvn/} and a pre-existing
	 * {@code .test-order/} at its root) must write its class-id-map to ITS OWN
	 * basedir, not to the outer repo root. Maven walks up to find {@code .mvn/} so
	 * {@code getMultiModuleProjectDirectory()} returns the outer root, but
	 * {@code getExecutionRootDirectory()} returns the inner project's directory —
	 * that mismatch must prevent the outer root from being used.
	 */
	@Test
	void standaloneSubProjectInsideOuterRepoUsesOwnRoot() throws IOException {
		Path outerRoot = tempDir.resolve("outer-repo");
		// The outer repo already has a shared .test-order/ from a previous build
		Files.createDirectories(outerRoot.resolve(".test-order"));

		// The sub-project lives nested inside the outer repo
		Path innerDir = outerRoot.resolve("third-party").resolve("demo-shop");
		writeJava(innerDir.resolve("src/main/java"), "com/shop/Cart.java");
		writeJava(innerDir.resolve("src/test/java"), "com/shop/CartTest.java");

		MavenProject inner = newProject("demo-shop", innerDir, List.of(innerDir.resolve("src/main/java").toString()),
				List.of(innerDir.resolve("src/test/java").toString()));

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(inner));
		when(session.getTopLevelProject()).thenReturn(inner);
		// Maven found the outer .mvn/ so mmDir → outer root
		MavenExecutionRequest request = mock(MavenExecutionRequest.class);
		when(session.getRequest()).thenReturn(request);
		when(request.getMultiModuleProjectDirectory()).thenReturn(outerRoot.toFile());
		// But the user invoked mvn from the inner dir → executionRootDirectory = inner
		when(session.getExecutionRootDirectory()).thenReturn(innerDir.toString());

		participant.prepareReactorClassIdMap(session);

		// File must be at the inner project's .test-order/, NOT the outer root
		Path outerFile = outerRoot.resolve(".test-order").resolve("class-id-map.bin");
		Path innerFile = innerDir.resolve(".test-order").resolve("class-id-map.bin");

		assertFalse(Files.exists(outerFile), "class-id-map.bin must NOT be written to the outer repo root");
		assertTrue(Files.exists(innerFile), "class-id-map.bin should be at the inner project's .test-order/");

		Map<String, Integer> classMap = ClassIdMapping.load(innerFile).toClassMap();
		assertEquals(2, classMap.size());
		assertNotNull(classMap.get("com.shop.Cart"));
		assertNotNull(classMap.get("com.shop.CartTest"));
	}

	@Test
	void duplicateProjectInSessionDoesNotDoubleRegister() throws IOException {
		// Defensive: if Maven somehow hands us the same project twice, the map
		// must not contain duplicate entries.
		Path modA = tempDir.resolve("module-a");
		writeJava(modA.resolve("src/main/java"), "com/a/A.java");
		MavenProject pA = newProject("module-a", modA, List.of(modA.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA, pA, pA));

		participant.prepareReactorClassIdMap(session);

		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(1, classMap.size());
	}

	@Test
	void deeplyNestedPackagePathProducesCorrectFqn() throws IOException {
		// Deeply-nested package — long path under src/main/java.
		Path modA = tempDir.resolve("module-a");
		writeJava(modA.resolve("src/main/java"), "com/example/very/deeply/nested/sub/package/here/Cls.java");
		MavenProject pA = newProject("module-a", modA, List.of(modA.resolve("src/main/java").toString()),
				Collections.emptyList());
		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenSession session = newSession(tempDir, top, List.of(top, pA));

		participant.prepareReactorClassIdMap(session);

		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertNotNull(classMap.get("com.example.very.deeply.nested.sub.package.here.Cls"));
	}

	@Test
	void modulesWithNoTestSourcesOrMixedHaveTestsRegistered() throws IOException {
		// Some modules have ONLY main sources, some have ONLY test sources, some
		// have both. All combinations must be handled.
		Path mainOnly = tempDir.resolve("main-only");
		Path testOnly = tempDir.resolve("test-only");
		Path both = tempDir.resolve("both");
		writeJava(mainOnly.resolve("src/main/java"), "m/MainCls.java");
		writeJava(testOnly.resolve("src/test/java"), "t/TestCls.java");
		writeJava(both.resolve("src/main/java"), "b/Both.java");
		writeJava(both.resolve("src/test/java"), "b/BothTest.java");

		MavenProject top = newProject("reactor", tempDir, Collections.emptyList(), Collections.emptyList());
		MavenProject pMainOnly = newProject("main-only", mainOnly,
				List.of(mainOnly.resolve("src/main/java").toString()), Collections.emptyList());
		MavenProject pTestOnly = newProject("test-only", testOnly, Collections.emptyList(),
				List.of(testOnly.resolve("src/test/java").toString()));
		MavenProject pBoth = newProject("both", both, List.of(both.resolve("src/main/java").toString()),
				List.of(both.resolve("src/test/java").toString()));

		MavenSession session = newSession(tempDir, top, List.of(top, pMainOnly, pTestOnly, pBoth));
		participant.prepareReactorClassIdMap(session);

		Map<String, Integer> classMap = ClassIdMapping.load(reactorMapFile()).toClassMap();
		assertEquals(4, classMap.size());
		assertNotNull(classMap.get("m.MainCls"));
		assertNotNull(classMap.get("t.TestCls"));
		assertNotNull(classMap.get("b.Both"));
		assertNotNull(classMap.get("b.BothTest"));
	}
}
