package me.bechberger.testorder.changes;

import java.util.*;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

/**
 * Alternative parser backend using JavaParser instead of the island-grammar
 * regex parser.
 * <p>
 * Activated via {@code testorder.parser.mode=javaparser}. Produces the same
 * {@link SourceFileModel.Model} output, so all downstream code (StructuralDiff,
 * scoring, etc.) works unchanged.
 */
class JavaParserModel {

	private static final boolean AVAILABLE;
	static {
		boolean found;
		try {
			Class.forName("com.github.javaparser.StaticJavaParser");
			// Configure for latest Java features (records, sealed, etc.)
			StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
			found = true;
		} catch (ClassNotFoundException e) {
			found = false;
		}
		AVAILABLE = found;
	}

	/** Returns true if JavaParser is on the classpath. */
	static boolean isAvailable() {
		return AVAILABLE;
	}

	/**
	 * Parse Java source using JavaParser and return the same Model that
	 * {@link SourceFileModel#parse} would produce.
	 */
	static SourceFileModel.Model parse(String source, String packageName, SourceFileModel.Detail detail) {
		CompilationUnit cu = StaticJavaParser.parse(source);
		String stripped = SourceFileModel.stripCommentsAndStrings(source);

		List<SourceFileModel.TypeNode> types = new ArrayList<>();
		List<SourceFileModel.MethodNode> methods = new ArrayList<>();
		List<SourceFileModel.FieldNode> fields = new ArrayList<>();
		List<SourceFileModel.InitializerNode> initializers = new ArrayList<>();

		// Visit all type declarations (top-level and nested)
		for (TypeDeclaration<?> td : cu.getTypes()) {
			visitType(td, packageName, "", stripped, source, detail, types, methods, fields, initializers);
		}

		return new SourceFileModel.Model(packageName, types, methods, fields, initializers);
	}

	private static void visitType(TypeDeclaration<?> td, String packageName, String outerFqcn, String stripped,
			String source, SourceFileModel.Detail detail, List<SourceFileModel.TypeNode> types,
			List<SourceFileModel.MethodNode> methods, List<SourceFileModel.FieldNode> fields,
			List<SourceFileModel.InitializerNode> initializers) {

		String simpleName = td.getNameAsString();
		String fqcn;
		if (outerFqcn.isEmpty()) {
			fqcn = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
		} else {
			fqcn = outerFqcn + "$" + simpleName;
		}

		SourceFileModel.TypeKind kind = typeKindOf(td);

		// Compute body positions from the AST range
		int bodyStart = -1;
		int bodyEnd = -1;
		String signature = "";
		String bodyText = null;
		String compactBody = null;

		if (td.getRange().isPresent()) {
			var range = td.getRange().get();
			// Find the opening brace of this type in the stripped source
			// The type declaration starts at range.begin, the body brace follows it
			int searchFrom = positionToOffset(source, range.begin.line, range.begin.column);
			if (searchFrom >= 0) {
				bodyStart = findOpenBrace(stripped, searchFrom);
				if (bodyStart >= 0 && bodyStart < stripped.length()) {
					bodyEnd = SourceFileModel.findMatchingBrace(stripped, bodyStart);
					if (bodyEnd >= 0) {
						signature = stripped.substring(searchFrom, bodyStart).trim();
						bodyText = stripped.substring(bodyStart + 1, bodyEnd);
						compactBody = SourceFileModel
								.removeCommentsAndEmptyLines(source.substring(bodyStart + 1, bodyEnd));
					}
				}
			}
		}

		types.add(new SourceFileModel.TypeNode(kind, simpleName, fqcn, bodyStart, bodyEnd, signature, bodyText,
				compactBody));

		// Methods and constructors
		if (detail == SourceFileModel.Detail.METHODS || detail == SourceFileModel.Detail.FIELDS) {
			for (MethodDeclaration md : td.getMethods()) {
				visitMethod(md, fqcn, stripped, source, methods);
			}
			for (ConstructorDeclaration cd : td.getConstructors()) {
				visitConstructor(cd, fqcn, stripped, source, methods);
			}
		}

		// Fields
		if (detail == SourceFileModel.Detail.FIELDS) {
			for (FieldDeclaration fd : td.getFields()) {
				visitField(fd, fqcn, stripped, fields);
			}

			// Initializer blocks
			if (td instanceof ClassOrInterfaceDeclaration coid) {
				for (var member : coid.getMembers()) {
					if (member instanceof InitializerDeclaration id) {
						visitInitializer(id, fqcn, stripped, initializers);
					}
				}
			} else if (td instanceof EnumDeclaration ed) {
				for (var member : ed.getMembers()) {
					if (member instanceof InitializerDeclaration id) {
						visitInitializer(id, fqcn, stripped, initializers);
					}
				}
			}
		}

		// Recurse into nested types
		for (var member : td.getMembers()) {
			if (member instanceof TypeDeclaration<?> nested) {
				visitType(nested, packageName, fqcn, stripped, source, detail, types, methods, fields, initializers);
			}
		}
	}

