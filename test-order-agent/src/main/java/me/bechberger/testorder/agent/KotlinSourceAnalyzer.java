package me.bechberger.testorder.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzer for Kotlin source files to extract package names and class
 * definitions. Uses regex-based parsing of Kotlin syntax to extract packages
 * and top-level classes.
 *
 * This is more reliable than directory-based scanning and handles non-standard
 * directory layouts.
 */
public class KotlinSourceAnalyzer {
	private static final Pattern PACKAGE_DECLARATION = Pattern
			.compile("^\\s*package\\s+([a-zA-Z_][a-zA-Z0-9._]*)\\s*(?://.*)?(?:/\\*.*?\\*/)?$", Pattern.MULTILINE);

	private static final Pattern CLASS_DECLARATION = Pattern.compile(
			"^\\s*(?:public\\s+)?(?:abstract\\s+)?(?:sealed\\s+)?(?:data\\s+)?class\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\b",
			Pattern.MULTILINE);

	private static final Pattern INTERFACE_DECLARATION = Pattern
			.compile("^\\s*(?:public\\s+)?interface\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\b", Pattern.MULTILINE);

	private static final Pattern OBJECT_DECLARATION = Pattern
			.compile("^\\s*(?:public\\s+)?object\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\b", Pattern.MULTILINE);

	/**
	 * Extract package name from a Kotlin source file.
	 *
	 * @param kotlinFile
	 *            path to the Kotlin source file
	 * @return the package name, or null if not found
	 */
	public static String extractPackage(Path kotlinFile) {
		try {
			String content = Files.readString(kotlinFile);
			// Remove string literals and comments to avoid false matches
			String cleaned = removeStringsAndComments(content);

			Matcher matcher = PACKAGE_DECLARATION.matcher(cleaned);
			if (matcher.find()) {
				return matcher.group(1);
			}
		} catch (IOException e) {
			// File read error - return null
		}
		return null;
	}

	/**
	 * Extract top-level class/object names from a Kotlin source file. Filters out
	 * nested and inner classes by tracking brace depth. Top-level classes/objects
	 * are those declared at brace depth 0.
	 *
	 * @param kotlinFile
	 *            path to the Kotlin source file
	 * @return set of top-level class and object names
	 */
	public static Set<String> extractTopLevelClasses(Path kotlinFile) {
		Set<String> classes = new HashSet<>();
		try {
			String content = Files.readString(kotlinFile);
			String cleaned = removeStringsAndComments(content);

			// Split by lines to check indentation
			String[] lines = cleaned.split("\n");
			int braceDepth = 0;

			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.isEmpty())
					continue;

				// Check declarations BEFORE updating brace depth
				if (braceDepth == 0) {
					// Check for class declaration
					Matcher classMatcher = CLASS_DECLARATION.matcher(line);
					if (classMatcher.find()) {
						classes.add(classMatcher.group(1));
					}

					// Check for interface declaration
					Matcher interfaceMatcher = INTERFACE_DECLARATION.matcher(line);
					if (interfaceMatcher.find()) {
						classes.add(interfaceMatcher.group(1));
					}

					// Check for object declaration
					Matcher objectMatcher = OBJECT_DECLARATION.matcher(line);
					if (objectMatcher.find()) {
						classes.add(objectMatcher.group(1));
					}
				}

				// Now count braces for next line
				for (char c : line.toCharArray()) {
					if (c == '{')
						braceDepth++;
					else if (c == '}')
						braceDepth--;
				}

				// Sanity check
				if (braceDepth < 0)
					braceDepth = 0;
			}
		} catch (IOException e) {
			// File read error - return empty set
		}
		return classes;
	}

	/**
	 * Remove string literals and comments from Kotlin source to avoid false
	 * matches. Handles single-line strings, triple-quoted strings, and both comment
	 * types.
	 */
	private static String removeStringsAndComments(String content) {
		StringBuilder result = new StringBuilder();
		int i = 0;
		while (i < content.length()) {
			// Handle triple-quoted strings
			if (i + 2 < content.length() && content.startsWith("\"\"\"", i)) {
				// Skip until closing triple quote
				i += 3;
				while (i + 2 < content.length() && !content.startsWith("\"\"\"", i)) {
					result.append(' ');
					i++;
				}
				if (i + 2 < content.length()) {
					i += 3;
				}
				continue;
			}

			// Handle regular strings
			if (content.charAt(i) == '"') {
				result.append(' ');
				i++;
				while (i < content.length() && content.charAt(i) != '"') {
					if (content.charAt(i) == '\\' && i + 1 < content.length()) {
						i += 2;
					} else {
						i++;
					}
					result.append(' ');
				}
				if (i < content.length()) {
					result.append(' ');
					i++;
				}
				continue;
			}

			// Handle character literals
			if (content.charAt(i) == '\'') {
				result.append(' ');
				i++;
				while (i < content.length() && content.charAt(i) != '\'') {
					if (content.charAt(i) == '\\' && i + 1 < content.length()) {
						i += 2;
					} else {
						i++;
					}
					result.append(' ');
				}
				if (i < content.length()) {
					result.append(' ');
					i++;
				}
				continue;
			}

			// Handle line comments
			if (i + 1 < content.length() && content.startsWith("//", i)) {
				// Skip until end of line
				while (i < content.length() && content.charAt(i) != '\n') {
					result.append(' ');
					i++;
				}
				if (i < content.length()) {
					result.append('\n');
					i++;
				}
				continue;
			}

			// Handle block comments
			if (i + 1 < content.length() && content.startsWith("/*", i)) {
				// Skip until closing */
				result.append(' ');
				result.append(' ');
				i += 2;
				while (i + 1 < content.length() && !content.startsWith("*/", i)) {
					result.append(content.charAt(i) == '\n' ? '\n' : ' ');
					i++;
				}
				if (i + 1 < content.length()) {
					result.append(' ');
					result.append(' ');
					i += 2;
				}
				continue;
			}

			result.append(content.charAt(i));
			i++;
		}
		return result.toString();
	}
}
