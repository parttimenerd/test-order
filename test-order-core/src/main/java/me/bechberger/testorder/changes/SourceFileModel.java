package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lenient island-grammar parser for Java source files.
 * <p>
 * Produces a rough structural model of a source file by isolating two kinds of
 * "islands": <strong>type declarations</strong> (class, interface, enum, record,
 * {@code @interface}) and, optionally, <strong>method/constructor declarations</strong>.
 * Everything else (field initialisers, statement-level logic inside method bodies)
 * is treated as opaque "water" and is not parsed.
 * <p>
 * The parser operates on <em>stripped</em> source text (comments, string literals,
 * char literals and text blocks replaced with spaces by
 * {@link #stripCommentsAndStrings}) so that:
 * <ul>
 *   <li>Regex islands never match inside comments or strings.</li>
 *   <li>Brace-depth computation is trivially correct.</li>
 *   <li>Method body hashes are insensitive to comment changes.</li>
 * </ul>
 *
 * <h3>Java 26 awareness</h3>
 * <ul>
 *   <li>{@code sealed}, {@code non-sealed} modifiers and {@code permits} clauses.</li>
 *   <li>{@code record} types (component list before opening brace).</li>
 *   <li>Bounded generics up to two nesting levels
 *       ({@code Map<String, List<Integer>>}).</li>
 *   <li>Unicode identifiers via {@code \p{javaJavaIdentifierStart}}.</li>
 *   <li>JEP 512 implicit classes (root-level methods without an enclosing type).</li>
 * </ul>
 *
 * <h3>Tested on (April 2026)</h3>
 * Cross-checked against tree-sitter on ~85k Java source files from:
 * OpenJDK, Spring Boot, Spring AI, Spring PetClinic, Keycloak, LangChain4j,
 * picocli, StarRocks, JUnit, and several smaller projects.
 *
 * Also provides utility methods for extracting class names from source files,
 * converting file paths to FQCNs, and stripping comments/strings.
 * <p>
 * <b>Notes:</b> Yes, this only tries to approximate (in non JavaParser mode), but its tested with
 * the Java code from OpenJDK, starrocks, spring-ai, spring-boot, keycloak, langchain4j, 
 * junit-examples, junit-framework, and a few of my own projects to produce valid,
 * in some cames more complete results than the Java tree sitter parser.
 * So it is probably good enough for parsing most Java code.
 */
public class SourceFileModel {

    // ── Type island regex ────────────────────────────────────────────
    //
    // Matches only the kernel: modifiers + keyword + name.
    // The opening brace is found programmatically afterwards, so we
    // never need to regex-match arbitrarily deep generics, permits
    // clauses, record component lists, or multi-bound extends.
    //
    // Captures group 1 = architectural keyword (class|interface|enum|record|@interface)
    //          group 2 = simple type name

    // Flattened to avoid StackOverflowError: iterates per inner <...> block,
    // not per character (Java's regex engine recurses for each (?:A|B)* iteration).
    private static final String BOUNDED_GENERICS_REQUIRED =
            "(?:<[^<>{};]*(?:<[^<>{};]*(?:<[^<>{};]*(?:<[^<>{};]*>[^<>{};]*)*>[^<>{};]*)*>[^<>{};]*)*>)";  // up to 4 nesting levels
    private static final String BOUNDED_GENERICS = BOUNDED_GENERICS_REQUIRED + "?";  // optional version

    static final Pattern TYPE_ISLAND = Pattern.compile(
            "(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*"
            + "(?<!\\p{javaJavaIdentifierPart})"                                 // word boundary
            + "(class|interface|enum|record|@interface)\\s+"                     // keyword (group 1)
            + "(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)"       // name    (group 2)
    );

    // Kotlin variant
    static final Pattern KOTLIN_TYPE_ISLAND = Pattern.compile(
            "^(?:public\\s+|protected\\s+|private\\s+|internal\\s+|abstract\\s+|final\\s+|open\\s+"
            + "|sealed\\s+|data\\s+|value\\s+|inline\\s+|annotation\\s+|actual\\s+|expect\\s+)*"
            + "(?:class|interface|object|enum\\s+class)\\s+"
            + "(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)",       // name (group 1)
            Pattern.MULTILINE
    );

    // ── Method island regex ──────────────────────────────────────────
    //
    // Captures group 1 = method/constructor name
    //          group 2 = terminator: { or ;

    private static final String METHOD_RETURN_TYPE =
            "[\\w.]+(?:\\s*" + BOUNDED_GENERICS_REQUIRED + ")?(?:\\s*\\[\\s*])*";  // e.g. List<Map<K,V>>[] or HashMap <String>

    // parameter list that handles one level of nested parens
    // (e.g. @PathVariable(name = "...", required = false) inside params)
    // Uses possessive quantifiers to prevent catastrophic backtracking.
    private static final String PARAM_LIST =
            "\\s*\\((?:[^()]++|\\((?:[^()]++|\\([^()]*+\\))*+\\))*+\\)\\s*";

    // Optional default clause for annotation elements (e.g. String value() default "foo")
    private static final String ANNOTATION_DEFAULT =
            "(?:default\\s+[^;{]*)?";

    // Array dimensions with type annotations between type and name:
    // e.g. "int @TA [] @TB []", "DirectoryEntry []", "byte[][]"
    private static final String ARRAY_DIMS_WITH_ANNOS =
            "(?:\\s*(?:@\\p{javaJavaIdentifierStart}[\\w.]*+(?:\\s*\\([^)]*\\))?\\s*)*\\[\\s*])*";

    static final Pattern METHOD_ISLAND = Pattern.compile(
            "(?:@\\p{javaJavaIdentifierStart}[\\w.]*+(?:\\s*\\([^)]*\\))?\\s*)*"  // annotations (possessive ident)
            + "(?:(?:public|protected|private|static|final|native|synchronized"
            +      "|abstract|default|strictfp)\\s+)*"                           // modifiers
            + "(?:@\\p{javaJavaIdentifierStart}[\\w.]*+(?:\\s*\\([^)]*\\))?\\s*)*" // type annotations (@Nullable etc.)
            + "(?:" + BOUNDED_GENERICS + "\\s+)?"                                // type parameters <T>
            + "(?:void|" + METHOD_RETURN_TYPE + ")"                              // return type (optional for ctor)
            + "\\s+(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)"   // name (group 1)
            + PARAM_LIST                                                         // parameter list
            + ARRAY_DIMS_WITH_ANNOS                                              // old-style return array dims
            + "(?:throws\\s+(?:[\\w\\s,.@]|\\([^)]*\\))+)?\\s*"                   // throws (with annotation args)
            + ANNOTATION_DEFAULT                                                  // annotation default
            + "(\\{|;)"                                                          // body or abstract (group 2)
    );

    // The same pattern but with the return type made optional so we also catch constructors
    static final Pattern METHOD_OR_CTOR_ISLAND = Pattern.compile(
            "(?:@\\p{javaJavaIdentifierStart}[\\w.]*+(?:\\s*\\([^)]*\\))?\\s*)*"  // annotations (possessive ident)
            + "(?:(?:public|protected|private|static|final|native|synchronized"
            +      "|abstract|default|strictfp)\\s+)*"
            + "(?:@\\p{javaJavaIdentifierStart}[\\w.]*+(?:\\s*\\([^)]*\\))?\\s*)*" // type annotations (@Nullable etc.)
            + "(?:" + BOUNDED_GENERICS + "\\s+)?"                                // type parameters <T>
            + "(?:(?:void|" + METHOD_RETURN_TYPE + ")\\s+)?"                     // return type (optional)
            + "(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)"       // name (group 1)
            + PARAM_LIST                                                         // parameter list
            + ARRAY_DIMS_WITH_ANNOS                                              // old-style return array dims
            + "(?:throws\\s+(?:[\\w\\s,.@]|\\([^)]*\\))+)?\\s*"                   // throws (with annotation args)
            + ANNOTATION_DEFAULT                                                  // annotation default
            + "(\\{|;)"                                                          // body or abstract (group 2)
    );

    // ── Field island regex ──────────────────────────────────────────
    //
    // Matches field declarations: [annotations] [modifiers] Type name [= init] ;
    // Captures group 1 = field name

    static final Pattern FIELD_ISLAND = Pattern.compile(
            "(?:@\\p{javaJavaIdentifierStart}[\\w.]*+(?:\\s*\\([^)]*\\))?\\s*)*"  // annotations (possessive ident)
            + "(?:(?:public|protected|private|static|final|transient|volatile)\\s+)*" // modifiers
            + "(?:@\\p{javaJavaIdentifierStart}[\\w.]*+(?:\\s*\\([^)]*\\))?\\s*)*" // type annotations (@Nullable etc.)
            + METHOD_RETURN_TYPE                                                      // type
            + ARRAY_DIMS_WITH_ANNOS                                                   // array dims between type and name
            + "\\s+(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)"       // field name (group 1)
            + "(?:\\s*\\[\\s*])*"                                                   // C-style array dims after name
            + "\\s*(?:[=,;])"                                                        // followed by = , or ;
    );

    // ── Public data model ────────────────────────────────────────────

    /** Architectural kind of a type declaration. */
    public enum TypeKind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, IMPLICIT }

    /** A top-level type in the source file. */
    public record TypeNode(
            TypeKind kind,
            String simpleName,
            String fqcn,
            int bodyStart,   // index of opening { in stripped source
            int bodyEnd,      // index of matching } in stripped source
            String signature, // declaration text before { (modifiers, keyword, name, generics, extends, implements, permits)
            String bodyText,  // stripped text between { and } (null if no body)
            String compactBody // original body with comments and empty lines removed (null if no body)
    ) {}

    /** A field declaration inside a type. */
    public record FieldNode(
            String name,
            String enclosingFqcn,
            String declarationText,  // stripped declaration text
            String declarationHash   // SHA-256 of the stripped declaration text
    ) {}

    /** A method or constructor inside a top-level type. */
    public record MethodNode(
            String name,
            String enclosingFqcn,
            boolean isConstructor,
            boolean isAbstract,    // ends with ; instead of {
            String bodyText,       // stripped body text (null if abstract)
            String bodyHash,       // SHA-256 hex of bodyText (null if abstract)
            String compactBody     // original body with comments and empty lines removed (null if abstract)
    ) {}

    /** A static or instance initializer block inside a type. */
    public record InitializerNode(
            boolean isStatic,
            String enclosingFqcn,
            String bodyText,       // stripped block text including braces
            String bodyHash        // SHA-256 hex of bodyText
    ) {}

    /** The parsed rough model. */
    public record Model(
            String packageName,
            List<TypeNode> types,
            List<MethodNode> methods,       // empty if parsed without METHODS
            List<FieldNode> fields,          // empty if parsed without FIELDS
            List<InitializerNode> initializers // empty if parsed without FIELDS
    ) {
        /** Returns the set of FQCNs of all types. */
        public Set<String> typeNames() {
            Set<String> names = new TreeSet<>();
            for (TypeNode t : types) names.add(t.fqcn);
            return names;
        }

        /** Returns the method hashes as {@code fqcn#methodName → hash}. */
        public Map<String, String> methodHashes() {
            Map<String, String> result = new LinkedHashMap<>();
            for (MethodNode m : methods) {
                if (m.isConstructor || m.isAbstract) continue;
                String key = m.enclosingFqcn + "#" + m.name;
                if (result.containsKey(key)) {
                    // overloads: combine hashes
                    result.put(key, sha256(result.get(key) + m.bodyHash));
                } else {
                    result.put(key, m.bodyHash);
                }
            }
            return result;
        }

        /** Returns the field hashes as {@code fqcn#fieldName → hash}. */
        public Map<String, String> fieldHashes() {
            Map<String, String> result = new LinkedHashMap<>();
            for (FieldNode f : fields) {
                result.put(f.enclosingFqcn + "#" + f.name, f.declarationHash);
            }
            return result;
        }
    }

    /** Controls what the parser extracts. */
    public enum Detail {
        /** Extract only type declarations (fast). */
        TYPES,
        /** Extract type declarations and method declarations (full). */
        METHODS,
        /** Extract type declarations, method declarations, and field declarations. */
        FIELDS
    }

    // ── Parser mode ────────────────────────────────────────────────

    /**
     * Parser backend: {@code island} (default, regex-based) or {@code javaparser}
     * (uses the JavaParser library, must be on the classpath).
     * Set via {@code -Dtestorder.parser.mode=javaparser} or in {@code testorder-config.properties}.
     */
    public enum ParserMode {
        /** Regex island-grammar parser (default, zero extra dependencies). */
        ISLAND,
        /** JavaParser-based parser (requires com.github.javaparser:javaparser-core on classpath). */
        JAVAPARSER
    }

    private static volatile ParserMode parserMode;

    /** Returns the active parser mode, reading from the system property on first call. */
    public static ParserMode getParserMode() {
        ParserMode mode = parserMode;
        if (mode == null) {
            String val = System.getProperty("testorder.parser.mode", "island");
            mode = "javaparser".equalsIgnoreCase(val) ? ParserMode.JAVAPARSER : ParserMode.ISLAND;
            parserMode = mode;
        }
        return mode;
    }

    /** Override the parser mode programmatically (e.g. from tests). */
    public static void setParserMode(ParserMode mode) {
        parserMode = mode;
    }

    // ── Factory methods ──────────────────────────────────────────────

    /**
     * Parses a Java source string and returns a rough structural model.
     * <p>
     * The parser backend is chosen by {@link #getParserMode()}.
     *
     * @param source      raw Java source code (not yet stripped)
     * @param packageName dot-separated package name, or empty for default package
     * @param detail      what to extract
     * @return the parsed model
     */
    public static Model parse(String source, String packageName, Detail detail) {
        if (getParserMode() == ParserMode.JAVAPARSER) {
            try {
                return JavaParserModel.parse(source, packageName, detail);
            } catch (Exception e) {
                // Fall back to the island-grammar parser
            }
        }
        String stripped = stripCommentsAndStrings(source);
        return parseStripped(stripped, source, packageName, detail);
    }

    /**
     * Parses a Java source string, extracting types only.
     */
    public static Model parse(String source, String packageName) {
        return parse(source, packageName, Detail.TYPES);
    }

    /**
     * Internal: parses already-stripped source text.
     */
    static Model parseStripped(String stripped, String source, String packageName, Detail detail) {
        int len = stripped.length();

        // ── 1. pre-compute cumulative brace depth and paren depth ──
        int[] braceDepth = new int[len + 1];
        int[] parenDepth = new int[len + 1];
        for (int i = 0; i < len; i++) {
            char c = stripped.charAt(i);
            braceDepth[i + 1] = braceDepth[i] + (c == '{' ? 1 : c == '}' ? -1 : 0);
            parenDepth[i + 1] = parenDepth[i] + (c == '(' ? 1 : c == ')' ? -1 : 0);
        }

        // ── 2. find type islands (depth 0) ───────────────────────
        List<TypeNode> types = findTypeIslands(stripped, source, braceDepth, packageName);

        // Collect brace positions claimed by types and methods for
        // initializer-block detection.
        Set<Integer> claimedBraces = new HashSet<>();
        for (TypeNode t : types) {
            if (t.bodyStart >= 0) claimedBraces.add(t.bodyStart);
        }

        // ── 3. optionally find method islands (depth 1) ──────────
        List<MethodNode> methods;
        // Collect method body ranges (start, end) so field detection can skip
        // matches that fall inside method bodies.
        List<int[]> methodBodyRanges = new ArrayList<>();
        if (detail == Detail.METHODS || detail == Detail.FIELDS) {
            methods = findMethodIslands(stripped, source, braceDepth, types, claimedBraces, methodBodyRanges);
        } else {
            methods = List.of();
        }

        // ── 4. optionally find field islands (depth 1) ───────────
        List<FieldNode> fields;
        if (detail == Detail.FIELDS) {
            fields = findFieldIslands(stripped, braceDepth, parenDepth, types, claimedBraces, methodBodyRanges);
        } else {
            fields = List.of();
        }

        // ── 5. optionally find initializer blocks (depth 1) ──────
        List<InitializerNode> initializers;
        if (detail == Detail.FIELDS) {
            initializers = findInitializerBlocks(stripped, braceDepth, types, claimedBraces);
        } else {
            initializers = List.of();
        }

        return new Model(packageName, types, methods, fields, initializers);
    }

    // ── Type island extraction ───────────────────────────────────────

    private static List<TypeNode> findTypeIslands(String stripped, String source, int[] braceDepth, String packageName) {
        List<TypeNode> types = new ArrayList<>();
        Matcher m = TYPE_ISLAND.matcher(stripped);
        while (m.find()) {
            int matchDepth = braceDepth[m.start()];

            String keyword = m.group(1);
            String simpleName = m.group(2);
            TypeKind kind = kindOf(keyword);

            // Scan forward from the end of the regex match to the opening brace.
            // This skips over generics, extends/implements, permits, record
            // components — no matter how complex — without needing to regex them.
            int bodyStart = stripped.indexOf('{', m.end());
            if (bodyStart < 0) continue;
            // Verify the brace is at the same depth as the match start
            // (i.e. this brace opens a new nesting level for this type)
            if (braceDepth[bodyStart] != matchDepth) continue;
            int bodyEnd = findMatchingBrace(stripped, bodyStart);
            if (bodyEnd < 0) continue;

            // Build FQCN: top-level uses package.Name, inner uses Enclosing$Name
            String fqcn;
            if (matchDepth == 0) {
                fqcn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            } else {
                TypeNode enclosing = findInnermostEnclosingType(types, m.start());
                if (enclosing == null) continue;
                fqcn = enclosing.fqcn + "$" + simpleName;
            }

            String signature = stripped.substring(m.start(), bodyStart).trim();
            String bodyText = stripped.substring(bodyStart + 1, bodyEnd);
            String compactBody = removeCommentsAndEmptyLines(source.substring(bodyStart + 1, bodyEnd));
            types.add(new TypeNode(kind, simpleName, fqcn, bodyStart, bodyEnd, signature, bodyText, compactBody));
        }
        return types;
    }

    static List<TypeNode> findKotlinTypeIslands(String stripped, int[] braceDepth, String packageName) {
        List<TypeNode> types = new ArrayList<>();
        Matcher m = KOTLIN_TYPE_ISLAND.matcher(stripped);
        while (m.find()) {
            if (braceDepth[m.start()] != 0) continue;
            String simpleName = m.group(1);
            String fqcn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
            // Kotlin types may or may not have a body — find next { or assume no body
            int bodyStart = -1, bodyEnd = -1;
            for (int i = m.end(); i < stripped.length(); i++) {
                char c = stripped.charAt(i);
                if (c == '{') { bodyStart = i; break; }
                if (c == '\n' || c == ';') break; // no body
            }
            if (bodyStart >= 0) {
                bodyEnd = findMatchingBrace(stripped, bodyStart);
            }
            String signature = (bodyStart >= 0) ? stripped.substring(m.start(), bodyStart).trim() : stripped.substring(m.start(), m.end()).trim();
            String bodyText = (bodyStart >= 0 && bodyEnd >= 0) ? stripped.substring(bodyStart + 1, bodyEnd) : null;
            types.add(new TypeNode(TypeKind.CLASS, simpleName, fqcn, bodyStart, bodyEnd, signature, bodyText, null));
        }
        return types;
    }

    private static TypeKind kindOf(String keyword) {
        return switch (keyword) {
            case "class" -> TypeKind.CLASS;
            case "interface" -> TypeKind.INTERFACE;
            case "enum" -> TypeKind.ENUM;
            case "record" -> TypeKind.RECORD;
            case "@interface" -> TypeKind.ANNOTATION;
            default -> TypeKind.CLASS;
        };
    }

    // ── Method island extraction ─────────────────────────────────────

    private static List<MethodNode> findMethodIslands(
            String stripped, String source, int[] braceDepth, List<TypeNode> types,
            Set<Integer> claimedBraces, List<int[]> methodBodyRanges) {

        List<MethodNode> methods = new ArrayList<>();
        Matcher matcher = METHOD_OR_CTOR_ISLAND.matcher(stripped);

        while (matcher.find()) {
            int pos = matcher.start();
            int depth = braceDepth[pos];

            String name = matcher.group(1);
            String terminator = matcher.group(2);

            // Skip inner type declarations, constructor invocations (`new Foo()`),
            // and method calls (`obj.method()`) by inspecting the token before the name.
            if (isPrecededByTypeKeyword(stripped, matcher.start(1))) continue;

            // determine enclosing type (innermost for nested types)
            TypeNode enclosing = findInnermostEnclosingType(types, pos);
            if (enclosing == null) continue;

            // method must be directly inside the enclosing type body
            int expectedDepth = braceDepth[enclosing.bodyStart] + 1;
            if (depth != expectedDepth) continue;

            // Skip enum constants: inside an enum, anything before the first ';'
            // at the right depth is a constant declaration, not a method.
            if (enclosing.kind == TypeKind.ENUM) {
                int enumSemicolon = findEnumConstantEnd(stripped, braceDepth, enclosing);
                if (enumSemicolon < 0 || pos < enumSemicolon) continue;
            }

            boolean isCtor = name.equals(enclosing.simpleName);
            boolean isAbstract = ";".equals(terminator);

            // Annotation elements (inside @interface) are always abstract — they
            // never have method bodies. A `default {}` is a default value, not a body.
            if (enclosing.kind == TypeKind.ANNOTATION) {
                isAbstract = true;
            }

            String bodyText = null;
            String bodyHash = null;
            String compactBody = null;
            if ("{".equals(terminator)) {
                int bodyStart = matcher.end() - 1;
                claimedBraces.add(bodyStart);
                if (!isAbstract) {
                    int bodyEnd = findMatchingBrace(stripped, bodyStart);
                    if (bodyEnd < 0) continue;
                    bodyText = stripped.substring(bodyStart, bodyEnd + 1);
                    bodyHash = sha256(bodyText);
                    compactBody = removeCommentsAndEmptyLines(source.substring(bodyStart, bodyEnd + 1));
                    methodBodyRanges.add(new int[]{bodyStart, bodyEnd, expectedDepth});
                }
            }

            methods.add(new MethodNode(name, enclosing.fqcn, isCtor, isAbstract, bodyText, bodyHash, compactBody));
        }

        return methods;
    }

    /**
     * Finds the position of the ';' that ends the enum-constant section inside
     * an enum body. Enum constants are comma-separated and terminated by ';'
     * at depth = body-depth + 1. Returns -1 if no such separator is found
     * (enum may have only constants and no methods).
     */
    private static int findEnumConstantEnd(String stripped, int[] braceDepth, TypeNode enumType) {
        int targetDepth = braceDepth[enumType.bodyStart] + 1;
        for (int i = enumType.bodyStart + 1; i < enumType.bodyEnd; i++) {
            char c = stripped.charAt(i);
            if (c == ';' && braceDepth[i] == targetDepth) return i;
            // Skip over constant-specific class bodies { ... } at greater depth
        }
        return -1;
    }

    private static TypeNode findInnermostEnclosingType(List<TypeNode> types, int pos) {
        TypeNode best = null;
        for (TypeNode t : types) {
            if (t.bodyStart >= 0 && pos > t.bodyStart && pos < t.bodyEnd) {
                if (best == null || t.bodyStart > best.bodyStart) {
                    best = t;
                }
            }
        }
        return best;
    }

    // Words that, when immediately preceding a matched name, indicate this is
    // NOT a method/constructor declaration but something else entirely.
    private static final Set<String> SKIP_PRECEDING_WORDS =
            Set.of("class", "interface", "enum", "record", "new");

    // Words that, when used as the "type" in a field match, indicate this is
    // NOT a field declaration (e.g. "throws Exception;", "extends Base,",
    // "implements Runnable,", "case FOO,").
    private static final Set<String> FIELD_SKIP_TYPE_WORDS =
            Set.of("throws", "extends", "implements", "permits", "instanceof",
                    "case", "return", "yield", "import", "package", "goto",
                    "assert", "throw", "catch", "else", "if", "for", "while",
                    "do", "switch", "try", "break", "continue", "super", "this",
                    "default", "var");

    // Names that can never be field names (Java reserved words/literals)
    private static final Set<String> FIELD_SKIP_NAMES =
            Set.of("true", "false", "null", "class", "new", "this", "super",
                    "void", "instanceof", "_");

    /**
     * Checks whether the match should be skipped because the text before the
     * captured name indicates it is not a method/constructor declaration.
     * <p>
     * Returns {@code true} (skip) when:
     * <ul>
     *   <li>Preceded by a type keyword ({@code class}, {@code interface},
     *       {@code enum}, {@code record}) – inner type declaration.</li>
     *   <li>Preceded by {@code new} – constructor <em>invocation</em>, not
     *       a declaration (e.g. {@code new MathHelper()}).</li>
     *   <li>Preceded by {@code .} – method call on an object or class, not
     *       a declaration (e.g. {@code Pattern.compile(...)}).</li>
     *   <li>Preceded by an expression operator ({@code * + - / = , ? :}) –
     *       function call inside an expression (e.g. {@code PI * sqrt(2)}).</li>
     * </ul>
     *
     * @param nameStart position of the first character of the captured method name
     */
    private static boolean isPrecededByTypeKeyword(String source, int nameStart) {
        // scan backwards from the name over whitespace to find the preceding token
        int i = nameStart - 1;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
        if (i < 0) return false;

        char ch = source.charAt(i);

        // if preceded by '.' it's a method call (e.g. Pattern.compile, List.of)
        if (ch == '.') return true;

        // if preceded by '@' it's an annotation name (e.g. @API(...))
        if (ch == '@') return true;

        // if preceded by '>' check what kind of > it is:
        if (ch == '>') {
            // '->' is a lambda arrow (e.g. "__ -> fail()")
            if (i >= 1 && source.charAt(i - 1) == '-') return true;

            // Otherwise scan past <...> to check what's before the angle brackets.
            // Type-witness calls (e.g. Arrays.<String>asList) have '.' before the <>,
            // generic constructor invocations (e.g. new <T> Foo()) have 'new' before.
            // Generic return types (e.g. List<String> items()) have a type name before — keep those.
            int angleCount = 1;
            i--;
            while (i >= 0 && angleCount > 0) {
                char ac = source.charAt(i);
                if (ac == '>') angleCount++;
                else if (ac == '<') angleCount--;
                i--;
            }
            // skip whitespace before the <
            while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
            if (i < 0) return false;
            ch = source.charAt(i);
            if (ch == '.') return true; // type-witness: Type.<T>method()
            if (ch == '@') return true;
            int wEnd2 = i + 1;
            while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) i--;
            if (wEnd2 > i + 1) {
                return SKIP_PRECEDING_WORDS.contains(source.substring(i + 1, wEnd2));
            }
            return false;
        }

        // if preceded by an expression operator, it's a call inside an expression
        // (e.g. "PI * sqrt(2)", "x = foo()", "cond ? bar() : baz()")
        if ("*+-/=,?:!&|^~".indexOf(ch) >= 0) return true;

        int wordEnd = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) i--;
        if (wordEnd == i + 1) return false;
        String word = source.substring(i + 1, wordEnd);
        if (SKIP_PRECEDING_WORDS.contains(word)) return true;

        // If the preceding word is an annotation name (preceded by @),
        // scan past annotations to check for 'new' keyword further back.
        // Handles: new @TA @TB Object() { } where annotations are between 'new' and the type name.
        while (i >= 0 && source.charAt(i) == '@') {
            i--; // skip '@'
            while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
            if (i < 0) return false;
            char pc = source.charAt(i);
            // If preceded by another annotation name, keep looping
            if (Character.isJavaIdentifierPart(pc)) {
                int wEnd3 = i + 1;
                while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) i--;
                if (wEnd3 > i + 1) {
                    String prevWord = source.substring(i + 1, wEnd3);
                    if (SKIP_PRECEDING_WORDS.contains(prevWord)) return true;
                }
                // Skip whitespace to check if there's another @
                while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
                // Loop continues if next char is '@' (another annotation)
            } else {
                break;
            }
        }

        return false;
    }

    /**
     * Checks if the given position is inside angle brackets {@code <...>} at
     * the same brace depth. Scans backward from pos to find an unmatched
     * {@code <}. Stops at semicolons, braces, or start of text.
     */
    private static boolean isInsideAngleBrackets(String stripped, int pos, int braceDepth) {
        int angleCount = 0;
        for (int i = pos - 1; i >= 0; i--) {
            char c = stripped.charAt(i);
            // Stop at statement boundaries
            if (c == ';' || c == '{' || c == '}') return false;
            if (c == '>') angleCount++;
            else if (c == '<') {
                if (angleCount > 0) angleCount--;
                else return true; // unmatched '<' found — we're inside <...>
            }
        }
        return false;
    }

    /**
     * Checks if a position falls inside a method body that belongs to the same
     * enclosing type (same expected depth). This prevents matching local
     * variables inside method bodies as fields, while allowing fields of
     * local types nested inside methods.
     */
    private static boolean isInsideMethodBody(int pos, int expectedDepth, List<int[]> methodBodyRanges) {
        for (int[] range : methodBodyRanges) {
            // range = {bodyStart, bodyEnd, expectedDepthOfMethod}
            if (range[2] == expectedDepth && pos > range[0] && pos < range[1]) return true;
        }
        return false;
    }

    /**
     * Checks if the word immediately preceding the field name (the "type" part
     * of the field match) is a Java keyword and thus can't be a real type name.
     * E.g. "throws Exception;" — "throws" is not a type.
     */
    private static boolean isFieldTypeAKeyword(String source, int nameStart) {
        // Walk backwards past whitespace + generic suffixes to find the "type" word
        int i = nameStart - 1;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
        if (i < 0) return false;
        // Skip trailing generic/array brackets: >, ], . — part of the type
        if (source.charAt(i) == '>' || source.charAt(i) == ']' || source.charAt(i) == '.') return false;
        // Now we should be at the end of a word
        if (!Character.isJavaIdentifierPart(source.charAt(i))) return false;
        int wordEnd = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) i--;
        String word = source.substring(i + 1, wordEnd);
        return FIELD_SKIP_TYPE_WORDS.contains(word);
    }

    // ── Field island extraction ────────────────────────────────────────

    private static List<FieldNode> findFieldIslands(
            String stripped, int[] braceDepth, int[] parenDepth,
            List<TypeNode> types,
            Set<Integer> claimedBraces, List<int[]> methodBodyRanges) {

        List<FieldNode> fields = new ArrayList<>();
        Matcher matcher = FIELD_ISLAND.matcher(stripped);

        while (matcher.find()) {
            int pos = matcher.start();
            int depth = braceDepth[pos];

            // Skip matches inside parentheses (e.g. method parameter lists)
            if (parenDepth[pos] > 0) continue;

            // Skip matches inside angle brackets (e.g. generic type parameters)
            if (isInsideAngleBrackets(stripped, pos, braceDepth[pos])) continue;

            String name = matcher.group(1);

            // Skip reserved words/literals that can't be field names
            if (FIELD_SKIP_NAMES.contains(name)) continue;

            // Skip type keywords matched as field names
            if (isPrecededByTypeKeyword(stripped, matcher.start(1))) continue;

            // Skip when the "type" part of the match is a Java keyword
            // (e.g. "throws Exception;", "extends Base,", "case FOO,")
            if (isFieldTypeAKeyword(stripped, matcher.start(1))) continue;

            // determine enclosing type (innermost for nested types)
            TypeNode enclosing = findInnermostEnclosingType(types, pos);
            if (enclosing == null) continue;

            // field must be directly inside the enclosing type body
            int expectedDepth = braceDepth[enclosing.bodyStart] + 1;
            if (depth != expectedDepth) continue;

            // Skip matches inside method/constructor bodies at the same depth
            if (isInsideMethodBody(pos, expectedDepth, methodBodyRanges)) continue;

            // Skip enum constants: inside an enum, anything before the first ';'
            // at the right depth is a constant declaration, not a field.
            if (enclosing.kind == TypeKind.ENUM) {
                int enumSemicolon = findEnumConstantEnd(stripped, braceDepth, enclosing);
                if (enumSemicolon < 0 || pos < enumSemicolon) continue;
            }

            // Find the end of the declaration (the ; at the right depth)
            int declEnd = findFieldDeclarationEnd(stripped, matcher.end() - 1, expectedDepth, braceDepth);
            if (declEnd < 0) continue;

            // Claim all '{' within this field declaration so they are not
            // mistaken for initializer blocks (covers array init, anon classes,
            // lambda bodies, etc.)
            for (int i = pos; i <= declEnd; i++) {
                if (stripped.charAt(i) == '{') claimedBraces.add(i);
            }

            // Hash the full declaration text
            String declText = stripped.substring(pos, declEnd + 1);
            String declHash = sha256(declText);

            fields.add(new FieldNode(name, enclosing.fqcn, declText, declHash));

            // Handle multi-field declarations (e.g. "int a, b, c;")
            // Scan from the terminator char (,/;/=) that ended the first name match
            // so that MULTI_FIELD_NAME can find ", nextName" patterns.
            extractAdditionalFieldNames(stripped, matcher.end() - 1, declEnd,
                    expectedDepth, braceDepth, parenDepth, enclosing.fqcn, declText, declHash, fields);
        }

        return fields;
    }

    /** Regex to match additional field names after a comma in multi-field declarations. */
    private static final Pattern MULTI_FIELD_NAME = Pattern.compile(
            ",\\s*(?:\\[\\s*]\\s*)*" +   // comma, optional C-style array dims
            "(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)" // name (group 1)
    );

    /**
     * Extracts additional field names from a multi-field declaration like
     * {@code int a, b, c;}.
     */
    private static void extractAdditionalFieldNames(
            String stripped, int from, int declEnd,
            int expectedDepth, int[] braceDepth, int[] parenDepth,
            String fqcn, String declText, String declHash,
            List<FieldNode> fields) {
        // Pre-compute angle bracket depth so we can skip commas inside generics
        // e.g. "new LinkedHashMap<String, String>()" — the comma inside <> is not
        // a multi-field separator.
        int[] angleDepth = new int[declEnd + 2];
        int depth = 0;
        for (int i = from; i <= declEnd; i++) {
            char c = stripped.charAt(i);
            if (c == '<') {
                // Skip << operator (left shift) — not an angle bracket
                if (i + 1 <= declEnd && stripped.charAt(i + 1) == '<') {
                    angleDepth[i] = depth;
                    i++;
                    angleDepth[i] = depth;
                    continue;
                }
                depth++;
            }
            angleDepth[i] = depth;
            if (c == '>') {
                // Skip >> and >>> operators (right shift) — not angle brackets
                if (i + 1 <= declEnd && stripped.charAt(i + 1) == '>') {
                    i++;
                    angleDepth[i] = depth;
                    if (i + 1 <= declEnd && stripped.charAt(i + 1) == '>') {
                        i++;
                        angleDepth[i] = depth;
                    }
                    continue;
                }
                if (depth > 0) depth--;
            }
        }

        Matcher m = MULTI_FIELD_NAME.matcher(stripped);
        m.region(from, declEnd + 1);
        while (m.find()) {
            // Must be at the right depth and not inside nested braces/parens/angles
            if (braceDepth[m.start()] != expectedDepth) continue;
            if (parenDepth[m.start()] > 0) continue;
            if (angleDepth[m.start()] > 0) continue;
            String extraName = m.group(1);
            if (FIELD_SKIP_NAMES.contains(extraName)) continue;
            fields.add(new FieldNode(extraName, fqcn, declText, declHash));
        }
    }

    /**
     * Finds the semicolon that terminates a field declaration at the expected
     * brace depth, skipping over anonymous class bodies and lambda blocks.
     */
    private static int findFieldDeclarationEnd(
            String stripped, int from, int expectedDepth, int[] braceDepth) {
        for (int i = from; i < stripped.length(); i++) {
            if (stripped.charAt(i) == ';' && braceDepth[i] == expectedDepth) return i;
        }
        return -1;
    }

    // ── Initializer block extraction ─────────────────────────────────

    private static List<InitializerNode> findInitializerBlocks(
            String stripped, int[] braceDepth, List<TypeNode> types,
            Set<Integer> claimedBraces) {

        // Also claim braces from compact record constructors:
        //   public Person { ... }  — no param list, so METHOD_OR_CTOR_ISLAND misses it
        claimCompactConstructorBraces(stripped, braceDepth, types, claimedBraces);

        List<InitializerNode> initializers = new ArrayList<>();

        for (TypeNode type : types) {
            if (type.bodyStart < 0) continue;
            // @interface can't have initializer blocks
            if (type.kind == TypeKind.ANNOTATION) continue;

            int expectedDepth = braceDepth[type.bodyStart] + 1;

            // Inside enums, skip past the constant section
            int scanStart = type.bodyStart + 1;
            if (type.kind == TypeKind.ENUM) {
                int enumSemicolon = findEnumConstantEnd(stripped, braceDepth, type);
                if (enumSemicolon >= 0) scanStart = enumSemicolon + 1;
            }

            for (int i = scanStart; i < type.bodyEnd; i++) {
                if (stripped.charAt(i) != '{') continue;
                if (braceDepth[i] != expectedDepth) continue;
                if (claimedBraces.contains(i)) continue;

                // An initializer block '{' must be preceded (ignoring whitespace)
                // by '}', ';', or '{' (the type body opening). Anything else
                // (e.g. '=', ')', identifier) means this is an annotation array,
                // field initializer, or similar — not an initializer block.
                // isPrecededByStaticKeyword already handles "static {".
                if (!isValidInitializerPosition(stripped, i)) continue;

                int blockEnd = findMatchingBrace(stripped, i);
                if (blockEnd < 0) continue;

                boolean isStatic = isPrecededByStaticKeyword(stripped, i);
                String bodyText = stripped.substring(i, blockEnd + 1);
                String bodyHash = sha256(bodyText);

                initializers.add(new InitializerNode(isStatic, type.fqcn, bodyText, bodyHash));

                // Skip past this block
                i = blockEnd;
            }
        }

        return initializers;
    }

    /**
     * Detects compact record constructors (e.g. {@code public Person { ... }})
     * and claims their opening brace so they are not mistaken for initializer blocks.
     */
    private static void claimCompactConstructorBraces(
            String stripped, int[] braceDepth, List<TypeNode> types,
            Set<Integer> claimedBraces) {
        for (TypeNode type : types) {
            if (type.kind != TypeKind.RECORD) continue;
            if (type.bodyStart < 0) continue;
            int expectedDepth = braceDepth[type.bodyStart] + 1;
            // Scan for: [modifiers] RecordName { at the expected depth
            for (int i = type.bodyStart + 1; i < type.bodyEnd; i++) {
                if (stripped.charAt(i) != '{') continue;
                if (braceDepth[i] != expectedDepth) continue;
                if (claimedBraces.contains(i)) continue;
                // Walk backwards past whitespace to find the preceding identifier
                if (isPrecededByName(stripped, i, type.simpleName)) {
                    claimedBraces.add(i);
                }
            }
        }
    }

    /**
     * Checks whether the '{' at the given position is preceded (after optional whitespace)
     * by the given identifier name.
     */
    private static boolean isPrecededByName(String source, int bracePos, String name) {
        int i = bracePos - 1;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
        if (i < name.length() - 1) return false;
        int wordEnd = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) i--;
        String word = source.substring(i + 1, wordEnd);
        return name.equals(word);
    }

    /**
     * Checks whether a '{' at the given position can validly start an initializer block.
     * An initializer block must be preceded (skipping whitespace) by '}', ';', '{', or
     * the keyword 'static'. Anything else (e.g. '=', ')', identifier, ',') indicates
     * the brace is part of an annotation array, field initializer, etc.
     */
    private static boolean isValidInitializerPosition(String source, int bracePos) {
        int i = bracePos - 1;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
        if (i < 0) return true; // start of source — shouldn't happen but safe default
        char preceding = source.charAt(i);
        // '}', ';', '{' → valid initializer position
        if (preceding == '}' || preceding == ';' || preceding == '{') return true;
        // Check for 'static' keyword
        if (Character.isJavaIdentifierPart(preceding)) {
            int wordEnd = i + 1;
            while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) i--;
            String word = source.substring(i + 1, wordEnd);
            return "static".equals(word);
        }
        return false;
    }

    /**
     * Checks whether the '{' at the given position is preceded by the keyword 'static'.
     */
    private static boolean isPrecededByStaticKeyword(String source, int bracePos) {
        int i = bracePos - 1;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) i--;
        if (i < 5) return false; // "static" is 6 chars
        int wordEnd = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) i--;
        String word = source.substring(i + 1, wordEnd);
        return "static".equals(word);
    }

    // ── Shared utilities ─────────────────────────────────────────────

    /**
     * Finds the matching closing brace for an opening brace in stripped source.
     * Returns -1 if no match found (truncated file).
     */
    static int findMatchingBrace(String source, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Removes comments and empty lines from a Java source body.
     * <p>
     * Line comments ({@code //}) and block comments ({@code /* ... * /}) are
     * removed. Lines that become entirely whitespace after comment removal are
     * also removed, <b>except</b> empty lines that fall inside text blocks
     * ({@code """..."""}), which are preserved verbatim.
     */
    static String removeCommentsAndEmptyLines(String body) {
        if (body == null) return null;

        // Phase 1: Remove comments, preserve strings/text blocks, track text block lines
        StringBuilder sb = new StringBuilder(body.length());
        Set<Integer> textBlockLines = new HashSet<>();
        int lineNum = 0;
        int i = 0;
        int len = body.length();

        while (i < len) {
            char c = body.charAt(i);

            if (c == '\n') {
                sb.append(c);
                lineNum++;
                i++;
                continue;
            }

            // Line comment: skip to end of line
            if (c == '/' && i + 1 < len && body.charAt(i + 1) == '/') {
                i += 2;
                while (i < len && body.charAt(i) != '\n') i++;
                continue;
            }

            // Block comment: skip to */, preserving newlines
            if (c == '/' && i + 1 < len && body.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < len && !(body.charAt(i) == '*' && body.charAt(i + 1) == '/')) {
                    if (body.charAt(i) == '\n') {
                        sb.append('\n');
                        lineNum++;
                    }
                    i++;
                }
                if (i + 1 < len) i += 2; // skip */
                continue;
            }

            // Text block: preserve verbatim, track lines as text block lines
            if (c == '"' && i + 2 < len && body.charAt(i + 1) == '"' && body.charAt(i + 2) == '"') {
                sb.append('"').append('"').append('"');
                i += 3;
                while (i + 2 < len && !(body.charAt(i) == '"' && body.charAt(i + 1) == '"'
                        && body.charAt(i + 2) == '"')) {
                    if (body.charAt(i) == '\n') {
                        lineNum++;
                        textBlockLines.add(lineNum);
                    }
                    sb.append(body.charAt(i));
                    i++;
                }
                if (i + 2 < len) {
                    sb.append('"').append('"').append('"');
                    i += 3;
                }
                continue;
            }

            // String literal: preserve verbatim
            if (c == '"') {
                sb.append(c);
                i++;
                while (i < len && body.charAt(i) != '"') {
                    if (body.charAt(i) == '\\' && i + 1 < len) {
                        sb.append(body.charAt(i));
                        i++;
                    }
                    sb.append(body.charAt(i));
                    i++;
                }
                if (i < len) { sb.append('"'); i++; }
                continue;
            }

            // Char literal: preserve verbatim
            if (c == '\'') {
                sb.append(c);
                i++;
                while (i < len && body.charAt(i) != '\'') {
                    if (body.charAt(i) == '\\' && i + 1 < len) {
                        sb.append(body.charAt(i));
                        i++;
                    }
                    sb.append(body.charAt(i));
                    i++;
                }
                if (i < len) { sb.append('\''); i++; }
                continue;
            }

            // Normal character
            sb.append(c);
            i++;
        }

        // Phase 2: Remove empty lines not inside text blocks
        String[] lines = sb.toString().split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int ln = 0; ln < lines.length; ln++) {
            if (lines[ln].isBlank() && !textBlockLines.contains(ln)) {
                continue;
            }
            if (result.length() > 0) result.append('\n');
            result.append(lines[ln]);
        }

        return result.toString();
    }

    private static final HexFormat HEX_FORMAT = HexFormat.of();

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HEX_FORMAT.formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ── Class name extraction (merged from SourceFileClassExtractor) ─

    /**
     * Matches top-level Java type declarations for the legacy extraction path.
     */
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "^(?:public\\s+|protected\\s+|private\\s+|abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+|strictfp\\s+)*"
            + "(?:class|interface|enum|record|@interface)\\s+(\\w+)",
            Pattern.MULTILINE
    );

    /**
     * Matches top-level Kotlin type declarations: class, interface, enum class, object,
     * data class, sealed class/interface, value class, annotation class, abstract class.
     */
    private static final Pattern KOTLIN_TYPE_DECLARATION = Pattern.compile(
            "^(?:public\\s+|protected\\s+|private\\s+|internal\\s+|abstract\\s+|final\\s+|open\\s+"
            + "|sealed\\s+|data\\s+|value\\s+|inline\\s+|annotation\\s+|actual\\s+|expect\\s+)*"
            + "(?:class|interface|object|enum\\s+class)\\s+(\\w+)",
            Pattern.MULTILINE
    );

    /** Matches a Java or Kotlin package declaration. */
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
            "^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE
    );

    /**
     * Extracts all top-level class FQCNs from a Java or Kotlin source file.
     * The package name is discovered from the source file's {@code package} declaration.
     */
    public static Set<String> extractClassNames(Path sourceFile) throws IOException {
        String source = Files.readString(sourceFile);
        String packageName = extractPackageName(source);
        boolean kotlin = sourceFile.toString().endsWith(".kt");
        return kotlin ? extractKotlinClassNames(source, packageName)
                      : extractClassNames(source, packageName);
    }

    /**
     * Extracts all top-level class FQCNs from a Java source file.
     */
    public static Set<String> extractClassNames(Path sourceFile, String packageName) throws IOException {
        return extractClassNames(Files.readString(sourceFile), packageName);
    }

    /**
     * Extracts all top-level class FQCNs from Java source text.
     */
    public static Set<String> extractClassNames(String source, String packageName) {
        return extractTypes(source, packageName, TYPE_DECLARATION);
    }

    /**
     * Extracts all top-level class/object FQCNs from Kotlin source text.
     */
    public static Set<String> extractKotlinClassNames(String source, String packageName) {
        return extractTypes(source, packageName, KOTLIN_TYPE_DECLARATION);
    }

    private static Set<String> extractTypes(String source, String packageName, Pattern pattern) {
        if (pattern == KOTLIN_TYPE_DECLARATION) {
            return extractTypesLegacy(source, packageName, pattern);
        }
        return parse(source, packageName, Detail.TYPES).typeNames();
    }

    /** Legacy Kotlin extraction path — uses the Kotlin-specific regex. */
    private static Set<String> extractTypesLegacy(String source, String packageName, Pattern pattern) {
        String stripped = stripCommentsAndStrings(source);

        int len = stripped.length();
        int[] braceDepth = new int[len + 1];
        for (int i = 0; i < len; i++) {
            char c = stripped.charAt(i);
            braceDepth[i + 1] = braceDepth[i] + (c == '{' ? 1 : c == '}' ? -1 : 0);
        }

        Set<String> result = new TreeSet<>();
        Matcher matcher = pattern.matcher(stripped);
        while (matcher.find()) {
            if (braceDepth[matcher.start()] == 0) {
                String typeName = matcher.group(1);
                result.add(packageName.isEmpty() ? typeName : packageName + "." + typeName);
            }
        }
        return result;
    }

    /**
     * Extracts the package name from a Java or Kotlin source string.
     * Returns empty string for default package.
     */
    public static String extractPackageName(String source) {
        Matcher m = PACKAGE_DECLARATION.matcher(source);
        return m.find() ? m.group(1) : "";
    }

    // ── File path → FQCN conversion ──────────────────────────────────

    /**
     * Converts relative {@code .java} file paths to FQCNs.
     * If a {@code sourceRoot} is provided and the file exists, parses the source
     * to find all top-level types. Otherwise falls back to filename-based derivation.
     */
    public static Set<String> filesToClassNames(Set<String> filePaths, Path sourceRoot) {
        Set<String> classNames = new TreeSet<>();
        for (String path : filePaths) {
            if (!isSourceFile(path)) continue;
            classNames.addAll(fileToClassNames(path, sourceRoot));
        }
        return classNames;
    }

    /**
     * Converts relative {@code .java} file paths to FQCNs using filename-only mapping.
     */
    public static Set<String> filesToClassNames(Set<String> filePaths) {
        return filesToClassNames(filePaths, null);
    }

    /**
     * Converts a single relative {@code .java} path to FQCNs by parsing the source file.
     * Falls back to filename-based derivation if the file cannot be read.
     */
    public static Set<String> fileToClassNames(String relativePath, Path sourceRoot) {
        if (sourceRoot != null) {
            Path file = sourceRoot.resolve(relativePath);
            if (Files.isRegularFile(file)) {
                try {
                    String source = Files.readString(file);
                    Set<String> names = extractWithFallbackPackage(relativePath, source);
                    if (!names.isEmpty()) return names;
                } catch (IOException e) {
                    // fall through to filename-based
                }
            }
        }
        return Set.of(pathToClassName(relativePath));
    }

    /**
     * Converts a single relative {@code .java} path to FQCNs by parsing source text.
     * Falls back to filename-based derivation if parsing yields no results.
     */
    public static Set<String> fileToClassNames(String relativePath, String sourceText) {
        Set<String> names = extractWithFallbackPackage(relativePath, sourceText);
        return names.isEmpty() ? Set.of(pathToClassName(relativePath)) : names;
    }

    private static Set<String> extractWithFallbackPackage(String relativePath, String sourceText) {
        String pkg = extractPackageName(sourceText);
        if (pkg.isEmpty()) pkg = pathToPackage(relativePath);
        boolean kotlin = relativePath.endsWith(".kt");
        return kotlin ? extractKotlinClassNames(sourceText, pkg)
                      : extractClassNames(sourceText, pkg);
    }

    /**
     * Derives a single FQCN from a relative file path (filename-based, no parsing).
     * E.g., {@code com/example/Foo.java} → {@code com.example.Foo}
     */
    public static String pathToClassName(String relativePath) {
        String withoutExt = relativePath;
        if (withoutExt.endsWith(".java")) {
            withoutExt = withoutExt.substring(0, withoutExt.length() - 5);
        } else if (withoutExt.endsWith(".kt")) {
            withoutExt = withoutExt.substring(0, withoutExt.length() - 3);
        }
        return withoutExt.replace('/', '.').replace('\\', '.');
    }

    /** Returns true if the path ends with a supported source extension (.java or .kt). */
    public static boolean isSourceFile(String path) {
        return path.endsWith(".java") || path.endsWith(".kt");
    }

    /**
     * Converts a relative file path to a package name.
     * E.g., {@code com/example/Foo.java} → {@code com.example}
     */
    public static String pathToPackage(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        if (lastSlash < 0) {
            lastSlash = relativePath.lastIndexOf('\\');
        }
        if (lastSlash < 0) {
            return ""; // default package
        }
        return relativePath.substring(0, lastSlash).replace('/', '.').replace('\\', '.');
    }

    // ── Comment/string stripping ─────────────────────────────────────

    /**
     * Strips block comments, line comments, and string/char literals from Java source code.
     * Replaces them with spaces (preserving newlines) so line positions are maintained.
     */
    public static String stripCommentsAndStrings(String source) {
        StringBuilder sb = new StringBuilder(source.length());
        int i = 0;
        int len = source.length();
        while (i < len) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < len) {
                char next = source.charAt(i + 1);
                if (next == '/') {
                    i = skipLineComment(source, sb, i, len);
                } else if (next == '*') {
                    i = skipBlockComment(source, sb, i, len);
                } else {
                    sb.append(c);
                    i++;
                }
            } else if (c == '"') {
                if (i + 2 < len && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                    i = skipTextBlock(source, sb, i, len);
                } else {
                    i = skipStringLiteral(source, sb, i, len);
                }
            } else if (c == '\'') {
                i = skipCharLiteral(source, sb, i, len);
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static int skipLineComment(String source, StringBuilder sb, int i, int len) {
        while (i < len && source.charAt(i) != '\n') {
            sb.append(' ');
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String source, StringBuilder sb, int i, int len) {
        sb.append(' ').append(' ');
        i += 2;
        while (i + 1 < len && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
            sb.append(source.charAt(i) == '\n' ? '\n' : ' ');
            i++;
        }
        if (i + 1 < len) {
            sb.append(' ').append(' ');
            i += 2;
        }
        return i;
    }

    private static int skipTextBlock(String source, StringBuilder sb, int i, int len) {
        sb.append(' ').append(' ').append(' ');
        i += 3;
        while (i < len) {
            // Handle escape sequences (especially \" which must not trigger
            // premature closure of the text block)
            if (source.charAt(i) == '\\' && i + 1 < len) {
                sb.append(' ').append(' ');
                i += 2;
                continue;
            }
            // Check for closing """
            if (i + 2 < len && source.charAt(i) == '"' && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                sb.append(' ').append(' ').append(' ');
                i += 3;
                return i;
            }
            sb.append(source.charAt(i) == '\n' ? '\n' : ' ');
            i++;
        }
        return i;
    }

    private static int skipStringLiteral(String source, StringBuilder sb, int i, int len) {
        sb.append(' ');
        i++;
        while (i < len && source.charAt(i) != '"') {
            if (source.charAt(i) == '\\' && i + 1 < len) {
                sb.append(' ').append(' ');
                i += 2;
            } else {
                sb.append(source.charAt(i) == '\n' ? '\n' : ' ');
                i++;
            }
        }
        if (i < len) {
            sb.append(' ');
            i++;
        }
        return i;
    }

    private static int skipCharLiteral(String source, StringBuilder sb, int i, int len) {
        sb.append(' ');
        i++;
        while (i < len && source.charAt(i) != '\'') {
            if (source.charAt(i) == '\\' && i + 1 < len) {
                sb.append(' ').append(' ');
                i += 2;
            } else {
                sb.append(' ');
                i++;
            }
        }
        if (i < len) {
            sb.append(' ');
            i++;
        }
        return i;
    }
}