	private static void visitMethod(MethodDeclaration md, String fqcn, String stripped, String source,
			List<SourceFileModel.MethodNode> methods) {
		String name = md.getNameAsString();
		boolean isAbstract = md.getBody().isEmpty();
		String bodyText = null;
		String bodyHash = null;
		String compactBody = null;
		String signatureText = null;

		// Capture signature text (annotations + modifiers + return type + name + params)
		// from original source for annotation-aware hashing
		if (md.getRange().isPresent()) {
			var fullRange = md.getRange().get();
			int sigStart = positionToOffset(source, fullRange.begin.line, fullRange.begin.column);
			if (sigStart >= 0) {
				if (!isAbstract && md.getBody().isPresent() && md.getBody().get().getRange().isPresent()) {
					var bodyRange = md.getBody().get().getRange().get();
					int bodyStart = positionToOffset(source, bodyRange.begin.line, bodyRange.begin.column);
					if (bodyStart >= 0 && bodyStart > sigStart) {
						signatureText = source.substring(sigStart, bodyStart);
					}
				} else {
					int sigEnd = positionToOffset(source, fullRange.end.line, fullRange.end.column);
					if (sigEnd >= 0) {
						signatureText = source.substring(sigStart, sigEnd + 1);
					}
				}
			}
		}

		if (!isAbstract && md.getBody().isPresent() && md.getBody().get().getRange().isPresent()) {
			var range = md.getBody().get().getRange().get();
			int start = positionToOffset(source, range.begin.line, range.begin.column);
			int end = positionToOffset(source, range.end.line, range.end.column);
			if (start >= 0 && end >= 0 && end < stripped.length()) {
				// include braces in body text (matches island parser convention)
				bodyText = stripped.substring(start, end + 1);
				bodyHash = SourceFileModel.sha256(bodyText);
				compactBody = SourceFileModel.removeCommentsAndEmptyLines(source.substring(start, end + 1));
			}
		}

		methods.add(new SourceFileModel.MethodNode(name, fqcn, false, isAbstract, bodyText, bodyHash, compactBody,
				signatureText));
	}

