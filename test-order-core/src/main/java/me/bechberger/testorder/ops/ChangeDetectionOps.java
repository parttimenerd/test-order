package me.bechberger.testorder.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import me.bechberger.testorder.changes.ChangeDetectionSupport;
import me.bechberger.testorder.changes.MethodHashStore;

/**
 * Shared change-detection operations that are framework-agnostic.
 * <p>
 * Both the Maven and Gradle plugins delegate here for change detection,
 * method-hash comparison, and Kotlin sibling handling.
 */
public final class ChangeDetectionOps {

	private ChangeDetectionOps() {
	}

	/**
	 * Detects changed classes. Falls back to an empty set on I/O errors.
	 *
	 * @param changeMode
	 *            change detection mode (e.g. "since-last-commit", "uncommitted")
	 * @param projectRoot
	 *            project root directory (git root for ReactorContext usage)
	 * @param sourceRoot
	 *            main source root directory
	 * @param hashPath
	 *            path to the hash snapshot file
	 * @param changedClasses
	 *            explicit changed-class list (may be {@code null})
	 * @param readOnly
	 *            {@code true} to avoid updating the hash snapshot
	 * @param log
	 *            logger for warnings
	 * @return set of changed fully-qualified class names
	 */
	public static Set<String> detectChangedClasses(String changeMode, Path projectRoot, Path sourceRoot, Path hashPath,
			String changedClasses, boolean readOnly, PluginLog log) {
		try {
			return ChangeDetectionSupport.detectChangedClasses(changeMode, projectRoot, sourceRoot, hashPath,
					changedClasses, readOnly);
		} catch (IOException e) {
			log.warn("[test-order] Change detection failed: " + e.getMessage() + " — falling back to no changes");
			return Set.of();
		}
	}

	/**
	 * Detects changed test classes. EXPLICIT mode always returns empty. Falls back
	 * to an empty set on I/O errors.
	 */
	public static Set<String> detectChangedTestClasses(String changeMode, Path projectRoot, Path testSourceRoot,
			Path testHashPath, boolean readOnly, PluginLog log) {
		return detectChangedTestClasses(changeMode, projectRoot, testSourceRoot, testHashPath, null, readOnly, log);
	}

	/**
	 * Detects changed test classes and optionally merges explicit test-class input
	 * (CSV of FQCNs from {@code testorder.changed.test.classes}).
	 */
	public static Set<String> detectChangedTestClasses(String changeMode, Path projectRoot, Path testSourceRoot,
			Path testHashPath, String explicitChangedTestClasses, boolean readOnly, PluginLog log) {
		Set<String> explicit = parseCsv(explicitChangedTestClasses);
		if ("explicit".equalsIgnoreCase(changeMode)) {
			return explicit;
		}
		try {
			Set<String> detected = ChangeDetectionSupport.detectChangedTestClasses(changeMode, projectRoot,
					testSourceRoot, testHashPath, readOnly);
			if (explicit.isEmpty()) {
				return detected;
			}
			Set<String> merged = new LinkedHashSet<>(detected);
			merged.addAll(explicit);
			return merged;
		} catch (IOException e) {
			log.warn("[test-order] Test change detection failed: " + e.getMessage() + " — falling back to no changes");
			return explicit;
		}
	}

	private static Set<String> parseCsv(String csv) {
		if (csv == null || csv.isBlank()) {
			return Set.of();
		}
		Set<String> out = new LinkedHashSet<>();
		for (String token : csv.split(",")) {
			String trimmed = token.trim();
			if (!trimmed.isEmpty()) {
				out.add(trimmed);
			}
		}
		return out;
	}

	/**
	 * Detects changed test methods by comparing current method hashes against a
	 * previous snapshot. Returns a set of {@code className#methodName} keys.
	 */
	public static Set<String> detectChangedMethods(Path testSourceRoot, Path methodHashFile, PluginLog log) {
		try {
			if (!Files.isDirectory(testSourceRoot))
				return Set.of();
			if (Files.exists(methodHashFile)) {
				MethodHashStore previous = MethodHashStore.load(methodHashFile);
				MethodHashStore current = MethodHashStore.scanIncremental(testSourceRoot, previous);
				return current.getChangedMethods(previous);
			}
			// no previous snapshot → all methods are "changed"
			return MethodHashStore.scan(testSourceRoot).getHashes().keySet();
		} catch (IOException e) {
			log.warn("[test-order] Method change detection failed: " + e.getMessage());
			return Set.of();
		}
	}

