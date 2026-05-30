package me.bechberger.testorder.changes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import me.bechberger.util.json.JSONParser;
import me.bechberger.util.json.PrettyPrinter;

/**
 * Saves and loads the static-analysis sidecar JSON that accompanies each
 * {@code uncertain-classes*.txt} file. The sidecar carries BFS hop-depth and
 * parent-class information for the dashboard Static Analysis tab.
 *
 * <p>
 * Naming convention:
 * <ul>
 * <li>{@code uncertain-classes.txt} → {@code static-analysis-data.json}</li>
 * <li>{@code uncertain-classes-{id}.txt} →
 * {@code static-analysis-data-{id}.json}</li>
 * </ul>
 */
public final class StaticAnalysisDataStore {

	private StaticAnalysisDataStore() {
	}

	/**
	 * Returns the sidecar path for the given {@code uncertain-classes*.txt} file.
	 */
	public static Path sidecarPath(Path uncertainClassesFile) {
		String name = uncertainClassesFile.getFileName().toString();
		String sidecarName;
		if (name.equals("uncertain-classes.txt")) {
			sidecarName = "static-analysis-data.json";
		} else if (name.startsWith("uncertain-classes-") && name.endsWith(".txt")) {
			String id = name.substring("uncertain-classes-".length(), name.length() - ".txt".length());
			sidecarName = "static-analysis-data-" + id + ".json";
		} else {
			sidecarName = name.replaceFirst("uncertain-classes", "static-analysis-data").replaceFirst("\\.txt$",
					".json");
		}
		return uncertainClassesFile.resolveSibling(sidecarName);
	}

	/**
	 * Atomically writes the sidecar JSON file.
	 */
	public static void save(Path sidecarFile, SelectiveLearnSupport.StaticAnalysisData data) throws IOException {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("degraded", data.degraded());
		root.put("seedSize", data.seedSize());
		root.put("expandedSize", data.expandedSize());
		root.put("classDepths", data.classDepths());
		root.put("classParents", data.classParents());

		// Extended fields for the redesigned Static Analysis tab.
		root.put("changedClasses", new java.util.ArrayList<>(data.changedClasses()));

		Map<String, Object> membersByClass = new LinkedHashMap<>();
		for (Map.Entry<String, java.util.Set<String>> e : data.membersByClass().entrySet()) {
			membersByClass.put(e.getKey(), new java.util.ArrayList<>(e.getValue()));
		}
		root.put("membersByClass", membersByClass);

		Map<String, Object> kinds = new LinkedHashMap<>();
		for (Map.Entry<String, StructuralChangeAnalyzer.ChangeKind> e : data.memberChangeKinds().entrySet()) {
			kinds.put(e.getKey(), e.getValue().name());
		}
		root.put("memberChangeKinds", kinds);

		root.put("classesWithTypeChanges", new java.util.ArrayList<>(data.classesWithTypeChanges()));
		root.put("changedStaticFieldKeys", new java.util.ArrayList<>(data.changedStaticFieldKeys()));

		java.util.List<Object> fileSummaries = new java.util.ArrayList<>();
		for (SelectiveLearnSupport.StaticAnalysisData.FileSummary fs : data.fileSummaries()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("path", fs.path());
			entry.put("added", fs.added());
			entry.put("removed", fs.removed());
			entry.put("signature", fs.signature());
			entry.put("body", fs.body());
			entry.put("totalLines", fs.totalLines());
			fileSummaries.add(entry);
		}
		root.put("fileSummaries", fileSummaries);

		String json = PrettyPrinter.compactPrint(root);
		Path parent = sidecarFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path tmp = sidecarFile.resolveSibling(sidecarFile.getFileName() + ".tmp");
		try {
			Files.writeString(tmp, json);
			try {
				Files.move(tmp, sidecarFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				Files.move(tmp, sidecarFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
			}
		}
	}

	/**
	 * Reads the sidecar JSON. Returns {@code null} if the file is absent or
	 * unparseable. Numbers in {@code classDepths} are returned as {@link Double} by
	 * the JSON parser — callers should use {@code ((Number) v).intValue()}.
	 */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> load(Path sidecarFile) throws IOException {
		if (!Files.exists(sidecarFile)) {
			return null;
		}
		String json = Files.readString(sidecarFile);
		try {
			Object parsed = JSONParser.parse(json);
			if (parsed instanceof Map) {
				return (Map<String, Object>) parsed;
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}
}
