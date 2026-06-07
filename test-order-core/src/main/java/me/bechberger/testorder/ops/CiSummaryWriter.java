package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Writes a concise CI run summary in multiple formats after every test
 * selection. Activated by the {@code testorder.ci.summary=true} system
 * property.
 *
 * <p>
 * Output files (relative to the project build directory):
 * <ul>
 * <li>{@code target/test-order-summary.md} — Markdown (human-readable; appended
 * to {@code $GITHUB_STEP_SUMMARY} when
 * {@code testorder.ci.githubStepSummary=true})</li>
 * <li>{@code target/test-order-summary.json} — JSON (machine-readable)</li>
 * <li>{@code target/test-order-selection-report.xml} — JUnit-XML style
 * selection report (for Allure / ReportPortal / CI test dashboards)</li>
 * </ul>
 *
 * <h2>GitHub PR comment</h2> When {@code testorder.ci.prComment=true} and
 * {@code GITHUB_TOKEN} is set, the Markdown summary is posted (or updated) as a
 * comment on the open pull request detected from {@code GITHUB_REF} /
 * {@code GITHUB_PULL_REQUEST_NUMBER}. The comment is identified by a hidden
 * HTML marker so repeated runs update rather than spam.
 */
public final class CiSummaryWriter {

	private CiSummaryWriter() {
	}

	/**
	 * Input data for the summary — built from any workflow's selection result.
	 *
	 * @param totalTestsInIndex
	 *            total number of test classes in the dependency index
	 * @param selectedTests
	 *            tests that will run in this invocation
	 * @param deferredTests
	 *            tests that were deferred (remaining, or tier 2/3 if this is a
	 *            tiered run)
	 * @param changedClasses
	 *            source classes detected as changed
	 * @param changedTests
	 *            test classes detected as changed
	 * @param topChangedDrivers
	 *            up to 5 source classes whose change most influenced the selection
	 *            (may be empty)
	 * @param mode
	 *            one of: {@code auto}, {@code tiered-select}, {@code run-tiered}
	 * @param tier
	 *            tier number (1, 2, or 3) for tiered mode; 0 for non-tiered
	 * @param buildDir
	 *            project build output directory
	 */
	public record SummaryInput(int totalTestsInIndex, List<String> selectedTests, List<String> deferredTests,
			Set<String> changedClasses, Set<String> changedTests, List<String> topChangedDrivers, String mode, int tier,
			Path buildDir) {
	}

	/**
	 * Writes all enabled summary formats.
	 *
	 * @param input
	 *            summary data
	 * @param log
	 *            plugin log for warnings
	 */
	public static void writeSummary(SummaryInput input, PluginLog log) {
		if (!isEnabled()) {
			return;
		}
		try {
			Path md = writeMd(input);
			writeJson(input);
			writeJUnitXml(input);
			if (isGithubStepSummaryEnabled()) {
				appendToGithubStepSummary(md, log);
			}
			if (isPrCommentEnabled()) {
				postPrComment(md, log);
			}
		} catch (IOException e) {
			log.warn("[test-order] Failed to write CI summary: " + e.getMessage());
		}
	}

	// ── Property checks ──────────────────────────────────────────────────────

	public static boolean isEnabled() {
		return "true".equalsIgnoreCase(System.getProperty("testorder.ci.summary"));
	}

	public static boolean isGithubStepSummaryEnabled() {
		return "true".equalsIgnoreCase(System.getProperty("testorder.ci.githubStepSummary"));
	}

	public static boolean isPrCommentEnabled() {
		return "true".equalsIgnoreCase(System.getProperty("testorder.ci.prComment"));
	}

	// ── Markdown ─────────────────────────────────────────────────────────────

	private static Path writeMd(SummaryInput input) throws IOException {
		String md = buildMd(input);
		Path out = input.buildDir().resolve("test-order-summary.md");
		Files.createDirectories(out.getParent());
		Path temp = me.bechberger.testorder.PersistenceSupport.temporarySibling(out);
		Files.writeString(temp, md, StandardCharsets.UTF_8);
		me.bechberger.testorder.PersistenceSupport.moveIntoPlace(temp, out);
		return out;
	}

	static String buildMd(SummaryInput input) {
		StringBuilder sb = new StringBuilder();
		sb.append("## test-order run summary\n\n");
		appendStatsTable(sb, input);
		appendChangedClasses(sb, input);
		appendTopDrivers(sb, input);
		return sb.toString();
	}