	/**
	 * Saves method-level hash snapshots for test source files.
	 */
	public static void snapshotMethodHashes(Path testSourceRoot, Path methodHashFile, PluginLog log) {
		try {
			if (Files.isDirectory(testSourceRoot)) {
				MethodHashStore store = MethodHashStore.scan(testSourceRoot);
				store.save(methodHashFile);
				log.info("[test-order] Saved method hash snapshot (" + store.getHashes().size() + " methods): "
						+ methodHashFile);
			}
		} catch (IOException e) {
			log.warn("[test-order] Failed to save method hash snapshot: " + e.getMessage());
		}
	}

	/**
	 * Detects changes in the Kotlin sibling of a source root and merges them with
	 * an existing set of changed classes.
	 *
	 * @param base
	 *            existing changed classes
	 * @param sourceRoot
	 *            Java source root (Kotlin sibling is resolved as a peer directory)
	 * @param hashPath
	 *            Java hash file path (Kotlin path derived via
	 *            {@link HashSnapshotOperation#kotlinHashFile})
	 * @param changeMode
	 *            change detection mode
	 * @param changedClasses
	 *            explicit changed classes (may be {@code null})
	 * @param projectRoot
	 *            project/git root
	 * @param readOnly
	 *            whether to avoid updating snapshots
	 * @param skipExplicit
	 *            if {@code true}, use test-class detection (ignores explicit mode)
	 * @param log
	 *            logger
	 * @return merged set including Kotlin changes, or {@code base} unchanged if no
	 *         Kotlin root exists
	 */
	public static Set<String> mergeKotlinChanges(Set<String> base, Path sourceRoot, Path hashPath, String changeMode,
			String changedClasses, Path projectRoot, boolean readOnly, boolean skipExplicit, PluginLog log) {
		Path kotlinRoot = sourceRoot.resolveSibling("kotlin");
		if (!Files.isDirectory(kotlinRoot)) {
			return base;
		}
		Path kotlinHashPath = HashSnapshotOperation.kotlinHashFile(hashPath);
		try {
			Set<String> kotlinChanges;
			if (skipExplicit) {
				kotlinChanges = ChangeDetectionSupport.detectChangedTestClasses(changeMode, projectRoot, kotlinRoot,
						kotlinHashPath, readOnly);
			} else {
				kotlinChanges = ChangeDetectionSupport.detectChangedClasses(changeMode, projectRoot, kotlinRoot,
						kotlinHashPath, changedClasses, readOnly);
			}
			if (kotlinChanges.isEmpty()) {
				return base;
			}
			Set<String> merged = new LinkedHashSet<>(base);
			merged.addAll(kotlinChanges);
			return merged;
		} catch (IOException e) {
			log.warn("[test-order] Kotlin change detection failed: " + e.getMessage());
			return base;
		}
	}

	/**
	 * Detects changed classes and merges Kotlin sibling changes in a single call.
	 */
	public static Set<String> detectChangedClassesWithKotlin(String changeMode, Path projectRoot, Path sourceRoot,
			Path hashPath, String changedClasses, boolean readOnly, PluginLog log) {
		Set<String> changed = detectChangedClasses(changeMode, projectRoot, sourceRoot, hashPath, changedClasses,
				readOnly, log);
		return mergeKotlinChanges(changed, sourceRoot, hashPath, changeMode, changedClasses, projectRoot, readOnly,
				false, log);
	}

	/**
	 * Detects changed test classes and merges Kotlin sibling changes in a single
	 * call.
	 */
	public static Set<String> detectChangedTestClassesWithKotlin(String changeMode, Path projectRoot,
			Path testSourceRoot, Path testHashPath, boolean readOnly, PluginLog log) {
		Set<String> changed = detectChangedTestClasses(changeMode, projectRoot, testSourceRoot, testHashPath, readOnly,
				log);
		return mergeKotlinChanges(changed, testSourceRoot, testHashPath, changeMode, null, projectRoot, readOnly, true,
				log);
	}

	/**
	 * Detects changed test classes with explicit overrides and merges Kotlin
	 * sibling changes in a single call.
	 */
	public static Set<String> detectChangedTestClassesWithKotlin(String changeMode, Path projectRoot,
			Path testSourceRoot, Path testHashPath, String explicitChangedTestClasses, boolean readOnly,
			PluginLog log) {
		Set<String> changed = detectChangedTestClasses(changeMode, projectRoot, testSourceRoot, testHashPath,
				explicitChangedTestClasses, readOnly, log);
		return mergeKotlinChanges(changed, testSourceRoot, testHashPath, changeMode, null, projectRoot, readOnly, true,
				log);
	}
}
