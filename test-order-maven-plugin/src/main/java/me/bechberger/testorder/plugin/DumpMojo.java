package me.bechberger.testorder.plugin;

import me.bechberger.testorder.DependencyMap;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dumps a (binary V2) dependency index as human-readable V1 text format.
 * <p>
 * Usage: {@code mvn test-order:dump}
 */
@Mojo(name = "dump")
public class DumpMojo extends AbstractTestOrderMojo {

    /** Output text file. If not set, writes to stdout. */
    @Parameter(property = "testorder.dump.output")
    private String outputFile;

    @Override
    public void execute() throws MojoExecutionException {
        initContext();
        Path idxPath = resolveIndexPath();
        if (!Files.exists(idxPath)) {
            throw new MojoExecutionException("Dependency index not found: " + idxPath);
        }

        try {
            DependencyMap map = DependencyMap.load(idxPath);
            if (map.size() == 0) {
                getLog().info("[test-order] Dependency index is empty: " + idxPath);
                getLog().info("[test-order] Run learn mode first: mvn test -Dtestorder.mode=learn");
                return;
            }
            if (outputFile != null && !outputFile.isBlank()) {
                Path out = Path.of(outputFile);
                map.saveText(out);
                getLog().info("[test-order] Dumped " + map.size() + " test classes → " + out);
            } else {
                // write V1 text to stdout
                for (String tc : map.testClasses()) {
                    System.out.print(tc);
                    System.out.print('\t');
                    System.out.println(String.join(",", map.get(tc)));
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to dump index", e);
        }
    }
}