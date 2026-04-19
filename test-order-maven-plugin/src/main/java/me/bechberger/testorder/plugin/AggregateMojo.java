package me.bechberger.testorder.plugin;

import me.bechberger.testorder.DependencyMap;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Aggregates individual {@code .deps} files from learn mode into a single dependency index.
 */
@Mojo(name = "aggregate")
public class AggregateMojo extends AbstractTestOrderMojo {

    @Override
    public void execute() throws MojoExecutionException {
        initContext();
        Path depsDirPath = ctx.resolveDepsDir(depsDir);
        if (!Files.isDirectory(depsDirPath)) {
            throw new MojoExecutionException("Deps directory does not exist: " + depsDirPath
                    + ". Run tests in learn mode first: mvn test -Dtestorder.mode=learn");
        }

        try {
            DependencyMap map = DependencyMap.aggregate(depsDirPath);
            Path output = resolveIndexPath();
            if (map.size() == 0) {
                if (Files.exists(output)) {
                    getLog().warn("[test-order] No .deps files found — refusing to overwrite existing index at " + output);
                    getLog().warn("[test-order] If you intended to clear the index, delete " + output + " manually.");
                } else {
                    getLog().warn("[test-order] No .deps files found — no index to write.");
                    getLog().warn("[test-order] Run tests in learn mode first: mvn test -Dtestorder.mode=learn");
                }
                return;
            }
            map.save(output);
            getLog().info("[test-order] Aggregated " + map.size() + " test classes → " + output);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to aggregate deps", e);
        }
    }
}
