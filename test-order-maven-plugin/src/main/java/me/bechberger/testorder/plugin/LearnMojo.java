package me.bechberger.testorder.plugin;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Alias for the {@code combined} goal.
 * <p>
 * Many users expect {@code mvn test-order:learn} to work. This Mojo simply
 * delegates to {@link CombinedMojo}.
 * <p>
 * Usage: {@code mvn test-order:learn test}
 */
@Mojo(name = "learn", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class LearnMojo extends CombinedMojo {
}
