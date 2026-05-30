package me.bechberger.testorder.ci;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for CI artifact downloading. Parsed from .test-order-ci.yml
 * config file.
 *
 * Example: ci: github: owner: bechberger repo: test-order workflow:
 * integration-tests.yml artifact-name: test-order-deps branch: main http: url:
 * https://ci.example.com/artifacts/deps.json auth: bearer token-env: CI_TOKEN
 */
public class CiConfig {
	private final GithubConfig github;
	private final HttpConfig http;
	private final GitLabConfig gitlab;
	private final MavenConfig maven;
	private final ProxyConfig proxy;

	public CiConfig(Map<String, Object> configMap) {
		@SuppressWarnings("unchecked")
		Map<String, Object> ciConfig = (Map<String, Object>) configMap.getOrDefault("ci", new LinkedHashMap<>());

		this.github = parseGithubConfig(ciConfig);
		this.http = parseHttpConfig(ciConfig);
		this.gitlab = parseGitLabConfig(ciConfig);
		this.maven = parseMavenConfig(ciConfig);
		this.proxy = parseProxyConfig(ciConfig);
	}

	private GithubConfig parseGithubConfig(Map<String, Object> ciConfig) {
		@SuppressWarnings("unchecked")
		Map<String, Object> ghConfig = (Map<String, Object>) ciConfig.get("github");
		if (ghConfig == null) {
			return null;
		}

		return new GithubConfig((String) ghConfig.get("owner"), (String) ghConfig.get("repo"),
				(String) ghConfig.get("workflow"), (String) ghConfig.get("artifact-name"),
				(String) ghConfig.getOrDefault("branch", "main"));
	}

	private HttpConfig parseHttpConfig(Map<String, Object> ciConfig) {
		@SuppressWarnings("unchecked")
		Map<String, Object> httpCfg = (Map<String, Object>) ciConfig.get("http");
		if (httpCfg == null) {
			return null;
		}

		return new HttpConfig((String) httpCfg.get("url"), (String) httpCfg.getOrDefault("auth", "none"),
				(String) httpCfg.get("token-env"));
	}

	private GitLabConfig parseGitLabConfig(Map<String, Object> ciConfig) {
		@SuppressWarnings("unchecked")
		Map<String, Object> glCfg = (Map<String, Object>) ciConfig.get("gitlab");
		if (glCfg == null) {
			return null;
		}
		Object projectIdVal = glCfg.get("project-id");
		String projectId = projectIdVal != null ? String.valueOf(projectIdVal) : null;
		return new GitLabConfig((String) glCfg.getOrDefault("base-url", "https://gitlab.com"), projectId,
				(String) glCfg.get("job-name"), (String) glCfg.get("artifact-name"),
				(String) glCfg.getOrDefault("branch", "main"), (String) glCfg.get("token-env"));
	}

	private MavenConfig parseMavenConfig(Map<String, Object> ciConfig) {
		@SuppressWarnings("unchecked")
		Map<String, Object> mvnCfg = (Map<String, Object>) ciConfig.get("maven");
		if (mvnCfg == null) {
			return null;
		}
		return new MavenConfig((String) mvnCfg.get("url"), (String) mvnCfg.get("group-id"),
				(String) mvnCfg.get("artifact-id"), (String) mvnCfg.getOrDefault("version", "LATEST"),
				(String) mvnCfg.getOrDefault("classifier", "test-deps"),
				(String) mvnCfg.getOrDefault("extension", "lz4"), (String) mvnCfg.getOrDefault("auth", "none"),
				(String) mvnCfg.get("token-env"));
	}

	private ProxyConfig parseProxyConfig(Map<String, Object> ciConfig) {
		@SuppressWarnings("unchecked")
		Map<String, Object> proxyCfg = (Map<String, Object>) ciConfig.get("proxy");
		if (proxyCfg == null) {
			return null;
		}
		Object portVal = proxyCfg.get("port");
		int port = portVal instanceof Number ? ((Number) portVal).intValue() : 8080;
		return new ProxyConfig((String) proxyCfg.get("host"), port, (String) proxyCfg.getOrDefault("type", "http"));
	}

	public GithubConfig getGithub() {
		return github;
	}

	public HttpConfig getHttp() {
		return http;
	}

	public GitLabConfig getGitlab() {
		return gitlab;
	}

	public MavenConfig getMaven() {
		return maven;
	}

	public ProxyConfig getProxy() {
		return proxy;
	}

	public static class GithubConfig {
		private final String owner;
		private final String repo;
		private final String workflow;
		private final String artifactName;
		private final String branch;

