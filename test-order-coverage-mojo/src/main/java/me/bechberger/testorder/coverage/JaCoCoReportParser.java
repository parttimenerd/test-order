package me.bechberger.testorder.coverage;

import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JaCoCoReportParser {
    private static final Pattern COUNTER_PATTERN = Pattern.compile("^(\\d+)/(\\d+)$");

    public List<ClassMetrics> parse(File jacocoReportFile) throws IOException, ParserConfigurationException, SAXException {
        if (!jacocoReportFile.exists()) {
            return new ArrayList<>();
        }

        List<ClassMetrics> metrics = new ArrayList<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(jacocoReportFile);

        parseClasses(doc.getDocumentElement(), "", metrics);
        return metrics;
    }

    private void parseClasses(Element element, String packageName, List<ClassMetrics> metrics) {
        NodeList children = element.getChildNodes();
        String currentPackage = packageName;

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;

            Element el = (Element) child;
            String tagName = el.getTagName();

            if ("package".equals(tagName)) {
                currentPackage = el.getAttribute("name").replace('/', '.');
                parseClasses(el, currentPackage, metrics);
            } else if ("class".equals(tagName)) {
                ClassMetrics metric = parseClass(el, currentPackage);
                if (metric != null) {
                    metrics.add(metric);
                }
            } else if ("sourcefile".equals(tagName)) {
                // Skip sourcefile nodes, focus on class nodes
            }
        }
    }

    private ClassMetrics parseClass(Element classElement, String packageName) {
        String className = classElement.getAttribute("name");
        if (className.isEmpty()) return null;

        String fqcn = packageName.isEmpty() ? className : packageName + "." + className;

        // Extract coverage metrics from counter elements
        int lineCoverage = (int) extractCoveragePercent(classElement, "LINE");
        int methodCoverage = (int) extractCoveragePercent(classElement, "METHOD");
        int branchCoverage = (int) extractCoveragePercent(classElement, "BRANCH");
        int statementsCovered = extractCovered(classElement, "LINE");
        int statementsTotal = statementsCovered + extractMissed(classElement, "LINE");

        String simpleName = className.contains(".") ? className.substring(className.lastIndexOf(".") + 1) : className;

        return new ClassMetrics(
                fqcn,
                "",  // module will be resolved later
                packageName,
                simpleName,
                lineCoverage,
                methodCoverage,
                branchCoverage,
                statementsCovered,
                statementsTotal,
                0,  // methodsCovered - not available in simple parsing
                0,  // methodsTotal
                0,  // test count will be filled by SurefireReportParser
                new ArrayList<>(),  // test names
                false,  // isAbstract
                false,  // isInterface
                false   // isEnum
        );
    }

    private double extractCoveragePercent(Element element, String counterType) {
        NodeList counters = element.getElementsByTagName("counter");
        for (int i = 0; i < counters.getLength(); i++) {
            Element counter = (Element) counters.item(i);
            if (counterType.equals(counter.getAttribute("type"))) {
                int covered = Integer.parseInt(counter.getAttribute("covered"));
                int missed = Integer.parseInt(counter.getAttribute("missed"));
                int total = covered + missed;
                if (total == 0) return 0.0;
                return (100.0 * covered) / total;
            }
        }
        return 0.0;
    }

    private int extractCovered(Element element, String counterType) {
        NodeList counters = element.getElementsByTagName("counter");
        for (int i = 0; i < counters.getLength(); i++) {
            Element counter = (Element) counters.item(i);
            if (counterType.equals(counter.getAttribute("type"))) {
                return Integer.parseInt(counter.getAttribute("covered"));
            }
        }
        return 0;
    }

    private int extractMissed(Element element, String counterType) {
        NodeList counters = element.getElementsByTagName("counter");
        for (int i = 0; i < counters.getLength(); i++) {
            Element counter = (Element) counters.item(i);
            if (counterType.equals(counter.getAttribute("type"))) {
                return Integer.parseInt(counter.getAttribute("missed"));
            }
        }
        return 0;
    }
}
