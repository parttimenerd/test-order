package me.bechberger.testorder.plugin;

import java.nio.file.Path;
import me.bechberger.testorder.ops.DiagnosticOperation;
import me.bechberger.testorder.ops.DiagnosticOperation.DiagnosticConfig;
import me.bechberger.testorder.ops.DiagnosticOperation.DiagnosticReport;
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
        requiresProject = true)
public class DiagnosticMojo extends AbstractTestOrderMojo {

    @Parameter(property = "testorder.changeMode", defaultValue = "uncommitted")
    private String changeMode;

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

        // Print report
        getLog().info("");
        getLog().info("═══════════════════════════════════════════════════════════");
        getLog().info("[test-order] Diagnostic Report");
        getLog().info("═══════════════════════════════════════════════════════════");
        getLog().info("");
        getLog().info("Health Score: " + report.healthScore() + "%  " + report.overallStatus());
        getLog().info("");
        getLog().info("Checks Performed: " + report.results().size());
        getLog().info("  Errors: " + report.results().stream().filter(r -> r.isError()).count());
        getLog().info(
                "  Warnings: "
                        + report.results()
                                .stream()
                                .filter(r -> r.isInformational() && !r.isSuccess())
                                .count());
        getLog().info("");

        // Print individual results
        for (var result : report.results()) {
            if (result.isError()) {
                getLog().warn("❌ " + result.code() + ": " + result.message());
                for (String suggestion : result.suggestions()) {
                    getLog().warn("   → " + suggestion);
                }
            } else if (result.isInformational()) {
                getLog().info("⚠️  " + result.code() + ": " + result.message());
                if (!result.suggestions().isEmpty()) {
                    for (String suggestion : result.suggestions()) {
                        getLog().info("   → " + suggestion);
                    }
                }
            } else {
                getLog().info("✓ " + result.code().getMessage());
            }
            getLog().info("");
        }

        getLog().info("═══════════════════════════════════════════════════════════");
        getLog().info("");

        // Print summary
        getLog().info("Summary:");
        for (var entry : report.summary().entrySet()) {
            getLog().info("  " + entry.getKey() + ": " + entry.getValue());
        }
        getLog().info("");

        // Fail if requested and there are errors
        if (failOnError && report.hasErrors()) {
            throw new MojoExecutionException(
                    "[test-order] Diagnostic found " + report.results().stream().filter(r -> r.isError())
                            .count()
                            + " error(s). "
                            + "Fix the issues above or run with -Dtestorder.failOnError=false to continue.");
        }

        // Return appropriate exit code
        if (!report.isHealthy()) {
            getLog().warn("[test-order] Diagnostic detected issues. Review above and take action if needed.");
        } else {
            getLog().info("[test-order] Setup looks good! ✓");
        }
    }
}

        DiagnosticReport report = DiagnosticOperation.diagnose(config);

        // Print report
        getLog().info("");
        getLog().info("═══════════════════════════════════════════════════════════");
        getLog().info("[test-order] Diagnostic Report");
        getLog().info("═══════════════════════════════════════════════════════════");
        getLog().info("");
        getLog().info("Health Score: " + report.healthScore() + "%  " + report.overallStatus());
        getLog().info("");
        getLog().info("Checks Performed: " + report.results().size());
        getLog().info("  Errors: " + report.results().stream().filter(r -> r.isError()).count());
        getLog().info(
                "  Warnings: "
                        + report.results()
                                .stream()
                                .filter(r -> r.isInformational() && !r.isSuccess())
                                .count());
        getLog().info("");

        // Print individual results
        for (var result : report.results()) {
            if (result.isError()) {
                getLog().warn("❌ " + result.code() + ": " + result.message());
                for (String suggestion : result.suggestions()) {
                    getLog().warn("   → " + suggestion);
                }
            } else if (result.isInformational()) {
                getLog().info("⚠️  " + result.code() + ": " + result.message());
                if (!result.suggestions().isEmpty()) {
                    for (String suggestion : result.suggestions()) {
                        getLog().info("   → " + suggestion);
                    }
                }
            } else {
                getLog().info("✓ " + result.code().getMessage());
            }
            getLog().info("");
        }

        getLog().info("═══════════════════════════════════════════════════════════");
        getLog().info("");

        // Print summary
        getLog().info("Summary:");
        for (var entry : report.summary().entrySet()) {
            getLog().info("  " + entry.getKey() + ": " + entry.getValue());
        }
        getLog().info("");

        // Fail if requested and there are errors
        if (failOnError && report.hasErrors()) {
            throw new MojoExecutionException(
                    "[test-order] Diagnostic found " + report.results().stream().filter(r -> r.isError())
                            .count()
                            + " error(s). "
                            + "Fix the issues above or run with -Dtestorder.failOnError=false to continue.");
        }

        // Return appropriate exit code
        if (!report.isHealthy()) {
            getLog().warn("[test-order] Diagnostic detected issues. Review above and take action if needed.");
        } else {
            getLog().info("[test-order] Setup looks good! ✓");
        }
    }
}
