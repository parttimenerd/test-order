package me.bechberger.testorder.ml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads ML failure prediction files. Used by PriorityClassOrderer in the forked
 * test JVM to incorporate P(fail) scores into ordering.
 */
public final class MLPredictions {

	private MLPredictions() {
	}

	/**
	 * Reads predictions from a key=value file written by the Maven plugin.
	 *
	 * @param predictionsFile
	 *            path to the predictions file
	 * @return map from test class name to P(fail), empty if file absent
	 */
	public static Map<String, Double> read(Path predictionsFile) throws IOException {
		if (!Files.exists(predictionsFile)) {
			return Map.of();
		}
		Map<String, Double> predictions = new HashMap<>();
		for (String line : Files.readAllLines(predictionsFile)) {
			if (line.startsWith("#") || line.isBlank()) {
				continue;
			}
			int eq = line.indexOf('=');
			if (eq > 0) {
				String key = line.substring(0, eq).trim();
				try {
					double val = Double.parseDouble(line.substring(eq + 1).trim());
					predictions.put(key, val);
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return predictions;
	}
}
