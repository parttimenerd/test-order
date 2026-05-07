package me.bechberger.testorder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Detects top-level package prefixes from a Java/Kotlin source root and
 * resolves the effective instrumentation package list.
 * <p>
 * Shared between the Maven and Gradle plugins.
 */
public final class PackageDetectorSupport {

	private PackageDetectorSupport() {
	}

	/**
	 * Scans the given source root for top-level package prefixes. Walks down
	 * single-child directories to find a stable prefix (e.g.
	 * {@code src/main/java/com/example/app} → {@code com.example.app}).
	 *
	 * @param sourceRoot
	 *            path to {@code src/main/java} (or similar)
	 * @return list of detected package prefixes
	 */
	public static List<String> detectSourcePackages(Path sourceRoot) {
		List<String> result = new ArrayList<>();
		if (!Files.isDirectory(sourceRoot))
			return result;
		try (Stream<Path> topDirs = Files.list(sourceRoot)) {
			topDirs.filter(Files::isDirectory).forEach(dir -> {
				String topPkg = dir.getFileName().toString();
				Path current = dir;
				StringBuilder pkg = new StringBuilder(topPkg);
				while (true) {
					try (Stream<Path> children = Files.list(current)) {
						List<Path> childDirs = children.filter(Files::isDirectory).toList();
						boolean hasJavaFiles;
						try (Stream<Path> files = Files.list(current)) {
							hasJavaFiles = files
									.anyMatch(f -> f.toString().endsWith(".java") || f.toString().endsWith(".kt"));
						}
						if (childDirs.size() == 1 && !hasJavaFiles) {
							current = childDirs.get(0);
							pkg.append('.').append(current.getFileName().toString());
						} else {
							break;
						}
					} catch (IOException e) {
						break;
					}
				}
				result.add(pkg.toString());
			});
		} catch (IOException ignored) {
			// source root not listable — return empty
		}
		return result;
	}

	/**
	 * Resolves the effective include-packages string by combining auto-detected
	 * source packages, user-specified packages, and an optional groupId fallback.
	 *
	 * @param sourceRoot
	 *            path to main source root
	 * @param includePackages
	 *            user-specified comma-separated packages (may be null)
	 * @param filterByGroupId
	 *            whether to use groupId as a fallback
	 * @param groupId
	 *            project groupId (used when no packages are detected and
	 *            filterByGroupId is true)
	 * @return resolved packages string (comma-separated), or null if nothing
	 *         detected
	 */
	public static String resolveIncludePackages(Path sourceRoot, String includePackages, boolean filterByGroupId,
			String groupId) {
		List<String> prefixes = new ArrayList<>();

		// 1. Scan source root for actual packages
		prefixes.addAll(detectSourcePackages(sourceRoot));

		// 2. Add user-specified packages (additive)
		if (includePackages != null && !includePackages.isBlank()) {
			for (String pkg : includePackages.split(",")) {
				String trimmed = pkg.trim();
				if (!trimmed.isEmpty())
					prefixes.add(trimmed);
			}
		}

		// 3. Add groupId when it is a prefix of an existing package (helps minimize)
		// or as a fallback when nothing else was detected
		if (filterByGroupId && groupId != null && !groupId.isBlank()) {
			if (prefixes.isEmpty()) {
				prefixes.add(groupId);
			} else {
				String gidPrefix = groupId + ".";
				boolean subsumes = prefixes.stream().anyMatch(p -> p.startsWith(gidPrefix) || p.equals(groupId));
				if (subsumes) {
					prefixes.add(groupId);
				}
			}
		}

		if (prefixes.isEmpty())
			return null;

		List<String> minimal = minimisePrefixes(prefixes);
		return String.join(",", minimal);
	}

	/**
	 * Removes prefixes that are already covered by a shorter prefix in the list.
	 * For example, if both {@code com.example} and {@code com.example.app} are
	 * present, only {@code com.example} is kept.
	 */
	public static List<String> minimisePrefixes(List<String> prefixes) {
		List<String> sorted = prefixes.stream().distinct().sorted().toList();
		List<String> result = new ArrayList<>();
		for (String p : sorted) {
			boolean covered = result.stream().anyMatch(r -> p.startsWith(r + ".") || p.equals(r));
			if (!covered)
				result.add(p);
		}
		return result;
	}
}
