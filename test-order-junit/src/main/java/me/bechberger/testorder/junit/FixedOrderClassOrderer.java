package me.bechberger.testorder.junit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
		String orderFilePath = FixedOrderSupport.resolveOrderFilePath(ORDER_FILE_PROPERTY, true,
				getClass().getClassLoader());
		if (orderFilePath == null)
			return;

		Path orderFile = Path.of(orderFilePath);
		if (!Files.exists(orderFile))
			return;

		Map<String, Integer> positionMap = FixedOrderSupport.buildPositionMap(orderFile, "class");
		if (positionMap == null)
			return;

		@SuppressWarnings("unchecked")
		List<ClassDescriptor> descriptors = (List<ClassDescriptor>) context.getClassDescriptors();
		FixedOrderSupport.applyOrder(descriptors, d -> d.getTestClass().getName(), positionMap, true);
	}
}
