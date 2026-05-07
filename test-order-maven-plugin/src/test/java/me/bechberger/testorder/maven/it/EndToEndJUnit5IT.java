package me.bechberger.testorder.maven.it;

/** End-to-end tests against test-order-example-junit5 (JUnit 5). */
class EndToEndJUnit5IT extends AbstractEndToEndIT {

	@Override
	protected String projectDirName() {
		return "test-order-example/test-order-example-junit5";
	}
}
