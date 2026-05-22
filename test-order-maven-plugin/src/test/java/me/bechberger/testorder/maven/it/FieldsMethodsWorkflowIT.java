package me.bechberger.testorder.maven.it;

import static me.bechberger.testorder.maven.it.TestOrderAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import me.bechberger.testorder.DependencyMap;

/**
 * End-to-end workflow tests against test-order-fields-methods-example. Focuses
 * on member-level dependency capture and since-last-run snapshot behavior.
 * <p>
 * Enable with: {@code -Dtestorder.it=true}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldsMethodsWorkflowIT {

	private static final String ITEM_METHODS_TEST = "com.example.coverage.ItemMethodsTest";
	private static final String METADATA_METHODS_TEST = "com.example.coverage.MetadataMethodsTest";
	private static final String WIDE_COVERAGE_SERVICE = "com.example.coverage.WideCoverageService";
	private static final String STATE_MACHINE = "com.example.fields.StateMachine";

	private static final String WIDE_COVERAGE_SERVICE_SRC = "src/main/java/com/example/coverage/WideCoverageService.java";
	private static final String STATE_MACHINE_SRC = "src/main/java/com/example/fields/StateMachine.java";

	TestProject project;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve("test-order-example/test-order-fields-methods-example"),
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
	@DisplayName("Learn MEMBER: captures member-level dependencies in the binary index")
	void learnFullMemberCapturesMemberDependencies() {
		project.cleanAll();

		MavenResult result = project.maven().run("clean", "test", "-Dtestorder.mode=learn",
				"-Dtestorder.instrumentation.mode=MEMBER");
		assertThat(result).succeeded().outputContains("Tests run:");

		DependencyMap depMap = project.loadIndex();
		assertThat(depMap).isLoaded().hasSize(16);
		org.assertj.core.api.Assertions.assertThat(depMap.hasMemberDeps())
				.as("MEMBER learn mode should persist member-level dependencies").isTrue();

		Set<String> itemMembers = depMap.getMemberDeps(ITEM_METHODS_TEST);
		Set<String> metadataMembers = depMap.getMemberDeps(METADATA_METHODS_TEST);

		org.assertj.core.api.Assertions.assertThat(itemMembers).contains(WIDE_COVERAGE_SERVICE + "#addItem")
				.contains(WIDE_COVERAGE_SERVICE + "#getItemCount");
		org.assertj.core.api.Assertions.assertThat(metadataMembers).contains(WIDE_COVERAGE_SERVICE + "#setMetadata")
				.contains(WIDE_COVERAGE_SERVICE + "#getMetadata");
		org.assertj.core.api.Assertions.assertThat(metadataMembers).doesNotContain(WIDE_COVERAGE_SERVICE + "#addItem");
	}

	@Test
	@Order(5)
	@DisplayName("show-order: displays scoring with dependency overlap after source modification")
	void showOrderDisplaysScoringAfterSourceChange() {
		project.cleanAll();

		MavenResult learn = project.maven().run("clean", "test", "-Dtestorder.mode=learn",
				"-Dtestorder.instrumentation.mode=MEMBER");
		assertThat(learn).succeeded();

		MavenResult snapshot = project.maven().snapshot();
		assertThat(snapshot).succeeded();

		// Modify addItem method body so since-last-run detects WideCoverageService as
		// changed
		project.replaceInFile(WIDE_COVERAGE_SERVICE_SRC, "\t\taccessCount++;\n", "\t\taccessCount += 2;\n");

		try {
			MavenResult showOrder = project.maven().run("test-order:show-order",
					"-Dtestorder.changeMode=since-last-run");
			assertThat(showOrder).succeeded();

			// Changed class should be reported
			org.assertj.core.api.Assertions.assertThat(showOrder.output()).contains("Changed classes:")
					.contains(WIDE_COVERAGE_SERVICE);

			// All tests should appear in the output
			org.assertj.core.api.Assertions.assertThat(showOrder.output()).contains("ItemMethodsTest")
					.contains("MetadataMethodsTest").contains("CombinedMethodsTest");

			// Tests that depend on the changed class should have positive dep overlap
			int itemScore = extractDisplayedScore(showOrder.output(), "ItemMethodsTest");
			int combinedScore = extractDisplayedScore(showOrder.output(), "CombinedMethodsTest");
			org.assertj.core.api.Assertions.assertThat(itemScore)
					.as("ItemMethodsTest depends on WideCoverageService and should have a positive score")
					.isGreaterThan(0);
			org.assertj.core.api.Assertions.assertThat(combinedScore)
					.as("CombinedMethodsTest depends on WideCoverageService and should have a positive score")
					.isGreaterThan(0);
		} finally {
			project.restoreAll();
		}
	}

	@Test
	@Order(10)
	@DisplayName("since-last-run: timestamp-only source touch does not produce changed classes")
	void sinceLastRunIgnoresTimestampOnlyTouch() throws Exception {
		project.cleanAll();

		MavenResult learn = project.maven().learn();
		assertThat(learn).succeeded();

		MavenResult snapshot = project.maven().snapshot();
		assertThat(snapshot).succeeded().outputContains("[test-order] Snapshot:");

		Path sourceFile = project.path(STATE_MACHINE_SRC);
		FileTime originalTime = Files.getLastModifiedTime(sourceFile);
		try {
			Files.setLastModifiedTime(sourceFile, FileTime.fromMillis(originalTime.toMillis() + 5_000));

			MavenResult showOrder = project.maven().run("test-order:show-order",
					"-Dtestorder.changeMode=since-last-run");
			assertThat(showOrder).succeeded().outputContains("StateMachineTest");
			org.assertj.core.api.Assertions.assertThat(showOrder.output())
					.doesNotContain("Changed classes: [" + STATE_MACHINE + "]");
		} finally {
			Files.setLastModifiedTime(sourceFile, originalTime);
		}
	}

	private static int extractDisplayedScore(String output, String testSimpleName) {
		return output.lines().filter(line -> line.contains(testSimpleName)).map(line -> line.trim().split("\\s+"))
				.filter(parts -> parts.length >= 3).map(parts -> Integer.parseInt(parts[2])).findFirst()
				.orElseThrow(() -> new AssertionError(
						"No score line found for " + testSimpleName + " in output:\n" + output));
	}
}
