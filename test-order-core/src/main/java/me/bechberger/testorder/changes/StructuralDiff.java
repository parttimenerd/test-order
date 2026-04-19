package me.bechberger.testorder.changes;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Computes a structural diff between two versions of a Java source file.
 * <p>
 * Uses {@link SourceFileModel} to parse each version into types, methods, fields,
 * and initializers, then compares them to report additions, removals, and modifications
 * at the structural level (rather than line-by-line).
 */
public class StructuralDiff {

    /** A single structural change. */
    public record Change(Kind kind, Category category, String fqcn, String name, String detail) {
        public enum Kind { ADDED, REMOVED, MODIFIED, SIGNATURE_CHANGED }
        public enum Category { TYPE, METHOD, FIELD, INITIALIZER }
    }

    /**
     * Old and new body text for a modified member, enabling line-level diff computation.
     * For ADDED members, {@code oldBody} is null; for REMOVED, {@code newBody} is null.
     */
    public record BodyChange(String fqcn, String memberName, Change.Category category,
                             String oldBody, String newBody) {
        /** Number of changed lines computed via LCS-based line diff. */
        public int changedLineCount() {
            return LineDiff.changedLineCount(oldBody, newBody);
        }
    }

    /** Result of diffing a single file. */
    public record FileDiff(Path file, List<Change> changes, List<BodyChange> bodyChanges) {
        /** Backward-compatible constructor without body changes. */
        public FileDiff(Path file, List<Change> changes) {
            this(file, changes, List.of());
        }
        public boolean hasChanges() { return !changes.isEmpty(); }
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Diff the current working-tree version of a file against a git ref (default HEAD).
     */
    public static FileDiff diffAgainstGit(Path file, Path projectRoot, String gitRef) throws IOException {
        String currentSource = Files.readString(file);

        // Compute the git-relative path
        Path gitRoot = resolveGitRoot(projectRoot);
        Path absFile = file.toAbsolutePath().normalize();
        String relativePath = gitRoot.relativize(absFile).toString();

        String oldSource = readFileFromGit(gitRoot, gitRef, relativePath);
        if (oldSource == null) {
            // New file — everything is an addition
            return diffSources(file, "", currentSource);
        }
        return diffSources(file, oldSource, currentSource);
    }

    /**
     * Diff two source strings directly.
     */
    public static FileDiff diffSources(Path file, String oldSource, String newSource) {
        String oldPkg = SourceFileModel.extractPackageName(oldSource);
        String newPkg = SourceFileModel.extractPackageName(newSource);

        SourceFileModel.Model oldModel = oldSource.isEmpty()
                ? new SourceFileModel.Model("", List.of(), List.of(), List.of(), List.of())
                : SourceFileModel.parse(oldSource, oldPkg, SourceFileModel.Detail.FIELDS);
        SourceFileModel.Model newModel = SourceFileModel.parse(newSource, newPkg, SourceFileModel.Detail.FIELDS);

        List<Change> changes = new ArrayList<>();
        List<BodyChange> bodyChanges = new ArrayList<>();
        diffTypes(oldModel, newModel, changes);
        diffMethods(oldModel, newModel, changes, bodyChanges);
        diffFields(oldModel, newModel, changes, bodyChanges);
        diffInitializers(oldModel, newModel, changes, bodyChanges);
        return new FileDiff(file, changes, bodyChanges);
    }

    /**
     * Diff all Java files changed in git (uncommitted + staged) against HEAD.
     */
    public static List<FileDiff> diffUncommitted(Path projectRoot) throws IOException {
        return diffGitChanges(projectRoot, false);
    }

    /**
     * Diff all Java files changed since the last commit (HEAD~1..HEAD) plus uncommitted.
     */
    public static List<FileDiff> diffSinceLastCommit(Path projectRoot) throws IOException {
        return diffGitChanges(projectRoot, true);
    }

