package me.bechberger.testorder;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.testorder.changes.ChangeDetector;
import me.bechberger.testorder.changes.FileHashStore;
import me.bechberger.testorder.changes.StructuralDiff;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * CLI tool for managing test-order dependency indexes and change detection.
 */
@Command(name = "test-order", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "Manage JUnit test ordering based on dependency telemetry",
        subcommands = {Tool.Aggregate.class, Tool.Affected.class, Tool.Stats.class,
                Tool.HashSnapshot.class, Tool.Changed.class, Tool.Run.class, Tool.Dump.class,
                Tool.Optimize.class, Tool.Select.class, Tool.StructDiff.class})
public class Tool implements Runnable {

    @Override
    public void run() {
        System.err.println("Specify a subcommand. Use --help for usage.");
        System.exit(1);
    }

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new Tool(), args));
    }

    @Command(name = "aggregate", description = "Aggregate .deps files into a dependency index",
            mixinStandardHelpOptions = true)
    static class Aggregate implements Callable<Integer> {
        @Parameters(description = "Directory containing .deps files")
        Path depsDir;

        @Option(names = {"-o", "--output"}, description = "Output index file (default: ${DEFAULT-VALUE})",
                defaultValue = "test-dependencies.lz4")
        Path output;

        @Override
        public Integer call() {
            try {
                DependencyMap map = DependencyMap.aggregate(depsDir);
                if (map.size() == 0) {
                    if (Files.exists(output)) {
                        System.err.println("No .deps files found — refusing to overwrite existing index at " + output);
                        System.err.println("If you intended to clear the index, delete " + output + " manually.");
                    } else {
                        System.err.println("No .deps files found — no index to write.");
                        System.err.println("Run learn mode first: mvn test -Dtestorder.mode=learn");
                    }
                    return 0;
                }
                map.save(output);
                System.out.printf("Aggregated %d test classes → %s%n", map.size(), output);
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "affected", description = "List test classes affected by changed classes",
            mixinStandardHelpOptions = true)
    static class Affected implements Callable<Integer> {
        @Parameters(description = "Dependency index file")
        Path indexFile;

        @Option(names = {"-c", "--classes"}, required = true, description = "Comma-separated changed class FQCNs")
        String changedClasses;

        @Override
        public Integer call() {
            try {
                DependencyMap map = DependencyMap.load(indexFile);
                Set<String> changed = Set.of(changedClasses.split(","));
                Set<String> affected = map.getAffectedTests(changed);
                if (affected.isEmpty()) {
                    System.out.println("No affected test classes found.");
                } else {
                    affected.stream().sorted().forEach(System.out::println);
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "stats", description = "Print dependency index statistics",
            mixinStandardHelpOptions = true)
    static class Stats implements Callable<Integer> {
        @Parameters(description = "Dependency index file")
        Path indexFile;

        @Override
        public Integer call() {
            try {
                DependencyMap map = DependencyMap.load(indexFile);
                System.out.printf("Test classes:          %d%n", map.size());
                System.out.printf("Unique app classes:    %d%n", map.totalUniqueClasses());
                System.out.printf("Avg deps per test:     %.1f%n", map.averageDeps());
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "hash-snapshot", description = "Scan source tree and save file hash snapshot",
            mixinStandardHelpOptions = true)
    static class HashSnapshot implements Callable<Integer> {
        @Option(names = {"-s", "--source-root"}, description = "Source root directory (default: ${DEFAULT-VALUE})",
                defaultValue = "src/main/java")
        Path sourceRoot;

        @Option(names = {"-o", "--output"}, description = "Hash file path (default: ${DEFAULT-VALUE})",
                defaultValue = ".test-order-hashes.lz4")
        Path hashFile;

        @Override
        public Integer call() {
            try {
                FileHashStore store = FileHashStore.scan(sourceRoot);
                store.save(hashFile);
                System.out.printf("Snapshot: %d files → %s%n", store.getHashes().size(), hashFile);
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "changed", description = "Detect changed source files",
            mixinStandardHelpOptions = true)
    static class Changed implements Callable<Integer> {
        @Option(names = {"-m", "--mode"}, description = "Detection mode: since-last-run, since-last-commit, uncommitted, explicit (default: ${DEFAULT-VALUE})",
                defaultValue = "since-last-run")
        String modeStr;

        @Option(names = {"-p", "--project-root"}, description = "Project root directory (default: ${DEFAULT-VALUE})",
                defaultValue = ".")
        Path projectRoot;

        @Option(names = {"-s", "--source-root"}, description = "Source root (relative to project root, default: ${DEFAULT-VALUE})",
                defaultValue = "src/main/java")
        Path sourceRoot;

        @Option(names = {"--hash-file"}, description = "Hash file path (default: ${DEFAULT-VALUE})",
                defaultValue = ".test-order-hashes.lz4")
        Path hashFile;

        @Option(names = {"-c", "--classes"}, description = "Comma-separated class FQCNs (for EXPLICIT mode)")
        String classes;

        @Override
        public Integer call() {
            try {
                ChangeDetector.Mode mode = ChangeDetector.Mode.parse(modeStr);
                Set<String> changed = ChangeDetector.detect(mode, projectRoot, sourceRoot, hashFile, classes);
                if (changed.isEmpty()) {
                    System.out.println("No changes detected.");
                } else {
                    changed.stream().sorted().forEach(System.out::println);
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "run", description = "Detect changes and print affected test classes",
            mixinStandardHelpOptions = true)
    static class Run implements Callable<Integer> {
        @Parameters(description = "Dependency index file")
        Path indexFile;

        @Option(names = {"-m", "--mode"}, description = "Detection mode: since-last-run, since-last-commit, uncommitted, explicit (default: ${DEFAULT-VALUE})",
                defaultValue = "since-last-run")
        String modeStr;

        @Option(names = {"-p", "--project-root"}, description = "Project root (default: ${DEFAULT-VALUE})",
                defaultValue = ".")
        Path projectRoot;

        @Option(names = {"-s", "--source-root"}, description = "Source root (default: ${DEFAULT-VALUE})",
                defaultValue = "src/main/java")
        Path sourceRoot;

        @Option(names = {"--hash-file"}, description = "Hash file path (default: ${DEFAULT-VALUE})",
                defaultValue = ".test-order-hashes.lz4")
        Path hashFile;

        @Option(names = {"-c", "--classes"}, description = "Comma-separated class FQCNs (for EXPLICIT mode)")
        String classes;

        @Override
        public Integer call() {
            try {
                ChangeDetector.Mode mode = ChangeDetector.Mode.parse(modeStr);
                Set<String> changed = ChangeDetector.detect(mode, projectRoot, sourceRoot, hashFile, classes);
                if (changed.isEmpty()) {
                    System.out.println("No changes detected → all tests run in default order.");
                    return 0;
                }
                System.out.println("Changed classes:");
                changed.forEach(c -> System.out.println("  " + c));

                DependencyMap map = DependencyMap.load(indexFile);
                Set<String> affected = map.getAffectedTests(changed);
                if (affected.isEmpty()) {
                    System.out.println("No affected test classes found.");
                } else {
                    System.out.println("Affected test classes (run first):");
                    affected.stream().sorted().forEach(t -> System.out.println("  " + t));
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "dump", description = "Dump a (binary) dependency index in human-readable V1 text format",
            mixinStandardHelpOptions = true)
    static class Dump implements Callable<Integer> {
        @Parameters(description = "Dependency index file (V1 or V2)")
        Path indexFile;

        @Option(names = {"-o", "--output"}, description = "Output file (default: stdout)")
        Path output;

        @Override
        public Integer call() {
            try {
                DependencyMap map = DependencyMap.load(indexFile);
                if (map.size() == 0) {
                    System.err.println("Dependency index is empty: " + indexFile);
                    System.err.println("Run learn mode first: mvn test -Dtestorder.mode=learn");
                    return 0;
                }
                if (output != null) {
                    map.saveText(output);
                    System.out.printf("Dumped %d test classes → %s%n", map.size(), output);
                } else {
                    // write to stdout
                    for (String tc : map.testClasses()) {
                        System.out.print(tc);
                        System.out.print('\t');
                        System.out.println(String.join(",", map.get(tc)));
                    }
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "optimize", description = "Analyse run history and optimise scoring weights",
            mixinStandardHelpOptions = true)
    static class Optimize implements Callable<Integer> {
        @Parameters(description = "State file (.test-order-state)",
                defaultValue = ".test-order-state")
        Path stateFile;

        @Override
        public Integer call() {
            try {
                TestOrderState state = TestOrderState.load(stateFile);
                java.util.List<TestOrderState.RunRecord> runs = state.runs();
                if (runs.isEmpty()) {
                    System.out.println("No run history found.");
                    return 0;
                }

                long withFailures = runs.stream().filter(r -> r.totalFailures() > 0).count();
                System.out.printf("Runs: %d total, %d with failures%n", runs.size(), withFailures);
                System.out.println();

                System.out.println("Run history (most recent last):");
                for (TestOrderState.RunRecord r : runs) {
                    String date = java.time.Instant.ofEpochMilli(r.timestamp())
                            .atZone(java.time.ZoneId.systemDefault())
                            .toLocalDateTime()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
                    if (r.totalFailures() > 0) {
                        System.out.printf("  %s  tests=%d  failures=%d  first-fail@%d  APFD=%.1f%%%n",
                                date, r.totalTests(), r.totalFailures(),
                                r.firstFailurePosition() + 1, r.apfd() * 100);
                    } else {
                        System.out.printf("  %s  tests=%d  all passed%n", date, r.totalTests());
                    }
                }
                System.out.println();

                System.out.printf("Current weights:  %s%n", state.weights().format());
                System.out.println();

                TestOrderState.OptimizeResult opt = state.optimize();
                if (opt == null) {
                    System.out.printf("Need at least %d runs with failures to optimise (have %d).%n",
                            TestOrderState.MIN_RUNS_FOR_OPTIMISATION, withFailures);
                    return 0;
                }

                // save optimised weights back to the state file
                state.setWeights(opt.weights());
                state.save(stateFile);

                System.out.printf("Optimised weights saved:  %s%n", opt.weights().format());
                System.out.printf("  Training APFDc:    %.1f%%%n", opt.trainScore() * 100);
                System.out.printf("  Validation APFDc:  %.1f%%%n", opt.validationScore() * 100);
                System.out.printf("  Folds:             %d%n", opt.folds());
                if (opt.overfit()) {
                    System.out.println("  WARNING: Overfitting detected — default weights used instead.");
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }

    }

    @Command(name = "select", description = "Select a fast subset of tests (new + top-n + m diverse fast tests)",
            mixinStandardHelpOptions = true)
    static class Select implements Callable<Integer> {
        @Parameters(description = "Dependency index file")
        Path indexFile;

        @Option(names = {"--state"}, description = "State file (default: ${DEFAULT-VALUE})",
                defaultValue = ".test-order-state")
        Path stateFile;

        @Option(names = {"--top-n"}, description = "Number of top-scored tests (default: ${DEFAULT-VALUE})",
                defaultValue = "20")
        int topN;

        @Option(names = {"--random-m"}, description = "Number of random diverse fast tests (default: ${DEFAULT-VALUE})",
                defaultValue = "10")
        int randomM;

        @Option(names = {"--seed"}, description = "Random seed for reproducibility")
        Long seed;

        @Option(names = {"-c", "--changed"}, description = "Comma-separated changed class FQCNs")
        String changedClasses;

        @Option(names = {"--changed-tests"}, description = "Comma-separated changed test class FQCNs")
        String changedTestClasses;

        @Option(names = {"--selected-file"}, description = "Output file for selected tests",
                defaultValue = "test-order-selected.txt")
        Path selectedFile;

        @Option(names = {"--remaining-file"}, description = "Output file for remaining tests",
                defaultValue = "test-order-remaining.txt")
        Path remainingFile;

        @Override
        public Integer call() {
            try {
                DependencyMap depMap = DependencyMap.load(indexFile);
                TestOrderState state = java.nio.file.Files.exists(stateFile)
                        ? TestOrderState.load(stateFile) : new TestOrderState();

                java.util.Set<String> changed = changedClasses != null
                        ? java.util.Set.of(changedClasses.split(",")) : java.util.Set.of();
                java.util.Set<String> changedTests = changedTestClasses != null
                        ? java.util.Set.of(changedTestClasses.split(",")) : java.util.Set.of();

                TestSelector.Selection sel = new TestSelector(
                        depMap, state, changed, changedTests, state.weights(),
                        new TestSelector.Config(topN, randomM, seed)).select();

                TestSelector.writeTestList(sel.selected(), selectedFile);
                TestSelector.writeTestList(sel.remaining(), remainingFile);

                System.out.printf("Selected %d tests → %s%n", sel.selected().size(), selectedFile);
                System.out.printf("Remaining %d tests → %s%n", sel.remaining().size(), remainingFile);
                for (String tc : sel.selected()) {
                    System.out.println("  + " + tc);
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }

    @Command(name = "struct-diff", description = "Structural diff of Java files (types, methods, fields)",
            mixinStandardHelpOptions = true)
    static class StructDiff implements Callable<Integer> {

        @Option(names = {"-p", "--project-root"}, description = "Project root directory (default: ${DEFAULT-VALUE})",
                defaultValue = ".")
        Path projectRoot;

        @Option(names = {"--ref"}, description = "Git ref to diff against (default: ${DEFAULT-VALUE})",
                defaultValue = "HEAD")
        String gitRef;

        @Option(names = {"--since-last-commit"}, description = "Include changes from the last commit (HEAD~1..HEAD)")
        boolean sinceLastCommit;

        @Parameters(description = "Specific Java files to diff (default: all changed files)")
        List<Path> files;

        @Override
        public Integer call() {
            try {
                Path root = projectRoot.toAbsolutePath().normalize();

                if (files != null && !files.isEmpty()) {
                    // Diff specific files against git ref
                    List<StructuralDiff.FileDiff> diffs = new java.util.ArrayList<>();
                    for (Path f : files) {
                        Path absFile = f.toAbsolutePath().normalize();
                        if (!Files.isRegularFile(absFile) || !absFile.toString().endsWith(".java")) {
                            System.err.println("Skipping non-Java file: " + f);
                            continue;
                        }
                        StructuralDiff.FileDiff diff = StructuralDiff.diffAgainstGit(absFile, root, gitRef);
                        if (diff.hasChanges()) diffs.add(diff);
                    }
                    System.out.print(StructuralDiff.formatReport(diffs));
                } else if (sinceLastCommit) {
                    List<StructuralDiff.FileDiff> diffs = StructuralDiff.diffSinceLastCommit(root);
                    System.out.print(StructuralDiff.formatReport(diffs));
                } else {
                    List<StructuralDiff.FileDiff> diffs = StructuralDiff.diffUncommitted(root);
                    System.out.print(StructuralDiff.formatReport(diffs));
                }
                return 0;
            } catch (IOException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            }
        }
    }
}