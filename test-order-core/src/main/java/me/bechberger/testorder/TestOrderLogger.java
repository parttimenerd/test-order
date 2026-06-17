package me.bechberger.testorder;

import java.io.PrintStream;

/**
 * Small internal logger used by the runtime-facing test-order components.
 * Avoids bringing an external logging implementation onto downstream test
 * classpaths.
 */
public final class TestOrderLogger {

	private TestOrderLogger() {
	}

	public static void debug(String message, Object... args) {
		if (isDebugEnabled()) {
			log("DEBUG", message, null, args);
		}
	}

	public static void info(String message, Object... args) {
		log("INFO", message, null, args);
	}

	public static void warn(String message, Object... args) {
		log("WARN", message, null, args);
	}

	public static void error(String message, Object... args) {
		log("ERROR", message, null, args);
	}

	public static void error(String message, Throwable throwable, Object... args) {
		log("ERROR", message, throwable, args);
	}

	private static boolean isDebugEnabled() {
		String value = System.getProperty(TestOrderConfig.DEBUG);
		return value != null && Boolean.parseBoolean(value);
	}

	private static void log(String level, String message, Throwable throwable, Object... args) {
		PrintStream stream = System.err;
		stream.println(prefix(level) + format(message, args));
		if (throwable != null) {
			throwable.printStackTrace(stream);
		}
	}

	private static String prefix(String level) {
		return "[" + level + "] [test-order] ";
	}

	static String format(String template, Object... args) {
		if (template == null) {
			return "null";
		}
		if (args == null || args.length == 0) {
			return template;
		}
		int estimatedArgLength = Math.min(args.length * 20, 1024);
		StringBuilder builder = new StringBuilder(Math.min(template.length() + estimatedArgLength, 8192));
		int argIndex = 0;
		for (int i = 0; i < template.length(); i++) {
			char current = template.charAt(i);
			if (current == '{' && i + 1 < template.length() && template.charAt(i + 1) == '}') {
				if (argIndex < args.length) {
					builder.append(String.valueOf(args[argIndex++]));
				} else {
					builder.append("{}");
				}
				i++;
			} else {
				builder.append(current);
			}
		}
		if (argIndex < args.length) {
			builder.append(" [");
			for (int i = argIndex; i < args.length; i++) {
				if (i > argIndex) {
					builder.append(", ");
				}
				builder.append(String.valueOf(args[i]));
			}
			builder.append(']');
		}
		return builder.toString();
	}
}
