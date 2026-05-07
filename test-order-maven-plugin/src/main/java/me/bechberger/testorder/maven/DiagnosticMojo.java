package me.bechberger.testorder.maven;

import java.nio.file.Path;
import me.bechberger.testorder.ops.DiagnosticOperation;
import me.bechberger.testorder.ops.DiagnosticOperation.DiagnosticConfig;
import me.bechberger.testorder.ops.DiagnosticOperation.DiagnosticReport;
import me.bechberger.testorder.ops.DiagnosticReportPrinter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Maven goal to diagnose test-order setup and detect common issues.
 * Validates index, state, hash files, permissions, and change detection.
 *
 * Usage: mvn test-order:diagnose
 */
@Mojo(
        name = "diagnose",
        defaultPhase = LifecyclePhase.VALIDATE,
        threadSafe = true,
        aggregator = true,
        requiresProject = true)
public class DiagnosticMojo extends AbstractTestOrderMojo {

    @Parameter(property = "testorder.failOnError", defaultValue = "false")
    private boolean failOnError;

    @Override
    public void execute() throws MojoExecutionException {
        initContext();
        if (skip) {
            return;
        }

        DiagnosticConfig config =
                new DiagnosticConfig(
                        project.getBasedir().toPath().toAbsolutePath(),
                        ctx.resolveIndexFile(indexFile),
                        ctx.resolveStateFile(stateFile),
                        ctx.resolveHashFile(hashFile),
                        ctx.resolveTestHashFile(testHashFile),
                        ctx.resolveMethodHashFile(methodHashFile),
                        ctx.resolveDepsDir(depsDir),
                        resolveTestSourceRoot(),
                        changeMode,
                        MavenPluginLog.wrap(getLog()));

        DiagnosticReport report = DiagnosticOperation.diagnose(config);

        DiagnosticReportPrinter.print(report, MavenPluginLog.wrap(getLog()));

        // Fail if requested and there are errors
        if (failOnError && report.hasErrors()) {
            throw new MojoExecutionException(
                    "[test-order] Diagnostic found " + report.results().stream().filter(r -> r.isError())
                            .count()
                            + " error(s). "
                            + "Fix the issues above or run with -Dtestorder.failOnError=false to continue.");
        }
    }
}

