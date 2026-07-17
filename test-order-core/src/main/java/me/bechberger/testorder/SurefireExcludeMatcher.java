package me.bechberger.testorder;

import java.util.List;

/**
 * Matches test-class FQCNs against Surefire/Failsafe {@code <excludes>}
 * patterns.
 *
 * <p>
 * This is the Maven-independent single source of truth for exclude matching,
 * shared by two call sites so they never diverge:
 * <ul>
 * <li>the <b>run path</b> — {@code SurefireHelper.configureIncludes} drops
 * excluded classes before building {@code -Dtest=} (BUG-168: Surefire's
 * {@code -Dtest=} overrides {@code <excludes>}, so a deliberately-excluded test
 * would otherwise be run);</li>
 * <li>the <b>reporting path</b> — the {@code show} Selection Preview filters
 * excluded classes so it does not advertise tests that will never run (BUG-172:
 * a {@code **}{@code /*PerformanceTest.java} exclude was shown as the top
 * selection while {@code affected} correctly dropped it).</li>
 * </ul>
 *
 * <p>
 * Supports Ant-style file globs (matched against the class's path form, e.g.
 * {@code org/apache/foo/BarIT.java}) and Surefire's {@code %regex[...]} form
 * (matched against the dotted FQCN).
 */
public final class SurefireExcludeMatcher {

	private SurefireExcludeMatcher() {
	}

	/**
	 * Returns {@code true} when the given test class FQCN matches any of the given
	 * Surefire exclude patterns. Inner classes are normalized to their enclosing
	 * top-level class before matching.
	 */
	public static boolean matches(String fqcn, List<String> patterns) {
		if (fqcn == null || patterns == null || patterns.isEmpty()) {
			return false;
		}
		// Normalize inner classes to their enclosing top-level class.
		int dollar = fqcn.indexOf('$');
		String topLevel = dollar > 0 ? fqcn.substring(0, dollar) : fqcn;
		String path = topLevel.replace('.', '/');
		String javaPath = path + ".java";
		String classPath = path + ".class";
		for (String pattern : patterns) {
			if (pattern == null || pattern.isBlank()) {
				continue;
			}
			String p = pattern.trim();
			if (p.startsWith("%regex[") && p.endsWith("]")) {
				String regex = p.substring("%regex[".length(), p.length() - 1);
				// Surefire matches %regex[...] against the class name (dotted FQCN),
				// optionally with a .class/.java suffix — match against the dotted form.
				if (topLevel.matches(regex)) {
					return true;
				}
				continue;
			}
			String regex = globToRegex(p);
			if (javaPath.matches(regex) || classPath.matches(regex)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Converts an Ant-style file glob (as used in Surefire {@code <excludes>}) to a
	 * regular expression anchored to the full path. {@code **} matches across
	 * directory separators (including none), {@code *} matches within a single path
	 * segment, {@code ?} matches a single non-separator character. A leading
	 * {@code **}{@code /} also matches a class at the path root (zero directories).
	 */
	private static String globToRegex(String glob) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			switch (c) {
				case '*' :
					if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
						i++;
						// Collapse "**/" so it also matches zero directories at the root.
						if (i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
							i++;
							sb.append("(?:.*/)?");
						} else {
							sb.append(".*");
						}
					} else {
						sb.append("[^/]*");
					}
					break;
				case '?' :
					sb.append("[^/]");
					break;
				case '.' :
				case '(' :
				case ')' :
				case '+' :
				case '|' :
				case '^' :
				case '$' :
				case '{' :
				case '}' :
				case '[' :
				case ']' :
				case '\\' :
					sb.append('\\').append(c);
					break;
				default :
					sb.append(c);
			}
		}
		return sb.toString();
	}
}
