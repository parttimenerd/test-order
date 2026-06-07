package me.bechberger.testorder.ci;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Parses CI download configuration from YAML. The config location is
 * {@code .test-order/download-config.yml}.
 */
public class CiConfigParser {
	private static final String DEFAULT_CONFIG_FILE = ".test-order/download-config.yml";

	/**
	 * Parse config file from default location relative to the current directory.
	 */
	public static CiConfig parseDefault() throws IOException, CiConfigException {
		return parseFromProjectDir(Paths.get("."));
	}

	/**
	 * Resolve the config file relative to {@code projectDir}.
	 *
	 * @return the parsed config, or {@code null} if no config file exists
	 */
	public static CiConfig parseFromProjectDir(Path projectDir) throws IOException, CiConfigException {
		Path primary = projectDir.resolve(DEFAULT_CONFIG_FILE);
		if (Files.exists(primary)) {
			return parse(primary);
		}
		return null;
	}

	/**
	 * Parse config file from specified path
	 */
	public static CiConfig parse(Path configPath) throws IOException, CiConfigException {
		if (!Files.exists(configPath)) {
			throw new CiConfigException("Config file not found: " + configPath);
		}

		try {
			String content;
			try {
				content = Files.readString(configPath, StandardCharsets.UTF_8);
			} catch (IOException e) {
				// Fall back to Latin-1 which can decode any byte sequence
				content = Files.readString(configPath, StandardCharsets.ISO_8859_1);
			}
			@SuppressWarnings("unchecked")
			Map<String, Object> configMap = new Yaml().load(content);

			if (configMap == null) {
				throw new CiConfigException("Config file is empty: " + configPath);
			}

			CiConfig config = new CiConfig(configMap);

			// Validate that at least one CI config is provided
			if (config.getGithub() == null && config.getHttp() == null && config.getGitlab() == null
					&& config.getMaven() == null) {
				throw new CiConfigException("Config must have a 'github', 'gitlab', 'maven', or 'http' section");
			}

			// Validate GitHub config if present
			if (config.getGithub() != null && !config.getGithub().isValid()) {
				throw new CiConfigException(
						"GitHub config is invalid: missing owner, repo, workflow, or artifact-name");
			}

			// Validate HTTP config if present
			if (config.getHttp() != null && !config.getHttp().isValid()) {
				throw new CiConfigException("HTTP config is invalid: missing url");
			}

			// Validate GitLab config if present
			if (config.getGitlab() != null && !config.getGitlab().isValid()) {
				throw new CiConfigException("GitLab config is invalid: missing project-id or job-name");
			}

			// Validate Maven config if present
			if (config.getMaven() != null && !config.getMaven().isValid()) {
				throw new CiConfigException("Maven config is invalid: url, group-id, and artifact-id are required");
			}

			// Validate proxy config if present
			if (config.getProxy() != null && !config.getProxy().isValid()) {
				throw new CiConfigException("Proxy config is invalid: missing host, or port out of range 1-65535");
			}

			return config;
		} catch (CiConfigException e) {
			throw e;
		} catch (IOException e) {
			throw new CiConfigException("Error reading config file: " + e.getMessage(), e);
		} catch (RuntimeException e) {
			throw new CiConfigException("Error parsing config file " + configPath + ": " + e.getClass().getSimpleName()
					+ ": " + e.getMessage(), e);
		}
	}

	/**
	 * Check if a config file exists in or below the current directory.
	 */
	public static boolean defaultConfigExists() {
		return configExistsIn(Paths.get("."));
	}

	/**
	 * Check if a config file exists in the given project directory.
	 */
	public static boolean configExistsIn(Path projectDir) {
		return Files.exists(projectDir.resolve(DEFAULT_CONFIG_FILE));
	}

	/**
	 * Exception thrown when config parsing fails
	 */
	public static class CiConfigException extends Exception {
		public CiConfigException(String message) {
			super(message);
		}

		public CiConfigException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
