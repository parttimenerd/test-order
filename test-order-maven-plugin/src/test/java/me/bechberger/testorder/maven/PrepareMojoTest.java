package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.TestOrderState;

class PrepareMojoTest {

	@TempDir
	Path tempDir;

	private PrepareMojo mojo;
	private MavenProject project;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new PrepareMojo();
		project = mock(MavenProject.class);
		when(project.getBasedir()).thenReturn(tempDir.toFile());
		when(project.getProperties()).thenReturn(new Properties());
		org.apache.maven.model.Build build = new org.apache.maven.model.Build();
		build.setTestOutputDirectory(tempDir.resolve("test-classes").toString());
		when(project.getBuild()).thenReturn(build);
		when(project.getArtifactId()).thenReturn("test-artifact");
		when(project.getBuildPlugins()).thenReturn(List.of());

		// Mock MavenSession required by ReactorContext
		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);
		when(session.getGoals()).thenReturn(List.of("test"));
		inject(mojo, "session", session);

		inject(mojo, "project", project);
		inject(mojo, "mode", "auto");
		inject(mojo, "instrumentationMode", "CLASS");
		inject(mojo, "changeMode", "auto");
		inject(mojo, "indexFile", tempDir.resolve("test-dependencies.lz4").toString());
		inject(mojo, "stateFile", tempDir.resolve(".test-order-state").toString());
		inject(mojo, "depsDir", tempDir.resolve("test-order-deps").toString());
		inject(mojo, "hashFile", tempDir.resolve(".test-order-hashes.lz4").toString());
		inject(mojo, "testHashFile", tempDir.resolve(".test-order-test-hashes.lz4").toString());
		inject(mojo, "methodHashFile", tempDir.resolve(".test-order-method-hashes.lz4").toString());
		inject(mojo, "filterByGroupId", true);
		inject(mojo, "autoLearnRunThreshold", 0);
		inject(mojo, "autoLearnDiffThreshold", 0);
	}

	@Test
	void executeWithUnrecognizedModeThrows() throws Exception {
		inject(mojo, "mode", "garbage");
		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains("Invalid mode"), "Error should mention invalid mode: " + ex.getMessage());
	}

	@Test
	void executeWithInvalidInstrumentationModeThrowsMojoExecutionException() throws Exception {
		inject(mojo, "instrumentationMode", "INVALID");
		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains("INVALID"), "Error should mention the invalid value: " + ex.getMessage());
	}

	@Test
	void executeWithValidModesDoesNotThrowOnModeCheck() throws Exception {
		// "auto" with no index → should switch to learn mode, not throw on mode
		// validation
		inject(mojo, "mode", "auto");
		// no index file exists, so it will attempt to switch to learn mode
		// (which may throw if agent jar is missing — we only care the mode check
		// passes)
		try {
			mojo.execute();
		} catch (MojoExecutionException e) {
			// Only acceptable if it's not about mode validation
			assertFalse(e.getMessage().startsWith("Invalid mode"),
					"Should not throw 'Invalid mode' for valid mode 'auto': " + e.getMessage());
		}
	}

	@Test
	void prepareSkipsWhenCliWorkflowGoalIsPresent() throws Exception {
		when(mojo.session.getGoals()).thenReturn(List.of("test-order:affected", "test"));

		assertDoesNotThrow(() -> mojo.execute());
		assertTrue(project.getProperties().isEmpty(), "prepare should not mutate properties when it skips");
	}

	@Test
	void injectingAgentWithPlaceholderArgLineDoesNotSetGlobalArgLineProperty() throws Exception {
		// When Surefire argLine uses a property placeholder (e.g. ${argLine}),
		// System.setProperty("argLine", ...) must NOT be called.
		inject(mojo, "mode", "learn");
		System.clearProperty("argLine");
		try {
			mojo.execute();
		} catch (MojoExecutionException e) {
			// Expected: no agent jar found in test classpath
		}
		assertNull(System.getProperty("argLine"),
				"System.setProperty('argLine', ...) should NOT be called for placeholder argLine");
	}

	@Test
	void autoLearnRunThreshold_triggersSwitchToLearnModeWhenReached() throws Exception {
		// When runsSinceLearn >= autoLearnRunThreshold, auto mode should switch to
		// learn.
		// Verified by observing the surefire-not-found error from switchToLearnMode()
		// path.
		inject(mojo, "mode", "auto");
		inject(mojo, "autoLearnRunThreshold", 3);

		Path idxPath = tempDir.resolve("test-dependencies.lz4");
		Files.write(idxPath, new byte[]{1, 2, 3});
		inject(mojo, "indexFile", idxPath.toString());

		TestOrderState state = new TestOrderState();
		for (int i = 0; i < 3; i++)
			state.incrementRunsSinceLearn();
		Path statePath = tempDir.resolve(".test-order-state");
		state.save(statePath);
		inject(mojo, "stateFile", statePath.toString());

		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains("not found in project"),
				"Expected learn-mode path (surefire not found), got: " + ex.getMessage());
	}

	@Test
	void autoLearnRunThreshold_doesNotTriggerWhenBelowThreshold() throws Exception {
		// When runsSinceLearn < threshold, auto mode stays in order mode.
		inject(mojo, "mode", "auto");
		inject(mojo, "autoLearnRunThreshold", 5);

		// Write a valid (empty) dependency index so that order-mode doesn't
		// fall back to learn due to a corrupt-index recovery path.
		Path idxPath = tempDir.resolve("test-dependencies.lz4");
		new me.bechberger.testorder.DependencyMap().save(idxPath);
		inject(mojo, "indexFile", idxPath.toString());

		// Create a test hash file so the "new test" detection uses retainAll
		// (simulating a module that has been learned before)
		Path testHashPath = tempDir.resolve(".test-order-test-hashes.lz4");
		Files.write(testHashPath, new byte[]{0});

		TestOrderState state = new TestOrderState();
		for (int i = 0; i < 2; i++)
			state.incrementRunsSinceLearn();
		Path statePath = tempDir.resolve(".test-order-state");
		state.save(statePath);
		inject(mojo, "stateFile", statePath.toString());

		// Order mode path should NOT require surefire-plugin (no learn mode).
		// Execute may succeed (no exception) or throw something unrelated to surefire.
		try {
			mojo.execute();
			// No exception is also valid — order mode ran successfully
		} catch (MojoExecutionException e) {
			assertFalse(e.getMessage().contains("not found in project"),
					"Should NOT enter learn-mode for runsSinceLearn=2 < threshold=5, got: " + e.getMessage());
		}
	}

	@Test
	void autoLearnDiffThreshold_triggersSwitchToLearnModeWhenReached() throws Exception {
		// When changedClass count >= autoLearnDiffThreshold, auto mode should switch to
		// learn.
		inject(mojo, "mode", "auto");
		inject(mojo, "autoLearnDiffThreshold", 1);
		inject(mojo, "changeMode", "explicit");
		inject(mojo, "changedClasses", "com.example.ChangedClass");

		Path idxPath = tempDir.resolve("test-dependencies.lz4");
		Files.write(idxPath, new byte[]{1, 2, 3});
		inject(mojo, "indexFile", idxPath.toString());

		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains("not found in project"),
				"Expected learn-mode path (surefire not found), got: " + ex.getMessage());
	}

	@Test
	void orderModeSupportsClassLevelParallelExecution() throws Exception {
		// Class-level parallelism should be allowed in order mode.
		// The PriorityClassOrderer is parallel-safe — ordering is a priority hint.
		inject(mojo, "mode", "order");

		Path idxPath = tempDir.resolve("test-dependencies.lz4");
		Files.write(idxPath, new byte[]{1, 2, 3});
		inject(mojo, "indexFile", idxPath.toString());

		TestOrderState state = new TestOrderState();
		Path statePath = tempDir.resolve(".test-order-state");
		state.save(statePath);
		inject(mojo, "stateFile", statePath.toString());

		// Configure Surefire with class-level parallel
		org.codehaus.plexus.util.xml.Xpp3Dom config = new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
		org.codehaus.plexus.util.xml.Xpp3Dom parallel = new org.codehaus.plexus.util.xml.Xpp3Dom("parallel");
		parallel.setValue("classesAndMethods");
		config.addChild(parallel);

		Plugin surefire = new Plugin();
		surefire.setGroupId("org.apache.maven.plugins");
		surefire.setArtifactId("maven-surefire-plugin");
		surefire.setConfiguration(config);
		when(project.getBuildPlugins()).thenReturn(List.of(surefire));

		// Should NOT throw — class-level parallelism is supported in order mode
		try {
			mojo.execute();
		} catch (MojoExecutionException e) {
			assertFalse(e.getMessage().contains("parallel"),
					"Order mode should allow class-level parallelism, got: " + e.getMessage());
		}
	}

	@Test
	void orderModeSupportsJunitClassConcurrentExecution() throws Exception {
		// JUnit jupiter parallel mode.classes.default=concurrent is allowed in order
		// mode.
		inject(mojo, "mode", "order");

		Path idxPath = tempDir.resolve("test-dependencies.lz4");
		Files.write(idxPath, new byte[]{1, 2, 3});
		inject(mojo, "indexFile", idxPath.toString());

		TestOrderState state = new TestOrderState();
		Path statePath = tempDir.resolve(".test-order-state");
		state.save(statePath);
		inject(mojo, "stateFile", statePath.toString());

		// Configure JUnit class-level concurrency via systemPropertyVariables
		org.codehaus.plexus.util.xml.Xpp3Dom config = new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
		org.codehaus.plexus.util.xml.Xpp3Dom sysProps = new org.codehaus.plexus.util.xml.Xpp3Dom(
				"systemPropertyVariables");
		org.codehaus.plexus.util.xml.Xpp3Dom classesDefault = new org.codehaus.plexus.util.xml.Xpp3Dom(
				"junit.jupiter.execution.parallel.mode.classes.default");
		classesDefault.setValue("concurrent");
		sysProps.addChild(classesDefault);
		config.addChild(sysProps);

		Plugin surefire = new Plugin();
		surefire.setGroupId("org.apache.maven.plugins");
		surefire.setArtifactId("maven-surefire-plugin");
		surefire.setConfiguration(config);
		when(project.getBuildPlugins()).thenReturn(List.of(surefire));

		// Should NOT throw — class-level parallelism is supported in order mode
		try {
			mojo.execute();
		} catch (MojoExecutionException e) {
			assertFalse(e.getMessage().contains("parallel"),
					"Order mode should allow JUnit class-level concurrency, got: " + e.getMessage());
		}
	}

	@Test
	void learnModeRejectsClassLevelParallelExecution() throws Exception {
		// Class-level parallelism must be rejected in learn mode to protect dependency
		// tracking.
		inject(mojo, "mode", "learn");

		org.codehaus.plexus.util.xml.Xpp3Dom config = new org.codehaus.plexus.util.xml.Xpp3Dom("configuration");
		org.codehaus.plexus.util.xml.Xpp3Dom parallel = new org.codehaus.plexus.util.xml.Xpp3Dom("parallel");
		parallel.setValue("classesAndMethods");
		config.addChild(parallel);

		Plugin surefire = new Plugin();
		surefire.setGroupId("org.apache.maven.plugins");
		surefire.setArtifactId("maven-surefire-plugin");
		surefire.setConfiguration(config);
		when(project.getBuildPlugins()).thenReturn(List.of(surefire));

		MojoExecutionException ex = assertThrows(MojoExecutionException.class, () -> mojo.execute());
		assertTrue(ex.getMessage().contains("not supported in learn mode"),
				"Should reject class-level parallel in learn mode, got: " + ex.getMessage());
	}

	private static Plugin testOrderPlugin(boolean extensions) {
		Plugin p = new Plugin();
		p.setGroupId("me.bechberger");
		p.setArtifactId("test-order-maven-plugin");
		p.setExtensions(extensions);
		return p;
	}

	@Test
	void missingExtensionsTrueLogsWarning() throws Exception {
		// Plugin declared WITHOUT <extensions>true</extensions> and no session signal
		when(project.getBuildPlugins()).thenReturn(List.of(testOrderPlugin(false)));

		Properties userProps = new Properties(); // no extensionActive
		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);
		when(session.getGoals()).thenReturn(List.of("test"));
		when(session.getUserProperties()).thenReturn(userProps);
		inject(mojo, "session", session);

		List<String> warnings = new java.util.ArrayList<>();
		mojo.setLog(new org.apache.maven.plugin.logging.SystemStreamLog() {
			@Override
			public void warn(CharSequence msg) {
				warnings.add(msg.toString());
			}
		});

		// execute() will exit early (no index, no test classes) — that's fine,
		// the warning fires before that decision
		try {
			mojo.execute();
		} catch (Exception ignored) {
		}

		assertFalse(warnings.isEmpty(), "Expected a warning about missing <extensions>true</extensions>");
		assertTrue(warnings.stream().anyMatch(e -> e.contains("CONFIGURATION WARNING") && e.contains("extensions")),
				"Warning should mention CONFIGURATION WARNING and extensions, got: " + warnings);
	}

	@Test
	void extensionActiveSignalSuppressesWarning() throws Exception {
		when(project.getBuildPlugins()).thenReturn(List.of(testOrderPlugin(false)));

		Properties userProps = new Properties();
		userProps.setProperty("testorder.extensionActive", "true"); // extension loaded
		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);
		when(session.getGoals()).thenReturn(List.of("test"));
		when(session.getUserProperties()).thenReturn(userProps);
		inject(mojo, "session", session);

		List<String> warnings = new java.util.ArrayList<>();
		mojo.setLog(new org.apache.maven.plugin.logging.SystemStreamLog() {
			@Override
			public void warn(CharSequence msg) {
				warnings.add(msg.toString());
			}
		});

		try {
			mojo.execute();
		} catch (Exception ignored) {
		}

		assertTrue(warnings.stream().noneMatch(e -> e.contains("CONFIGURATION WARNING") && e.contains("extensions")),
				"No extensions warning expected when extension is active, got: " + warnings);
	}

	@Test
	void extensionsTrueInPluginSuppressesWarning() throws Exception {
		when(project.getBuildPlugins()).thenReturn(List.of(testOrderPlugin(true)));

		Properties userProps = new Properties(); // no extensionActive signal
		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);
		when(session.getGoals()).thenReturn(List.of("test"));
		when(session.getUserProperties()).thenReturn(userProps);
		inject(mojo, "session", session);

		List<String> warnings = new java.util.ArrayList<>();
		mojo.setLog(new org.apache.maven.plugin.logging.SystemStreamLog() {
			@Override
			public void warn(CharSequence msg) {
				warnings.add(msg.toString());
			}
		});

		try {
			mojo.execute();
		} catch (Exception ignored) {
		}

		assertTrue(warnings.stream().noneMatch(e -> e.contains("CONFIGURATION WARNING") && e.contains("extensions")),
				"No extensions warning expected when <extensions>true</extensions> is present, got: " + warnings);
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception {
		Class<?> clazz = target.getClass();
		while (clazz != null) {
			try {
				Field field = clazz.getDeclaredField(fieldName);
				field.setAccessible(true);
				field.set(target, value);
				return;
			} catch (NoSuchFieldException e) {
				clazz = clazz.getSuperclass();
			}
		}
		throw new NoSuchFieldException("Field not found in class hierarchy: " + fieldName);
	}
}
