import pathlib

f = pathlib.Path('/Users/i560383_1/code/experiments/test-order/test-order-core/src/test/java/me/bechberger/testorder/changes/ChangeDetectorTest.java')
lines = f.read_text().splitlines()

# Keep lines 1-280 (indices 0-279). Line 281 (index 280) is the class-closing '}'.
# We replace it with the new test method + the class-closing '}'.
new_tail = (
    "\n"
    "    // ── Tier 3e ────────────────────────────────────────────────────────────\n"
    "\n"
    "    @Test\n"
    "    void sinceLastCommitWithSingleCommitRepoFallsBackGracefully() throws Exception {\n"
    "        // When HEAD~1 doesn't exist (single-commit repo), the detector must not\n"
    "        // crash; it should either return all tracked files or an empty set.\n"
    '        git(tempDir, "init");\n'
    '        git(tempDir, "config", "user.email", "test@test.com");\n'
    '        git(tempDir, "config", "user.name", "Test");\n'
    "\n"
    '        Path srcDir = tempDir.resolve("src/main/java/com/example");\n'
    "        Files.createDirectories(srcDir);\n"
    '        Files.writeString(srcDir.resolve("Foo.java"), "public class Foo {}");\n'
    '        git(tempDir, "add", ".");\n'
    '        git(tempDir, "commit", "-m", "initial");\n'
    "\n"
    "        // Only one commit exists — HEAD~1 doesn't exist; must not throw\n"
    "        assertDoesNotThrow(() -> {\n"
    "            ChangeDetector.detect(\n"
    "                    ChangeDetector.Mode.SINCE_LAST_COMMIT,\n"
    "                    tempDir, Path.of(\"src/main/java\"),\n"
    '                    tempDir.resolve("hashes.lz4"), null);\n'
    '        }, "SINCE_LAST_COMMIT on a single-commit repo must not throw");\n'
    "    }\n"
    "}\n"
)

result = '\n'.join(lines[:280]) + new_tail
f.write_text(result)
print(f"Done. Total lines: {len(result.splitlines())}")