	private static void visitConstructor(ConstructorDeclaration cd, String fqcn, String stripped, String source,
			List<SourceFileModel.MethodNode> methods) {
		String name = cd.getNameAsString();
		String bodyText = null;
		String bodyHash = null;
		String compactBody = null;
		String signatureText = null;

		if (cd.getRange().isPresent() && cd.getBody().getRange().isPresent()) {
			var fullRange = cd.getRange().get();
			var bodyRange = cd.getBody().getRange().get();
			int sigStart = positionToOffset(source, fullRange.begin.line, fullRange.begin.column);
			int bodyStart = positionToOffset(source, bodyRange.begin.line, bodyRange.begin.column);
			if (sigStart >= 0 && bodyStart >= 0 && bodyStart > sigStart) {
				signatureText = source.substring(sigStart, bodyStart);
			}
		}

		if (cd.getBody().getRange().isPresent()) {
			var range = cd.getBody().getRange().get();
			int start = positionToOffset(source, range.begin.line, range.begin.column);
			int end = positionToOffset(source, range.end.line, range.end.column);
			if (start >= 0 && end >= 0 && end < stripped.length()) {
				// include braces in body text (matches island parser convention)
				bodyText = stripped.substring(start, end + 1);
				bodyHash = SourceFileModel.sha256(bodyText);
				compactBody = SourceFileModel.removeCommentsAndEmptyLines(source.substring(start, end + 1));
			}
		}

		methods.add(new SourceFileModel.MethodNode(name, fqcn, true, false, bodyText, bodyHash, compactBody,
				signatureText));
	}

	private static void visitField(FieldDeclaration fd, String fqcn, String stripped,
			List<SourceFileModel.FieldNode> fields) {
		// A FieldDeclaration can declare multiple variables (e.g. "int x, y;")
		for (VariableDeclarator vd : fd.getVariables()) {
			String name = vd.getNameAsString();
			// Use the full declaration line from stripped source for hashing
			String declText = "";
			if (fd.getRange().isPresent()) {
				var range = fd.getRange().get();
				int start = positionToOffset(stripped, range.begin.line, range.begin.column);
				int end = positionToOffset(stripped, range.end.line, range.end.column);
				if (start >= 0 && end >= 0 && end < stripped.length()) {
					declText = stripped.substring(start, end + 1);
				}
			}
			String hash = SourceFileModel.sha256(declText);
			fields.add(new SourceFileModel.FieldNode(name, fqcn, declText, hash));
		}
	}

	private static void visitInitializer(InitializerDeclaration id, String fqcn, String stripped,
			List<SourceFileModel.InitializerNode> initializers) {
		boolean isStatic = id.isStatic();
		String bodyText = "";
		if (id.getBody().getRange().isPresent()) {
			var range = id.getBody().getRange().get();
			int start = positionToOffset(stripped, range.begin.line, range.begin.column);
			int end = positionToOffset(stripped, range.end.line, range.end.column);
			if (start >= 0 && end >= 0 && end < stripped.length()) {
				bodyText = stripped.substring(start, end + 1);
			}
		}
		String hash = SourceFileModel.sha256(bodyText);
		initializers.add(new SourceFileModel.InitializerNode(isStatic, fqcn, bodyText, hash));
	}

	// ── Helpers ──────────────────────────────────────────────────────

	private static SourceFileModel.TypeKind typeKindOf(TypeDeclaration<?> td) {
		if (td instanceof EnumDeclaration)
			return SourceFileModel.TypeKind.ENUM;
		if (td instanceof RecordDeclaration)
			return SourceFileModel.TypeKind.RECORD;
		if (td instanceof AnnotationDeclaration)
			return SourceFileModel.TypeKind.ANNOTATION;
		if (td instanceof ClassOrInterfaceDeclaration coid) {
			return coid.isInterface() ? SourceFileModel.TypeKind.INTERFACE : SourceFileModel.TypeKind.CLASS;
		}
		return SourceFileModel.TypeKind.CLASS;
	}

	/**
	 * Convert a 1-based line/column to a 0-based offset in the source string.
	 */
	private static int positionToOffset(String source, int line, int column) {
		int currentLine = 1;
		int offset = 0;
		while (currentLine < line && offset < source.length()) {
			if (source.charAt(offset) == '\n')
				currentLine++;
			offset++;
		}
		if (currentLine != line)
			return -1;
		return offset + column - 1; // column is 1-based
	}

	/**
	 * Find the first '{' at source level starting from the given offset.
	 */
	private static int findOpenBrace(String stripped, int from) {
		for (int i = from; i < stripped.length(); i++) {
			if (stripped.charAt(i) == '{')
				return i;
		}
		return -1;
	}
}
