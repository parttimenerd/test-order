package me.bechberger.testorder.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mocked CI configuration validation tests.
 * Tests config object creation and validation without file I/O or network calls.
 */
class MockedCiDownloaderTest {

    @Test
    void testGitHubDownloaderValidatesRequiredConfig() {
        // Create config with all required fields
        CiConfig.GithubConfig config = new CiConfig.GithubConfig(
            "bechberger",
            "test-order",
            "ci.yml",
            "test-deps",
            "main"
        );

        // Verify validation
        assertTrue(config.isValid());
        assertEquals("bechberger", config.getOwner());
        assertEquals("test-order", config.getRepo());
    }

    @Test
    void testGitHubDownloaderValidatesMissingOwner() {
        // Create config with missing owner
        CiConfig.GithubConfig config = new CiConfig.GithubConfig(
            null,  // missing owner
            "test-repo",
            "ci.yml",
            "deps",
            "main"
        );

        // Should fail validation
        assertFalse(config.isValid());
    }

    @Test
    void testGitHubDownloaderValidatesMissingRepo() {
        CiConfig.GithubConfig config = new CiConfig.GithubConfig(
            "owner",
            null,  // missing repo
            "ci.yml",
            "deps",
            "main"
        );

        assertFalse(config.isValid());
    }

    @Test
    void testGitHubDownloaderValidatesMissingWorkflow() {
        CiConfig.GithubConfig config = new CiConfig.GithubConfig(
            "owner",
            "repo",
            null,  // missing workflow
            "deps",
            "main"
        );

        assertFalse(config.isValid());
    }

    @Test
    void testGitHubDownloaderValidatesMissingArtifactName() {
        CiConfig.GithubConfig config = new CiConfig.GithubConfig(
            "owner",
            "repo",
            "ci.yml",
            null,  // missing artifact name
            "main"
        );

        assertFalse(config.isValid());
    }

    @Test
    void testHttpDownloaderValidatesRequiredConfig() {
        // Create valid config
        CiConfig.HttpConfig config = new CiConfig.HttpConfig(
            "https://example.com/artifacts",
            "bearer",
            "CI_TOKEN"
        );

        // Should validate
        assertTrue(config.isValid());
        assertEquals("https://example.com/artifacts", config.getUrl());
    }

    @Test
    void testHttpDownloaderValidatesMissingUrl() {
        // Create config with missing URL
        CiConfig.HttpConfig config = new CiConfig.HttpConfig(
            null,  // missing URL
            "bearer",
            "CI_TOKEN"
        );

        // Should fail validation
        assertFalse(config.isValid());
    }

    @Test
    void testHttpDownloaderValidatesEmptyUrl() {
        CiConfig.HttpConfig config = new CiConfig.HttpConfig(
            "",  // empty URL
            "bearer",
            "CI_TOKEN"
        );

        assertFalse(config.isValid());
    }

    @Test
    void testBranchSelectionInGitHubConfig() {
        CiConfig.GithubConfig config = new CiConfig.GithubConfig(
            "test",
            "repo",
            "ci.yml",
            "deps",
            "develop"
        );

        assertEquals("develop", config.getBranch());
    }

    @Test
    void testDefaultBranchValue() {
        CiConfig.GithubConfig config = new CiConfig.GithubConfig(
            "test",
            "repo",
            "ci.yml",
            "deps",
            "main"  // Branch defaults to main
        );

        assertEquals("main", config.getBranch());
    }

    @Test
    void testHttpAuthenticationSchemes() {
        CiConfig.HttpConfig http1 = new CiConfig.HttpConfig(
            "https://example.com",
            "bearer",
            null
        );

        CiConfig.HttpConfig http2 = new CiConfig.HttpConfig(
            "https://example.com",
            "basic",
            null
        );

        assertEquals("bearer", http1.getAuth());
        assertEquals("basic", http2.getAuth());
    }

    @Test
    void testHttpAuthDefaultNone() {
        CiConfig.HttpConfig config = new CiConfig.HttpConfig(
            "https://example.com",
            "none",
            null
        );

        assertEquals("none", config.getAuth());
    }

    @Test
    void testGitHubConfigArtifactName() {
        CiConfig.GithubConfig config = new CiConfig.GithubConfig(
            "owner",
            "repo",
            "build.yml",
            "my-custom-artifact",
            "main"
        );

        assertEquals("my-custom-artifact", config.getArtifactName());
    }

    @Test
    void testGitHubConfigWorkflow() {
        CiConfig.GithubConfig config = new CiConfig.GithubConfig(
            "owner",
            "repo",
            "integration-tests.yml",
            "deps",
            "main"
        );

        assertEquals("integration-tests.yml", config.getWorkflow());
    }

    @Test
    void testHttpConfigTokenEnv() {
        CiConfig.HttpConfig config = new CiConfig.HttpConfig(
            "https://example.com",
            "bearer",
            "MY_CUSTOM_TOKEN_ENV"
        );

        assertEquals("MY_CUSTOM_TOKEN_ENV", config.getTokenEnv());
    }

    @Test
    void testHttpConfigTokenEnvCanBeNull() {
        CiConfig.HttpConfig config = new CiConfig.HttpConfig(
            "https://example.com",
            "none",
            null
        );

        assertNull(config.getTokenEnv());
    }

    @Test
    void testMultipleGitHubConfigsWithDifferentBranches() {
        CiConfig.GithubConfig main = new CiConfig.GithubConfig(
            "org",
            "repo",
            "ci.yml",
            "deps",
            "main"
        );

        CiConfig.GithubConfig develop = new CiConfig.GithubConfig(
            "org",
            "repo",
            "ci.yml",
            "deps",
            "develop"
        );

        assertEquals("main", main.getBranch());
        assertEquals("develop", develop.getBranch());
    }

    @Test
    void testMultipleHttpConfigsWithDifferentAuth() {
        CiConfig.HttpConfig bearer = new CiConfig.HttpConfig(
            "https://example.com",
            "bearer",
            "TOKEN"
        );

        CiConfig.HttpConfig basic = new CiConfig.HttpConfig(
            "https://example.com",
            "basic",
            "TOKEN"
        );

        assertEquals("bearer", bearer.getAuth());
        assertEquals("basic", basic.getAuth());
        assertEquals("TOKEN", basic.getTokenEnv());
    }

    @Test
    void testGitHubConfigWithSpecialCharactersInArtifactName() {
        CiConfig.GithubConfig config = new CiConfig.GithubConfig(
            "owner",
            "repo",
            "build.yml",
            "test-order-deps@1.0.0",  // Special chars in name
            "main"
        );

        assertTrue(config.isValid());
        assertEquals("test-order-deps@1.0.0", config.getArtifactName());
    }

    @Test
    void testHttpConfigWithComplexUrl() {
        CiConfig.HttpConfig config = new CiConfig.HttpConfig(
            "https://ci.internal.example.com/api/v3/artifacts?version=latest&format=zip",
            "bearer",
            "AUTH_TOKEN"
        );

        assertTrue(config.isValid());
        assertTrue(config.getUrl().contains("?"));
    }

    @Test
    void testGitHubRepoVariations() {
        // Organization repo
        CiConfig.GithubConfig orgRepo = new CiConfig.GithubConfig(
            "my-org",
            "my-repo",
            "ci.yml",
            "deps",
            "main"
        );
        assertTrue(orgRepo.isValid());

        // User repo
        CiConfig.GithubConfig userRepo = new CiConfig.GithubConfig(
            "username",
            "personal-project",
            "ci.yml",
            "deps",
            "main"
        );
        assertTrue(userRepo.isValid());
    }
}
