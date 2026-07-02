package me.bechberger.testorder.maven;

import java.io.IOException;
import java.nio.file.Path;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Restores class files that were instrumented during offline learn mode back to
 * their original state. Bound to {@code prepare-package} so this runs after
 * tests finish but before {@code package} and {@code install} create JARs from
 * {@code target/classes}.
 *
 * <p>Without this, a module that is also used as a Maven plugin (e.g.
 * {@code cds-maven-plugin}) would install its instrumented bytecode into the
 * local repository. When downstream modules then load that plugin, Guice
 * injection of mojo classes would fail with
 * {@code NoClassDefFoundError: me/bechberger/testorder/agent/runtime/UsageStore}
 * because the plugin classloader does not include the test-order runtime JAR.
 *
 * <p>This goal is auto-injected by {@link CollectorLifecycleParticipant} into
 * every non-POM module alongside the {@code prepare} goal; users do not need to
 * configure it explicitly.
 */
@Mojo(name = "restore-instrumentation", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, threadSafe = true)
public class RestoreInstrumentationMojo extends AbstractTestOrderMojo {

	@Override
	protected String resolveEffectiveIncludePackages() {
		return "";
	}

	@Override
	public void execute() {
		String buildDir = project.getBuild().getDirectory();
		if (buildDir == null) {
			return;
		}
		Path targetDir = Path.of(buildDir);
		Path backupDir = targetDir.resolve(".test-order").resolve("classes-backup");
		Path testBackupDir = targetDir.resolve(".test-order").resolve("classes-backup-test");
		try {
			boolean restored = me.bechberger.testorder.agent.OfflineInstrumentor.restore(backupDir);
			if (me.bechberger.testorder.agent.OfflineInstrumentor.restore(testBackupDir)) {
				restored = true;
			}
			if (restored) {
				getLog().info("[test-order] Restored instrumented classes before package phase"
						+ " (prevents NoClassDefFoundError in downstream plugin classloaders).");
			}
		} catch (IOException e) {
			getLog().warn("[test-order] Could not restore instrumented classes before package: " + e.getMessage());
		}
	}
}
