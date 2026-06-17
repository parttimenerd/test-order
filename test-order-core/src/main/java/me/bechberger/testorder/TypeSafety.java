package me.bechberger.testorder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Type-safe casting utilities for generic collections loaded from serialized or
 * dynamic sources. Validates types at runtime and returns safe defaults on
 * failure instead of throwing unchecked cast exceptions.
 */
public final class TypeSafety {

	private TypeSafety() {
	}

	/**
	 * Safely casts an object to a Map with String keys and typed values. Returns an
	 * empty map if the cast fails or the object is not a Map.
	 *
	 * @param obj
	 *            object to cast
	 * @param valueType
	 *            expected value class
	 * @return unmodifiable view of the cast map, or empty map if cast fails
	 */
	public static <V> Map<String, V> asMap(Object obj, Class<V> valueType) {
		if (obj == null) {
			return Collections.emptyMap();
		}
		if (!(obj instanceof Map<?, ?>)) {
			return Collections.emptyMap();
		}
		Map<?, ?> raw = (Map<?, ?>) obj;
		Map<String, V> result = new HashMap<>();
		for (var entry : raw.entrySet()) {
			if (!(entry.getKey() instanceof String)) {
				return Collections.emptyMap();
			}
			if (entry.getValue() != null && !valueType.isInstance(entry.getValue())) {
				return Collections.emptyMap();
			}
			@SuppressWarnings("unchecked")
			V value = (V) entry.getValue();
			result.put((String) entry.getKey(), value);
		}
		return Collections.unmodifiableMap(result);
	}

	/**
	 * Safely casts an object to a Map<String, Object> (generic object values).
	 * Returns an empty map if the cast fails or the object is not a Map.
	 */
	public static Map<String, Object> asObjectMap(Object obj) {
		return asMap(obj, Object.class);
	}

	/**
	 * Safely casts an object to a Map<String, Double>.
	 */
	public static Map<String, Double> asDoubleMap(Object obj) {
		return asMap(obj, Double.class);
	}

	/**
	 * Safely casts an object to a Map<String, Integer>.
	 */
	public static Map<String, Integer> asIntMap(Object obj) {
		return asMap(obj, Integer.class);
	}
}
