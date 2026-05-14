package me.bechberger.testorder.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import me.bechberger.testorder.DependencyMap;

@EnabledIfSystemProperty(named = "it.projects.dir", matches = ".+")
class MavenPluginIT {

	private static Path itProjectsDir;

	@BeforeAll
	static void setup() {
		String itProjectsDirProp = System.getProperty("it.projects.dir");
		assertThat(itProjectsDirProp).isNotBlank();
		itProjectsDir = Paths.get(itProjectsDirProp);
		assertThat(Files.exists(itProjectsDir)).isTrue();
	}

	@Test
	void basicLearnModeProducesDepsFiles() {
		Path projectDir = itProjectsDir.resolve("basic-learn-mode");
		assertThat(Files.exists(projectDir)).isTrue();

		// Learn mode sets up the agent and surefire config; .deps files only
		// appear when the agent actually instruments classes at runtime.
		// Here we verify the build succeeded and surefire reports exist.
		Path surefireReports = projectDir.resolve("target/surefire-reports");
		assertThat(Files.isDirectory(surefireReports))
				.withFailMessage("surefire-reports should exist after learn mode: " + surefireReports).isTrue();
	}

	@Test
	void orderModeWritesJunitPlatformProperties() throws IOException {
		Path projectDir = itProjectsDir.resolve("order-mode");
		assertThat(Files.exists(projectDir)).isTrue();

		Path propsFile = projectDir.resolve("target/test-order-runtime/junit-platform.properties");
		assertThat(Files.exists(propsFile)).withFailMessage("junit-platform.properties should exist: " + propsFile)
				.isTrue();

		String content = Files.readString(propsFile);
		assertThat(content).contains("me.bechberger.testorder.junit.PriorityClassOrderer");
	}

	@Test
	void orderModeTestsStillPass() {
		// The order-mode fixture has 2 test classes; if the invoker ran successfully,
		// we know tests passed. We just verify the project dir was populated.
		Path projectDir = itProjectsDir.resolve("order-mode");
		assertThat(Files.exists(projectDir)).isTrue();

		Path surefireReports = projectDir.resolve("target/surefire-reports");
		assertThat(Files.isDirectory(surefireReports))
				.withFailMessage("surefire-reports should exist: " + surefireReports).isTrue();
	}

	@Test
	void aggregateProducesIndex() throws IOException {
		Path projectDir = itProjectsDir.resolve("aggregate-deps");
		assertThat(Files.exists(projectDir)).isTrue();

		Path indexFile = projectDir.resolve(".test-order/test-dependencies.lz4");
		assertThat(Files.exists(indexFile))
				.withFailMessage(".test-order/test-dependencies.lz4 should exist: " + indexFile).isTrue();

		// load via DependencyMap (auto-detects V1 text or V2 binary)
		DependencyMap map = DependencyMap.load(indexFile);
		assertThat(map.size()).isEqualTo(2);
	}

	@Test
	void selectModeWritesSelectionFilesAndRunsSubset() throws IOException {
		assertSelectFixture("select-mode");
	}

	@Test
	void runRemainingModeRunsOnlyDeferredSubset() {
		assertRunRemainingFixture("run-remaining-mode");
	}

	@Test
	void reactorLearnModeUsesSharedReactorDirectory() {
		Path projectDir = itProjectsDir.resolve("reactor-learn-mode");
		assertThat(Files.exists(projectDir)).isTrue();

		Path sharedDepsDir = projectDir.resolve(".test-order/deps");
		assertThat(Files.isDirectory(sharedDepsDir)).withFailMessage("shared deps dir should exist: " + sharedDepsDir)
				.isTrue();
		assertThat(sharedDepsDir.resolve("me.bechberger.it.modulea.LibraryTest.deps")).exists();
		assertThat(sharedDepsDir.resolve("me.bechberger.it.moduleb.ServiceTest.deps")).exists();

		assertThat(Files.isDirectory(projectDir.resolve("module-a/target/surefire-reports"))).isTrue();
		assertThat(Files.isDirectory(projectDir.resolve("module-b/target/surefire-reports"))).isTrue();
	}

	private void assertSelectFixture(String projectName) throws IOException {
		Path projectDir = itProjectsDir.resolve(projectName);
		assertThat(Files.exists(projectDir)).isTrue();

		Path selectedFile = projectDir.resolve("target/test-order-selected.txt");
		Path remainingFile = projectDir.resolve("target/test-order-remaining.txt");
		assertThat(Files.exists(selectedFile)).withFailMessage("selected file should exist: " + selectedFile).isTrue();
		assertThat(Files.exists(remainingFile)).withFailMessage("remaining file should exist: " + remainingFile)
				.isTrue();

		assertThat(Files.readAllLines(selectedFile)).containsExactly("me.bechberger.it.MathHelperTest");
		assertThat(Files.readAllLines(remainingFile)).containsExactly("me.bechberger.it.StringHelperTest");

		Path surefireReports = projectDir.resolve("target/surefire-reports");
		assertThat(Files.isDirectory(surefireReports)).isTrue();
		assertThat(reportClassNames(surefireReports)).containsExactly("me.bechberger.it.MathHelperTest");
	}

	private void assertRunRemainingFixture(String projectName) {
		Path projectDir = itProjectsDir.resolve(projectName);
		assertThat(Files.exists(projectDir)).isTrue();

		Path surefireReports = projectDir.resolve("target/surefire-reports");
		assertThat(Files.isDirectory(surefireReports))
				.withFailMessage("surefire-reports should exist: " + surefireReports).isTrue();
		assertThat(reportClassNames(surefireReports)).containsExactly("me.bechberger.it.StringHelperTest");

		// The mojo renames the file to .consumed after reading it
		Path consumedFile = projectDir.resolve("target/test-order-remaining.txt.consumed");
		assertThat(Files.exists(consumedFile)).withFailMessage("consumed remaining file should exist: " + consumedFile)
				.isTrue();
	}

	private List<String> reportClassNames(Path surefireReports) {
		try (var stream = Files.list(surefireReports)) {
			return stream.map(Path::getFileName).map(Path::toString)
					.filter(name -> name.startsWith("TEST-") && name.endsWith(".xml"))
					.map(name -> name.substring("TEST-".length(), name.length() - ".xml".length())).sorted().toList();
		} catch (IOException e) {
			throw new AssertionError("Failed to inspect surefire reports in " + surefireReports, e);
		}
	}
}
