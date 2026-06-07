package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ParameterValidatorTest {

	/** Minimal PluginLog that captures warnings for assertion. */
	private static class CapturingLog implements PluginLog {
		final List<String> warnings = new ArrayList<>();
		final List<String> infos = new ArrayList<>();

		@Override
		public void info(String msg) {
			infos.add(msg);
		}

		@Override
		public void warn(String msg) {
			warnings.add(msg);
		}

		@Override
		public void debug(String msg) {
		}
	}

	// ── warnChangedClassesFormat ──────────────────────────────────────────────

	@Test
	void warnChangedClassesFormat_nullOrBlank_noWarning() {
		CapturingLog log = new CapturingLog();
		ParameterValidator v = new ParameterValidator(log);
		v.warnChangedClassesFormat(null);
		v.warnChangedClassesFormat("");
		v.warnChangedClassesFormat("   ");
		assertTrue(log.warnings.isEmpty());
	}

	@Test
	void warnChangedClassesFormat_commaOnly_noWarning() {
		CapturingLog log = new CapturingLog();
		ParameterValidator v = new ParameterValidator(log);
		v.warnChangedClassesFormat("com.example.Foo,com.example.Bar");
		assertTrue(log.warnings.isEmpty());
	}

	@Test
	void warnChangedClassesFormat_semicolonOnly_emitsWarning() {
		CapturingLog log = new CapturingLog();
		ParameterValidator v = new ParameterValidator(log);
		v.warnChangedClassesFormat("com.example.Foo;com.example.Bar");
		assertEquals(1, log.warnings.size());
		assertTrue(log.warnings.get(0).contains("testorder.changed.classes"),
				"Default property name should appear in warning");
		assertTrue(log.warnings.get(0).contains("com.example.Foo,com.example.Bar"),
				"Warning should suggest comma-separated form");
	}

	@Test
	void warnChangedClassesFormat_withCustomPropertyName_usesSuppliedName() {
		CapturingLog log = new CapturingLog();
		ParameterValidator v = new ParameterValidator(log);
		v.warnChangedClassesFormat("com.example.FooTest;com.example.BarTest", "testorder.changed.test.classes");
		assertEquals(1, log.warnings.size());
		assertTrue(log.warnings.get(0).contains("testorder.changed.test.classes"),
				"Supplied property name should appear in warning");
		assertFalse(log.warnings.get(0).contains("testorder.changed.classes\""),
				"Default property name should NOT appear when custom name is given");
	}

	@Test
	void warnChangedClassesFormat_mixedCommaAndSemicolon_noWarning() {
		// Has comma → not a pure-semicolon list; no warning expected
		CapturingLog log = new CapturingLog();
		ParameterValidator v = new ParameterValidator(log);
		v.warnChangedClassesFormat("com.example.Foo,com.example.Bar;extra");
		assertTrue(log.warnings.isEmpty(), "Mixed separators should not emit a warning (commas present)");
	}

	// ── validateChangeMode ────────────────────────────────────────────────────

	@Test
	void validateChangeMode_validModes_noException() {
		CapturingLog log = new CapturingLog();
		ParameterValidator v = new ParameterValidator(log);
		for (String mode : new String[]{"uncommitted", "since-last-run", "since-last-commit", "explicit", "auto"}) {
			assertDoesNotThrow(() -> v.validateChangeMode(mode), "Mode should be valid: " + mode);
		}
	}

	@Test
	void validateChangeMode_invalidMode_throwsIllegalArgument() {
		CapturingLog log = new CapturingLog();
		ParameterValidator v = new ParameterValidator(log);
		assertThrows(IllegalArgumentException.class, () -> v.validateChangeMode("bogus-mode"));
	}

	@Test
	void validateChangeMode_nullOrBlank_noException() {
		CapturingLog log = new CapturingLog();
		ParameterValidator v = new ParameterValidator(log);
		assertDoesNotThrow(() -> v.validateChangeMode(null));
		assertDoesNotThrow(() -> v.validateChangeMode(""));
	}

	// ── validateAutoLearnThresholds ───────────────────────────────────────────

	@Test
	void validateAutoLearnThresholds_negativeRunThreshold_throws() {
		ParameterValidator v = new ParameterValidator(new CapturingLog());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> v.validateAutoLearnThresholds(-1, 0, 0));
		assertTrue(e.getMessage().contains("autoLearnRunThreshold"), "message should name the offending param");
	}

	@Test
	void validateAutoLearnThresholds_negativeDiffThreshold_throws() {
		ParameterValidator v = new ParameterValidator(new CapturingLog());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> v.validateAutoLearnThresholds(0, -5, 0));
		assertTrue(e.getMessage().contains("autoLearnDiffThreshold"), "message should name the offending param");
	}

	@Test
	void validateAutoLearnThresholds_negativeOptimizeEvery_throws() {
		ParameterValidator v = new ParameterValidator(new CapturingLog());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> v.validateAutoLearnThresholds(0, 0, -1));
		assertTrue(e.getMessage().contains("optimizeEvery"), "message should name the offending param");
	}

	@Test
	void validateAutoLearnThresholds_zeroAndPositiveAccepted() {
		ParameterValidator v = new ParameterValidator(new CapturingLog());
		assertDoesNotThrow(() -> v.validateAutoLearnThresholds(0, 0, 0));
		assertDoesNotThrow(() -> v.validateAutoLearnThresholds(10, 5, 50));
	}
}
