package me.bechberger.testorder.junit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Function;

import me.bechberger.testorder.TestOrderLogger;

/**
 * Shared utilities for {@link FixedOrderClassOrderer} and
 * {@link FixedOrderMethodOrderer}.
 */
final class FixedOrderSupport {

	private FixedOrderSupport() {
	}

	/**
	 * Resolves the order-file path from the given system property, optionally
	 * falling back to {@code testorder-config.properties} on the classpath.
	 *
	 * @param propertyName
	 *            system property name to look up
	 * @param classpathFallback
	 *            when {@code true}, also check {@code testorder-config.properties}
	 *            if the system property is absent
	 * @param cl
	 *            class loader for the classpath fallback lookup
	 * @return the resolved path string, or {@code null} when unconfigured
	 */
	static String resolveOrderFilePath(String propertyName, boolean classpathFallback, ClassLoader cl) {
		String path = System.getProperty(propertyName);
		if ((path == null || path.isBlank()) && classpathFallback) {
			try {
				Properties props = new Properties();
				try (var stream = cl.getResourceAsStream("testorder-config.properties")) {
					if (stream != null) {
						props.load(stream);
						path = props.getProperty(propertyName);
					}
				}
			} catch (IOException e) {
				TestOrderLogger.warn("[test-order] failed to read testorder-config.properties: {}", e.getMessage());
			}
		}
		return (path == null || path.isBlank()) ? null : path;
	}

	/**
	 * Reads the order file and builds a name → position map. Lines that are blank
	 * or start with {@code #} are ignored.
	 *
	 * @param orderFile
	 *            path to the order file
	 * @param label
	 *            human-readable label used in warning messages (e.g. "class",
	 *            "method")
	 * @return position map, or {@code null} if the file could not be read
	 */
	static Map<String, Integer> buildPositionMap(Path orderFile, String label) {
		List<String> lines;
		try {
			lines = Files.readAllLines(orderFile).stream().map(String::trim)
					.filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
		} catch (IOException e) {
			TestOrderLogger.warn("[test-order] failed to read {} order file {}: {}", label, orderFile, e.getMessage());
			return null;
		}
		Map<String, Integer> positionMap = new HashMap<>();
		for (int i = 0; i < lines.size(); i++) {
			positionMap.put(lines.get(i), i);
		}
		return positionMap;
	}

	/**
	 * Sorts {@code descriptors} in place according to {@code positionMap}, placing
	 * unrecognised entries at the end in their original relative order.
	 *
	 * @param descriptors
	 *            mutable list of JUnit descriptors (ClassDescriptor or
	 *            MethodDescriptor)
	 * @param nameExtractor
	 *            extracts the lookup name from a descriptor
	 * @param positionMap
	 *            name → position mapping
	 * @param nestedClassFallback
	 *            when {@code true}, strip everything from the first {@code $}
	 *            onwards and retry the lookup (for top-level/nested class matching)
	 */
	static <D> void applyOrder(List<D> descriptors, Function<D, String> nameExtractor, Map<String, Integer> positionMap,
			boolean nestedClassFallback) {
		List<D> sorted = new ArrayList<>(descriptors);
		sorted.sort(Comparator.comparingInt(d -> {
			String name = nameExtractor.apply(d);
			Integer pos = positionMap.get(name);
			if (pos != null)
				return pos;
			if (nestedClassFallback) {
				int dollar = name.indexOf('$');
				if (dollar > 0) {
					pos = positionMap.get(name.substring(0, dollar));
					if (pos != null)
						return pos;
				}
			}
			return Integer.MAX_VALUE;
		}));
		descriptors.clear();
		descriptors.addAll(sorted);
	}
}
