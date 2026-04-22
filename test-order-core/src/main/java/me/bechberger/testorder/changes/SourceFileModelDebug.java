package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * CLI debug tool: parses Java source files with {@link SourceFileModel} and
 * prints the rough structural model as JSON to stdout.
 * <p>
 * Usage: {@code java SourceFileModelDebug <file-or-directory> ...}
 */
public class SourceFileModelDebug {

	public static void main(String[] args) throws IOException {
		if (args.length == 0) {
			System.err.println("Usage: SourceFileModelDebug <file-or-directory> ...");
			System.exit(1);
		}

		StringBuilder json = new StringBuilder();
		json.append("[\n");
		boolean first = true;

		for (String arg : args) {
			Path p = Path.of(arg);
			List<Path> files;
			if (Files.isDirectory(p)) {
				try (Stream<Path> walk = Files.walk(p)) {
					files = walk.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".java")).sorted()
							.toList();
				}
			} else {
				files = List.of(p);
			}
			for (Path file : files) {
				if (!first)
					json.append(",\n");
				first = false;
				json.append(fileToJson(file));
			}
		}

		json.append("\n]");
		System.out.println(json);
	}

	static String fileToJson(Path file) {
		StringBuilder sb = new StringBuilder();
		String fileName = file.getFileName().toString();
		try {
			String source = Files.readString(file);
			String pkg = SourceFileModel.extractPackageName(source);
			SourceFileModel.Model model = SourceFileModel.parse(source, pkg, SourceFileModel.Detail.METHODS);

			sb.append("  {\"file\": ").append(jsonStr(fileName));
			sb.append(", \"package\": ").append(jsonStr(pkg));
			sb.append(",\n   \"types\": [");
			for (int i = 0; i < model.types().size(); i++) {
				if (i > 0)
					sb.append(",");
				var t = model.types().get(i);
				sb.append("\n     {\"kind\": ").append(jsonStr(t.kind().name()));
				sb.append(", \"name\": ").append(jsonStr(t.simpleName()));
				sb.append(", \"fqcn\": ").append(jsonStr(t.fqcn()));
				sb.append("}");
			}
			sb.append("],\n   \"methods\": [");
			for (int i = 0; i < model.methods().size(); i++) {
				if (i > 0)
					sb.append(",");
				var m = model.methods().get(i);
				sb.append("\n     {\"name\": ").append(jsonStr(m.name()));
				sb.append(", \"in\": ").append(jsonStr(m.enclosingFqcn()));
				sb.append(", \"ctor\": ").append(m.isConstructor());
				sb.append(", \"abstract\": ").append(m.isAbstract());
				if (m.bodyHash() != null) {
					sb.append(", \"hash\": ").append(jsonStr(m.bodyHash().substring(0, 12) + "..."));
				}
				sb.append("}");
			}
			sb.append("]}");
		} catch (Exception e) {
			sb.append("  {\"file\": ").append(jsonStr(fileName));
			sb.append(", \"error\": ").append(jsonStr(e.getClass().getSimpleName() + ": " + e.getMessage()));
			sb.append("}");
		}
		return sb.toString();
	}

	private static String jsonStr(String s) {
		if (s == null)
			return "null";
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
				.replace("\t", "\\t") + "\"";
	}
}
