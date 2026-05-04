package me.bechberger.testorder.ops;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import me.bechberger.testorder.DependencyMap;

/**
 * Dumps a binary dependency index as human-readable text. Framework-agnostic —
 * used by both the Maven {@code dump} mojo and the Gradle {@code testOrderDump}
 * task.
 */
public final class DumpOperation {

	private DumpOperation() {
	}

	/**
	 * Dumps the dependency index to a file.
	 *
	 * @param indexPath
	 *            path to the binary index
	 * @param outputPath
	 *            output text file path
	 * @param log
	 *            logger
	 * @throws IOException
	 *             on I/O failure
	 */
	public static void dump(Path indexPath, Path outputPath, PluginLog log) throws IOException {
		DependencyMap map = DependencyMap.load(indexPath);
		if (map.size() == 0) {
			log.info("[test-order] Dependency index is empty: " + indexPath);
			return;
		}
		map.saveText(outputPath);
		log.info("[test-order] Dumped " + map.size() + " test classes → " + outputPath);
	}

	/**
	 * Dumps the dependency index to a print stream (typically stdout).
	 *
	 * @param indexPath
	 *            path to the binary index
	 * @param out
	 *            print stream to write to
	 * @param log
	 *            logger
	 * @throws IOException
	 *             on I/O failure
	 */
	public static void dump(Path indexPath, PrintStream out, PluginLog log) throws IOException {
		DependencyMap map = DependencyMap.load(indexPath);
		if (map.size() == 0) {
			log.info("[test-order] Dependency index is empty: " + indexPath);
			return;
		}
		log.info("[test-order] Dependency index: " + indexPath + " (" + map.size() + " test classes)");
		for (String tc : map.testClasses()) {
			out.println(tc + "\t" + String.join(",", map.get(tc)));
		}
	}
}
