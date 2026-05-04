package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Scans compiled test class files for the {@code @AlwaysRun} annotation
 * descriptor in the constant pool.
 */
public final class AlwaysRunScanner {

	private AlwaysRunScanner() {
	}

	/**
	 * Walks the given test-classes directory and returns the fully-qualified class
	 * names of all top-level classes annotated with {@code @AlwaysRun}.
	 *
	 * @param testClassesDir
	 *            directory containing compiled {@code .class} files
	 * @return FQCNs of classes carrying {@code @AlwaysRun}; empty set when the
	 *         directory does not exist or an I/O error occurs
	 */
	public static Set<String> scan(Path testClassesDir) {
		if (!Files.isDirectory(testClassesDir)) {
			return Set.of();
		}
		Set<String> result = new LinkedHashSet<>();
		try (Stream<Path> walk = Files.walk(testClassesDir)) {
			walk.filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$"))
					.filter(AlwaysRunScanner::hasAlwaysRunAnnotation).forEach(p -> {
						String relative = testClassesDir.relativize(p).toString();
						String fqcn = relative.replace('/', '.').replace('\\', '.').replaceAll("\\.class$", "");
						result.add(fqcn);
					});
		} catch (IOException e) {
			// best-effort scan
		}
		return result;
	}

	/**
	 * Returns {@code true} when the class file's constant pool contains the
	 * {@code @AlwaysRun} annotation descriptor.
	 */
	public static boolean hasAlwaysRunAnnotation(Path classFile) {
		try {
			byte[] bytes = Files.readAllBytes(classFile);
			String content = new String(bytes, StandardCharsets.ISO_8859_1);
			return content.contains("Lme/bechberger/testorder/AlwaysRun;");
		} catch (IOException e) {
			return false;
		}
	}
}
