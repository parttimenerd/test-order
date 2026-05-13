package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildFileFingerprintTest {

	@TempDir
	Path tempDir;

	// ── computeFromClasspath ────────────────────────────────────────

	@Test
	void classpathFingerprintReturnsNullForEmptyCollection() {
		assertNull(BuildFileFingerprint.computeFromClasspath(List.of()));
		assertNull(BuildFileFingerprint.computeFromClasspath(null));
	}

	@Test
	void classpathFingerprintIgnoresNonJarFiles() throws IOException {
		Path dir = tempDir.resolve("classes");
		Files.createDirectories(dir);
		// directories are not JARs — should be excluded
		assertNull(BuildFileFingerprint.computeFromClasspath(List.of(dir)));
	}

	@Test
	void classpathFingerprintIgnoresNonExistentFiles() {
		Path missing = tempDir.resolve("missing.jar");
		assertNull(BuildFileFingerprint.computeFromClasspath(List.of(missing)));
	}

	@Test
	void classpathFingerprintProducesDeterministicHash() throws IOException {
		Path jar1 = tempDir.resolve("a-lib-1.0.jar");
		Path jar2 = tempDir.resolve("b-lib-2.0.jar");
		Files.write(jar1, new byte[]{1, 2, 3});
		Files.write(jar2, new byte[]{4, 5, 6});

		String fp1 = BuildFileFingerprint.computeFromClasspath(List.of(jar1, jar2));
		String fp2 = BuildFileFingerprint.computeFromClasspath(List.of(jar2, jar1));

		assertNotNull(fp1);
		// Order should not matter (sorted internally)
		assertEquals(fp1, fp2);
	}

	@Test
	void classpathFingerprintChangesWhenJarSizeChanges() throws IOException {
		Path jar = tempDir.resolve("lib-1.0-SNAPSHOT.jar");
		Files.write(jar, new byte[]{1, 2, 3});

		String fp1 = BuildFileFingerprint.computeFromClasspath(List.of(jar));

		// Simulate SNAPSHOT rebuild: same name, different content/size
		Files.write(jar, new byte[]{1, 2, 3, 4, 5});

		String fp2 = BuildFileFingerprint.computeFromClasspath(List.of(jar));

		assertNotNull(fp1);
		assertNotNull(fp2);
		assertNotEquals(fp1, fp2);
	}

	@Test
	void classpathFingerprintChangesWhenJarAdded() throws IOException {
		Path jar1 = tempDir.resolve("a-lib.jar");
		Files.write(jar1, new byte[]{1, 2, 3});

		String fpBefore = BuildFileFingerprint.computeFromClasspath(List.of(jar1));

		Path jar2 = tempDir.resolve("b-new-dep.jar");
		Files.write(jar2, new byte[]{7, 8, 9});

		String fpAfter = BuildFileFingerprint.computeFromClasspath(List.of(jar1, jar2));

		assertNotNull(fpBefore);
		assertNotNull(fpAfter);
		assertNotEquals(fpBefore, fpAfter);
	}

	@Test
	void classpathFingerprintChangesWhenVersionBumped() throws IOException {
		Path jar1 = tempDir.resolve("spring-boot-3.1.0.jar");
		Files.write(jar1, new byte[]{1, 2, 3});

		String fpBefore = BuildFileFingerprint.computeFromClasspath(List.of(jar1));

		// Version bump: different filename
		Path jar2 = tempDir.resolve("spring-boot-3.2.0.jar");
		Files.write(jar2, new byte[]{1, 2, 3}); // same content, different name

		String fpAfter = BuildFileFingerprint.computeFromClasspath(List.of(jar2));

		assertNotNull(fpBefore);
		assertNotNull(fpAfter);
		assertNotEquals(fpBefore, fpAfter);
	}

	// ── computeFromBuildFiles ───────────────────────────────────────

	@Test
	void buildFileFingerprintReturnsNullForMissingRoot() {
		assertNull(BuildFileFingerprint.computeFromBuildFiles(null));
		assertNull(BuildFileFingerprint.computeFromBuildFiles(tempDir.resolve("nonexistent")));
	}

	@Test
	void buildFileFingerprintReturnsNullWhenNoBuildFilesExist() {
		// tempDir exists but has no build files
		assertNull(BuildFileFingerprint.computeFromBuildFiles(tempDir));
	}

	@Test
	void buildFileFingerprintHashesPomXml() throws IOException {
		Files.writeString(tempDir.resolve("pom.xml"), "<project><dependencies/></project>");

		String fp = BuildFileFingerprint.computeFromBuildFiles(tempDir);
		assertNotNull(fp);
		assertFalse(fp.isBlank());
	}

	@Test
	void buildFileFingerprintChangesWhenPomChanges() throws IOException {
		Files.writeString(tempDir.resolve("pom.xml"), "<project><dependencies/></project>");
		String fp1 = BuildFileFingerprint.computeFromBuildFiles(tempDir);

		Files.writeString(tempDir.resolve("pom.xml"),
				"<project><dependencies><dependency>new</dependency></dependencies></project>");
		String fp2 = BuildFileFingerprint.computeFromBuildFiles(tempDir);

		assertNotEquals(fp1, fp2);
	}

	@Test
	void buildFileFingerprintHashesBuildGradle() throws IOException {
		Files.writeString(tempDir.resolve("build.gradle"), "dependencies { implementation 'com.foo:bar:1.0' }");

		String fp = BuildFileFingerprint.computeFromBuildFiles(tempDir);
		assertNotNull(fp);
	}

	@Test
	void buildFileFingerprintHashesVersionCatalog() throws IOException {
		Path gradleDir = tempDir.resolve("gradle");
		Files.createDirectories(gradleDir);
		Files.writeString(gradleDir.resolve("libs.versions.toml"), "[versions]\nspring = \"3.1.0\"");

		String fp = BuildFileFingerprint.computeFromBuildFiles(tempDir);
		assertNotNull(fp);
	}

	// ── compute (combined) ──────────────────────────────────────────

	@Test
	void computePrefersClasspathOverBuildFiles() throws IOException {
		// Set up both: a pom.xml and a JAR
		Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
		Path jar = tempDir.resolve("lib.jar");
		Files.write(jar, new byte[]{1, 2, 3});

		String fpCombined = BuildFileFingerprint.compute(List.of(jar), tempDir);
		String fpClasspath = BuildFileFingerprint.computeFromClasspath(List.of(jar));

		// Should use classpath when available
		assertEquals(fpClasspath, fpCombined);
	}

	@Test
	void computeFallsToBuildFilesWhenNoJars() throws IOException {
		Files.writeString(tempDir.resolve("pom.xml"), "<project/>");

		String fpCombined = BuildFileFingerprint.compute(List.of(), tempDir);
		String fpBuildFiles = BuildFileFingerprint.computeFromBuildFiles(tempDir);

		assertEquals(fpBuildFiles, fpCombined);
	}
}