		public GithubConfig(String owner, String repo, String workflow, String artifactName, String branch) {
			this.owner = owner;
			this.repo = repo;
			this.workflow = workflow;
			this.artifactName = artifactName;
			this.branch = branch;
		}

		public String getOwner() {
			return owner;
		}
		public String getRepo() {
			return repo;
		}
		public String getWorkflow() {
			return workflow;
		}
		public String getArtifactName() {
			return artifactName;
		}
		public String getBranch() {
			return branch;
		}

		public boolean isValid() {
			return owner != null && repo != null && workflow != null && artifactName != null;
		}
	}

	public static class HttpConfig {
		private final String url;
		private final String auth;
		private final String tokenEnv;

		public HttpConfig(String url, String auth, String tokenEnv) {
			this.url = url;
			this.auth = auth;
			this.tokenEnv = tokenEnv;
		}

		public String getUrl() {
			return url;
		}
		public String getAuth() {
			return auth;
		}
		public String getTokenEnv() {
			return tokenEnv;
		}

		public boolean isValid() {
			return url != null && !url.isEmpty();
		}
	}

	public static class GitLabConfig {
		private final String baseUrl;
		private final String projectId;
		private final String jobName;
		private final String artifactName;
		private final String branch;
		private final String tokenEnv;

		public GitLabConfig(String baseUrl, String projectId, String jobName, String artifactName, String branch,
				String tokenEnv) {
			this.baseUrl = baseUrl;
			this.projectId = projectId;
			this.jobName = jobName;
			this.artifactName = artifactName;
			this.branch = branch;
			this.tokenEnv = tokenEnv;
		}

		public String getBaseUrl() {
			return baseUrl;
		}
		public String getProjectId() {
			return projectId;
		}
		public String getJobName() {
			return jobName;
		}
		public String getArtifactName() {
			return artifactName != null ? artifactName : jobName + "-artifacts";
		}
		public String getBranch() {
			return branch;
		}
		public String getTokenEnv() {
			return tokenEnv;
		}

		public boolean isValid() {
			return projectId != null && !projectId.isEmpty() && jobName != null && !jobName.isEmpty();
		}
	}

	public static class ProxyConfig {
		private final String host;
		private final int port;
		/** "http" (default) or "socks5" */
		private final String type;

		public ProxyConfig(String host, int port, String type) {
			this.host = host;
			this.port = port;
			this.type = type != null ? type : "http";
		}

		public String getHost() {
			return host;
		}
		public int getPort() {
			return port;
		}
		public String getType() {
			return type;
		}

		public boolean isValid() {
			return host != null && !host.isEmpty() && port > 0 && port <= 65535;
		}
	}

	/**
	 * Config for downloading artifacts from a Maven / Nexus / Artifactory
	 * repository.
	 *
	 * <pre>
	 * ci:
	 *   maven:
	 *     url: https://repo.example.com/repository/snapshots
	 *     group-id: com.example
	 *     artifact-id: my-service
	 *     version: LATEST        # LATEST, RELEASE, or a fixed version string
	 *     classifier: test-deps  # optional; default: test-deps
	 *     extension: lz4         # optional; default: lz4
	 *     auth: bearer           # optional: bearer | basic | none; default: none
	 *     token-env: NEXUS_TOKEN # optional
	 * </pre>
	 */
	public static class MavenConfig {
		private final String url;
		private final String groupId;
		private final String artifactId;
		private final String version;
		private final String classifier;
		private final String extension;
		private final String auth;
		private final String tokenEnv;

		public MavenConfig(String url, String groupId, String artifactId, String version, String classifier,
				String extension, String auth, String tokenEnv) {
			this.url = url;
			this.groupId = groupId;
			this.artifactId = artifactId;
			this.version = version != null ? version : "LATEST";
			this.classifier = classifier != null ? classifier : "test-deps";
			this.extension = extension != null ? extension : "lz4";
			this.auth = auth != null ? auth : "none";
			this.tokenEnv = tokenEnv;
		}

		public String getUrl() {
			return url;
		}
		public String getGroupId() {
			return groupId;
		}
		public String getArtifactId() {
			return artifactId;
		}
		public String getVersion() {
			return version;
		}
		public String getClassifier() {
			return classifier;
		}
		public String getExtension() {
			return extension;
		}
		public String getAuth() {
			return auth;
		}
		public String getTokenEnv() {
			return tokenEnv;
		}

		public boolean isValid() {
			return url != null && !url.isEmpty() && groupId != null && !groupId.isEmpty() && artifactId != null
					&& !artifactId.isEmpty();
		}
	}
}
