package me.bechberger.testorder.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Parses .test-order-ci.yml configuration files. Supports YAML format with
 * validation.
 */
public class CiConfigParser {
	private static final String DEFAULT_CONFIG_FILE = ".test-order-ci.yml";
	private static final Yaml yaml = new Yaml();

	/**
	 * Parse config file from default location (.test-order-ci.yml in current
	 * directory)
	 */
	public static CiConfig parseDefault() throws IOException, CiConfigException {
		return parse(Paths.get(DEFAULT_CONFIG_FILE));
	}

	/**
	 * Parse config file from specified path
	 */
	public static CiConfig parse(Path configPath) throws IOException, CiConfigException {
		if (!Files.exists(configPath)) {
			throw new CiConfigException("Config file not found: " + configPath);
		}

		try {
			byte[] fileBytes = Files.readAllBytes(configPath);
			@SuppressWarnings("unchecked")
			Map<String, Object> configMap = yaml.load(new String(fileBytes));

			if (configMap == null) {
				throw new CiConfigException("Config file is empty: " + configPath);
			}

			CiConfig config = new CiConfig(configMap);

			// Validate that at least one CI config is provided
			if (config.getGithub() == null && config.getHttp() == null) {
				throw new CiConfigException("Config must have either 'github' or 'http' section");
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

			return config;
		} catch (CiConfigException e) {
			throw e;
		} catch (IOException e) {
			throw new CiConfigException("Error reading config file: " + e.getMessage(), e);
		}
	}

	/**
	 * Check if default config file exists
	 */
	public static boolean defaultConfigExists() {
		return Files.exists(Paths.get(DEFAULT_CONFIG_FILE));
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
