package me.bechberger.testorder.ops;

/**
 * Unified checked exception for test-order operations. Plugins should catch
 * this and translate it to their framework-specific exception
 * (MojoExecutionException for Maven, GradleException for Gradle).
 */
public class TestOrderException extends Exception {

	public TestOrderException(String message) {
		super(message);
	}

	public TestOrderException(String message, Throwable cause) {
		super(message, cause);
	}
}
