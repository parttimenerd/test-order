package me.bechberger.testorder.ci;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DownloaderTests {

	@Test
	void testGitHubConfigValidation() throws DepDownloader.DepDownloadException {
		CiConfig.GithubConfig validConfig = new CiConfig.GithubConfig("bechberger", "test-order",
				"integration-tests.yml", "test-order-deps", "main");
		GitHubActionsDownloader downloader = new GitHubActionsDownloader(validConfig, "test-token");

		// Should not throw
		downloader.validate();
	}

	@Test
	void testGitHubConfigValidationMissingOwner() {
		CiConfig.GithubConfig invalidConfig = new CiConfig.GithubConfig(null, "test-order", "workflow.yml", "artifact",
				"main");
		GitHubActionsDownloader downloader = new GitHubActionsDownloader(invalidConfig, null);

		assertThrows(DepDownloader.DepDownloadException.class, downloader::validate);
	}

	@Test
	void testGitHubDownloaderName() {
		CiConfig.GithubConfig config = new CiConfig.GithubConfig("owner", "repo", "workflow", "artifact", "main");
		GitHubActionsDownloader downloader = new GitHubActionsDownloader(config, null);

		assertEquals("GitHub Actions", downloader.getName());
	}

	@Test
	void testHttpConfigValidation() throws DepDownloader.DepDownloadException {
		CiConfig.HttpConfig validConfig = new CiConfig.HttpConfig("https://example.com/artifacts/deps.json", "bearer",
				"TOKEN");
		HttpDownloader downloader = new HttpDownloader(validConfig, "test-token");

		// Should not throw
		downloader.validate();
	}

	@Test
	void testHttpConfigValidationMissingUrl() {
		CiConfig.HttpConfig invalidConfig = new CiConfig.HttpConfig(null, "bearer", "TOKEN");
		HttpDownloader downloader = new HttpDownloader(invalidConfig, null);

		assertThrows(DepDownloader.DepDownloadException.class, downloader::validate);
	}

	@Test
	void testHttpConfigValidationInvalidUrl() {
		CiConfig.HttpConfig invalidConfig = new CiConfig.HttpConfig("not a valid url", "bearer", null);
		HttpDownloader downloader = new HttpDownloader(invalidConfig, null);

		assertThrows(DepDownloader.DepDownloadException.class, downloader::validate);
	}

	@Test
	void testHttpDownloaderName() {
		CiConfig.HttpConfig config = new CiConfig.HttpConfig("https://example.com/deps", "bearer", null);
		HttpDownloader downloader = new HttpDownloader(config, null);

		assertEquals("HTTP", downloader.getName());
	}
}