    /**
     * Diff all changed Java files. If {@code includeLastCommit} is true,
     * includes HEAD~1..HEAD changes as well as uncommitted ones.
     */
    private static List<FileDiff> diffGitChanges(Path projectRoot, boolean includeLastCommit) throws IOException {
        Path absProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path gitRoot = resolveGitRoot(projectRoot);
        Set<String> changedFiles = new TreeSet<>();

        // Uncommitted (unstaged + staged)
        changedFiles.addAll(runGit(gitRoot, "diff", "--name-only"));
        changedFiles.addAll(runGit(gitRoot, "diff", "--cached", "--name-only"));
        // Untracked
        changedFiles.addAll(runGit(gitRoot, "ls-files", "--others", "--exclude-standard"));

        if (includeLastCommit) {
            changedFiles.addAll(runGit(gitRoot, "diff", "--name-only", "HEAD~1", "HEAD"));
        }

        List<FileDiff> diffs = new ArrayList<>();
        for (String relPath : changedFiles) {
            if (!relPath.endsWith(".java")) continue;

            Path absFile = gitRoot.resolve(relPath).normalize();
            if (!absFile.startsWith(absProjectRoot)) {
                continue;
            }
            String gitRef = includeLastCommit ? "HEAD~1" : "HEAD";

            String oldSource = readFileFromGit(gitRoot, gitRef, relPath);
            String newSource = Files.exists(absFile) ? Files.readString(absFile) : null;

            if (oldSource == null && newSource == null) continue;
            if (oldSource == null) oldSource = "";
            if (newSource == null) {
                // File deleted — all removals
                newSource = "";
            }

            FileDiff diff = diffSources(absFile, oldSource, newSource);
            if (diff.hasChanges()) {
                diffs.add(diff);
            }
        }
        return diffs;
    }

    private static Path resolveGitRoot(Path projectRoot) throws IOException {
        List<String> lines = runGit(projectRoot, "rev-parse", "--show-toplevel");
        if (lines.isEmpty()) {
            throw new IOException("Failed to resolve git root for " + projectRoot);
        }
        return Path.of(lines.get(0)).toAbsolutePath().normalize();
    }

    // ── Diff logic ───────────────────────────────────────────────────

    private static void diffTypes(SourceFileModel.Model oldModel, SourceFileModel.Model newModel, List<Change> changes) {
        Map<String, SourceFileModel.TypeNode> oldTypes = new HashMap<>();
        for (SourceFileModel.TypeNode t : oldModel.types()) {
            oldTypes.putIfAbsent(t.fqcn(), t);
        }
        Map<String, SourceFileModel.TypeNode> newTypes = new HashMap<>();
        for (SourceFileModel.TypeNode t : newModel.types()) {
            newTypes.putIfAbsent(t.fqcn(), t);
        }

        for (var entry : newTypes.entrySet()) {
            if (!oldTypes.containsKey(entry.getKey())) {
                changes.add(new Change(Change.Kind.ADDED, Change.Category.TYPE,
                        entry.getKey(), entry.getValue().simpleName(),
                        entry.getValue().kind() + " " + entry.getValue().simpleName()));
            }
        }
        for (var entry : oldTypes.entrySet()) {
            if (!newTypes.containsKey(entry.getKey())) {
                changes.add(new Change(Change.Kind.REMOVED, Change.Category.TYPE,
                        entry.getKey(), entry.getValue().simpleName(),
                        entry.getValue().kind() + " " + entry.getValue().simpleName()));
            }
        }
        for (var entry : newTypes.entrySet()) {
            SourceFileModel.TypeNode oldType = oldTypes.get(entry.getKey());
            if (oldType == null) continue;
            SourceFileModel.TypeNode newType = entry.getValue();

            // Compare signatures (modifiers, extends, implements changed)
            if (!Objects.equals(normalizeWhitespace(oldType.signature()),
                                normalizeWhitespace(newType.signature()))) {
                changes.add(new Change(Change.Kind.SIGNATURE_CHANGED, Change.Category.TYPE,
                        entry.getKey(), newType.simpleName(),
                        "was: " + oldType.signature().strip() + "\n now: " + newType.signature().strip()));
            }
        }
    }

