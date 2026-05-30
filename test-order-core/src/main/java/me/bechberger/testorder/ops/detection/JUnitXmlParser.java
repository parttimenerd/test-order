package me.bechberger.testorder.ops.detection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses JUnit/Surefire XML test reports to determine class- and method-level
 * pass/fail outcomes. Used by both the Maven and Gradle test runners.
 */
public final class JUnitXmlParser {

	private JUnitXmlParser() {
	}

	/**
	 * How to classify a test class that has no matching report file.
	 */
	public enum MissingReportPolicy {
		/** Count as passed (Maven Surefire: missing = skipped). */
		PASS,
		/** Count as failed (Gradle: missing = crash/OOM/System.exit). */
		FAIL
	}

	/**
	 * Parses all {@code TEST-*.xml} files in {@code reportDir} and populates
	 * {@code passed}/{@code failed} with class FQCNs. Uses
	 * {@code preferClassnameAttr=true} for Gradle reports (FQCN in testcase
	 * classname attribute), {@code false} for Surefire reports (FQCN in testsuite
	 * name attribute).
	 *
	 * <p>
	 * Tests in {@code executionOrder} not mentioned in any report are classified
	 * according to {@code missingPolicy}.
	 *
	 * @return populated {@link TestRunner.TestRunResult}
	 */
	public static TestRunner.TestRunResult parseClassResults(Path reportDir, List<String> executionOrder,
			boolean preferClassnameAttr, MissingReportPolicy missingPolicy) {

		Set<String> passed = new HashSet<>();
		Set<String> failed = new HashSet<>();

		if (Files.exists(reportDir)) {
			try (var files = Files.list(reportDir)) {
				files.filter(p -> p.getFileName().toString().startsWith("TEST-")
						&& p.getFileName().toString().endsWith(".xml"))
						.forEach(report -> parseClassReport(report, passed, failed, preferClassnameAttr));
			} catch (IOException ignored) {
			}
		}

		for (String test : executionOrder) {
			if (!passed.contains(test) && !failed.contains(test)) {
				if (missingPolicy == MissingReportPolicy.PASS) {
					passed.add(test);
				} else {
					failed.add(test);
				}
			}
		}

		return new TestRunner.TestRunResult(executionOrder, passed, failed);
	}

	/**
	 * Parses all {@code TEST-*.xml} files in {@code reportDir} and returns
	 * method-level pass/fail for {@code testClass}.
	 *
	 * <p>
	 * Methods in {@code methodOrder} not mentioned in any report are classified
	 * according to {@code missingPolicy}.
	 */
	public static TestRunner.MethodRunResult parseMethodResults(Path reportDir, String testClass,
			List<String> methodOrder, MissingReportPolicy missingPolicy) {

		Set<String> passed = new HashSet<>();
		Set<String> failed = new HashSet<>();

		if (Files.exists(reportDir)) {
			try (var files = Files.list(reportDir)) {
				files.filter(p -> p.getFileName().toString().startsWith("TEST-")
						&& p.getFileName().toString().endsWith(".xml"))
						.forEach(report -> parseMethodReport(report, testClass, passed, failed));
			} catch (IOException ignored) {
			}
		}

		for (String method : methodOrder) {
			if (!passed.contains(method) && !failed.contains(method)) {
				if (missingPolicy == MissingReportPolicy.PASS) {
					passed.add(method);
				} else {
					failed.add(method);
				}
			}
		}

		return new TestRunner.MethodRunResult(testClass, methodOrder, passed, failed);
	}

	private static void parseClassReport(Path report, Set<String> passed, Set<String> failed,
			boolean preferClassnameAttr) {
		try {
			String content = Files.readString(report);

			String className = null;
			if (preferClassnameAttr) {
				className = extractFirstTestCaseClassname(content);
			}
			if (className == null) {
				className = extractAttribute(content, "name");
			}
			if (className == null)
				return;

			int totalTests = parseIntSafe(extractAttribute(content, "tests"));
			int failCount = parseIntSafe(extractAttribute(content, "failures"))
					+ parseIntSafe(extractAttribute(content, "errors"));
			int skipped = parseIntSafe(extractAttribute(content, "skipped"));
			int effective = totalTests - skipped;

			if (effective > 0 && failCount >= effective) {
				failed.add(className);
			} else {
				passed.add(className);
			}
		} catch (IOException ignored) {
		}
	}

	private static void parseMethodReport(Path report, String testClass, Set<String> passed, Set<String> failed) {
		try {
			String content = Files.readString(report);
			int idx = 0;
			while (true) {
				int tcStart = content.indexOf("<testcase ", idx);
				if (tcStart < 0)
					break;
				int tcEnd = content.indexOf(">", tcStart);
				if (tcEnd < 0)
					break;

				String tag = content.substring(tcStart, tcEnd + 1);

				String className = extractAttributeFromTag(tag, "classname");
				if (className != null && !testClass.equals(className) && !testClass.endsWith("." + className)) {
					idx = tcEnd + 1;
					continue;
				}

				String methodName = extractAttributeFromTag(tag, "name");
				if (methodName != null) {
					int parenIdx = methodName.indexOf('(');
					if (parenIdx > 0) {
						methodName = methodName.substring(0, parenIdx);
					}

					boolean hasFail = false;
					if (!tag.endsWith("/>")) {
						int nextClose = content.indexOf("</testcase>", tcEnd);
						if (nextClose > 0) {
							String body = content.substring(tcEnd, nextClose);
							hasFail = body.contains("<failure") || body.contains("<error");
							idx = nextClose + "</testcase>".length();
						} else {
							idx = tcEnd + 1;
						}
					} else {
						idx = tcEnd + 1;
					}

					if (hasFail) {
						failed.add(methodName);
					} else {
						passed.add(methodName);
					}
				} else {
					idx = tcEnd + 1;
				}
			}
		} catch (IOException ignored) {
		}
	}

	/**
	 * Extracts an XML attribute value from an XML string. Uses space-prefix to
	 * avoid matching the attribute name as a suffix of another (e.g. "name" inside
	 * "classname").
	 */
	public static String extractAttribute(String xml, String attr) {
		String prefix = " " + attr + "=\"";
		int start = xml.indexOf(prefix);
		if (start < 0)
			return null;
		start += prefix.length();
		int end = xml.indexOf('"', start);
		if (end < 0)
			return null;
		return xml.substring(start, end);
	}

	/**
	 * Extracts an XML attribute value from a tag snippet (same space-prefix
	 * avoidance).
	 */
	public static String extractAttributeFromTag(String tag, String attr) {
		String prefix = " " + attr + "=\"";
		int start = tag.indexOf(prefix);
		if (start < 0)
			return null;
		start += prefix.length();
		int end = tag.indexOf('"', start);
		if (end < 0)
			return null;
		return tag.substring(start, end);
	}

	/**
	 * Extracts the FQCN from the first {@code <testcase classname="...">} element.
	 * Gradle XML reports always include FQCN in classname; Surefire may use simple
	 * names in some versions.
	 */
	public static String extractFirstTestCaseClassname(String xml) {
		int tcStart = xml.indexOf("<testcase ");
		if (tcStart < 0)
			return null;
		int tcEnd = xml.indexOf(">", tcStart);
		if (tcEnd < 0)
			return null;
		return extractAttributeFromTag(xml.substring(tcStart, tcEnd + 1), "classname");
	}

	/** Parses an integer, returning 0 for null/empty/non-numeric input. */
	public static int parseIntSafe(String s) {
		if (s == null || s.isEmpty())
			return 0;
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
