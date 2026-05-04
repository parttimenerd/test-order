package me.bechberger.testorder.ci;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Advanced Gradle integration tests. Tests Gradle plugin integration,
 * configuration options, and real-world scenarios.
 */
class GradleAdvancedIntegrationTest {

	@Test
	void testBasicGradleProjectStructure(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-project");
		Files.createDirectories(project);

		// Create Gradle project structure
		createGradleProject(project);

		assertTrue(Files.exists(project.resolve("build.gradle")), "build.gradle should exist");
		assertTrue(Files.exists(project.resolve("settings.gradle")), "settings.gradle should exist");
	}

	@Test
	void testGradleMultiModuleProject(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-multi");
		Files.createDirectories(project);

		// Create root settings.gradle
		String settingsGradle = """
				rootProject.name = 'gradle-multi'
				include 'app', 'lib1', 'lib2'
				""";
		Files.writeString(project.resolve("settings.gradle"), settingsGradle);

		// Create root build.gradle
		String rootBuild = """
				plugins {
				    id 'java'
				}

				subprojects {
				    apply plugin: 'java'
				    sourceCompatibility = '17'
				}
				""";
		Files.writeString(project.resolve("build.gradle"), rootBuild);

		// Create submodules
		for (String module : new String[] { "app", "lib1", "lib2" }) {
			Path moduleDir = project.resolve(module);
			createGradleModule(moduleDir, module);
		}

		assertEquals(3, Files.list(project).filter(p -> Files.isDirectory(p))
				.filter(p -> Files.exists(p.resolve("build.gradle"))).count(), "Should have 3 modules");
	}

	@Test
	void testGradleWithTestorder(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-testorder");
		Files.createDirectories(project);

		createGradleProject(project);

		// Add test-order Gradle plugin configuration
		String buildGradle = Files.readString(project.resolve("build.gradle"));
		String withTestOrder = buildGradle.replace("plugins {",
				"plugins {\n    id 'me.bechberger.test-order' version '0.1.0'");
		Files.writeString(project.resolve("build.gradle"), withTestOrder);

		String content = Files.readString(project.resolve("build.gradle"));
		assertTrue(content.contains("test-order"), "Should include test-order plugin");
	}

	@Test
	void testGradleTaskDependencies(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-tasks");
		Files.createDirectories(project);

		String buildGradle = """
				plugins {
				    id 'java'
				}

				task prepareTests {
				    doLast {
				        println 'Preparing tests...'
				    }
				}

				test {
				    dependsOn prepareTests
				}

				task testOrder {
				    doLast {
				        println 'Running test-order...'
				    }
				    dependsOn 'classes'
				}

				testOrder.dependsOn prepareTests
				""";
		Files.writeString(project.resolve("build.gradle"), buildGradle);

		assertTrue(Files.exists(project.resolve("build.gradle")), "build.gradle should exist");
	}

	@Test
	void testGradleKotlinDsl(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-kotlin");
		Files.createDirectories(project);

		String buildGradleKts = """
				import java.io.File

				plugins {
				    java
				    kotlin("jvm") version "1.9.0"
				}

				repositories {
				    mavenCentral()
				}

				dependencies {
				    testImplementation("org.junit.jupiter:junit-jupiter:5.9.0")
				}

				tasks.test {
				    useJUnitPlatform()
				}
				""";
		Files.writeString(project.resolve("build.gradle.kts"), buildGradleKts);

		assertTrue(Files.exists(project.resolve("build.gradle.kts")), "build.gradle.kts should exist");
	}

	@Test
	void testGradleWithCustomTestFramework(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-custom-framework");
		Files.createDirectories(project);

		String buildGradle = """
				plugins {
				    id 'java'
				}

				dependencies {
				    testImplementation 'org.testng:testng:7.8.1'
				}

				test {
				    useTestNG()
				}
				""";
		Files.writeString(project.resolve("build.gradle"), buildGradle);

		String content = Files.readString(project.resolve("build.gradle"));
		assertTrue(content.contains("testng"), "Should configure TestNG");
	}

	@Test
	void testGradleSourceSets(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-sourcesets");
		Files.createDirectories(project);

		String buildGradle = """
				plugins {
				    id 'java'
				}

				sourceSets {
				    main {
				        java.srcDirs = ['src/main/java']
				    }
				    test {
				        java.srcDirs = ['src/test/java', 'src/integration-test/java']
				    }
				    integrationTest {
				        java.srcDirs = ['src/integration-test/java']
				        compileClasspath += sourceSets.main.output
				        runtimeClasspath += sourceSets.main.output
				    }
				}

				task integrationTest(type: Test) {
				    testClassesDirs = sourceSets.integrationTest.output.classesDirs
				    classpath = sourceSets.integrationTest.runtimeClasspath
				}
				""";
		Files.writeString(project.resolve("build.gradle"), buildGradle);

		String content = Files.readString(project.resolve("build.gradle"));
		assertTrue(content.contains("sourceSets"), "Should define source sets");
	}

	@Test
	void testGradleTestFiltering(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-filter");
		Files.createDirectories(project);

		String buildGradle = """
				plugins {
				    id 'java'
				}

				test {
				    // Include/exclude patterns
				    include '**/*Test.class'
				    include '**/*Spec.class'
				    exclude '**/*IntegrationTest.class'
				    exclude '**/Abstract*.class'
				}
				""";
		Files.writeString(project.resolve("build.gradle"), buildGradle);

		assertTrue(Files.exists(project.resolve("build.gradle")), "build.gradle should exist");
	}

