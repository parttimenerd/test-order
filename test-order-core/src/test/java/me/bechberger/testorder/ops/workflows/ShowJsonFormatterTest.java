package me.bechberger.testorder.ops.workflows;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import me.bechberger.util.json.JSONParser;

class ShowJsonFormatterTest {

	@Test
	void format_withoutData_includesMetaSkippedSectionsAndGuidanceHints() throws Exception {
		ShowWorkflow.ShowResult result = new ShowWorkflow.ShowResult(null, null, null, null, null);

		String json = ShowJsonFormatter.format(result, null);
		Map<String, Object> root = asMap(JSONParser.parse(json));

		Map<String, Object> meta = asMap(root.get("meta"));
		assertTrue(meta.containsKey("filter"));
		assertNull(meta.get("filter"));

		List<String> shown = asStringList(meta.get("sectionsShown"));
		assertTrue(shown.isEmpty());

		List<Map<String, Object>> skipped = asMapList(meta.get("sectionsSkipped"));
		assertEquals(3, skipped.size());
		assertTrue(containsSkippedSection(skipped, "classOrder", "no class-order data"));
		assertTrue(containsSkippedSection(skipped, "methodOrder", "no method telemetry data"));
		assertTrue(containsSkippedSection(skipped, "ml", "no ML history found"));

		List<String> hints = asStringList(meta.get("guidanceHints"));
		assertEquals(3, hints.size());
		// All hints must use the agentic "Run: <cmd>" convention so an LLM can grep
		// ^Run:
		assertTrue(hints.stream().allMatch(h -> h.startsWith("Run: ")));
		assertTrue(hints.stream().anyMatch(h -> h.contains("testorder.mode=learn")));
		assertTrue(hints.stream().anyMatch(h -> h.contains("testorder.method.ordering")));
		assertTrue(hints.stream().anyMatch(h -> h.contains("testorder.ml.enabled")));
	}

	@Test
	void format_withFilter_propagatesFilterIntoMeta() throws Exception {
		ShowWorkflow.ShowResult result = new ShowWorkflow.ShowResult(null, null, null, null, null);

		String json = ShowJsonFormatter.format(result, "*Service*");
		Map<String, Object> root = asMap(JSONParser.parse(json));
		Map<String, Object> meta = asMap(root.get("meta"));

		assertEquals("*Service*", meta.get("filter"));
	}

	private static boolean containsSkippedSection(List<Map<String, Object>> skipped, String section, String reason) {
		return skipped.stream().anyMatch(e -> section.equals(e.get("section")) && reason.equals(e.get("reason")));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object o) {
		return (Map<String, Object>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> asMapList(Object o) {
		return (List<Map<String, Object>>) o;
	}

	@SuppressWarnings("unchecked")
	private static List<String> asStringList(Object o) {
		return (List<String>) o;
	}
}
