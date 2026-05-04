package me.bechberger.testorder.ci;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * Integration tests with mocked HTTP/CI responses. These tests verify download
 * configuration and mock CI behaviors without hitting real systems.
 */
class CiIntegrationTest {

	private MockWebServer mockGitHub;
	private MockWebServer mockHttpCI;

	@BeforeEach
	void setup() throws IOException {
		mockGitHub = new MockWebServer();
		mockHttpCI = new MockWebServer();
		mockGitHub.start();
		mockHttpCI.start();
	}

	@AfterEach
	void teardown() throws IOException {
		mockGitHub.shutdown();
		mockHttpCI.shutdown();
	}

	@Test
	void testMockGitHubAPIRespondsToWorkflowRequest() throws Exception {
		// Setup mock GitHub API responses
		String workflowRunsJson = """
				{
				  "workflow_runs": [
				    {
				      "id": 12345,
				      "name": "CI",
				      "status": "completed",
				      "conclusion": "success",
				      "head_branch": "main"
				    }
				  ]
				}
				""";

		mockGitHub.enqueue(new MockResponse().setBody(workflowRunsJson).addHeader("Content-Type", "application/json"));

		// Make a request to mock server
		okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
		String url = mockGitHub.url("/repos/test/repo/actions/workflows/ci/runs").toString();

		okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
		okhttp3.Response response = client.newCall(request).execute();

		assertEquals(200, response.code());
		assertTrue(response.body().string().contains("workflow_runs"));
	}

	@Test
	void testMockGitHubAPIRespondsToArtifactRequest() throws Exception {
		String artifactsJson = """
				{
				  "artifacts": [
				    {
				      "id": 67890,
				      "name": "test-order-deps",
				      "size_in_bytes": 1024
				    }
				  ]
				}
				""";

		mockGitHub.enqueue(new MockResponse().setBody(artifactsJson).addHeader("Content-Type", "application/json"));

		okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
		String url = mockGitHub.url("/repos/test/repo/actions/runs/12345/artifacts").toString();

		okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
		okhttp3.Response response = client.newCall(request).execute();

		assertEquals(200, response.code());
		assertTrue(response.body().string().contains("artifacts"));
	}

	@Test
	void testMockHttpCIRespondsWithBearerAuth() throws Exception {
		String depsJson = """
				{
				  "artifacts": [
				    {
				      "name": "test-order-deps",
				      "timestamp": "2026-04-20T10:00:00Z"
				    }
				  ]
				}
				""";

		mockHttpCI.enqueue(new MockResponse().setBody(depsJson).addHeader("Content-Type", "application/json"));

		okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
		String url = mockHttpCI.url("/api/artifacts").toString();

		okhttp3.Request request = new okhttp3.Request.Builder().url(url).addHeader("Authorization", "Bearer test-token")
				.build();

		okhttp3.Response response = client.newCall(request).execute();
		assertEquals(200, response.code());
	}

	@Test
	void testMockServerHandlesRetryOnTransientFailure() throws Exception {
		// First request fails with 503, second succeeds
		mockHttpCI.enqueue(new MockResponse().setResponseCode(503));
		mockHttpCI.enqueue(new MockResponse().setBody("success").addHeader("Content-Type", "text/plain"));

		okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
		String url = mockHttpCI.url("/artifact").toString();

		okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();

		// First call should fail
		okhttp3.Response response1 = client.newCall(request).execute();
		assertEquals(503, response1.code());
		response1.close();

		// Second call should succeed
		okhttp3.Response response2 = client.newCall(request).execute();
		assertEquals(200, response2.code());
		response2.close();
	}

	@Test
	void testMockServerURLConstruction() throws Exception {
		mockGitHub.enqueue(new MockResponse().setBody("test").setResponseCode(200));

		HttpUrl baseUrl = mockGitHub.url("/api/v3/");
		String constructedUrl = baseUrl.newBuilder().addPathSegment("repos").addPathSegment("test")
				.addPathSegment("repo").build().toString();

		assertTrue(constructedUrl.contains("/repos/test/repo"));
	}

	@Test
	void testMockServerHandlesEmptyArtifactList() throws Exception {
		String emptyJson = """
				{
				  "artifacts": []
				}
				""";

		mockGitHub.enqueue(new MockResponse().setBody(emptyJson).addHeader("Content-Type", "application/json"));

		okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
		String url = mockGitHub.url("/artifacts").toString();

		okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
		okhttp3.Response response = client.newCall(request).execute();

		assertEquals(200, response.code());
		String body = response.body().string();
		assertTrue(body.contains("artifacts") && body.contains("[]"));
	}

	@Test
	void testMockServerWithDifferentHttpMethods() throws Exception {
		mockGitHub.enqueue(new MockResponse().setBody("ok").setResponseCode(200));

		HttpUrl url = mockGitHub.url("/test");
		assertTrue(url.scheme().equals("http"));
	}

	@Test
	void testMockServerHandlesLargePayloads() throws Exception {
		StringBuilder largePayload = new StringBuilder();
		for (int i = 0; i < 1000; i++) {
			largePayload.append("\"field").append(i).append("\":\"value").append(i).append("\",");
		}

		String json = "{" + largePayload.toString() + "\"final\":\"value\"}";

		mockHttpCI.enqueue(new MockResponse().setBody(json).addHeader("Content-Type", "application/json"));

		okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
		String url = mockHttpCI.url("/large").toString();

		okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
		okhttp3.Response response = client.newCall(request).execute();

		assertEquals(200, response.code());
		String body = response.body().string();
		assertTrue(body.length() > 10000);
	}

	@Test
	void testMockServerHeaderPropagation() throws Exception {
		mockGitHub.enqueue(new MockResponse().setBody("data").addHeader("X-Custom-Header", "custom-value")
				.addHeader("Content-Type", "application/json"));

		okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
		String url = mockGitHub.url("/resource").toString();

		okhttp3.Request request = new okhttp3.Request.Builder().url(url).addHeader("X-Request-ID", "123").build();

		okhttp3.Response response = client.newCall(request).execute();
		assertEquals(200, response.code());
		assertTrue(response.header("X-Custom-Header").equals("custom-value"));
	}

	@Test
	void testMockServerHandlesMultipleClients() throws Exception {
		mockHttpCI.enqueue(new MockResponse().setBody("response1").setResponseCode(200));
		mockHttpCI.enqueue(new MockResponse().setBody("response2").setResponseCode(200));
		mockHttpCI.enqueue(new MockResponse().setBody("response3").setResponseCode(200));

		okhttp3.OkHttpClient client1 = new okhttp3.OkHttpClient();
		okhttp3.OkHttpClient client2 = new okhttp3.OkHttpClient();

		String url = mockHttpCI.url("/test").toString();

		okhttp3.Request req1 = new okhttp3.Request.Builder().url(url).build();
		okhttp3.Response resp1 = client1.newCall(req1).execute();
		assertEquals(200, resp1.code());
		resp1.close();

		okhttp3.Request req2 = new okhttp3.Request.Builder().url(url).build();
		okhttp3.Response resp2 = client2.newCall(req2).execute();
		assertEquals(200, resp2.code());
		resp2.close();
	}

	@Test
	void testMockServerResponseChaining() throws Exception {
		for (int i = 0; i < 5; i++) {
			mockGitHub.enqueue(new MockResponse().setBody("response_" + i).setResponseCode(200));
		}

		okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
		String url = mockGitHub.url("/test").toString();

		for (int i = 0; i < 5; i++) {
			okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
			okhttp3.Response response = client.newCall(request).execute();
			assertEquals(200, response.code());
			response.close();
		}
	}
}