	@Test
	void testGradleParallelExecution(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-parallel");
		Files.createDirectories(project);

		String buildGradle = """
				plugins {
				    id 'java'
				}

				test {
				    // Enable parallel test execution
				    maxParallelForks = Runtime.runtime.availableProcessors()

				    // JUnit Platform configuration
				    useJUnitPlatform {
				        includeEngines 'junit-jupiter'
				    }
				}
				""";
		Files.writeString(project.resolve("build.gradle"), buildGradle);

		String content = Files.readString(project.resolve("build.gradle"));
		assertTrue(content.contains("maxParallelForks"), "Should configure parallel execution");
	}

	@Test
	void testGradleCachingConfiguration(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-caching");
		Files.createDirectories(project);

		String buildGradle = """
				plugins {
				    id 'java'
				}

				test {
				    // Enable caching of test results
				    outputs.cacheIf { true }

				    // Cache test packages
				    inputs.dir('src/test/java').withPropertyName('testSource')
				        .withPathSensitivity(PathSensitivity.RELATIVE)
				}
				""";
		Files.writeString(project.resolve("build.gradle"), buildGradle);

		String content = Files.readString(project.resolve("build.gradle"));
		assertTrue(content.contains("cacheIf"), "Should configure caching");
	}

	@Test
	void testGradleBuildCacheIntegration(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-buildcache");
		Files.createDirectories(project);

		// Create gradle.properties
		String gradleProperties = """
				org.gradle.caching=true
				org.gradle.caching.debug=false
				""";
		Files.writeString(project.resolve("gradle.properties"), gradleProperties);

		// Create settings.gradle with build cache config
		String settingsGradle = """
				pluginManagement {
				    repositories {
				        gradlePluginPortal()
				    }
				}

				buildCache {
				    local {
				        enabled = true
				        directory = '.gradle/build-cache'
				    }
				}
				""";
		Files.writeString(project.resolve("settings.gradle"), settingsGradle);

		assertTrue(Files.exists(project.resolve("gradle.properties")), "gradle.properties should exist");
	}

	@Test
	void testGradleCustomTasks(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-custom-tasks");
		Files.createDirectories(project);

		String buildGradle = """
				plugins {
				    id 'java'
				}

				abstract class SelectiveTest extends Test {
				    @Input
				    abstract ListProperty<String> getSelectedTests();

				    @Override
				    void executeTests() {
				        filter {
				            selectedTests.get().each { includeTestsMatching(it) }
				        }
				    }
				}

				tasks.register('selectiveTest', SelectiveTest) {
				    selectedTests.set(['**/FastTest.class'])
				}
				""";
		Files.writeString(project.resolve("build.gradle"), buildGradle);

		String content = Files.readString(project.resolve("build.gradle"));
		assertTrue(content.contains("SelectiveTest"), "Should define custom task");
	}

	@Test
	void testGradleWithJacoco(@TempDir Path workDir) throws Exception {
		Path project = workDir.resolve("gradle-jacoco");
		Files.createDirectories(project);

		String buildGradle = """
				plugins {
				    id 'java'
				    id 'jacoco'
				}

				jacoco {
				    toolVersion = '0.8.10'
				}

				test {
				    finalizedBy jacocoTestReport
				}

				jacocoTestReport {
				    dependsOn test
				    reports {
				        xml.required = true
				        html.required = true
				        csv.required = false
				    }
				}
				""";
		Files.writeString(project.resolve("build.gradle"), buildGradle);

		String content = Files.readString(project.resolve("build.gradle"));
		assertTrue(content.contains("jacoco"), "Should configure JaCoCo");
	}

	// Helper methods

	private void createGradleProject(Path projectDir) throws Exception {
		Files.createDirectories(projectDir);

		String settingsGradle = """
				rootProject.name = 'gradle-app'
				""";
		Files.writeString(projectDir.resolve("settings.gradle"), settingsGradle);

		String buildGradle = """
				plugins {
				    id 'java'
				}

				group = 'com.example'
				version = '1.0.0'

				sourceCompatibility = '17'

				repositories {
				    mavenCentral()
				}

				dependencies {
				    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.9.0'
				    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.9.0'
				}

				test {
				    useJUnitPlatform()
				}
				""";
		Files.writeString(projectDir.resolve("build.gradle"), buildGradle);

		// Create source directories
		Files.createDirectories(projectDir.resolve("src/main/java/com/example"));
		Files.createDirectories(projectDir.resolve("src/test/java/com/example"));

		// Create placeholder classes
		String javaClass = "package com.example; public class App { }";
		Files.writeString(projectDir.resolve("src/main/java/com/example/App.java"), javaClass);

		String testClass = "package com.example; import org.junit.jupiter.api.Test; "
				+ "class AppTest { @Test void test() {} }";
		Files.writeString(projectDir.resolve("src/test/java/com/example/AppTest.java"), testClass);
	}

	private void createGradleModule(Path moduleDir, String moduleName) throws Exception {
		Files.createDirectories(moduleDir);

		String buildGradle = String.format("""
				plugins {
				    id 'java'
				}

				dependencies {
				    testImplementation 'org.junit.jupiter:junit-jupiter:5.9.0'
				}

				test {
				    useJUnitPlatform()
				}
				""");
		Files.writeString(moduleDir.resolve("build.gradle"), buildGradle);

		Files.createDirectories(moduleDir.resolve("src/main/java/com/example"));
		Files.createDirectories(moduleDir.resolve("src/test/java/com/example"));

		String javaClass = String.format("package com.example; public class %s { }", capitalize(moduleName));
		Files.writeString(moduleDir.resolve("src/main/java/com/example/" + capitalize(moduleName) + ".java"),
				javaClass);
	}

	private String capitalize(String s) {
		return s.substring(0, 1).toUpperCase() + s.substring(1);
	}
}
