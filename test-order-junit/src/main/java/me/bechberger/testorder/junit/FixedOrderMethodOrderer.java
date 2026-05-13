package me.bechberger.testorder.junit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.MethodDescriptor;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;

/**
 * A minimal MethodOrderer that enforces a fixed method execution order read
 * from a file.
 * <p>
 * Used by the detect-dependencies feature to precisely control test method
 * execution order within a class during intra-class OD-bug detection runs.
 * <p>
 * Configuration:
 * <ul>
 * <li>{@code testorder.fixed.method.order.file} — path to a file with one test
 * method name per line, in the desired execution order.</li>
 * </ul>
 * Methods not listed in the file are placed at the end in their original order.
 */
public class FixedOrderMethodOrderer implements MethodOrderer {

	/** System property for the method order file path. */
	public static final String METHOD_ORDER_FILE_PROPERTY = "testorder.fixed.method.order.file";

	@Override
	public void orderMethods(MethodOrdererContext context) {
		String orderFilePath = System.getProperty(METHOD_ORDER_FILE_PROPERTY);
		if (orderFilePath == null || orderFilePath.isBlank()) {
			return; // No order file configured — keep default order
		}

		Path orderFile = Path.of(orderFilePath);
		if (!Files.exists(orderFile)) {
			return;
		}

		List<String> orderedMethods;
		try {
			orderedMethods = Files.readAllLines(orderFile).stream().map(String::trim)
					.filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
		} catch (IOException e) {
			System.err.println(
					"[test-order] Warning: failed to read method order file " + orderFile + ": " + e.getMessage());
			return;
		}

		// Build position map: method name → index
		Map<String, Integer> positionMap = new HashMap<>();
		for (int i = 0; i < orderedMethods.size(); i++) {
			positionMap.put(orderedMethods.get(i), i);
		}

		// Sort descriptors by their position in the order file
		@SuppressWarnings("unchecked")
		List<MethodDescriptor> descriptors = (List<MethodDescriptor>) context.getMethodDescriptors();
		List<MethodDescriptor> sorted = new ArrayList<>(descriptors);
		sorted.sort(Comparator.comparingInt(d -> {
			String name = d.getMethod().getName();
			return positionMap.getOrDefault(name, Integer.MAX_VALUE);
		}));

		// In-place replacement (JUnit MethodOrderer contract)
		descriptors.clear();
		descriptors.addAll(sorted);
	}
}
