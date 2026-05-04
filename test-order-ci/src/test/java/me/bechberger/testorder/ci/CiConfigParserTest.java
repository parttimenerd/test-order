package me.bechberger.testorder.ci;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CiConfigParserTest {

	@Test
	void testParseGithubConfig(@TempDir Path tempDir) throws Exception {
		String yaml = """
				ci:
				  github:
				    owner: bechberger
				    repo: test-order
				    workflow: integration-tests.yml
				    artifact-name: test-order-deps
				    branch: main
				""";

		Path configFile = tempDir.resolve(".test-order-ci.yml");
		Files.writeString(configFile, yaml);

		CiConfig config = CiConfigParser.parse(configFile);

		assertNotNull(config.getGithub());
		assertEquals("bechberger", config.getGithub().getOwner());
		assertEquals("test-order", config.getGithub().getRepo());
		assertEquals("integration-tests.yml", config.getGithub().getWorkflow());
		assertEquals("test-order-deps", config.getGithub().getArtifactName());
		assertEquals("main", config.getGithub().getBranch());
	}

	@Test
	void testParseHttpConfig(@TempDir Path tempDir) throws Exception {
		String yaml = """
				ci:
				  http:
				    url: https://ci.example.com/artifacts/deps.json
				    auth: bearer
				    token-env: CI_TOKEN
				""";

		Path configFile = tempDir.resolve(".test-order-ci.yml");
		Files.writeString(configFile, yaml);

		CiConfig config = CiConfigParser.parse(configFile);

		assertNotNull(config.getHttp());
		assertEquals("https://ci.example.com/artifacts/deps.json", config.getHttp().getUrl());
		assertEquals("bearer", config.getHttp().getAuth());
		assertEquals("CI_TOKEN", config.getHttp().getTokenEnv());
	}

	@Test
	void testParseBothConfigs(@TempDir Path tempDir) throws Exception {
		String yaml = """
				ci:
				  github:
				    owner: bechberger
				    repo: test-order
				    workflow: integration-tests.yml
				    artifact-name: test-order-deps
				  http:
				    url: https://ci.example.com/artifacts/deps.json
				""";

		Path configFile = tempDir.resolve(".test-order-ci.yml");
		Files.writeString(configFile, yaml);

		CiConfig config = CiConfigParser.parse(configFile);

		assertNotNull(config.getGithub());
		assertNotNull(config.getHttp());
	}

	@Test
	void testMissingConfigFile(@TempDir Path tempDir) {
		Path configFile = tempDir.resolve(".test-order-ci.yml");

		assertThrows(CiConfigParser.CiConfigException.class, () -> CiConfigParser.parse(configFile));
	}

	@Test
	void testEmptyConfig(@TempDir Path tempDir) throws Exception {
		Path configFile = tempDir.resolve(".test-order-ci.yml");
		Files.writeString(configFile, "");

		assertThrows(CiConfigParser.CiConfigException.class, () -> CiConfigParser.parse(configFile));
	}

	@Test
	void testMissingCiSection(@TempDir Path tempDir) throws Exception {
		String yaml = """
				some:
				  other: config
				""";

		Path configFile = tempDir.resolve(".test-order-ci.yml");
		Files.writeString(configFile, yaml);

		assertThrows(CiConfigParser.CiConfigException.class, () -> CiConfigParser.parse(configFile));
	}

	@Test
	void testInvalidGithubConfig(@TempDir Path tempDir) throws Exception {
		String yaml = """
				ci:
				  github:
				    owner: bechberger
				    # missing repo, workflow, artifact-name
				""";

		Path configFile = tempDir.resolve(".test-order-ci.yml");
		Files.writeString(configFile, yaml);

		assertThrows(CiConfigParser.CiConfigException.class, () -> CiConfigParser.parse(configFile));
	}

	@Test
	void testInvalidHttpConfig(@TempDir Path tempDir) throws Exception {
		String yaml = """
				ci:
				  http:
				    # missing url
				""";

		Path configFile = tempDir.resolve(".test-order-ci.yml");
		Files.writeString(configFile, yaml);

		assertThrows(CiConfigParser.CiConfigException.class, () -> CiConfigParser.parse(configFile));
	}

	@Test
	void testDefaultBranch(@TempDir Path tempDir) throws Exception {
		String yaml = """
				ci:
				  github:
				    owner: bechberger
				    repo: test-order
				    workflow: integration-tests.yml
				    artifact-name: test-order-deps
				""";

		Path configFile = tempDir.resolve(".test-order-ci.yml");
		Files.writeString(configFile, yaml);

		CiConfig config = CiConfigParser.parse(configFile);
		assertEquals("main", config.getGithub().getBranch());
	}
}
