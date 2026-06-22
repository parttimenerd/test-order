package me.bechberger.testorder.ops;

import java.util.Comparator;
import java.util.List;

import me.bechberger.testorder.ops.DiagnosticOperation.DiagnosticReport;

/**
 * Shared formatter for diagnostic report output. Both Maven and Gradle plugins
 * delegate to this instead of duplicating the box-drawing header, emoji, and
 * indentation logic.
 */
public final class DiagnosticReportPrinter {

	private DiagnosticReportPrinter() {
	}

	/**
	 * Prints a formatted diagnostic report to the given logger.
	 *
	 * @param report
	 *            the diagnostic report
	 * @param log
	 *            plugin logger
	 */
	public static void print(DiagnosticReport report, PluginLog log) {
		log.info("");
		log.info("═══════════════════════════════════════════════════════════");
		log.info("[test-order] Diagnostic Report");
		log.info("═══════════════════════════════════════════════════════════");
		log.info("");
		log.info("Health Score: " + report.healthScore() + "%  " + report.overallStatus());
		log.info("");
		log.info("Checks Performed: " + report.results().size());
		log.info("  Errors: " + report.results().stream().filter(r -> r.isError()).count());
		log.info("  Warnings: " + report.results().stream().filter(r -> r.isInformational() && !r.isSuccess()).count());
		log.info("");

		// Sort ERROR → WARNING → SUCCESS so the most actionable issues appear first.
		List<DiagnosticResult> sorted = report.results().stream()
				.sorted(Comparator.comparingInt(r -> r.isError() ? 0 : (r.isInformational() && !r.isSuccess()) ? 1 : 2))
				.toList();

		for (var result : sorted) {
			if (result.isError()) {
				log.error("❌ " + result.code() + ": " + result.message());
				for (String suggestion : result.suggestions()) {
					log.error("   → " + suggestion);
				}
			} else if (result.isInformational()) {
				log.info("⚠️  " + result.code() + ": " + result.message());
				if (!result.suggestions().isEmpty()) {
					for (String suggestion : result.suggestions()) {
						log.info("   → " + suggestion);
					}
				}
			} else {
				log.info("✓ " + result.message());
			}
			log.info("");
		}

		log.info("═══════════════════════════════════════════════════════════");
		log.info("");

		log.info("Summary:");
		for (var entry : report.summary().entrySet()) {
			log.info("  " + entry.getKey() + ": " + entry.getValue());
		}
		log.info("");

		if (report.isFreshProject()) {
			log.info("[test-order] Not yet set up — run learn mode to start.");
			log.info("Run: mvn test -Dtestorder.mode=learn");
		} else if (!report.isHealthy()) {
			log.warn("[test-order] Diagnostic detected issues. Review above and take action if needed.");
		} else {
			log.info("[test-order] Setup looks good! ✓");
		}
	}
}
