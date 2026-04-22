package me.bechberger.testorder.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * HTTP error handling and authentication tests with MockWebServer. Validates
 * behavior under various HTTP scenarios and authentication schemes.
 */
class HttpErrorHandlingTests {

	private MockWebServer mockServer;

	@BeforeEach
	void setup() throws IOException {
		mockServer = new MockWebServer();
		mockServer.start();
	}

	@AfterEach
	void teardown() throws IOException {
		mockServer.shutdown();
	}

	/**
	 * Creates an HttpDownloader pointing at the local MockWebServer with SSRF
	 * checks disabled.
	 */
	private HttpDownloader localDownloader(CiConfig.HttpConfig httpConfig, String token) {
		return new HttpDownloader(httpConfig, token, true);
	}

	@Test
	void testHttpDownloader_401UnauthorizedResponse() throws Exception {
		mockServer.enqueue(new MockResponse().setResponseCode(401).setBody("{\"message\": \"Bad credentials\"}"));

		CiConfig.HttpConfig httpConfig = new CiConfig.HttpConfig(mockServer.url("/api/data").toString(), "bearer",
				"TOKEN");
		HttpDownloader downloader = localDownloader(httpConfig, "invalid-token");

		Path tempFile = Files.createTempFile("test", ".zip");
		try {
			assertThrows(DepDownloader.DepDownloadException.class, () -> {
				downloader.download(tempFile);
			});
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test
	void testHttpDownloader_404NotFoundResponse() throws Exception {
		mockServer.enqueue(new MockResponse().setResponseCode(404).setBody("Not Found"));

		CiConfig.HttpConfig httpConfig = new CiConfig.HttpConfig(mockServer.url("/missing").toString(), "none", null);
		HttpDownloader downloader = localDownloader(httpConfig, null);

		Path tempFile = Files.createTempFile("test", ".zip");
		try {
			assertThrows(DepDownloader.DepDownloadException.class, () -> {
				downloader.download(tempFile);
			});
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test
	void testHttpDownloader_500ServerErrorResponse() throws Exception {
		mockServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

		CiConfig.HttpConfig httpConfig = new CiConfig.HttpConfig(mockServer.url("/error").toString(), "none", null);
		HttpDownloader downloader = localDownloader(httpConfig, null);

		Path tempFile = Files.createTempFile("test", ".zip");
		try {
			assertThrows(DepDownloader.DepDownloadException.class, () -> {
				downloader.download(tempFile);
			});
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test
	void testHttpDownloader_BearerTokenAuthentication() throws Exception {
		mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("authenticated content"));

		CiConfig.HttpConfig httpConfig = new CiConfig.HttpConfig(mockServer.url("/secure").toString(), "bearer",
				"TOKEN");
		HttpDownloader downloader = localDownloader(httpConfig, "my-secret-token");

		Path tempFile = Files.createTempFile("test", ".zip");
		try {
			downloader.download(tempFile);
			assertTrue(Files.exists(tempFile));

			// Verify Authorization header
			String authHeader = mockServer.takeRequest().getHeader("Authorization");
			assertEquals("Bearer my-secret-token", authHeader);
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test
	void testHttpDownloader_BasicTokenAuthentication() throws Exception {
		mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("basic auth content"));

		CiConfig.HttpConfig httpConfig = new CiConfig.HttpConfig(mockServer.url("/secure").toString(), "basic", "AUTH");
		HttpDownloader downloader = localDownloader(httpConfig, "encoded-creds");

		Path tempFile = Files.createTempFile("test", ".zip");
		try {
			downloader.download(tempFile);
			assertTrue(Files.exists(tempFile));

			String authHeader = mockServer.takeRequest().getHeader("Authorization");
			assertEquals("Basic encoded-creds", authHeader);
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test
	void testHttpDownloader_NoAuthWhenTokenEmpty() throws Exception {
		mockServer.enqueue(new MockResponse().setResponseCode(200).setBody("public content"));

		CiConfig.HttpConfig httpConfig = new CiConfig.HttpConfig(mockServer.url("/public").toString(), "bearer",
				"TOKEN");
		HttpDownloader downloader = localDownloader(httpConfig, "");

		Path tempFile = Files.createTempFile("test", ".zip");
		try {
			downloader.download(tempFile);
			assertTrue(Files.exists(tempFile));

			String authHeader = mockServer.takeRequest().getHeader("Authorization");
			assertNull(authHeader);
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}

	@Test
	void testHttpDownloader_SuccessfulDownloadWithContent() throws Exception {
		String testContent = "test artifact content";
		mockServer.enqueue(new MockResponse().setResponseCode(200).setBody(testContent));

		CiConfig.HttpConfig httpConfig = new CiConfig.HttpConfig(mockServer.url("/artifact").toString(), "none", null);
		HttpDownloader downloader = localDownloader(httpConfig, null);

		Path tempFile = Files.createTempFile("test", ".zip");
		try {
			downloader.download(tempFile);
			assertTrue(Files.exists(tempFile));
			String downloaded = Files.readString(tempFile);
			assertEquals(testContent, downloaded);
		} finally {
			Files.deleteIfExists(tempFile);
		}
	}
}
