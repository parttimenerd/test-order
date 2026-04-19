package me.bechberger.testorder.changes;

import me.bechberger.util.json.JSONParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-checks {@link SourceFileModel} against independently generated
 * tree-sitter parsed JSON files in {@code src_parsed/}.
 * <p>
 * For every JSON file the test:
 * <ol>
 *   <li>Reads the original Java source file (from the JSON's {@code file} field).</li>
 *   <li>Parses it with {@link SourceFileModel} (detail=METHODS).</li>
 *   <li>Compares top-level + nested type FQCNs.</li>
 *   <li>Compares method names, abstract/concrete status, and body hashes per type.</li>
 * </ol>
 * <p>
 * <b>Note: These tests are really expensive and depend on running the python tree-sitter parser beforehand to generate the JSON files,
 * as well as having many Java files in the current folder</b>
 */
class SourceFileModelCrossCheckTest {

    /** Workspace root (parent of src_parsed). */
    private static final Path WORKSPACE_ROOT = findWorkspaceRoot();

    private static Path findWorkspaceRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        Path p = cwd;
        while (p != null) {
            if (Files.isDirectory(p.resolve("src_parsed"))) return p;
            p = p.getParent();
        }
        return cwd;
    }

    @Test
    @EnabledIfSystemProperty(named = "testorder.crosscheck.treeSitter", matches = "true")
    void crossCheckAllParsedFiles() throws IOException {
        Path srcParsedDir = WORKSPACE_ROOT.resolve("src_parsed");
        if (!Files.isDirectory(srcParsedDir)) {
            System.err.println("src_parsed/ directory not found at " + WORKSPACE_ROOT + " — skipped");
            return;
        }

        List<Path> jsonFiles;
        try (Stream<Path> walk = Files.walk(srcParsedDir)) {
            jsonFiles = walk
                    .filter(f -> f.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }

        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger checked = new AtomicInteger();

        jsonFiles.parallelStream().forEach(jsonFile -> {
            try {
                crossCheck(jsonFile);
                checked.incrementAndGet();
            } catch (AssertionError | Exception e) {
                errors.add(srcParsedDir.relativize(jsonFile) + ": " + e.getMessage());
            }
        });

        System.out.printf("Checked %d files, %d failures%n", checked.get(), errors.size());
        if (!errors.isEmpty()) {
            errors.sort(String::compareTo);
            fail(errors.size() + " cross-check failures:\n" + String.join("\n", errors));
        }
    }

    @SuppressWarnings("unchecked")
    private void crossCheck(Path jsonFile) throws IOException {
        String jsonText = Files.readString(jsonFile);
        Map<String, Object> root = asMap(JSONParser.parse(jsonText));

        // Skip files that had parse errors in tree-sitter
        if (root.containsKey("error")) {
            System.err.println("Skipping (tree-sitter error): " + root.get("error"));
            return;
        }

        // Skip files where tree-sitter had parse errors (ERROR nodes in the tree)
        if (Boolean.TRUE.equals(root.get("has_parse_errors"))) {
            return;
        }

        // Resolve the Java source file
        String fileField = (String) root.get("file");
        Path sourceFile = Path.of(fileField);
        if (!sourceFile.isAbsolute()) {
            sourceFile = WORKSPACE_ROOT.resolve(sourceFile);
        }
        if (!Files.isRegularFile(sourceFile)) {
            System.err.println("Source file not found, skipping: " + sourceFile);
            return;
        }

        // Skip Kotlin files — SourceFileModel is Java-only
        if (sourceFile.toString().endsWith(".kt")) return;

        // Parse with SourceFileModel
        String source = Files.readString(sourceFile);
        String pkg = (String) root.get("package");
        SourceFileModel.Model model = SourceFileModel.parse(source, pkg, SourceFileModel.Detail.FIELDS);
        String stripped = SourceFileModel.stripCommentsAndStrings(source);

        List<Map<String, Object>> tsTypes = asList(root.get("types"));

        // ── 1. Compare type FQCNs ───────────────────────────────────
        // SFM may find extra types (e.g. local classes inside method bodies)
        // that tree-sitter doesn't report, so we only check that all
        // tree-sitter types are present in SFM (superset check).
        Set<String> expectedTypes = collectTypeFqcns(tsTypes);
        Set<String> actualTypes = model.typeNames();
        Set<String> missing = new TreeSet<>(expectedTypes);
        missing.removeAll(actualTypes);
        assertTrue(missing.isEmpty(),
                "SFM missing types: " + missing + " for " + sourceFile.getFileName());

        // ── 2. Compare methods ──────────────────────────────────────
        // Build SourceFileModel method index: enclosingFqcn → list of MethodNode
        Map<String, List<SourceFileModel.MethodNode>> sfmByType = new LinkedHashMap<>();
        for (var m : model.methods()) {
            sfmByType.computeIfAbsent(m.enclosingFqcn(), k -> new ArrayList<>()).add(m);
        }

        // Build SourceFileModel field index: enclosingFqcn → set of field names
        Map<String, Set<String>> sfmFieldsByType = new LinkedHashMap<>();
        for (var f : model.fields()) {
            sfmFieldsByType.computeIfAbsent(f.enclosingFqcn(), k -> new TreeSet<>()).add(f.name());
        }

        // Build SourceFileModel initializer index: enclosingFqcn → list of InitializerNode
        Map<String, List<SourceFileModel.InitializerNode>> sfmInitsByType = new LinkedHashMap<>();
        for (var init : model.initializers()) {
            sfmInitsByType.computeIfAbsent(init.enclosingFqcn(), k -> new ArrayList<>()).add(init);
        }

        // Walk tree-sitter types and compare
        compareMethodsRecursive(tsTypes, sfmByType, sfmFieldsByType, sfmInitsByType,
                stripped, sourceFile.getFileName().toString());
    }

    @SuppressWarnings("unchecked")
    private void compareMethodsRecursive(
            List<Map<String, Object>> tsTypes,
            Map<String, List<SourceFileModel.MethodNode>> sfmByType,
            Map<String, Set<String>> sfmFieldsByType,
            Map<String, List<SourceFileModel.InitializerNode>> sfmInitsByType,
            String stripped,
            String fileName) {

        // Group tree-sitter types by FQCN to handle duplicate local class names
        Map<String, List<Map<String, Object>>> tsTypesByFqcn = new LinkedHashMap<>();
        for (Map<String, Object> tsType : tsTypes) {
            String fqcn = (String) tsType.get("fqname");
            tsTypesByFqcn.computeIfAbsent(fqcn, k -> new ArrayList<>()).add(tsType);
        }

        for (var entry : tsTypesByFqcn.entrySet()) {
            String fqcn = entry.getKey();
            List<Map<String, Object>> sameNameTypes = entry.getValue();
            String simpleName = (String) sameNameTypes.get(0).get("name");

            // Merge methods, fields, and initializers from all tree-sitter types with same FQCN
            List<Map<String, Object>> tsMethods = new ArrayList<>();
            List<Map<String, Object>> tsFields = new ArrayList<>();
            List<Map<String, Object>> tsInits = new ArrayList<>();
            List<Map<String, Object>> allInnerTypes = new ArrayList<>();
            for (var tsType : sameNameTypes) {
                tsMethods.addAll(asList(tsType.get("methods")));
                tsFields.addAll(asList(tsType.get("fields")));
                tsInits.addAll(asList(tsType.get("initializers")));
                allInnerTypes.addAll(asList(tsType.get("inner_types")));
            }
            Set<String> tsFieldNames = new TreeSet<>();
            for (var f : tsFields) tsFieldNames.add((String) f.get("name"));
            Set<String> sfmFieldNames = sfmFieldsByType.getOrDefault(fqcn, Set.of());
            assertEquals(tsFieldNames, sfmFieldNames,
                    "Field name mismatch in " + fqcn + " (" + fileName + ")");


            // Filter out constructors from tree-sitter side
            List<Map<String, Object>> tsNonCtors = new ArrayList<>();
            List<Map<String, Object>> tsCtors = new ArrayList<>();
            for (Map<String, Object> m : tsMethods) {
                String mName = (String) m.get("name");
                if (mName.equals(simpleName) || mName.equals("<init>")) {
                    tsCtors.add(m);
                } else {
                    tsNonCtors.add(m);
                }
            }

            List<SourceFileModel.MethodNode> sfmMethods =
                    sfmByType.getOrDefault(fqcn, List.of());

            // Separate SourceFileModel into constructors vs non-constructors
            List<SourceFileModel.MethodNode> sfmNonCtors = new ArrayList<>();
            List<SourceFileModel.MethodNode> sfmCtors = new ArrayList<>();
            for (var m : sfmMethods) {
                if (m.isConstructor()) sfmCtors.add(m);
                else sfmNonCtors.add(m);
            }

            // ── 2a. Compare non-constructor method names ────────────
            Set<String> tsNames = new TreeSet<>();
            for (var m : tsNonCtors) tsNames.add((String) m.get("name"));
            Set<String> sfmNames = new TreeSet<>();
            for (var m : sfmNonCtors) sfmNames.add(m.name());
            assertEquals(tsNames, sfmNames,
                    "Method name mismatch in " + fqcn + " (" + fileName + ")");

            // ── 2b. Compare constructor count ───────────────────────
            assertEquals(tsCtors.size(), sfmCtors.size(),
                    "Constructor count mismatch in " + fqcn + " (" + fileName + ")");

            // ── 2c. Compare abstract vs concrete ────────────────────
            for (var tsMethod : tsNonCtors) {
                String mName = (String) tsMethod.get("name");
                Object bodyObj = tsMethod.get("body");
                boolean tsAbstract = bodyObj == null;

                // Find matching SFM method(s) with same name
                List<SourceFileModel.MethodNode> matching = sfmNonCtors.stream()
                        .filter(m -> m.name().equals(mName)).toList();
                assertFalse(matching.isEmpty(),
                        "SFM missing method " + mName + " in " + fqcn);

                // For overloads, at least one should match the abstract status
                boolean sfmHasAbstract = matching.stream().anyMatch(SourceFileModel.MethodNode::isAbstract);
                boolean sfmHasConcrete = matching.stream().anyMatch(m -> !m.isAbstract());
                if (tsAbstract) {
                    assertTrue(sfmHasAbstract,
                            "Expected abstract " + mName + " in " + fqcn + " but SFM has none");
                } else {
                    assertTrue(sfmHasConcrete,
                            "Expected concrete " + mName + " in " + fqcn + " but SFM has none");
                }
            }

            // ── 2d. Compare body hashes for non-overloaded methods ──
            // For methods that appear exactly once in both, strip the
            // tree-sitter body the same way SFM does and compare hashes
            Map<String, Long> tsNameCounts = new HashMap<>();
            for (var m : tsNonCtors) {
                tsNameCounts.merge((String) m.get("name"), 1L, Long::sum);
            }
            for (var tsMethod : tsNonCtors) {
                String mName = (String) tsMethod.get("name");
                if (tsNameCounts.get(mName) != 1) continue; // skip overloads
                Object bodyObj = tsMethod.get("body");
                if (bodyObj == null) continue; // abstract

                String tsBody = (String) bodyObj;
                // Strip the tree-sitter body the same way SFM strips source
                String tsBodyStripped = SourceFileModel.stripCommentsAndStrings(tsBody);
                // SFM stores body as "{...}" (including braces)
                String tsBodyForHash = "{" + tsBodyStripped + "}";
                String tsHash = SourceFileModel.sha256(tsBodyForHash);

                List<SourceFileModel.MethodNode> matching = sfmNonCtors.stream()
                        .filter(m -> m.name().equals(mName) && !m.isAbstract()).toList();
                if (matching.size() == 1) {
                    String sfmHash = matching.get(0).bodyHash();
                    assertEquals(tsHash, sfmHash,
                            "Body hash mismatch for " + fqcn + "#" + mName + " (" + fileName + ")");
                }
            }

            // ── 2e. Compare method signatures against source ────────
            // Verify the tree-sitter signature text actually appears in the source
            for (var tsMethod : tsMethods) {
                String sig = (String) tsMethod.get("signature");
                if (sig == null || sig.isEmpty()) continue;
                // The signature should appear in the original source text
                // (after stripping, annotations may shift, so check stripped too)
                assertTrue(stripped.contains(sig) || source(tsMethod, stripped),
                        "Signature not found in source: " + sig + " in " + fqcn + " (" + fileName + ")");
            }

            // ── 2g. Compare initializer blocks ─────────────────
            List<SourceFileModel.InitializerNode> sfmInits =
                    sfmInitsByType.getOrDefault(fqcn, List.of());
            assertEquals(tsInits.size(), sfmInits.size(),
                    "Initializer count mismatch in " + fqcn + " (" + fileName + ")");
            for (int i = 0; i < Math.min(tsInits.size(), sfmInits.size()); i++) {
                boolean tsStatic = Boolean.TRUE.equals(tsInits.get(i).get("isStatic"));
                boolean sfmStatic = sfmInits.get(i).isStatic();
                assertEquals(tsStatic, sfmStatic,
                        "Initializer #" + i + " isStatic mismatch in " + fqcn + " (" + fileName + ")");
            }

            // Recurse into inner types
            if (!allInnerTypes.isEmpty()) {
                compareMethodsRecursive(allInnerTypes, sfmByType, sfmFieldsByType, sfmInitsByType, stripped, fileName);
            }
        }
    }

    /**
     * Checks if a method signature can be found in stripped source by trying
     * a stripped version of the signature.
     */
    private boolean source(Map<String, Object> tsMethod, String stripped) {
        String sig = (String) tsMethod.get("signature");
        if (sig == null) return true;
        String strippedSig = SourceFileModel.stripCommentsAndStrings(sig);
        return stripped.contains(strippedSig);
    }

    // ── JSON helper methods ─────────────────────────────────────────

    /** Recursively collects all type FQCNs from the tree-sitter JSON types list. */
    @SuppressWarnings("unchecked")
    private Set<String> collectTypeFqcns(List<Map<String, Object>> types) {
        Set<String> result = new TreeSet<>();
        if (types == null) return result;
        for (Map<String, Object> typeNode : types) {
            result.add((String) typeNode.get("fqname"));
            result.addAll(collectTypeFqcns(asList(typeNode.get("inner_types"))));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        if (o == null) return List.of();
        return (List<Map<String, Object>>) o;
    }
}
