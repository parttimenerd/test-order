package me.bechberger.testorder.maven;

import org.apache.maven.plugin.MojoExecutionException;

import me.bechberger.testorder.ErrorCode;

/**
 * Structured exception for test-order Maven plugin operations. Wraps an
 * {@link ErrorCode} for programmatic error handling in CI/CD.
 * <p>
 * CI systems can parse the error code from the message prefix:
 * {@code [test-order] [ERROR 1002] Index file is corrupted}
 */
public class TestOrderMojoException extends MojoExecutionException {

	private final ErrorCode errorCode;

	public TestOrderMojoException(ErrorCode errorCode, String detail) {
		super(formatMessage(errorCode, detail));
		this.errorCode = errorCode;
	}

	public TestOrderMojoException(ErrorCode errorCode, String detail, Throwable cause) {
		super(formatMessage(errorCode, detail), cause);
		this.errorCode = errorCode;
	}

	/**
	 * Returns the structured error code for programmatic handling.
	 */
	public ErrorCode getErrorCode() {
		return errorCode;
	}

	private static String formatMessage(ErrorCode code, String detail) {
		return String.format("[test-order] [%s %d] %s: %s\n  For more details: mvn test-order:diagnose",
				code.isError() ? "ERROR" : "WARN", code.getCode(), code.getMessage(), detail);
	}
}