    private static void diffMethods(SourceFileModel.Model oldModel, SourceFileModel.Model newModel,
                                     List<Change> changes, List<BodyChange> bodyChanges) {
        // Group methods by enclosingFqcn#name — overloads get separate entries
        // since bodyHash distinguishes them
        Map<String, List<SourceFileModel.MethodNode>> oldMethods = groupMethods(oldModel.methods());
        Map<String, List<SourceFileModel.MethodNode>> newMethods = groupMethods(newModel.methods());

        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(oldMethods.keySet());
        allKeys.addAll(newMethods.keySet());

        for (String key : allKeys) {
            List<SourceFileModel.MethodNode> oldList = oldMethods.getOrDefault(key, List.of());
            List<SourceFileModel.MethodNode> newList = newMethods.getOrDefault(key, List.of());

            String fqcn = key.substring(0, key.indexOf('#'));
            String methodName = key.substring(key.indexOf('#') + 1);

            if (oldList.isEmpty()) {
                // All new overloads added
                for (var m : newList) {
                    changes.add(new Change(Change.Kind.ADDED, Change.Category.METHOD,
                            fqcn, methodName, m.isConstructor() ? "constructor" : "method"));
                    bodyChanges.add(new BodyChange(fqcn, methodName, Change.Category.METHOD,
                            null, m.compactBody()));
                }
            } else if (newList.isEmpty()) {
                // All overloads removed
                for (var m : oldList) {
                    changes.add(new Change(Change.Kind.REMOVED, Change.Category.METHOD,
                            fqcn, methodName, m.isConstructor() ? "constructor" : "method"));
                    bodyChanges.add(new BodyChange(fqcn, methodName, Change.Category.METHOD,
                            m.compactBody(), null));
                }
            } else {
                // Compare body hashes — collect old and new hash sets
                Set<String> oldHashes = new HashSet<>(oldList.size());
                for (var m : oldList) oldHashes.add(m.bodyHash() != null ? m.bodyHash() : "abstract");
                Set<String> newHashes = new HashSet<>(newList.size());
                for (var m : newList) newHashes.add(m.bodyHash() != null ? m.bodyHash() : "abstract");

                if (!oldHashes.equals(newHashes)) {
                    changes.add(new Change(Change.Kind.MODIFIED, Change.Category.METHOD,
                            fqcn, methodName,
                            newList.get(0).isConstructor() ? "constructor body changed" : "method body changed"));
                    // Record old/new body for the first overload pair (best-effort)
                    String oldBody = !oldList.isEmpty() ? oldList.get(0).compactBody() : null;
                    String newBody = !newList.isEmpty() ? newList.get(0).compactBody() : null;
                    bodyChanges.add(new BodyChange(fqcn, methodName, Change.Category.METHOD,
                            oldBody, newBody));
                }
            }
        }
    }

