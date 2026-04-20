package me.bechberger.testorder.coverage;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates configuration parameters for coverage mojo.
 * Provides helpful error messages and guidance.
 */
public class CoverageParameterValidator {

    private final Log log;

    public CoverageParameterValidator(Log log) {
        this.log = log;
    }

    /**
     * Validates coverage threshold value (0-100).
     */
    public void validateThreshold(int threshold) throws MojoExecutionException {
        if (threshold < 0 || threshold > 100) {
            throw new MojoExecutionException(
                String.format(
                    "[test-order-coverage] Threshold must be between 0 and 100, got: %d",
                    threshold
                )
            );
        }
    }

    /**
     * Validates output format parameter.
     * Valid values: comprehensive, markdown, json
     */
    public void validateOutputFormat(String outputFormat) throws MojoExecutionException {
        if (outputFormat == null || outputFormat.isBlank()) {
            return; // null is OK, will use default
        }

        Set<String> validFormats = new HashSet<>(Arrays.asList(
            "comprehensive",
            "markdown",
            "json"
        ));

        if (!validFormats.contains(outputFormat.toLowerCase())) {
            throw new MojoExecutionException(
                String.format(
                    "[test-order-coverage] Invalid outputFormat '%s'. Valid values are: %s",
                    outputFormat,
                    String.join(", ", validFormats)
                )
            );
        }
    }

    /**
     * Validates output directory and creates if needed.
     */
    public void validateOutputDirectory(String dirPath, String parameterName) throws MojoExecutionException {
        if (dirPath == null || dirPath.isBlank()) {
            throw new MojoExecutionException(
                String.format("[test-order-coverage] Output directory (%s) cannot be empty", parameterName)
            );
        }

        Path path = Paths.get(dirPath);
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
                log.debug("[test-order-coverage] Created output directory: " + path.toAbsolutePath());
            }

            if (!Files.isDirectory(path)) {
                throw new MojoExecutionException(
                    String.format(
                        "[test-order-coverage] Output path is not a directory: %s",
                        path.toAbsolutePath()
                    )
                );
            }

            if (!Files.isWritable(path)) {
                throw new MojoExecutionException(
                    String.format(
                        "[test-order-coverage] Output directory is not writable: %s",
                        path.toAbsolutePath()
                    )
                );
            }
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException(
                String.format(
                    "[test-order-coverage] Failed to validate output directory: %s",
                    path.toAbsolutePath()
                ),
                e
            );
        }
    }

    /**
     * Validates includeModules parameter format.
     * Expected format: comma-separated module names (optional)
     */
    public void validateIncludeModules(String includeModules) throws MojoExecutionException {
        if (includeModules == null || includeModules.isBlank()) {
            return; // Optional parameter
        }

        // Check for obviously invalid characters
        if (includeModules.contains("//") || includeModules.contains("\\\\")) {
            throw new MojoExecutionException(
                String.format(
                    "[test-order-coverage] Invalid includeModules format: %s. "
                    + "Use comma-separated module names. Example: -DincludeModules=core,api,impl",
                    includeModules
                )
            );
        }

        // Warn about spaces
        if (includeModules.contains(" ")) {
            log.warn(
                "[test-order-coverage] includeModules contains spaces. "
                + "Use: -DincludeModules=core,api (no spaces)"
            );
        }
    }
}
