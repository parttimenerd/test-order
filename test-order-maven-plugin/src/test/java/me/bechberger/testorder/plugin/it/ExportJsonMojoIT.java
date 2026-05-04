package me.bechberger.testorder.plugin.it;

import static me.bechberger.testorder.plugin.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Integration tests for the Maven export-json mojo.
 * <p>
 * Verifies both stdout and file-output paths for
 * {@code mvn test-order:export-json}.
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportJsonMojoIT {

	private static final String CALCULATOR_TEST = "com.example.app.CalculatorTest";

	private TestProject project;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve("samples/sample-basic"),
				List.of("-Dtestorder.includePackages=com.example"));
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.restoreAll();
		}
	}

	@Test
	@Order(1)
	@DisplayName("learn mode creates index for export-json")
	void learnCreatesIndex() {
		project.cleanAll();
		MavenResult learn = project.maven().learn();
		assertThat(learn).succeeded();
		assertThat(project.loadIndex()).isNotNull();
	}

	@Test
	@Order(2)
	@DisplayName("export-json prints JSON to stdout")
	void exportJsonPrintsToStdout() {
		MavenResult result = project.maven().exportJson();
		assertThat(result).succeeded().outputContains("\"exportVersion\"").outputContains("\"depFormatVersion\"")
				.outputContains("\"testClassCount\"").outputContains(CALCULATOR_TEST);
	}

	@Test
	@Order(3)
	@DisplayName("export-json writes JSON output file")
	void exportJsonWritesOutputFile() {
		String out = "target/deps-export.json";
		project.deleteIfExists(out);

		MavenResult result = project.maven().exportJsonTo(out);
		assertThat(result).succeeded();

		String json = project.readFile(out);
		assertThat(json).isNotNull().contains("\"exportVersion\"").contains("\"depFormatVersion\"")
				.contains("\"tests\"").contains(CALCULATOR_TEST);
	}
}
