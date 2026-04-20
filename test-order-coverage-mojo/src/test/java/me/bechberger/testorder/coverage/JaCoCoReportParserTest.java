package me.bechberger.testorder.coverage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JaCoCoReportParserTest {

    private JaCoCoReportParser parser;
    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        parser = new JaCoCoReportParser();
        tempDir = Files.createTempDirectory("jacoco-test");
    }

    @Test
    void testParseNonExistentFile() throws IOException, ParserConfigurationException, SAXException {
        File nonExistent = new File(tempDir.toFile(), "non-existent.xml");
        List<ClassMetrics> metrics = parser.parse(nonExistent);

        assertNotNull(metrics);
        assertTrue(metrics.isEmpty());
    }

    @Test
    void testParseSimpleJacocoReport() throws IOException, ParserConfigurationException, SAXException {
        // Create a minimal JaCoCo XML report
        File jacocoFile = tempDir.resolve("index.xml").toFile();
        String jacocoXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<report name=\"coverage\">\n" +
                "  <package name=\"com/example\">\n" +
                "    <class name=\"MyClass\">\n" +
                "      <counter type=\"LINE\" covered=\"50\" missed=\"50\"/>\n" +
                "      <counter type=\"METHOD\" covered=\"3\" missed=\"2\"/>\n" +
                "      <counter type=\"BRANCH\" covered=\"10\" missed=\"5\"/>\n" +
                "    </class>\n" +
                "  </package>\n" +
                "</report>";

        Files.writeString(jacocoFile.toPath(), jacocoXml);

        List<ClassMetrics> metrics = parser.parse(jacocoFile);

        assertNotNull(metrics);
        assertEquals(1, metrics.size());

        ClassMetrics metric = metrics.get(0);
        assertEquals("com.example.MyClass", metric.getFullyQualifiedName());
        assertEquals(50, metric.getLineCoverage());
        assertEquals(60, metric.getMethodCoverage());
        assertEquals(66, metric.getBranchCoverage());
    }

    @Test
    void testParseMultipleClasses() throws IOException, ParserConfigurationException, SAXException {
        File jacocoFile = tempDir.resolve("index.xml").toFile();
        String jacocoXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<report name=\"coverage\">\n" +
                "  <package name=\"com/app\">\n" +
                "    <class name=\"Class1\">\n" +
                "      <counter type=\"LINE\" covered=\"100\" missed=\"0\"/>\n" +
                "      <counter type=\"METHOD\" covered=\"10\" missed=\"0\"/>\n" +
                "      <counter type=\"BRANCH\" covered=\"20\" missed=\"0\"/>\n" +
                "    </class>\n" +
                "    <class name=\"Class2\">\n" +
                "      <counter type=\"LINE\" covered=\"0\" missed=\"100\"/>\n" +
                "      <counter type=\"METHOD\" covered=\"0\" missed=\"10\"/>\n" +
                "      <counter type=\"BRANCH\" covered=\"0\" missed=\"20\"/>\n" +
                "    </class>\n" +
                "  </package>\n" +
                "</report>";

        Files.writeString(jacocoFile.toPath(), jacocoXml);

        List<ClassMetrics> metrics = parser.parse(jacocoFile);

        assertEquals(2, metrics.size());
        assertEquals(100, metrics.get(0).getLineCoverage());
        assertEquals(0, metrics.get(1).getLineCoverage());
    }

    @Test
    void testCoverageClamping() throws IOException, ParserConfigurationException, SAXException {
        File jacocoFile = tempDir.resolve("index.xml").toFile();
        String jacocoXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<report name=\"coverage\">\n" +
                "  <package name=\"pkg\">\n" +
                "    <class name=\"Test\">\n" +
                "      <counter type=\"LINE\" covered=\"0\" missed=\"0\"/>\n" +
                "      <counter type=\"METHOD\" covered=\"0\" missed=\"0\"/>\n" +
                "      <counter type=\"BRANCH\" covered=\"0\" missed=\"0\"/>\n" +
                "    </class>\n" +
                "  </package>\n" +
                "</report>";

        Files.writeString(jacocoFile.toPath(), jacocoXml);

        List<ClassMetrics> metrics = parser.parse(jacocoFile);

        assertEquals(1, metrics.size());
        assertEquals(0, metrics.get(0).getLineCoverage());
    }
}
