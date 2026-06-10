package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import me.bechberger.testorder.TestScorer;
import me.bechberger.testorder.ml.TestHealthReport;
import me.bechberger.testorder.ops.DashboardServerOperation;
import me.bechberger.testorder.ops.GenerateDashboardOperation;
import me.bechberger.testorder.ops.PluginContext;

/**
 * Dashboard workflow: load index → detect changes → structural analysis → build
 * scorer → generate self-contained HTML dashboard.
 *
 * <p>
 * Create an instance with the fully-configured {@link PluginContext}, HTML
 * template, and output directory, then call {@link #generate()} or
 * {@link #serve(int)}.
 */
public final class DashboardWorkflow {

	private final PluginContext ctx;
	private final String htmlTemplate;
	private final Path outputDir;
	private final Map<String, Double> mlPredictions;
	private final TestHealthReport healthReport;

	public DashboardWorkflow(PluginContext ctx, String htmlTemplate, Path outputDir) {
		this(ctx, htmlTemplate, outputDir, null, null);
	}

	public DashboardWorkflow(PluginContext ctx, String htmlTemplate, Path outputDir, Map<String, Double> mlPredictions,
			TestHealthReport healthReport) {
		this.ctx = ctx;
		this.htmlTemplate = htmlTemplate;
		this.outputDir = outputDir;
		this.mlPredictions = mlPredictions;
		this.healthReport = healthReport;
	}

	/**
	 * Generates the self-contained HTML dashboard.
	 *
	 * @return path to the generated index.html
	 */
	public Path generate() throws IOException {
		return generate(outputDir.resolve("index.html"));
	}

	/**
	 * Generates the self-contained HTML dashboard at the specified output path.
	 *
	 * @param outputFile
	 *            the target HTML file path
	 * @return the output file path
	 */
	public Path generate(Path outputFile) throws IOException {
		// When testClassesDir or testSourceRoot points to a specific module, filter the
		// shared reactor index to that module's tests only.
		boolean hasModuleScope = (ctx.testClassesDir() != null && java.nio.file.Files.isDirectory(ctx.testClassesDir()))
				|| (ctx.testSourceRoot() != null && java.nio.file.Files.isDirectory(ctx.testSourceRoot()));
		ChangeAnalysis.Options analysisOpts = hasModuleScope
				? ChangeAnalysis.Options.FULL_FILTERED
				: ChangeAnalysis.Options.FULL;
		ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, analysisOpts);

		TestScorer scorer = a.buildScorer();

		GenerateDashboardOperation.generate(a.allTests(), scorer, a.state(), a.weights(), a.changedClasses(),
				a.changedTests(), a.depMap(), ctx.projectName(),
				ctx.stateFile() != null ? relativize(ctx.projectRoot(), ctx.stateFile()) : "",
				ctx.indexFile() != null ? relativize(ctx.projectRoot(), ctx.indexFile()) : "", ctx.pluginVersion(),
				a.loadedWeights().defs(), mlPredictions, healthReport, ctx.depsDir(), htmlTemplate, outputFile,
				ctx.log());

		return outputFile;
	}

	/**
	 * Generates the dashboard and serves it via a local HTTP server. Blocks until
	 * interrupted (Ctrl+C).
	 *
	 * @param port
	 *            TCP port (0 = ephemeral)
	 * @return the port the server bound to
	 */
	public int serve(int port) throws IOException {
		Path htmlPath = generate();
		return DashboardServerOperation.start(htmlPath, ctx.stateFile(), port, ctx.log());
	}

	/**
	 * Relativize a path against the project root, falling back to file name if not
	 * relative.
	 */
	private static String relativize(Path projectRoot, Path target) {
		try {
			return projectRoot.toAbsolutePath().relativize(target.toAbsolutePath()).toString();
		} catch (IllegalArgumentException e) {
			return target.getFileName().toString();
		}
	}
}
