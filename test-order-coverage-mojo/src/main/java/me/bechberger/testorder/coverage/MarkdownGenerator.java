package me.bechberger.testorder.coverage;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class MarkdownGenerator {
	private final CoverageReporter reporter;
	private final Gson gson;

	public MarkdownGenerator(CoverageReporter reporter) {
		this.reporter = reporter;
		this.gson = new GsonBuilder().setPrettyPrinting().create();
	}

	/**
	 * Write coverage by module to markdown file
	 */
	public void writeCoverageByModule(File outputFile) throws IOException {
		String content = reporter.generateModuleReport();
		writeFile(outputFile, content);
	}

	/**
	 * Write least tested classes to markdown file
	 */
	public void writeLeastTestedClasses(File outputFile, int threshold) throws IOException {
		String content = reporter.generateDetailedReport(threshold);
		writeFile(outputFile, content);
	}

	/**
	 * Write recommendations to markdown file
	 */
	public void writeRecommendations(File outputFile, int threshold) throws IOException {
		String content = reporter.generateRecommendations(threshold);
		writeFile(outputFile, content);
	}

	/**
	 * Write coverage metrics to JSON file
	 */
	public void writeCoverageMetricsJson(File outputFile, int threshold) throws IOException {
		Map<String, Object> data = new LinkedHashMap<>();

		data.put("timestamp", System.currentTimeMillis());
		data.put("threshold", threshold);
		data.put("moduleStatistics", reporter.getModuleStatistics());

		String json = gson.toJson(data);
		writeFile(outputFile, json);
	}

	/**
	 * Write all coverage reports
	 */
	public void writeAllReports(File outputDir, int threshold) throws IOException {
		if (!outputDir.exists()) {
			outputDir.mkdirs();
		}

		writeCoverageByModule(new File(outputDir, "COVERAGE_BY_MODULE.md"));
		writeLeastTestedClasses(new File(outputDir, "LEAST_TESTED_CLASSES.md"), threshold);
		writeRecommendations(new File(outputDir, "COVERAGE_RECOMMENDATIONS.md"), threshold);
		writeCoverageMetricsJson(new File(outputDir, "coverage-metrics.json"), threshold);
	}

	/**
	 * Write a single summary report with all sections
	 */
	public void writeComprehensiveReport(File outputFile, int threshold) throws IOException {
		StringBuilder sb = new StringBuilder();

		sb.append(reporter.generateSummary(threshold));
		sb.append("\n\n---\n\n");
		sb.append(reporter.generateModuleReport());
		sb.append("\n\n---\n\n");
		sb.append(reporter.generateDetailedReport(threshold));
		sb.append("\n\n---\n\n");
		sb.append(reporter.generateRecommendations(threshold));

		writeFile(outputFile, sb.toString());
	}

	private void writeFile(File file, String content) throws IOException {
		if (file == null) {
			throw new IOException("Output file cannot be null");
		}

		File parentDir = file.getParentFile();
		if (parentDir != null && !parentDir.exists()) {
			if (!parentDir.mkdirs()) {
				throw new IOException("Failed to create parent directory: " + parentDir.getAbsolutePath());
			}
		}

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
			writer.write(content);
		}
	}
}
