package me.bechberger.testorder.plugin.it;

/** End-to-end tests against test-order-example (JUnit 6). */
class EndToEndJUnit6IT extends AbstractEndToEndIT {

	@Override
	protected String projectDirName() {
		return "test-order-example";
	}
}
