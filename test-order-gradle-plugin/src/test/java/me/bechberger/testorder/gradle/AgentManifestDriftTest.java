package me.bechberger.testorder.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Drift test: every Gradle task name listed in
 * {@code docs/agent-manifest.json} must register on a project that has
 * {@link TestOrderPlugin} applied.
 */
class AgentManifestDriftTest {

	@Test
	void manifestGradleTasksRegisterOnProject() throws IOException {
		Path manifest = locateManifest();
		Set<String> manifestGradleNames = extractGradleNames(manifest);
		assertFalse(manifestGradleNames.isEmpty(), "manifest must declare at least one gradle task");

		// Use a multi-project layout so conditionally-registered tasks like
		// testOrderAggregateAll (registered only when subprojects exist) are present.
		Project root = ProjectBuilder.builder().withName("root").build();
		Project sub = ProjectBuilder.builder().withName("sub").withParent(root).build();
		root.getPluginManager().apply("java");
		sub.getPluginManager().apply("java");
		new TestOrderPlugin().apply(root);
		new TestOrderPlugin().apply(sub);

		List<String> missing = new ArrayList<>();
		for (String name : manifestGradleNames) {
			if (root.getTasks().findByName(name) == null && sub.getTasks().findByName(name) == null) {
				missing.add(name);
			}
		}

		assertTrue(missing.isEmpty(),
				"manifest gradle tasks not registered by TestOrderPlugin (rename or remove from manifest): "
						+ missing);
	}

	@Test
	void manifestIsBundledOnPluginClasspath() {
		assertNotNull(AgentManifestDriftTest.class.getResourceAsStream("/agent-manifest.json"),
				"agent-manifest.json must be on the plugin classpath (src/main/resources/agent-manifest.json)");
	}

	@Test
	void bundledManifestMatchesDocsManifest() throws IOException {
		Path docs = locateManifest();
		String docsBody = Files.readString(docs).trim();
		try (InputStream in = AgentManifestDriftTest.class.getResourceAsStream("/agent-manifest.json")) {
			Assumptions.assumeTrue(in != null, "bundled manifest absent — covered by other test");
			String bundledBody = new String(in.readAllBytes()).trim();
			assertEquals(docsBody, bundledBody,
					"src/main/resources/agent-manifest.json must be byte-identical to docs/agent-manifest.json — "
							+ "after editing docs/agent-manifest.json, copy it into both plugins' src/main/resources");
		}
	}

	private static Path locateManifest() {
		Path[] candidates = {
				Paths.get("../docs/agent-manifest.json"),
				Paths.get("docs/agent-manifest.json"),
				Paths.get(System.getProperty("user.dir")).getParent() == null
						? Paths.get("docs/agent-manifest.json")
						: Paths.get(System.getProperty("user.dir")).getParent().resolve("docs/agent-manifest.json")
		};
		for (Path p : candidates) {
			if (Files.exists(p)) {
				return p;
			}
		}
		Assumptions.abort("docs/agent-manifest.json not found relative to "
				+ Paths.get("").toAbsolutePath() + " — skipping drift check");
		return candidates[0];
	}

	private static Set<String> extractGradleNames(Path manifest) throws IOException {
		String body = Files.readString(manifest);
		Set<String> out = new LinkedHashSet<>();
		Matcher m = Pattern.compile("\"gradle\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}
}
