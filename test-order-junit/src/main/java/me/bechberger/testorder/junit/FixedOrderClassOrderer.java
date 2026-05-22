package me.bechberger.testorder.junit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

/**
 * A minimal ClassOrderer that enforces a fixed execution order read from a
 * file.
 * <p>
 * Used by the detect-dependencies feature to precisely control test class
 * execution order during OD-bug detection runs.
 * <p>
 * Configuration:
 * <ul>
 * <li>{@code testorder.fixed.order.file} — path to a file with one test class
 * FQCN per line, in the desired execution order.</li>
 * </ul>
 * Classes not listed in the file are placed at the end in their original order.
 */
public class FixedOrderClassOrderer implements ClassOrderer {

	/** System/classpath property for the order file path. */
	public static final String ORDER_FILE_PROPERTY = "testorder.fixed.order.file";

	@Override
	public void orderClasses(ClassOrdererContext context) {
		String orderFilePath = System.getProperty(ORDER_FILE_PROPERTY);
		if (orderFilePath == null || orderFilePath.isBlank()) {
			// Try classpath properties
			try {
				Properties props = new Properties();
				try (var stream = getClass().getClassLoader().getResourceAsStream("testorder-config.properties")) {
					if (stream != null) {
						props.load(stream);
						orderFilePath = props.getProperty(ORDER_FILE_PROPERTY);
					}
				}
			} catch (IOException e) {
				System.err
						.println("[test-order] Warning: failed to read testorder-config.properties: " + e.getMessage());
			}
		}

		if (orderFilePath == null || orderFilePath.isBlank()) {
			return; // No order file configured — keep default order
		}

		Path orderFile = Path.of(orderFilePath);
		if (!Files.exists(orderFile)) {
			return;
		}

		List<String> orderedClasses;
		try {
			orderedClasses = Files.readAllLines(orderFile).stream().map(String::trim)
					.filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
		} catch (IOException e) {
			System.err.println(
					"[test-order] Warning: failed to read class order file " + orderFile + ": " + e.getMessage());
			return;
		}

		// Build position map: class name → index
		Map<String, Integer> positionMap = new HashMap<>();
		for (int i = 0; i < orderedClasses.size(); i++) {
			positionMap.put(orderedClasses.get(i), i);
		}

		// Sort descriptors by their position in the order file
		@SuppressWarnings("unchecked")
		List<ClassDescriptor> descriptors = (List<ClassDescriptor>) context.getClassDescriptors();
		List<ClassDescriptor> sorted = new ArrayList<>(descriptors);
		sorted.sort(Comparator.comparingInt(d -> {
			String name = d.getTestClass().getName();
			// First check exact match (handles explicitly listed nested classes)
			Integer pos = positionMap.get(name);
			if (pos != null)
				return pos;
			// Fall back to top-level enclosing class (first $) position
			int dollar = name.indexOf('$');
			if (dollar > 0) {
				String topLevel = name.substring(0, dollar);
				pos = positionMap.get(topLevel);
				if (pos != null)
					return pos;
			}
			return Integer.MAX_VALUE;
		}));

		// In-place replacement (JUnit ClassOrderer contract)
		descriptors.clear();
		descriptors.addAll(sorted);
	}
}