	private static void appendStatsTable(StringBuilder sb, SummaryInput input) {
		int selected = input.selectedTests().size();
		int deferred = input.deferredTests().size();
		int total = input.totalTestsInIndex();
		String pct = total > 0 ? String.format("%.0f%%", 100.0 * selected / total) : "—";
		sb.append("| | |\n|---|---|\n");
		sb.append("| **Mode** | ").append(describeMode(input)).append(" |\n");
		sb.append("| **Selected** | ").append(selected).append(" / ").append(total).append(" (").append(pct)
				.append(") |\n");
		if (deferred > 0) {
			sb.append("| **Deferred** | ").append(deferred).append(" |\n");
		}
		if (!input.changedClasses().isEmpty()) {
			sb.append("| **Changed source classes** | ").append(input.changedClasses().size()).append(" |\n");
		}
		if (!input.changedTests().isEmpty()) {
			sb.append("| **Changed test classes** | ").append(input.changedTests().size()).append(" |\n");
		}
		sb.append("\n");
	}

	private static void appendChangedClasses(StringBuilder sb, SummaryInput input) {
		if (input.changedClasses().isEmpty()) {
			return;
		}
		sb.append("<details><summary>Changed classes (").append(input.changedClasses().size())
				.append(")</summary>\n\n");
		for (String c : sorted(input.changedClasses())) {
			sb.append("- `").append(c).append("`\n");
		}
		sb.append("</details>\n\n");
	}

	private static void appendTopDrivers(StringBuilder sb, SummaryInput input) {
		if (input.topChangedDrivers().isEmpty()) {
			return;
		}
		sb.append("**Top selection drivers:**\n");
		for (String d : input.topChangedDrivers()) {
			sb.append("- `").append(d).append("`\n");
		}
		sb.append("\n");
	}

	private static String describeMode(SummaryInput input) {
		if (input.tier() > 0) {
			return "tiered (tier " + input.tier() + ")";
		}
		return input.mode();
	}

	// ── JSON ─────────────────────────────────────────────────────────────────

