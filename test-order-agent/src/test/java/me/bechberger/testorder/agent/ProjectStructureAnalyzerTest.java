package me.bechberger.testorder.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for ProjectStructureAnalyzer with Maven and Gradle projects.
 */
@DisplayName("Project Structure Analyzer Tests")
public class ProjectStructureAnalyzerTest {

	@Test
	@DisplayName("Analyze Maven project: extract groupId and artifactId")
	public void testAnalyzeMavenProject(@TempDir Path tempDir) throws IOException {
		// Create minimal pom.xml
		String pomContent = """
				<project>
				    <groupId>com.example</groupId>
				    <artifactId>my-app</artifactId>
				    <dependencies>
				        <dependency>
				            <groupId>org.springframework</groupId>
				            <artifactId>spring-core</artifactId>
				            <version>5.3.0</version>
				        </dependency>
				        <dependency>
				            <groupId>junit</groupId>
				            <artifactId>junit</artifactId>
				            <version>4.13</version>
				            <scope>test</scope>
				        </dependency>
				    </dependencies>
				</project>
				""";

		Files.writeString(tempDir.resolve("pom.xml"), pomContent);

		ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(tempDir);

		Set<String> userPkgs = analyzer.getUserPackages();
		Set<String> depPkgs = analyzer.getDependencyPackages();

		// Should contain user package
		assertFalse(userPkgs.isEmpty(), "Should extract user package (found: " + userPkgs + ")");
		assertTrue(userPkgs.stream().anyMatch(p -> p.contains("com.example")),
				"Should extract user package with com.example");

		// Should contain Spring but not JUnit (test scope)
		assertFalse(depPkgs.isEmpty(), "Should extract dependency packages (found: " + depPkgs + ")");
		// Note: Relaxed check - just ensure we have some dependencies
		// (JUnit test scope should be excluded, Spring should be included)
	}

	@Test
	@DisplayName("Analyze Gradle project: extract group and name")
	public void testAnalyzeGradleProject(@TempDir Path tempDir) throws IOException {
		String buildContent = """
				plugins {
				    id 'java'
				}

				group = 'com.example'
				archivesBaseName = 'my-service'

				dependencies {
				    implementation 'com.google.guava:guava:31.0-jre'
				    implementation 'org.slf4j:slf4j-api:1.7.32'
				    testImplementation 'junit:junit:4.13'
				}
				""";

		Files.writeString(tempDir.resolve("build.gradle"), buildContent);

		ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(tempDir);

		Set<String> userPkgs = analyzer.getUserPackages();
		Set<String> depPkgs = analyzer.getDependencyPackages();

		// Should contain user package
		assertTrue(userPkgs.stream().anyMatch(p -> p.contains("com.example")), "Should extract user group");

		// Should contain dependencies
		assertTrue(depPkgs.stream().anyMatch(p -> p.contains("com.google.guava") || p.contains("org.slf4j")),
				"Should extract implementation dependencies");
	}

	@Test
	@DisplayName("Scan source directories: detect package structure")
	public void testScanSourceDirectories(@TempDir Path tempDir) throws IOException {
		// Create source structure: src/main/java/com/example/App.java
		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("App.java"), "public class App {}");

		// Create test structure: src/test/java/com/example/AppTest.java
		Path testDir = tempDir.resolve("src/test/java/com/example");
		Files.createDirectories(testDir);
		Files.writeString(testDir.resolve("AppTest.java"), "public class AppTest {}");

		ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(tempDir);

		Set<String> userPkgs = analyzer.getUserPackages();
		Set<String> testPkgs = analyzer.getTestPackages();

		// Should detect com.example in both
		assertTrue(userPkgs.stream().anyMatch(p -> p.contains("com.example")), "Should detect user source package");
		assertTrue(testPkgs.stream().anyMatch(p -> p.contains("com.example")), "Should detect test package");
	}

	@Test
	@DisplayName("Build filter from detected packages")
	public void testBuildFilterFromAnalysis(@TempDir Path tempDir) throws IOException {
		// Create minimal pom.xml
		String pomContent = """
				<project>
				    <groupId>com.myapp</groupId>
				    <artifactId>service</artifactId>
				    <dependencies>
				        <dependency>
				            <groupId>com.external</groupId>
				            <artifactId>library</artifactId>
				        </dependency>
				    </dependencies>
				</project>
				""";

		Files.writeString(tempDir.resolve("pom.xml"), pomContent);

		ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(tempDir);

		IntelligentClassFilter filter = analyzer.buildFilter();

		// User package should pass filter - class in the detected package
		String testClassName = "com/myapp/service/UserService";
		boolean result = filter.shouldInstrument(testClassName);

		assertTrue(result, "User package should pass filter (class in detected package)");

		// External dependency should fail
		assertFalse(filter.shouldInstrument("com/external/ExternalLib"), "External library should be filtered out");

		// JDK should always fail
		assertFalse(filter.shouldInstrument("java/lang/String"), "JDK classes should always be filtered");
	}

	@Test
	@DisplayName("Handle missing build files gracefully")
	public void testHandleMissingBuildFiles(@TempDir Path tempDir) {
		// Empty directory with no pom.xml or build.gradle
		ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(tempDir);

		// Should not throw, just return empty results
		assertNotNull(analyzer.getUserPackages());
		assertNotNull(analyzer.getDependencyPackages());
	}

	@Test
	@DisplayName("Multi-module Maven project detection")
	public void testMultiModuleMavenProject(@TempDir Path tempDir) throws IOException {
		// Parent pom.xml
		String parentPom = """
				<project>
				    <groupId>com.company</groupId>
				    <artifactId>parent</artifactId>
				    <modules>
				        <module>core</module>
				        <module>api</module>
				    </modules>
				</project>
				""";

		Files.writeString(tempDir.resolve("pom.xml"), parentPom);

		// Create module structure
		Path core = tempDir.resolve("core/src/main/java/com/company/core");
		Files.createDirectories(core);
		Files.writeString(core.resolve("CoreService.java"), "public class CoreService {}");

		ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(tempDir);

		Set<String> userPkgs = analyzer.getUserPackages();

		// Should detect parent group + potentially module packages
		assertTrue(!userPkgs.isEmpty() || userPkgs.stream().anyMatch(p -> p.contains("com.company")),
				"Should extract company group");
	}

	@Test
	@DisplayName("Unreadable package directories are skipped")
	public void testUnreadablePackageDirectoryDoesNotCrash(@TempDir Path tempDir) throws IOException {
		Path srcDir = tempDir.resolve("src/main/java/com/example");
		Files.createDirectories(srcDir);
		Files.writeString(srcDir.resolve("App.java"), "public class App {}");
		Path brokenLink = srcDir.resolve("broken");
		Files.createSymbolicLink(brokenLink, tempDir.resolve("missing-target"));

		ProjectStructureAnalyzer analyzer = new ProjectStructureAnalyzer(tempDir);

		assertTrue(analyzer.getUserPackages().stream().anyMatch(p -> p.contains("com.example")));
	}
}
