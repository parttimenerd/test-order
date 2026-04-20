package me.bechberger.testorder.cli;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for CI artifact downloading.
 * Parsed from .test-order-ci.yml config file.
 *
 * Example:
 * ci:
 *   github:
 *     owner: bechberger
 *     repo: test-order
 *     workflow: integration-tests.yml
 *     artifact-name: test-order-deps
 *     branch: main
 *   http:
 *     url: https://ci.example.com/artifacts/deps.json
 *     auth: bearer
 *     token-env: CI_TOKEN
 */
public class CiConfig {
    private final GithubConfig github;
    private final HttpConfig http;

    public CiConfig(Map<String, Object> configMap) {
        @SuppressWarnings("unchecked")
        Map<String, Object> ciConfig = (Map<String, Object>) configMap.getOrDefault("ci", new LinkedHashMap<>());

        this.github = parseGithubConfig(ciConfig);
        this.http = parseHttpConfig(ciConfig);
    }

    private GithubConfig parseGithubConfig(Map<String, Object> ciConfig) {
        @SuppressWarnings("unchecked")
        Map<String, Object> ghConfig = (Map<String, Object>) ciConfig.get("github");
        if (ghConfig == null) {
            return null;
        }

        return new GithubConfig(
            (String) ghConfig.get("owner"),
            (String) ghConfig.get("repo"),
            (String) ghConfig.get("workflow"),
            (String) ghConfig.get("artifact-name"),
            (String) ghConfig.getOrDefault("branch", "main")
        );
    }

    private HttpConfig parseHttpConfig(Map<String, Object> ciConfig) {
        @SuppressWarnings("unchecked")
        Map<String, Object> httpCfg = (Map<String, Object>) ciConfig.get("http");
        if (httpCfg == null) {
            return null;
        }

        return new HttpConfig(
            (String) httpCfg.get("url"),
            (String) httpCfg.getOrDefault("auth", "none"),
            (String) httpCfg.get("token-env")
        );
    }

    public GithubConfig getGithub() {
        return github;
    }

    public HttpConfig getHttp() {
        return http;
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

        public String getOwner() { return owner; }
        public String getRepo() { return repo; }
        public String getWorkflow() { return workflow; }
        public String getArtifactName() { return artifactName; }
        public String getBranch() { return branch; }

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

        public String getUrl() { return url; }
        public String getAuth() { return auth; }
        public String getTokenEnv() { return tokenEnv; }

        public boolean isValid() {
            return url != null && !url.isEmpty();
        }
    }
}
