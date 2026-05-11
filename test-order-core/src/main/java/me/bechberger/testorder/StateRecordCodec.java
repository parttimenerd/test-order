package me.bechberger.testorder;

import java.util.List;
import java.util.logging.Logger;

import me.bechberger.util.json.Util;

/**
 * Encodes and decodes compact run outcome records for state persistence.
 */
final class StateRecordCodec {

	private StateRecordCodec() {
	}

	static List<Object> outcomeToCompact(TestOrderState.TestOutcome o) {
		int flags = (o.isNew() ? 1 : 0) | (o.isChanged() ? 2 : 0) | (o.isFast() ? 4 : 0) | (o.isSlow() ? 8 : 0)
				| (o.failed() ? 16 : 0) | (o.hasStaticFieldOverlap() ? 32 : 0);
		return List.of(o.testClass(), flags, o.depOverlap(), o.depTotal(), o.failScore(), o.complexityOverlap(),
				o.speedRatio());
	}

	static TestOrderState.TestOutcome compactToOutcome(Object obj, Logger log) {
		if (!(obj instanceof List<?>)) {
			log.fine("Skipping non-list outcome entry (legacy format): "
					+ (obj == null ? "null" : obj.getClass().getSimpleName()));
			return null;
		}
		List<Object> arr = Util.asList(obj);
		if (arr.size() < 2 || !(arr.get(0) instanceof String)) {
			log.fine("Skipping numeric/empty compact outcome entry (legacy format)");
			return null;
		}
		try {
			String tc = (String) arr.get(0);
			int flags = toInt(arr.get(1));
			return new TestOrderState.TestOutcome(tc, 0, (flags & 1) != 0, (flags & 2) != 0,
					Math.max(0, arr.size() > 2 ? toInt(arr.get(2)) : 0),
					Math.max(0, arr.size() > 3 ? toInt(arr.get(3)) : 0),
					Math.max(0.0, arr.size() > 4 ? toDouble(arr.get(4)) : 0.0),
					(flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0,
					Math.max(0.0, arr.size() > 5 ? toDouble(arr.get(5)) : 0.0),
					Math.max(0.0, arr.size() > 6 ? toDouble(arr.get(6)) : 0.0),
					(flags & 32) != 0);
		} catch (NumberFormatException e) {
			log.warning("Malformed compact outcome entry, skipping: " + e.getMessage());
			return null;
		}
	}

	private static int toInt(Object o) {
		if (o == null)
			return 0;
		if (o instanceof Number n)
			return n.intValue();
		return Integer.parseInt(o.toString());
	}

	private static double toDouble(Object o) {
		if (o == null)
			return 0.0;
		if (o instanceof Number n)
			return n.doubleValue();
		return Double.parseDouble(o.toString());
	}
}
