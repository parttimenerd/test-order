package me.bechberger.testorder.maven.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Verifies that tiered workflows treat nested JUnit test classes as normal
 * executable test classes when they appear in the dependency index.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "testorder.it", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NestedTieredWorkflowIT {

	private static final String VALIDATOR = "com.myapp.Validator";
	private static final String NESTED_TEST = "com.myapp.ValidatorParameterizedClassTest";

	private TestProject project;

	@BeforeAll
	void setup() {
		Path root = Paths.get("").toAbsolutePath();
		if (root.getFileName().toString().equals("test-order-maven-plugin")) {
			root = root.getParent();
		}
		project = new TestProject(root.resolve("samples/sample-junit6"),
				List.of("-Dtestorder.includePackages=com.myapp"));
	}

	@AfterAll
	void tearDown() {
		if (project != null) {
			project.restoreAll();
		}
	}

	@Test
	@Order(1)
	@DisplayName("learn+aggregate includes nested test class in dependency index")
	void learnAndAggregateIncludesNestedClass() {
		project.cleanAll();

		MavenResult learn = project.maven().learn();
		assertThat(learn.isSuccess()).as("learn phase failed").isTrue();

		MavenResult aggregate = project.maven().aggregate();
		assertThat(aggregate.isSuccess()).as("aggregate failed").isTrue();

		var index = project.loadIndex();
		assertThat(index).isNotNull();
		assertThat(index.testClasses()).contains(NESTED_TEST);
	}

	@Test
	@Order(2)
	@DisplayName("tiered-select writes nested class into tier files and run-tier executes that tier")
	void tieredSelectAndRunTierHandleNestedClass() throws IOException {
		MavenResult select = project.maven().tieredSelect(VALIDATOR);
		assertThat(select.isSuccess())
				.as("tiered-select failed: %s", select.output().substring(Math.max(0, select.output().length() - 500)))
				.isTrue();

		Path tier1 = project.path("target/test-order-tier1.txt");
		Path tier2 = project.path("target/test-order-tier2.txt");
		Path tier3 = project.path("target/test-order-tier3.txt");
		assertThat(tier1).exists();
		assertThat(tier2).exists();
		assertThat(tier3).exists();

		List<String> t1 = Files.readAllLines(tier1);
		List<String> t2 = Files.readAllLines(tier2);
		List<String> t3 = Files.readAllLines(tier3);
		List<String> all = new ArrayList<>(t1);
		all.addAll(t2);
		all.addAll(t3);

		assertThat(all).contains(NESTED_TEST);

		int tierWithNested = t1.contains(NESTED_TEST) ? 1 : (t2.contains(NESTED_TEST) ? 2 : 3);
		if (tierWithNested == 1) {
			// tier 1 is executed by tiered-select itself
			assertThat(select.output()).contains(NESTED_TEST.substring(0, NESTED_TEST.indexOf('$')));
		} else {
			MavenResult runTier = project.maven().runTier(tierWithNested);
			assertThat(runTier.isSuccess()).as("run-tier %s failed: %s", tierWithNested,
					runTier.output().substring(Math.max(0, runTier.output().length() - 500))).isTrue();
		}
	}
}