	private static void writeJson(SummaryInput input) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("{\n");
		sb.append("  \"mode\": ").append(jsonString(input.mode())).append(",\n");
		sb.append("  \"tier\": ").append(input.tier()).append(",\n");
		sb.append("  \"totalTestsInIndex\": ").append(input.totalTestsInIndex()).append(",\n");
		sb.append("  \"selectedCount\": ").append(input.selectedTests().size()).append(",\n");
		sb.append("  \"deferredCount\": ").append(input.deferredTests().size()).append(",\n");
		sb.append("  \"changedSourceClasses\": ").append(jsonStringArray(input.changedClasses())).append(",\n");
		sb.append("  \"changedTestClasses\": ").append(jsonStringArray(input.changedTests())).append(",\n");
		sb.append("  \"topChangedDrivers\": ").append(jsonStringArray(input.topChangedDrivers())).append("\n");
		sb.append("}\n");
		Path out = input.buildDir().resolve("test-order-summary.json");
		Files.createDirectories(out.getParent());
		Path temp = me.bechberger.testorder.PersistenceSupport.temporarySibling(out);
		Files.writeString(temp, sb.toString(), StandardCharsets.UTF_8);
		me.bechberger.testorder.PersistenceSupport.moveIntoPlace(temp, out);
	}

	// ── JUnit-XML selection report ────────────────────────────────────────────

	private static void writeJUnitXml(SummaryInput input) throws IOException {
		int selected = input.selectedTests().size();
		int deferred = input.deferredTests().size();
		int total = selected + deferred;
		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sb.append("<testsuites name=\"test-order-selection\" tests=\"").append(total).append("\" skipped=\"")
				.append(deferred).append("\" failures=\"0\" errors=\"0\">\n");
		sb.append("  <testsuite name=\"selection\" tests=\"").append(total).append("\" skipped=\"").append(deferred)
				.append("\" failures=\"0\" errors=\"0\">\n");
		// Selected tests — reported as passed (they will run)
		for (String t : input.selectedTests()) {
			sb.append("    <testcase classname=\"selection\" name=\"").append(xmlEscape(t)).append("\" time=\"0\"/>\n");
		}
		// Deferred tests — reported as skipped (won't run this invocation)
		for (String t : input.deferredTests()) {
			sb.append("    <testcase classname=\"deferred\" name=\"").append(xmlEscape(t)).append("\" time=\"0\">\n");
			sb.append("      <skipped message=\"deferred by test-order\"/>\n");
			sb.append("    </testcase>\n");
		}
		sb.append("  </testsuite>\n");
		sb.append("</testsuites>\n");
		Path out = input.buildDir().resolve("test-order-selection-report.xml");
		Files.createDirectories(out.getParent());
		Path temp = me.bechberger.testorder.PersistenceSupport.temporarySibling(out);
		Files.writeString(temp, sb.toString(), StandardCharsets.UTF_8);
		me.bechberger.testorder.PersistenceSupport.moveIntoPlace(temp, out);
	}

	// ── GitHub Step Summary ───────────────────────────────────────────────────

	private static void appendToGithubStepSummary(Path mdFile, PluginLog log) {
		String summaryPath = System.getenv("GITHUB_STEP_SUMMARY");
		if (summaryPath == null || summaryPath.isBlank()) {
			log.debug("[test-order] GITHUB_STEP_SUMMARY not set — skipping step summary append");
			return;
		}
		try {
			String content = Files.readString(mdFile, StandardCharsets.UTF_8);
			Files.writeString(Path.of(summaryPath), content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (IOException e) {
			log.warn("[test-order] Failed to append to GITHUB_STEP_SUMMARY: " + e.getMessage());
		}
	}

	// ── GitHub PR comment ─────────────────────────────────────────────────────

	private static final String COMMENT_MARKER = "<!-- test-order-summary -->";

	private static void postPrComment(Path mdFile, PluginLog log) {
		String token = System.getenv("GITHUB_TOKEN");
		if (token == null || token.isBlank()) {
			log.debug("[test-order] GITHUB_TOKEN not set — skipping PR comment");
			return;
		}
		String repo = System.getenv("GITHUB_REPOSITORY");
		String prNumber = System.getenv("GITHUB_PULL_REQUEST_NUMBER");
		if (prNumber == null || prNumber.isBlank()) {
			// Try to derive from GITHUB_REF: refs/pull/123/merge
			String ref = System.getenv("GITHUB_REF");
			if (ref != null && ref.startsWith("refs/pull/")) {
				String[] parts = ref.split("/");
				if (parts.length > 2) {
					prNumber = parts[2];
				}
			}
		}
		if (repo == null || repo.isBlank() || prNumber == null || prNumber.isBlank()) {
			log.debug("[test-order] Could not detect GitHub repo or PR number — skipping PR comment");
			return;
		}
		try {
			String mdContent = Files.readString(mdFile, StandardCharsets.UTF_8);
			String body = COMMENT_MARKER + "\n" + mdContent;
			postOrUpdateComment(repo, prNumber, token, body, log);
		} catch (IOException e) {
			log.warn("[test-order] Failed to post PR comment: " + e.getMessage());
		}
	}

	private static void postOrUpdateComment(String repo, String prNumber, String token, String body, PluginLog log) {
		// Uses the GitHub REST API via java.net.http (JDK 11+).
		// Attempts to find an existing comment with COMMENT_MARKER first; if found,
		// patches it. Otherwise creates a new comment.
		try {
			java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
			String baseUrl = "https://api.github.com/repos/" + repo + "/issues/" + prNumber + "/comments";

			// List existing comments (first page only — enough for CI bots)
			java.net.http.HttpRequest listReq = java.net.http.HttpRequest.newBuilder()
					.uri(java.net.URI.create(baseUrl + "?per_page=100")).header("Authorization", "Bearer " + token)
					.header("Accept", "application/vnd.github+json").GET().build();
			java.net.http.HttpResponse<String> listResp = client.send(listReq,
					java.net.http.HttpResponse.BodyHandlers.ofString());
			if (listResp.statusCode() == 429) {
				log.warn("[test-order] GitHub API rate-limited; skipping PR comment update");
				return;
			}
			if (listResp.statusCode() != 200) {
				log.warn("[test-order] Failed to list PR comments: HTTP " + listResp.statusCode());
				return;
			}

			// Find existing comment id by looking for our marker
			Long existingId = findExistingCommentId(listResp.body());

			String jsonBody = "{\"body\":" + jsonString(body) + "}";
			String url = existingId != null ? baseUrl + "/" + existingId : baseUrl;
			String method = existingId != null ? "PATCH" : "POST";

			java.net.http.HttpRequest postReq = java.net.http.HttpRequest.newBuilder().uri(java.net.URI.create(url))
					.header("Authorization", "Bearer " + token).header("Accept", "application/vnd.github+json")
					.header("Content-Type", "application/json")
					.method(method, java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody)).build();
			java.net.http.HttpResponse<String> postResp = client.send(postReq,
					java.net.http.HttpResponse.BodyHandlers.ofString());
			if (postResp.statusCode() == 429) {
				log.warn("[test-order] GitHub API rate-limited; PR comment not posted");
			} else if (postResp.statusCode() >= 200 && postResp.statusCode() < 300) {
				log.info("[test-order] PR comment " + (existingId != null ? "updated" : "posted") + " on #" + prNumber);
			} else {
				log.warn("[test-order] Failed to post PR comment: HTTP " + postResp.statusCode());
			}
		} catch (Exception e) {
			log.warn("[test-order] Failed to post PR comment: " + e.getMessage());
		}
	}

	static Long findExistingCommentId(String json) {
		// Minimal JSON scan without pulling in a full parser.
		// GitHub returns an array of comment objects:
		// [{"id": 12345, "user": {"id": 789, ...}, "body": "...MARKER..."}, ...]
		// Strategy: scan forward through the top-level array elements. For each
		// comment object ({...}), check whether its "body" value contains the marker.
		// If so, return its top-level "id" field value.
		// BUG-92: the old backward-walk picked the innermost "id" (e.g. "user".id)
		// because it found the nested object's '{' first. This forward scan tracks
		// brace depth properly so "id" is only read at depth 1 (direct comment field).
		int n = json.length();
		int i = 0;
		// Skip to opening '[' of the array
		while (i < n && json.charAt(i) != '[')
			i++;
		if (i >= n)
			return null;
		i++; // past '['

		while (i < n) {
			// Skip whitespace and commas between elements
			while (i < n && (json.charAt(i) == ' ' || json.charAt(i) == '\n' || json.charAt(i) == '\r'
					|| json.charAt(i) == '\t' || json.charAt(i) == ',')) {
				i++;
			}
			if (i >= n || json.charAt(i) == ']')
				break;
			if (json.charAt(i) != '{') {
				i++;
				continue;
			}

			// Found a comment object — scan it to collect id and body
			Long commentId = null;
			boolean bodyHasMarker = false;
			int depth = 0;
			while (i < n) {
				char c = json.charAt(i);
				if (c == '{') {
					depth++;
					i++;
				} else if (c == '}') {
					depth--;
					i++;
					if (depth == 0)
						break; // end of this top-level element
				} else if (c == '"') {
					// Parse a JSON string
					int keyStart = i + 1;
					i++; // skip opening '"'
					while (i < n) {
						char sc = json.charAt(i);
						i++;
						if (sc == '\\') {
							i++;
						} // skip escaped char
						else if (sc == '"')
							break;
					}
					int keyEnd = i - 1; // index of closing '"' was i-1 before last i++
					String key = json.substring(keyStart, keyEnd);

					// Check if this is a top-level key (depth==1) of interest
					if (depth == 1) {
						// Consume ':' and whitespace
						while (i < n && (json.charAt(i) == ' ' || json.charAt(i) == ':'))
							i++;
						if ("id".equals(key) && i < n && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-')) {
							int numStart = i;
							while (i < n && (Character.isDigit(json.charAt(i)) || json.charAt(i) == '-'))
								i++;
							try {
								commentId = Long.parseLong(json.substring(numStart, i));
							} catch (NumberFormatException ignored) {
							}
						} else if ("body".equals(key) && i < n && json.charAt(i) == '"') {
							// Parse the body string value and check for marker
							i++; // skip opening '"'
							int bodyStart = i;
							while (i < n) {
								char sc = json.charAt(i);
								i++;
								if (sc == '\\') {
									i++;
								} else if (sc == '"')
									break;
							}
							String bodyValue = json.substring(bodyStart, i - 1);
							if (bodyValue.contains(COMMENT_MARKER)) {
								bodyHasMarker = true;
							}
						}
					} else {
						// Non-top-level: skip the value (it was already consumed as part of
						// the key string parse above for string keys, but if it was a key we
						// might need to skip the value). Actually for nested objects the value
						// will be parsed naturally by the outer loop's depth tracking.
						// However, for non-object string values we need to consume them here.
						// Skip ':' and whitespace, then skip the value if it's a string.
						while (i < n && (json.charAt(i) == ' ' || json.charAt(i) == ':'))
							i++;
						if (i < n && json.charAt(i) == '"') {
							i++; // skip opening '"'
							while (i < n) {
								char sc = json.charAt(i);
								i++;
								if (sc == '\\')
									i++;
								else if (sc == '"')
									break;
							}
						}
						// If the value is a number, object, or array, the outer loop handles it
					}
				} else {
					i++;
				}
			}

			if (bodyHasMarker && commentId != null) {
				return commentId;
			}
		}
		return null;
	}

	// ── Helpers ──────────────────────────────────────────────────────────────

	private static List<String> sorted(Collection<String> c) {
		return c.stream().sorted().toList();
	}

	private static String jsonString(String s) {
		if (s == null)
			return "null";
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
				.replace("\t", "\\t") + "\"";
	}

	private static String jsonStringArray(Collection<String> c) {
		StringBuilder sb = new StringBuilder("[");
		boolean first = true;
		for (String s : c) {
			if (!first)
				sb.append(", ");
			sb.append(jsonString(s));
			first = false;
		}
		sb.append("]");
		return sb.toString();
	}

	private static String xmlEscape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}
}
