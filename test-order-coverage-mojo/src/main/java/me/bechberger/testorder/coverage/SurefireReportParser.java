package me.bechberger.testorder.coverage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class SurefireReportParser {

	public Map<String, Integer> parseTestCounts(File surefireReportsDir)
			throws IOException, ParserConfigurationException, SAXException {
		Map<String, Integer> testCountByClass = new HashMap<>();

		if (!surefireReportsDir.exists() || !surefireReportsDir.isDirectory()) {
			return testCountByClass;
		}

		// Find all TEST-*.xml files in the directory
		try (var pathStream = Files.walk(Paths.get(surefireReportsDir.toURI()))) {
			List<File> testReports = pathStream
					.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml"))
					.map(Path::toFile).collect(java.util.stream.Collectors.toList());

			for (File testReport : testReports) {
				parseTestReport(testReport, testCountByClass);
			}
		}

		return testCountByClass;
	}

	private void parseTestReport(File testReportFile, Map<String, Integer> testCountByClass)
			throws IOException, ParserConfigurationException, SAXException {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document doc = builder.parse(testReportFile);

		Element testSuite = doc.getDocumentElement();
		if (!"testsuite".equals(testSuite.getTagName())) {
			return;
		}

		String className = testSuite.getAttribute("name");
		if (className.isEmpty()) {
			return;
		}

		// Count test cases (testcase elements)
		NodeList testCases = doc.getElementsByTagName("testcase");
		int testCount = testCases.getLength();

		if (testCount > 0) {
			testCountByClass.put(className, testCount);
		}
	}
}
