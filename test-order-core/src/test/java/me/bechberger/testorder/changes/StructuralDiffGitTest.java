package me.bechberger.testorder.changes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StructuralDiffGitTest {

    @TempDir
    Path tempDir;

    @Test
    void diffSinceLastCommitHandlesMissingHeadParent() throws Exception {
        git("init");
        git("config", "user.email", "test@test.com");
        git("config", "user.name", "Test");

        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Foo.java"),
            "package com.example; public class Foo { int compute() { return 1; } }\n");
        git("add", ".");
        git("commit", "-m", "initial");

        Files.writeString(srcDir.resolve("Foo.java"),
            "package com.example; public class Foo { int compute() { return 2; } }\n");

        List<StructuralDiff.FileDiff> diffs = assertDoesNotThrow(() -> StructuralDiff.diffSinceLastCommit(tempDir));
        boolean hasAddedType = diffs.stream()
            .flatMap(diff -> diff.changes().stream())
            .anyMatch(change -> change.kind() == StructuralDiff.Change.Kind.ADDED
                && change.category() == StructuralDiff.Change.Category.TYPE
                && change.name().equals("Foo"));
        assertFalse(hasAddedType, "modified tracked file should not appear as a newly added type when HEAD~1 is missing");
    }

    private void git(String... args) throws IOException, InterruptedException {
        var cmd = new java.util.ArrayList<String>();
        cmd.add("git");
        java.util.Collections.addAll(cmd, args);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(tempDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("git " + String.join(" ", args) + " failed with exit code " + exitCode);
        }
    }
}