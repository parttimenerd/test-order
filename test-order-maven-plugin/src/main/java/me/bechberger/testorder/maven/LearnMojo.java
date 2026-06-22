package me.bechberger.testorder.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Forces learn mode: always instrument tests and build dependency index.
 * <p>
 * Unlike the {@code auto} goal, this goal always runs in learn mode regardless
 * of the current {@code testorder.mode} setting.
 * <p>
 * Usage: {@code mvn test-order:learn test}
 */
@Mojo(name = "learn", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES)
public class LearnMojo extends AutoMojo {

	@Override
	public void execute() throws MojoExecutionException {
		// Force learn mode regardless of configuration
		if (session != null && session.getUserProperties() != null) {
			session.getUserProperties().setProperty(MavenPluginConfigKeys.MODE, "learn");
		}
		// Warn if 'test' phase is likely not going to run (standalone CLI goal
		// invocation)
		if (session != null && session.getGoals() != null && session.getGoals().stream().noneMatch(g -> g.equals("test")
				|| g.equals("verify") || g.equals("install") || g.equals("package") || g.equals("deploy"))) {
			getLog().warn("[test-order] The 'learn' goal configures the agent but does not execute tests."
					+ "\nRun: mvn test-order:learn test");
		}
		super.execute();
	}
}