    private static void diffFields(SourceFileModel.Model oldModel, SourceFileModel.Model newModel,
                                    List<Change> changes, List<BodyChange> bodyChanges) {
        Map<String, SourceFileModel.FieldNode> oldFields = new HashMap<>();
        for (SourceFileModel.FieldNode f : oldModel.fields()) {
            oldFields.putIfAbsent(f.enclosingFqcn() + "#" + f.name(), f);
        }
        Map<String, SourceFileModel.FieldNode> newFields = new HashMap<>();
        for (SourceFileModel.FieldNode f : newModel.fields()) {
            newFields.putIfAbsent(f.enclosingFqcn() + "#" + f.name(), f);
        }

        for (var entry : newFields.entrySet()) {
            if (!oldFields.containsKey(entry.getKey())) {
                String fqcn = entry.getValue().enclosingFqcn();
                changes.add(new Change(Change.Kind.ADDED, Change.Category.FIELD,
                        fqcn, entry.getValue().name(), entry.getValue().declarationText().strip()));
                bodyChanges.add(new BodyChange(fqcn, entry.getValue().name(), Change.Category.FIELD,
                        null, entry.getValue().declarationText()));
            }
        }
        for (var entry : oldFields.entrySet()) {
            if (!newFields.containsKey(entry.getKey())) {
                String fqcn = entry.getValue().enclosingFqcn();
                changes.add(new Change(Change.Kind.REMOVED, Change.Category.FIELD,
                        fqcn, entry.getValue().name(), entry.getValue().declarationText().strip()));
                bodyChanges.add(new BodyChange(fqcn, entry.getValue().name(), Change.Category.FIELD,
                        entry.getValue().declarationText(), null));
            }
        }
        for (var entry : newFields.entrySet()) {
            SourceFileModel.FieldNode oldField = oldFields.get(entry.getKey());
            if (oldField == null) continue;
            SourceFileModel.FieldNode newField = entry.getValue();
            if (!Objects.equals(oldField.declarationHash(), newField.declarationHash())) {
                changes.add(new Change(Change.Kind.MODIFIED, Change.Category.FIELD,
                        newField.enclosingFqcn(), newField.name(),
                        "was: " + oldField.declarationText().strip() + "\n now: " + newField.declarationText().strip()));
                bodyChanges.add(new BodyChange(newField.enclosingFqcn(), newField.name(),
                        Change.Category.FIELD, oldField.declarationText(), newField.declarationText()));
            }
        }
    }

    private static void diffInitializers(SourceFileModel.Model oldModel, SourceFileModel.Model newModel,
                                         List<Change> changes, List<BodyChange> bodyChanges) {
        // Single-pass grouping: split into static/instance in one iteration
        Map<String, List<SourceFileModel.InitializerNode>> oldStaticInits = new LinkedHashMap<>();
        Map<String, List<SourceFileModel.InitializerNode>> oldInstanceInits = new LinkedHashMap<>();
        for (var init : oldModel.initializers()) {
            (init.isStatic() ? oldStaticInits : oldInstanceInits)
                    .computeIfAbsent(init.enclosingFqcn(), k -> new ArrayList<>()).add(init);
        }
        Map<String, List<SourceFileModel.InitializerNode>> newStaticInits = new LinkedHashMap<>();
        Map<String, List<SourceFileModel.InitializerNode>> newInstanceInits = new LinkedHashMap<>();
        for (var init : newModel.initializers()) {
            (init.isStatic() ? newStaticInits : newInstanceInits)
                    .computeIfAbsent(init.enclosingFqcn(), k -> new ArrayList<>()).add(init);
        }

        // Diff static initializers (<clinit>)
        diffInitializerGroup(oldStaticInits, newStaticInits, "<clinit>", "static initializer", changes, bodyChanges);
        // Diff instance initializers (<init>)
        diffInitializerGroup(oldInstanceInits, newInstanceInits, "<init>", "instance initializer", changes, bodyChanges);
    }

