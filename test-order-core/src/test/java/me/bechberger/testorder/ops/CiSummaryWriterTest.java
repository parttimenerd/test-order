package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CiSummaryWriterTest {

	@TempDir
	Path buildDir;

	@BeforeEach
	void clearSummaryFlag() {
		System.clearProperty("testorder.ci.summary");
		System.clearProperty("testorder.ci.githubStepSummary");
		System.clearProperty("testorder.ci.prComment");
	}

	@AfterEach
	void restoreSystemProperties() {
		System.clearProperty("testorder.ci.summary");
		System.clearProperty("testorder.ci.githubStepSummary");
		System.clearProperty("testorder.ci.prComment");
	}

	// ── isEnabled / toggle ────────────────────────────────────────────────────

	@Test
	void isEnabled_falseByDefault() {
		assertFalse(CiSummaryWriter.isEnabled());
	}

	@Test
	void isEnabled_trueWhenPropertySet() {
		System.setProperty("testorder.ci.summary", "true");
		assertTrue(CiSummaryWriter.isEnabled());
	}

	@Test
	void isEnabled_caseInsensitive() {
		System.setProperty("testorder.ci.summary", "TRUE");
		assertTrue(CiSummaryWriter.isEnabled());
	}

	@Test
	void isGithubStepSummaryEnabled_falseByDefault() {
		assertFalse(CiSummaryWriter.isGithubStepSummaryEnabled());
	}

	@Test
	void isGithubStepSummaryEnabled_trueWhenSet() {
		System.setProperty("testorder.ci.githubStepSummary", "true");
		assertTrue(CiSummaryWriter.isGithubStepSummaryEnabled());
	}

	@Test
	void isPrCommentEnabled_falseByDefault() {
		assertFalse(CiSummaryWriter.isPrCommentEnabled());
	}

	@Test
	void isPrCommentEnabled_trueWhenSet() {
		System.setProperty("testorder.ci.prComment", "true");
		assertTrue(CiSummaryWriter.isPrCommentEnabled());
	}

	// ── buildMd — basic structure ─────────────────────────────────────────────

	private CiSummaryWriter.SummaryInput input(int total, List<String> selected, List<String> deferred,
			Set<String> changedClasses, Set<String> changedTests, List<String> drivers, String mode, int tier) {
		return new CiSummaryWriter.SummaryInput(total, selected, deferred, changedClasses, changedTests, drivers, mode,
				tier, buildDir);
	}

	@Test
	void buildMd_emptySelection_dashPercentage() {
		CiSummaryWriter.SummaryInput in = input(0, List.of(), List.of(), Set.of(), Set.of(), List.of(), "auto", 0);
		String md = CiSummaryWriter.buildMd(in);
		assertTrue(md.contains("—"), "Should show — when total is 0 (avoid division by zero)");
		assertTrue(md.contains("test-order run summary"), "Should have header");
	}

	@Test
	void buildMd_containsSelectedCount() {
		CiSummaryWriter.SummaryInput in = input(10, List.of("A", "B", "C"), List.of(), Set.of(), Set.of(), List.of(),
				"auto", 0);
		String md = CiSummaryWriter.buildMd(in);
		assertTrue(md.contains("3 / 10"), "Should show selected/total");
		assertTrue(md.contains("30%"), "Should compute 30%");
	}

	@Test
	void buildMd_deferredRowOnlyWhenNonZero() {
		CiSummaryWriter.SummaryInput noDeferred = input(5, List.of("A"), List.of(), Set.of(), Set.of(), List.of(),
				"auto", 0);
		assertFalse(CiSummaryWriter.buildMd(noDeferred).contains("Deferred"));

		CiSummaryWriter.SummaryInput withDeferred = input(5, List.of("A"), List.of("B", "C"), Set.of(), Set.of(),
				List.of(), "auto", 0);
		assertTrue(CiSummaryWriter.buildMd(withDeferred).contains("Deferred"));
	}

	@Test
	void buildMd_tieredMode_showsTierNumber() {
		CiSummaryWriter.SummaryInput in = input(10, List.of("A"), List.of(), Set.of(), Set.of(), List.of(),
				"tiered-select", 2);
		String md = CiSummaryWriter.buildMd(in);
		assertTrue(md.contains("tiered (tier 2)"), "Should show tier number");
	}

	@Test
	void buildMd_autoMode_showsModeLabel() {
		CiSummaryWriter.SummaryInput in = input(10, List.of("A"), List.of(), Set.of(), Set.of(), List.of(), "auto", 0);
		String md = CiSummaryWriter.buildMd(in);
		assertTrue(md.contains("auto"));
	}

	@Test
	void buildMd_changedClassesSectionPresent() {
		CiSummaryWriter.SummaryInput in = input(5, List.of("A"), List.of(),
				Set.of("com.example.Foo", "com.example.Bar"), Set.of(), List.of(), "auto", 0);
		String md = CiSummaryWriter.buildMd(in);
		assertTrue(md.contains("<details>"), "Should have collapsible details block");
		assertTrue(md.contains("com.example.Foo"), "Should list changed class");
		assertTrue(md.contains("com.example.Bar"), "Should list changed class");
	}

	@Test
	void buildMd_changedClassesSectionAbsentWhenEmpty() {
		CiSummaryWriter.SummaryInput in = input(5, List.of("A"), List.of(), Set.of(), Set.of(), List.of(), "auto", 0);
		assertFalse(CiSummaryWriter.buildMd(in).contains("<details>"));
	}

	@Test
	void buildMd_topDriversSectionPresent() {
		CiSummaryWriter.SummaryInput in = input(5, List.of("A"), List.of(), Set.of(), Set.of(),
				List.of("com.example.Heavy"), "auto", 0);
		String md = CiSummaryWriter.buildMd(in);
		assertTrue(md.contains("Top selection drivers"), "Should have top drivers section");
		assertTrue(md.contains("com.example.Heavy"));
	}

	@Test
	void buildMd_topDriversSectionAbsentWhenEmpty() {
		CiSummaryWriter.SummaryInput in = input(5, List.of("A"), List.of(), Set.of(), Set.of(), List.of(), "auto", 0);
		assertFalse(CiSummaryWriter.buildMd(in).contains("Top selection drivers"));
	}

	// ── writeSummary produces expected files ──────────────────────────────────

	@Test
	void writeSummary_createsAllThreeFiles() throws IOException {
		System.setProperty("testorder.ci.summary", "true");
		CiSummaryWriter.SummaryInput in = input(5, List.of("Test1", "Test2"), List.of("Test3"), Set.of("Foo"),
				Set.of("FooTest"), List.of("Foo"), "auto", 0);
		CiSummaryWriter.writeSummary(in, PluginLog.NOOP);

		assertTrue(Files.exists(buildDir.resolve("test-order-summary.md")), "Markdown file should exist");
		assertTrue(Files.exists(buildDir.resolve("test-order-summary.json")), "JSON file should exist");
		assertTrue(Files.exists(buildDir.resolve("test-order-selection-report.xml")), "XML file should exist");
	}

	@Test
	void writeSummary_jsonContainsExpectedFields() throws IOException {
		System.setProperty("testorder.ci.summary", "true");
		CiSummaryWriter.SummaryInput in = input(10, List.of("T1", "T2"), List.of("T3"), Set.of("Src"),
				Set.of("SrcTest"), List.of(), "auto", 0);
		CiSummaryWriter.writeSummary(in, PluginLog.NOOP);

		String json = Files.readString(buildDir.resolve("test-order-summary.json"));
		assertTrue(json.contains("\"mode\""), "JSON should have mode field");
		assertTrue(json.contains("\"selectedCount\": 2"), "JSON should have selectedCount");
		assertTrue(json.contains("\"deferredCount\": 1"), "JSON should have deferredCount");
		assertTrue(json.contains("\"totalTestsInIndex\": 10"), "JSON should have totalTestsInIndex");
		assertTrue(json.contains("\"Src\""), "JSON should list changed source class");
		assertTrue(json.contains("\"SrcTest\""), "JSON should list changed test class");
	}

	@Test
	void writeSummary_xmlContainsPassedAndSkipped() throws IOException {
		System.setProperty("testorder.ci.summary", "true");
		CiSummaryWriter.SummaryInput in = input(3, List.of("RunMe"), List.of("SkipMe"), Set.of(), Set.of(), List.of(),
				"auto", 0);
		CiSummaryWriter.writeSummary(in, PluginLog.NOOP);

		String xml = Files.readString(buildDir.resolve("test-order-selection-report.xml"));
		assertTrue(xml.contains("name=\"RunMe\""), "Selected test should appear in XML");
		assertTrue(xml.contains("name=\"SkipMe\""), "Deferred test should appear in XML");
		assertTrue(xml.contains("<skipped"), "Deferred tests should be marked as skipped");
		// selected tests should NOT have a <skipped> tag
		int runMeIdx = xml.indexOf("name=\"RunMe\"");
		assertTrue(runMeIdx >= 0);
		// The selected testcase should be self-closing (no <skipped> child)
		int nextTag = xml.indexOf('<', runMeIdx + 1);
		assertFalse(xml.substring(runMeIdx, nextTag + 8).contains("skipped"),
				"Selected test should not be marked skipped");
	}

	@Test
	void writeSummary_xmlEscapesSpecialCharacters() throws IOException {
		System.setProperty("testorder.ci.summary", "true");
		CiSummaryWriter.SummaryInput in = input(1, List.of("a.b<Test>&\"C\""), List.of(), Set.of(), Set.of(), List.of(),
				"auto", 0);
		CiSummaryWriter.writeSummary(in, PluginLog.NOOP);

		String xml = Files.readString(buildDir.resolve("test-order-selection-report.xml"));
		assertTrue(xml.contains("&lt;"), "< should be escaped");
		assertTrue(xml.contains("&gt;"), "> should be escaped");
		assertTrue(xml.contains("&amp;"), "& should be escaped");
		assertTrue(xml.contains("&quot;"), "\" should be escaped");
		assertFalse(xml.contains("<Test>"), "Raw < > should not appear in attribute value");
	}

	@Test
	void writeSummary_noopWhenNotEnabled() throws IOException {
		// Property NOT set — no files should be created.
		CiSummaryWriter.SummaryInput in = input(5, List.of("T1"), List.of(), Set.of(), Set.of(), List.of(), "auto", 0);
		CiSummaryWriter.writeSummary(in, PluginLog.NOOP);

		assertFalse(Files.exists(buildDir.resolve("test-order-summary.md")));
	}

	// ── findExistingCommentId — JSON comment parsing ──────────────────────────

	@Test
	void findExistingCommentId_markerPresent_returnsId() {
		String json = "[{\"id\": 42, \"body\": \"<!-- test-order-summary -->\\nhello\"}]";
		Long id = CiSummaryWriter.findExistingCommentId(json);
		assertEquals(42L, id);
	}

	@Test
	void findExistingCommentId_markerAbsent_returnsNull() {
		String json = "[{\"id\": 99, \"body\": \"some other comment\"}]";
		assertNull(CiSummaryWriter.findExistingCommentId(json));
	}

	@Test
	void findExistingCommentId_emptyJson_returnsNull() {
		assertNull(CiSummaryWriter.findExistingCommentId("[]"));
		assertNull(CiSummaryWriter.findExistingCommentId(""));
	}

	@Test
	void findExistingCommentId_multipleComments_picksCorrectOne() {
		// Two comments; only the second has the marker.
		String json = "[" + "{\"id\": 10, \"body\": \"unrelated\"},"
				+ "{\"id\": 20, \"body\": \"<!-- test-order-summary -->\\nstuff\"}" + "]";
		assertEquals(20L, CiSummaryWriter.findExistingCommentId(json));
	}

	@Test
	void findExistingCommentId_malformedId_returnsNull() {
		// "id" field is not a number.
		String json = "[{\"id\": \"not-a-number\", \"body\": \"<!-- test-order-summary -->\"}]";
		assertNull(CiSummaryWriter.findExistingCommentId(json));
	}

	@Test
	void findExistingCommentId_markerInNestedBody_doesNotPickWrongId() {
		// Marker is present in an inner string, but the enclosing object's id should
		// be returned.
		String json = "[{\"id\": 55, \"nested\": {\"x\": 1}, \"body\": \"prefix <!-- test-order-summary --> suffix\"}]";
		assertEquals(55L, CiSummaryWriter.findExistingCommentId(json));
	}

	@Test
	void findExistingCommentId_nestedUserIdNotPickedInsteadOfCommentId() {
		// BUG-92: GitHub API returns objects with nested "user": {"id": 789, ...}.
		// The old backward-walk picked the innermost "id" (user's) instead of the
		// outer comment's "id".
		String json = "[{\"id\": 12345, \"user\": {\"id\": 789, \"login\": \"bot\"}, \"body\": \"<!-- test-order-summary -->\\nsome content\"}]";
		assertEquals(12345L, CiSummaryWriter.findExistingCommentId(json));
	}

	// ── GITHUB_REF parsing regression (BUG-85) ───────────────────────────────

	@Test
	void buildMd_validOutput_neverThrowsRegardlessOfInputSize() {
		// Regression guard: any non-null inputs should produce valid Markdown without
		// exceptions.
		CiSummaryWriter.SummaryInput in = input(0, List.of(), List.of(), Set.of(), Set.of(), List.of(), "auto", 0);
		String md = CiSummaryWriter.buildMd(in);
		assertFalse(md.isBlank());
	}

	// ── RuntimeExtras: cache / retry / quarantine sections ────────────────────

	@Test
	void buildMd_cachedTests_addsRowAndDetails() {
		CiSummaryWriter.SummaryInput in = input(5, List.of("A"), List.of(), Set.of(), Set.of(), List.of(), "auto", 0);
		CiSummaryWriter.RuntimeExtras extras = new CiSummaryWriter.RuntimeExtras(
				List.of("com.SlowTest", "com.OtherTest"), 12_345L, null);
		String md = CiSummaryWriter.buildMd(in, extras, me.bechberger.testorder.ml.FlakyRuntimeReport.empty());
		assertTrue(md.contains("Cached (skipped, unchanged)"), "stats row present");
		assertTrue(md.contains("12.3s"), "time-saved formatted as seconds");
		assertTrue(md.contains("Cached tests (2"), "details section present");
		assertTrue(md.contains("com.SlowTest"));
	}

	@Test
	void buildMd_cachedRowAbsentWhenEmpty() {
		CiSummaryWriter.SummaryInput in = input(5, List.of("A"), List.of(), Set.of(), Set.of(), List.of(), "auto", 0);
		String md = CiSummaryWriter.buildMd(in, CiSummaryWriter.RuntimeExtras.EMPTY,
				me.bechberger.testorder.ml.FlakyRuntimeReport.empty());
		assertFalse(md.contains("Cached"));
	}

	@Test
	void buildMd_retryAndQuarantineRows() {
		CiSummaryWriter.SummaryInput in = input(5, List.of("A"), List.of(), Set.of(), Set.of(), List.of(), "auto", 0);
		me.bechberger.testorder.ml.FlakyRuntimeReport flaky = new me.bechberger.testorder.ml.FlakyRuntimeReport(
				java.util.Map.of("com.Flaky1", 2, "com.Flaky2", 1), Set.of("com.Quarantined1"));
		String md = CiSummaryWriter.buildMd(in, CiSummaryWriter.RuntimeExtras.EMPTY, flaky);
		assertTrue(md.contains("Retried (flaky)"), "retry stats row");
		assertTrue(md.contains("Quarantined"), "quarantine stats row");
		assertTrue(md.contains("com.Flaky1"), "retry detail listed");
		assertTrue(md.contains("attempts: 2"));
		assertTrue(md.contains("com.Quarantined1"), "quarantine detail listed");
	}

	@Test
	void writeSummary_jsonIncludesCacheAndFlakyFields() throws IOException {
		System.setProperty("testorder.ci.summary", "true");
		CiSummaryWriter.SummaryInput in = input(5, List.of("Test1"), List.of("Test2"), Set.of(), Set.of(), List.of(),
				"auto", 0);
		CiSummaryWriter.RuntimeExtras extras = new CiSummaryWriter.RuntimeExtras(List.of("com.Cached"), 5_000L, null);
		CiSummaryWriter.writeSummary(in, extras, PluginLog.NOOP);
		String json = Files.readString(buildDir.resolve("test-order-summary.json"));
		assertTrue(json.contains("\"cachedCount\": 1"));
		assertTrue(json.contains("\"cachedTests\""));
		assertTrue(json.contains("com.Cached"));
		assertTrue(json.contains("\"retriedCount\": 0"));
		assertTrue(json.contains("\"quarantinedCount\": 0"));
	}

	@Test
	void writeSummary_xmlIncludesCachedAsSkipped() throws IOException {
		System.setProperty("testorder.ci.summary", "true");
		CiSummaryWriter.SummaryInput in = input(5, List.of("Test1"), List.of("Test2"), Set.of(), Set.of(), List.of(),
				"auto", 0);
		CiSummaryWriter.RuntimeExtras extras = new CiSummaryWriter.RuntimeExtras(List.of("com.Cached"), 1000L, null);
		CiSummaryWriter.writeSummary(in, extras, PluginLog.NOOP);
		String xml = Files.readString(buildDir.resolve("test-order-selection-report.xml"));
		assertTrue(xml.contains("classname=\"cached\""), "cached testcase present");
		assertTrue(xml.contains("name=\"com.Cached\""));
		assertTrue(xml.contains("skipped message=\"cached:"));
	}

	@Test
	void writeSummary_xmlIncludesQuarantinedFromStateDir() throws IOException {
		System.setProperty("testorder.ci.summary", "true");
		// Write a flaky-runtime.txt next to a state dir; writer should pick it up.
		Path stateDir = buildDir.resolve(".test-order");
		Files.createDirectories(stateDir);
		me.bechberger.testorder.ml.FlakyRuntimeReport.write(
				stateDir.resolve(me.bechberger.testorder.ml.FlakyRuntimeReport.DEFAULT_FILENAME),
				java.util.Map.of("com.Flaky", 2), Set.of("com.Q"));

		CiSummaryWriter.SummaryInput in = input(5, List.of("Test1"), List.of(), Set.of(), Set.of(), List.of(), "auto",
				0);
		CiSummaryWriter.RuntimeExtras extras = new CiSummaryWriter.RuntimeExtras(List.of(), 0L, stateDir);
		CiSummaryWriter.writeSummary(in, extras, PluginLog.NOOP);

		String xml = Files.readString(buildDir.resolve("test-order-selection-report.xml"));
		assertTrue(xml.contains("classname=\"quarantined\""));
		assertTrue(xml.contains("name=\"com.Q\""));
		String md = Files.readString(buildDir.resolve("test-order-summary.md"));
		assertTrue(md.contains("Retried (flaky)"));
		assertTrue(md.contains("Quarantined"));
	}
}
