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
	void printReport_noData_showsActionableGuidance() {
		ShowWorkflow.ShowResult result = new ShowWorkflow.ShowResult(null, null, null, null, null);
		ShowWorkflow.Options opts = ShowWorkflow.Options.defaults();

		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream out = new PrintStream(buffer);

		ShowWorkflow.printReport(out, result, opts, null);

		String text = buffer.toString();
		assertTrue(text.contains("Showing: none"));
		assertTrue(text.contains("Class order unavailable"));
		assertTrue(text.contains("run tests once in learn mode"));
		assertTrue(text.contains("Method order unavailable"));
		assertTrue(text.contains("ML health unavailable"));
		assertTrue(text.contains("-Dtestorder.ml.enabled=true"));
	}
}
