package me.bechberger.testorder.ops;

import java.util.List;
import java.util.Map;

import me.bechberger.testorder.ErrorCode;

/**
 * Result of a diagnostic check, containing error code, message, suggestions,
 * and context. Used by testOrderDiagnose and for programmatic error handling.
 */
public record DiagnosticResult(ErrorCode code, String message, List<String> suggestions, Map<String, String> context) {

	/**
	 * Create a success result.
	 */
	public static DiagnosticResult success(String message) {
		return new DiagnosticResult(ErrorCode.SUCCESS, message, List.of(), Map.of());
	}

	/**
	 * Create an error result.
	 */
	public static DiagnosticResult error(ErrorCode code, String message, List<String> suggestions) {
		return new DiagnosticResult(code, message, suggestions, Map.of());
	}

	/**
	 * Create an error result with context.
	 */
	public static DiagnosticResult error(ErrorCode code, String message, List<String> suggestions,
			Map<String, String> context) {
		return new DiagnosticResult(code, message, suggestions, context);
	}

	/**
	 * Create an informational result.
	 */
	public static DiagnosticResult info(ErrorCode code, String message) {
		return new DiagnosticResult(code, message, List.of(), Map.of());
	}

	/**
	 * Create an informational result with suggestions.
	 */
	public static DiagnosticResult info(ErrorCode code, String message, List<String> suggestions) {
		return new DiagnosticResult(code, message, suggestions, Map.of());
	}

	/**
	 * @return true if this is a success
	 */
	public boolean isSuccess() {
		return code.isSuccess();
	}

	/**
	 * @return true if this is an error
	 */
	public boolean isError() {
		return code.isError();
	}

	/**
	 * @return true if this is informational
	 */
	public boolean isInformational() {
		return code.isInformational();
	}

	/**
	 * Format for human-readable output.
	 */
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("[test-order] ");
		if (isError()) {
			sb.append("ERROR ");
		} else if (isInformational()) {
			sb.append("INFO ");
		} else {
			sb.append("OK ");
		}
		sb.append(code).append(": ").append(message);
		if (!suggestions.isEmpty()) {
			sb.append("\nSuggestions:\n");
			for (String suggestion : suggestions) {
				sb.append("  - ").append(suggestion).append("\n");
			}
		}
		return sb.toString();
	}
}
