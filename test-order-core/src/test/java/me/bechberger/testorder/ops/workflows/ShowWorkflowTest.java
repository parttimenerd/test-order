package me.bechberger.testorder.ops.workflows;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.Test;

class ShowWorkflowTest {

	@Test
	void compileFilter_supportsCaseInsensitiveAndCommaSeparatedPatterns() {
		var filter = ShowWorkflow.compileFilter("*service*,*Repository");

		assertNotNull(filter);
		assertTrue(filter.test("com.example.UserService"));
		assertTrue(filter.test("com.example.AccountRepository"));
		assertFalse(filter.test("com.example.Controller"));
	}

	@Test
	void compileFilter_blankReturnsNull() {
		assertNull(ShowWorkflow.compileFilter(null));
		assertNull(ShowWorkflow.compileFilter(""));
		assertNull(ShowWorkflow.compileFilter("   "));
	}

	@Test
	void shortenModuleId_dottedGroupId_stripsPrefix() {
		// org.jsoup-jsoup → jsoup
		assertEquals("jsoup", ShowWorkflow.shortenModuleId("org.jsoup-jsoup"));
		// com.sap.cloud.sdk.cloudplatform-cloudplatform-core → cloudplatform-core
		assertEquals("cloudplatform-core",
				ShowWorkflow.shortenModuleId("com.sap.cloud.sdk.cloudplatform-cloudplatform-core"));
	}

	@Test
	void shortenModuleId_repeatedGroupIdEqualsArtifactId_deduplicates() {
		// BUG fix: commons-codec-commons-codec should display as commons-codec
		assertEquals("commons-codec", ShowWorkflow.shortenModuleId("commons-codec-commons-codec"));
		assertEquals("joda-time", ShowWorkflow.shortenModuleId("joda-time-joda-time"));
		assertEquals("commons-validator", ShowWorkflow.shortenModuleId("commons-validator-commons-validator"));
	}

	@Test
	void shortenModuleId_normalSingleWordGroupId_unchanged() {
		// groupId != artifactId with no dots → unchanged
		assertEquals("mygroup-myartifact", ShowWorkflow.shortenModuleId("mygroup-myartifact"));
		// null → empty
		assertEquals("", ShowWorkflow.shortenModuleId(null));
	}

	@Test
	void printReport_noData_showsActionableGuidance() {
		ShowWorkflow.ShowResult result = new ShowWorkflow.ShowResult(null, null, null, null, null);
		// methods=true and ml=true force the "unavailable" guidance to print even when
		// no data is present — auto-detect (null) intentionally stays quiet.
		ShowWorkflow.Options opts = new ShowWorkflow.Options(true, Boolean.TRUE, Boolean.TRUE, false, false, "text",
				null, -1, 10, null);

		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(buffer);

		ShowWorkflow.printReport(out, result, opts, null);

		String text = buffer.toString();
		assertTrue(text.contains("Showing: none"));
		assertTrue(text.contains("Class order unavailable"));
		assertTrue(text.contains("Run: "));
		assertTrue(text.contains("Method order unavailable"));
		assertTrue(text.contains("ML health unavailable"));
		assertTrue(text.contains("-Dtestorder.ml.enabled=true"));
	}

	// ── BUG-172: Selection Preview honors Surefire <excludes> ────────────

	@Test
	void filterExcluded_dropsMatchingClassesFromAllLists() {
		var selection = new me.bechberger.testorder.TestSelector.Selection(
				java.util.List.of("com.foo.BarTest", "com.foo.perf.PerformanceTest"),
				java.util.List.of("com.foo.BazTest", "com.foo.SlowPerformanceTest"), 0,
				java.util.List.of("com.foo.perf.OtherPerformanceTest"));

		var filtered = ShowWorkflow.filterExcluded(selection, java.util.List.of("**/*PerformanceTest.java"));

		assertEquals(java.util.List.of("com.foo.BarTest"), filtered.selected());
		assertEquals(java.util.List.of("com.foo.BazTest"), filtered.remaining());
		assertTrue(filtered.cached().isEmpty(), "excluded cached entry must be dropped");
	}

	@Test
	void filterExcluded_noPatternsReturnsSameSelection() {
		var selection = new me.bechberger.testorder.TestSelector.Selection(java.util.List.of("com.foo.BarTest"),
				java.util.List.of("com.foo.BazTest"), 0);

		assertSame(selection, ShowWorkflow.filterExcluded(selection, java.util.List.of()));
		assertSame(selection, ShowWorkflow.filterExcluded(selection, null));
	}
}
