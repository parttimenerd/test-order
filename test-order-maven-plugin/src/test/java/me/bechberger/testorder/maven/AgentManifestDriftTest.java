package me.bechberger.testorder.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Drift test: keeps {@code docs/agent-manifest.json} in sync with the actual
 * Maven {@code @Mojo(name=...)} declarations and validates structural
 * invariants of the manifest itself.
 * <p>
 * The Gradle-side check (manifest task names resolve under
 * {@code TestOrderPlugin}) lives in the gradle plugin module.
 */
class AgentManifestDriftTest {

	private static final Pattern MOJO_NAME = Pattern.compile("@Mojo\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
	private static final Pattern TASK_BLOCK = Pattern
			.compile("\\{\\s*\"name\"\\s*:\\s*\"([^\"]+)\"" + "(?:[^{}]|\\{[^{}]*\\})*?" + "\\}", Pattern.DOTALL);

	@Test
	void manifestMavenGoalsMatchMojoSources() throws IOException {
		Path manifest = locateManifest();
		Path mojoDir = Paths.get("src/main/java/me/bechberger/testorder/maven");
		Assumptions.assumeTrue(Files.isDirectory(mojoDir), "Mojo source directory not present: " + mojoDir);

		Set<String> manifestMavenSuffixes = extractMavenSuffixes(manifest);
		Set<String> sourceMojoNames = extractMojoNames(mojoDir);

		Set<String> missingFromManifest = new TreeSet<>(sourceMojoNames);
		missingFromManifest.removeAll(manifestMavenSuffixes);
		Set<String> missingFromSource = new TreeSet<>(manifestMavenSuffixes);
		missingFromSource.removeAll(sourceMojoNames);

		assertThat(missingFromManifest)
				.as("@Mojo names in source not listed in docs/agent-manifest.json (add them or fix the maven field)")
				.isEmpty();
		assertThat(missingFromSource).as(
				"manifest 'maven' suffixes that have no matching @Mojo(name=...) in source (rename or remove from manifest)")
				.isEmpty();
	}

	@Test
	void everyTaskWithSupportsJsonHasJsonProperty() throws IOException {
		Path manifest = locateManifest();
		String body = Files.readString(manifest);

		Matcher m = TASK_BLOCK.matcher(body);
		List<String> violators = new ArrayList<>();
		while (m.find()) {
			String task = m.group();
			String name = m.group(1);
			boolean supportsJson = task.contains("\"supportsJson\"") && task.contains("\"supportsJson\": true");
			if (!supportsJson)
				continue;
			Matcher jp = Pattern.compile("\"jsonProperty\"\\s*:\\s*(?:\"([^\"]+)\"|null)").matcher(task);
			if (jp.find()) {
				String value = jp.group(1);
				if (value == null || value.isBlank()) {
					violators.add(name);
				}
			} else {
				violators.add(name);
			}
		}

		assertThat(violators).as("tasks with supportsJson=true must declare a non-null jsonProperty").isEmpty();
	}

	@Test
	void manifestIsBundledOnPluginClasspath() {
		assertThat(AgentManifestDriftTest.class.getResourceAsStream("/agent-manifest.json"))
				.as("agent-manifest.json must be on the plugin classpath (src/main/resources/agent-manifest.json)")
				.isNotNull();
	}

	@Test
	void bundledManifestMatchesDocsManifest() throws IOException {
		Path docs = locateManifest();
		String docsBody = Files.readString(docs).trim();
		try (InputStream in = AgentManifestDriftTest.class.getResourceAsStream("/agent-manifest.json")) {
			Assumptions.assumeTrue(in != null, "bundled manifest absent — covered by other test");
			String bundledBody = new String(in.readAllBytes()).trim();
			assertThat(bundledBody)
					.as("src/main/resources/agent-manifest.json must be byte-identical to docs/agent-manifest.json — "
							+ "after editing the docs copy, run: cp docs/agent-manifest.json "
							+ "test-order-maven-plugin/src/main/resources/agent-manifest.json && "
							+ "cp docs/agent-manifest.json test-order-gradle-plugin/src/main/resources/agent-manifest.json")
					.isEqualTo(docsBody);
		}
	}

	@Test
	void everyTaskHasValidStability() throws IOException {
		Path manifest = locateManifest();
		String body = Files.readString(manifest);
		Set<String> allowed = Set.of("stable", "deprecated", "experimental");

		Matcher m = TASK_BLOCK.matcher(body);
		List<String> violators = new ArrayList<>();
		while (m.find()) {
			String task = m.group();
			String name = m.group(1);
			Matcher s = Pattern.compile("\"stability\"\\s*:\\s*\"([^\"]+)\"").matcher(task);
			if (!s.find()) {
				violators.add(name + " (no stability field)");
				continue;
			}
			String value = s.group(1);
			if (!allowed.contains(value)) {
				violators.add(name + "=" + value);
			}
		}

		assertThat(violators)
				.as("tasks must declare stability ∈ {stable, deprecated, experimental} — see docs/AGENTS.md").isEmpty();
	}

	private static Path locateManifest() {
		Path[] candidates = {Paths.get("../docs/agent-manifest.json"), Paths.get("docs/agent-manifest.json"),
				Paths.get(System.getProperty("user.dir")).getParent() == null
						? Paths.get("docs/agent-manifest.json")
						: Paths.get(System.getProperty("user.dir")).getParent().resolve("docs/agent-manifest.json")};
		for (Path p : candidates) {
			if (Files.exists(p)) {
				return p;
			}
		}
		Assumptions.abort("docs/agent-manifest.json not found relative to " + Paths.get("").toAbsolutePath()
				+ " — skipping drift check");
		return candidates[0];
	}

	private static Set<String> extractMavenSuffixes(Path manifest) throws IOException {
		String body = Files.readString(manifest);
		Set<String> out = new LinkedHashSet<>();
		Matcher m = Pattern.compile("\"maven\"\\s*:\\s*\"test-order:([^\"]+)\"").matcher(body);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}

	private static Set<String> extractMojoNames(Path mojoDir) throws IOException {
		Set<String> out = new TreeSet<>();
		try (Stream<Path> files = Files.walk(mojoDir)) {
			List<Path> javaFiles = files.filter(p -> p.getFileName().toString().endsWith("Mojo.java")).toList();
			for (Path f : javaFiles) {
				String src = Files.readString(f);
				Matcher m = MOJO_NAME.matcher(src);
				while (m.find()) {
					out.add(m.group(1));
				}
			}
		}
		return out;
	}
}
