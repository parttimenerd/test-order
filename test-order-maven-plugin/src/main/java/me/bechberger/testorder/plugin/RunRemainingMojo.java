package me.bechberger.testorder.plugin;

import me.bechberger.testorder.TestSelector;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * Configures Surefire to run the remaining test classes that were deferred by
 * a previous {@code test-order:select} or {@code test-order:combined} goal.
 * <p>
 * Usage: {@code mvn test-order:run-remaining test}
 */
@Mojo(name = "run-remaining", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class RunRemainingMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** File containing the remaining test classes (one FQCN per line). */
    @Parameter(property = "testorder.select.remainingFile",
            defaultValue = "${project.build.directory}/test-order-remaining.txt")
    private String remainingFile;

    @Override
    public void execute() throws MojoExecutionException {
        Path remaining = Path.of(remainingFile);
        if (!Files.exists(remaining)) {
            getLog().info("[test-order] No remaining-tests file found at " + remaining
                    + " — nothing to run.");
            project.getProperties().setProperty("skipTests", "true");
            return;
        }

        List<String> tests;
        try {
            tests = TestSelector.readTestList(remaining);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read remaining tests file", e);
        }

        if (tests.isEmpty()) {
            getLog().info("[test-order] Remaining tests file is empty — skipping tests.");
            project.getProperties().setProperty("skipTests", "true");
            return;
        }

        getLog().info("[test-order] Running " + tests.size() + " remaining test classes");
        SurefireHelper.configureIncludes(project, tests, true);
    }
}
