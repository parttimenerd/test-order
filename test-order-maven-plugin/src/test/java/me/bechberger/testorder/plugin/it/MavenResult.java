package me.bechberger.testorder.plugin.it;

import java.nio.file.Path;
import java.util.List;

/**
 * Result of a Maven invocation.
 */
public record MavenResult(int exitCode, String output, List<String> args, Path projectDir) {

    public boolean isSuccess() {
        return exitCode == 0;
    }

    /** Returns all lines from the output containing the given substring. */
    public List<String> grepOutput(String substring) {
        return output.lines()
                .filter(line -> line.contains(substring))
                .toList();
    }
}
