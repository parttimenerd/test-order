package me.bechberger.testorder.junit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
		String orderFilePath = FixedOrderSupport.resolveOrderFilePath(METHOD_ORDER_FILE_PROPERTY, false,
				getClass().getClassLoader());
		if (orderFilePath == null)
			return;

		Path orderFile = Path.of(orderFilePath);
		if (!Files.exists(orderFile))
			return;

		Map<String, Integer> positionMap = FixedOrderSupport.buildPositionMap(orderFile, "method");
		if (positionMap == null)
			return;

		@SuppressWarnings("unchecked")
		List<MethodDescriptor> descriptors = (List<MethodDescriptor>) context.getMethodDescriptors();
		FixedOrderSupport.applyOrder(descriptors, d -> d.getMethod().getName(), positionMap, false);
	}
}
