package me.bechberger.testorder.ops.workflows;

import java.io.IOException;
import java.nio.file.Path;

import me.bechberger.testorder.TestScorer;
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

	public DashboardWorkflow(PluginContext ctx, String htmlTemplate, Path outputDir) {
		this.ctx = ctx;
		this.htmlTemplate = htmlTemplate;
		this.outputDir = outputDir;
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
		ChangeAnalysis.Result a = ChangeAnalysis.analyze(ctx, ChangeAnalysis.Options.FULL);

		TestScorer scorer = a.buildScorer();

		GenerateDashboardOperation.generate(a.allTests(), scorer, a.state(), a.weights(), a.changedClasses(),
				a.changedTests(), a.depMap(), ctx.projectName(),
				ctx.stateFile() != null ? ctx.stateFile().toString() : "",
				ctx.indexFile() != null ? ctx.indexFile().toString() : "", ctx.pluginVersion(),
				a.loadedWeights().defs(), htmlTemplate, outputFile, ctx.log());

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
}
