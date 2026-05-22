package me.bechberger.testorder.maven;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.execution.MavenSession;

/**
 * Drains all active IndexCollectorServer instances after the Maven session
 * ends, while the plugin classloader is still alive.
 * <p>
 * Without this, stopAndMerge() falls back to the JVM shutdown hook, which races
 * with Maven's classloader teardown and fails with NoClassDefFoundError on the
 * RoaringBitmap serialization path.
 * <p>
 * Registered as a Plexus component in META-INF/plexus/components.xml.
 */
public class CollectorLifecycleParticipant extends AbstractMavenLifecycleParticipant {

	@Override
	public void afterSessionEnd(MavenSession session) {
		Map<Path, me.bechberger.testorder.IndexCollectorServer> collectors = AbstractTestOrderMojo.activeCollectors;
		if (collectors.isEmpty()) {
			return;
		}
		List<Path> keys = new ArrayList<>(collectors.keySet());
		for (Path key : keys) {
			me.bechberger.testorder.IndexCollectorServer collector = collectors.remove(key);
			if (collector == null) {
				continue;
			}
			try {
				int merged = collector.stopAndMerge();
				if (merged > 0) {
					System.out.println(
							"[test-order] IndexCollectorServer merged " + merged + " test classes (session end)");
				}
			} catch (Exception | NoClassDefFoundError e) {
				System.err.println("[test-order] CollectorLifecycleParticipant: merge failed for " + key + ": " + e);
			}
		}
	}
}