    private static void diffInitializerGroup(
            Map<String, List<SourceFileModel.InitializerNode>> oldInits,
            Map<String, List<SourceFileModel.InitializerNode>> newInits,
            String memberName, String label, List<Change> changes, List<BodyChange> bodyChanges) {
        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(oldInits.keySet());
        allKeys.addAll(newInits.keySet());

        for (String fqcn : allKeys) {
            List<SourceFileModel.InitializerNode> oldList = oldInits.getOrDefault(fqcn, List.of());
            List<SourceFileModel.InitializerNode> newList = newInits.getOrDefault(fqcn, List.of());

            Set<String> oldHashes = new HashSet<>();
            for (var init : oldList) oldHashes.add(init.bodyHash());
            Set<String> newHashes = new HashSet<>();
            for (var init : newList) newHashes.add(init.bodyHash());

            if (!oldHashes.equals(newHashes)) {
                int added = 0, removed = 0;
                for (String h : newHashes) if (!oldHashes.contains(h)) added++;
                for (String h : oldHashes) if (!newHashes.contains(h)) removed++;
                if (removed > 0 && added > 0) {
                    changes.add(new Change(Change.Kind.MODIFIED, Change.Category.INITIALIZER,
                            fqcn, memberName, label + " block changed"));
                    String oldBody = !oldList.isEmpty() ? oldList.get(0).bodyText() : null;
                    String newBody = !newList.isEmpty() ? newList.get(0).bodyText() : null;
                    bodyChanges.add(new BodyChange(fqcn, memberName, Change.Category.INITIALIZER,
                            oldBody, newBody));
                } else if (added > 0) {
                    changes.add(new Change(Change.Kind.ADDED, Change.Category.INITIALIZER,
                            fqcn, memberName, label + " block added"));
                    String newBody = !newList.isEmpty() ? newList.get(0).bodyText() : null;
                    bodyChanges.add(new BodyChange(fqcn, memberName, Change.Category.INITIALIZER,
                            null, newBody));
                } else {
                    changes.add(new Change(Change.Kind.REMOVED, Change.Category.INITIALIZER,
                            fqcn, memberName, label + " block removed"));
                    String oldBody = !oldList.isEmpty() ? oldList.get(0).bodyText() : null;
                    bodyChanges.add(new BodyChange(fqcn, memberName, Change.Category.INITIALIZER,
                            oldBody, null));
                }
            }
        }
    }


    // ── Helpers ──────────────────────────────────────────────────────

    private static Map<String, List<SourceFileModel.MethodNode>> groupMethods(List<SourceFileModel.MethodNode> methods) {
        Map<String, List<SourceFileModel.MethodNode>> map = new LinkedHashMap<>();
        for (var m : methods) {
            map.computeIfAbsent(m.enclosingFqcn() + "#" + m.name(), k -> new ArrayList<>()).add(m);
        }
        return map;
    }

    private static String normalizeWhitespace(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    // ── Git helpers ─────────────────────────────────────────────────

    private static String readFileFromGit(Path projectRoot, String ref, String relPath) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("git", "show", ref + ":" + relPath);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(false);
        Process process = pb.start();
        String content;
        try (var is = process.getInputStream()) {
            content = new String(is.readAllBytes());
        }
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git show timed out for " + relPath);
            }
            if (process.exitValue() != 0) return null;
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return null;
        }
        return content;
    }

    private static List<String> runGit(Path workDir, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        Collections.addAll(command, args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) lines.add(trimmed);
            }
        }
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Collections.emptyList();
            }
            if (process.exitValue() != 0) return Collections.emptyList();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
        return lines;
    }

    // ── Text formatting ─────────────────────────────────────────────

    /**
     * Formats a list of file diffs as a human-readable report.
     */
    public static String formatReport(List<FileDiff> diffs) {
        if (diffs.isEmpty()) return "No structural changes detected.\n";

        StringBuilder sb = new StringBuilder();
        for (FileDiff diff : diffs) {
            sb.append("── ").append(diff.file()).append(" ──\n");
            for (Change c : diff.changes()) {
                String icon = switch (c.kind()) {
                    case ADDED -> "+";
                    case REMOVED -> "-";
                    case MODIFIED -> "~";
                    case SIGNATURE_CHANGED -> "≈";
                };
                String cat = switch (c.category()) {
                    case TYPE -> "type";
                    case METHOD -> "method";
                    case FIELD -> "field";
                    case INITIALIZER -> "initializer";
                };
                sb.append(String.format("  %s %-11s %-40s  %s%n", icon, cat, c.fqcn() + "#" + c.name(), c.detail()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Formats a single file diff as a compact summary.
     */
    public static String formatFileDiff(FileDiff diff) {
        return formatReport(List.of(diff));
    }
}
