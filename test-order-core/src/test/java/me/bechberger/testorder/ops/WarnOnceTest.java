package me.bechberger.testorder.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WarnOnceTest {

	private final List<String> warnings = new ArrayList<>();
	private final PluginLog log = new PluginLog() {
		@Override
		public void info(String m) {
		}

		@Override
		public void warn(String m) {
			warnings.add(m);
		}

		@Override
		public void debug(String m) {
		}
	};

	@BeforeEach
	void reset() {
		WarnOnce.resetForTesting();
		warnings.clear();
	}

	@Test
	void firstWarnEmitsSecondIsSuppressed() {
		WarnOnce.warn(log, "k1", "first");
		WarnOnce.warn(log, "k1", "second");
		assertEquals(List.of("first"), warnings);
	}

	@Test
	void differentKeysAreIndependent() {
		WarnOnce.warn(log, "k1", "msg-a");
		WarnOnce.warn(log, "k2", "msg-b");
		WarnOnce.warn(log, "k1", "again");
		assertEquals(List.of("msg-a", "msg-b"), warnings);
	}

	@Test
	void resetAllowsRewarn() {
		WarnOnce.warn(log, "k1", "first");
		WarnOnce.resetForTesting();
		WarnOnce.warn(log, "k1", "first");
		assertEquals(List.of("first", "first"), warnings);
	}
}
