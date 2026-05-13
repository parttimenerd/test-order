package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.maven.plugin.logging.Log;
import org.junit.jupiter.api.Test;

class HelpMojoTest {

	@Test
	void helpOutput_prioritizesQuickStartAndUnifiedShow() throws Exception {
		HelpMojo mojo = new HelpMojo();
		CapturingLog log = new CapturingLog();
		mojo.setLog(log);

		mojo.execute();

		String out = log.infoText.toString();
		assertTrue(out.contains("Quick start:"));
		assertTrue(out.contains("mvn test-order:show"));
		assertTrue(out.contains("show             Unified view: class order, method order, and ML health"));
		assertTrue(out.contains("show-order       (deprecated) Use 'show' instead"));
		assertTrue(out.contains("show-method-order  (deprecated) Use 'show' instead"));
	}

	@Test
	void helpOutput_typicalUsageIncludesUnifiedShowReportCommand() throws Exception {
		HelpMojo mojo = new HelpMojo();
		CapturingLog log = new CapturingLog();
		mojo.setLog(log);

		mojo.execute();

		String out = log.infoText.toString();
		assertTrue(out.contains("Show report: mvn test-order:show"));
	}

	private static final class CapturingLog implements Log {
		private final StringBuilder infoText = new StringBuilder();

		@Override
		public boolean isDebugEnabled() {
			return false;
		}

		@Override
		public void debug(CharSequence content) {
		}

		@Override
		public void debug(CharSequence content, Throwable error) {
		}

		@Override
		public void debug(Throwable error) {
		}

		@Override
		public boolean isInfoEnabled() {
			return true;
		}

		@Override
		public void info(CharSequence content) {
			if (content != null) {
				infoText.append(content);
			}
		}

		@Override
		public void info(CharSequence content, Throwable error) {
			info(content);
		}

		@Override
		public void info(Throwable error) {
		}

		@Override
		public boolean isWarnEnabled() {
			return false;
		}

		@Override
		public void warn(CharSequence content) {
		}

		@Override
		public void warn(CharSequence content, Throwable error) {
		}

		@Override
		public void warn(Throwable error) {
		}

		@Override
		public boolean isErrorEnabled() {
			return false;
		}

		@Override
		public void error(CharSequence content) {
		}

		@Override
		public void error(CharSequence content, Throwable error) {
		}

		@Override
		public void error(Throwable error) {
		}
	}
}
