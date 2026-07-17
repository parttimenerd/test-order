package me.bechberger.testorder.maven;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireHelperTest {

	@Test
	void acceptsNoParallelConfiguration() {
		MavenProject project = projectWithSurefire(config());

		assertThatCode(() -> SurefireHelper.validateNoClassLevelParallel(project, mockLog()))
				.doesNotThrowAnyException();
	}

	@Test
	void acceptsMethodLevelParallelInSurefire() {
		MavenProject project = projectWithSurefire(config(child("parallel", "methods")));

		assertThatCode(() -> SurefireHelper.validateNoClassLevelParallel(project, mockLog()))
				.doesNotThrowAnyException();
	}

	@Test
	void orderModeAllowsClassLevelParallelInSurefire() {
		MavenProject project = projectWithSurefire(config(child("parallel", "classesAndMethods")));

		assertThatCode(() -> SurefireHelper.validateNoClassLevelParallel(project, mockLog()))
				.doesNotThrowAnyException();
	}

	@Test
	void orderModeAllowsAllParallelInSurefire() {
		MavenProject project = projectWithSurefire(config(child("parallel", "all")));

		assertThatCode(() -> SurefireHelper.validateNoClassLevelParallel(project, mockLog()))
				.doesNotThrowAnyException();
	}

	@Test
	void orderModeAllowsJunitClassParallelInSystemPropertyVariables() {
		Xpp3Dom sysProps = child("systemPropertyVariables", null);
		sysProps.addChild(child("junit.jupiter.execution.parallel.mode.classes.default", "concurrent"));
		MavenProject project = projectWithSurefire(config(sysProps));

		assertThatCode(() -> SurefireHelper.validateNoClassLevelParallel(project, mockLog()))
				.doesNotThrowAnyException();
	}

	@Test
	void orderModeAllowsJunitClassParallelInConfigurationParameters() {
		Xpp3Dom props = child("properties", null);
		props.addChild(child("configurationParameters", "junit.jupiter.execution.parallel.enabled=true\n"
				+ "junit.jupiter.execution.parallel.mode.classes.default=concurrent\n"));
		MavenProject project = projectWithSurefire(config(props));

		assertThatCode(() -> SurefireHelper.validateNoClassLevelParallel(project, mockLog()))
				.doesNotThrowAnyException();
	}

	@Test
	void learnModeRejectsClassLevelParallelInSurefire() {
		MavenProject project = projectWithSurefire(config(child("parallel", "classesAndMethods")));

		assertThatThrownBy(() -> SurefireHelper.rejectClassLevelParallelForLearn(project, mockLog()))
				.isInstanceOf(MojoExecutionException.class).hasMessageContaining("not supported in learn mode");
	}

	@Test
	void learnModeRejectsAllParallelInSurefire() {
		MavenProject project = projectWithSurefire(config(child("parallel", "all")));

		assertThatThrownBy(() -> SurefireHelper.rejectClassLevelParallelForLearn(project, mockLog()))
				.isInstanceOf(MojoExecutionException.class).hasMessageContaining("not supported in learn mode");
	}

	@Test
	void learnModeRejectsJunitClassParallelInSystemPropertyVariables() {
		Xpp3Dom sysProps = child("systemPropertyVariables", null);
		sysProps.addChild(child("junit.jupiter.execution.parallel.mode.classes.default", "concurrent"));
		MavenProject project = projectWithSurefire(config(sysProps));

		assertThatThrownBy(() -> SurefireHelper.rejectClassLevelParallelForLearn(project, mockLog()))
				.isInstanceOf(MojoExecutionException.class).hasMessageContaining("not supported in learn mode");
	}

	@Test
	void learnModeRejectsJunitClassParallelInConfigurationParameters() {
		Xpp3Dom props = child("properties", null);
		props.addChild(child("configurationParameters", "junit.jupiter.execution.parallel.enabled=true\n"
				+ "junit.jupiter.execution.parallel.mode.classes.default=concurrent\n"));
		MavenProject project = projectWithSurefire(config(props));

		assertThatThrownBy(() -> SurefireHelper.rejectClassLevelParallelForLearn(project, mockLog()))
				.isInstanceOf(MojoExecutionException.class).hasMessageContaining("not supported in learn mode");
	}

	@Test
	void learnModeAcceptsMethodParallelOnly() {
		MavenProject project = projectWithSurefire(config(child("parallel", "methods")));

		assertThatCode(() -> SurefireHelper.rejectClassLevelParallelForLearn(project, mockLog()))
				.doesNotThrowAnyException();
	}

	@Test
	void acceptsJunitMethodParallelOnly() {
		Xpp3Dom props = child("properties", null);
		props.addChild(child("configurationParameters",
				"junit.jupiter.execution.parallel.enabled=true\n"
						+ "junit.jupiter.execution.parallel.mode.default=concurrent\n"
						+ "junit.jupiter.execution.parallel.mode.classes.default=same_thread\n"));
		MavenProject project = projectWithSurefire(config(props));

		assertThatCode(() -> SurefireHelper.validateNoClassLevelParallel(project, mockLog()))
				.doesNotThrowAnyException();
	}

	private static MavenProject projectWithSurefire(Xpp3Dom configuration) {
		MavenProject project = new MavenProject();
		Build build = new Build();
		project.setBuild(build);

		Plugin surefire = new Plugin();
		surefire.setGroupId("org.apache.maven.plugins");
		surefire.setArtifactId("maven-surefire-plugin");
		surefire.setConfiguration(configuration);
		build.addPlugin(surefire);

		return project;
	}

	private static Xpp3Dom config(Xpp3Dom... children) {
		Xpp3Dom c = new Xpp3Dom("configuration");
		for (Xpp3Dom child : children) {
			c.addChild(child);
		}
		return c;
	}

	private static Xpp3Dom child(String name, String value) {
		Xpp3Dom c = new Xpp3Dom(name);
		if (value != null) {
			c.setValue(value);
		}
		return c;
	}

	private static Log mockLog() {
		return mock(Log.class);
	}

	// ═══════════════════════════════════════════════════════════════════
	// Regression: configureIncludes sets test property correctly
	// (BUG_REPORT_2 #6/#7: select/run-remaining don't filter)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void configureIncludesSetsTestProperty() throws MojoExecutionException {
		MavenProject project = projectWithSurefire(config());

		SurefireHelper.configureIncludes(project, List.of("com.test.FooTest", "com.test.BarTest"), true);

		String testProp = project.getProperties().getProperty("test");
		assertNotNull(testProp);
		assertTrue(testProp.contains("com.test.FooTest"));
		assertTrue(testProp.contains("com.test.BarTest"));
	}

	@Test
	void configureIncludesClearExistingReplacesOldValue() throws MojoExecutionException {
		MavenProject project = projectWithSurefire(config());
		project.getProperties().setProperty("test", "com.test.OldTest");

		SurefireHelper.configureIncludes(project, List.of("com.test.NewTest"), true);

		String testProp = project.getProperties().getProperty("test");
		assertEquals("com.test.NewTest", testProp, "clearExisting=true should replace the old test property");
	}

	@Test
	void configureIncludesAppendPreservesExisting() throws MojoExecutionException {
		MavenProject project = projectWithSurefire(config());
		project.getProperties().setProperty("test", "com.test.OldTest");

		SurefireHelper.configureIncludes(project, List.of("com.test.NewTest"), false);

		String testProp = project.getProperties().getProperty("test");
		assertTrue(testProp.contains("com.test.OldTest"), "Should preserve old value");
		assertTrue(testProp.contains("com.test.NewTest"), "Should include new value");
	}

	@Test
	void configureIncludesEmptyListNoOp() throws MojoExecutionException {
		MavenProject project = projectWithSurefire(config());
		project.getProperties().setProperty("test", "com.test.OldTest");

		SurefireHelper.configureIncludes(project, List.of(), true);

		// Empty list should be a no-op (not clear the existing value)
		assertEquals("com.test.OldTest", project.getProperties().getProperty("test"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// isHardcodedArgLine — detects argLines that need direct injection
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void isHardcodedArgLine_nullAndBlankReturnFalse() {
		assertFalse(SurefireHelper.isHardcodedArgLine(null));
		assertFalse(SurefireHelper.isHardcodedArgLine(""));
		assertFalse(SurefireHelper.isHardcodedArgLine("   "));
	}

	@Test
	void isHardcodedArgLine_trueForLiteralJvmFlags() {
		assertTrue(SurefireHelper.isHardcodedArgLine("--add-opens java.base/java.lang=ALL-UNNAMED"));
		assertTrue(SurefireHelper.isHardcodedArgLine("-Xmx512m"));
	}

	@Test
	void isHardcodedArgLine_falseWhenDollarPlaceholderPresent() {
		assertFalse(SurefireHelper.isHardcodedArgLine("-Xmx512m ${argLine}"));
		assertFalse(SurefireHelper.isHardcodedArgLine("${argLine}"));
	}

	@Test
	void isHardcodedArgLine_falseWhenAtPlaceholderPresent() {
		assertFalse(SurefireHelper.isHardcodedArgLine("--add-opens java.base/java.lang=ALL-UNNAMED @{argLine}"));
		assertFalse(SurefireHelper.isHardcodedArgLine("@{argLine}"));
	}

	@Test
	void isHardcodedArgLine_falseWhenOtherPropertyPlaceholderPresent() {
		assertFalse(SurefireHelper.isHardcodedArgLine("${jacoco.agent.argLine}"));
		assertFalse(SurefireHelper.isHardcodedArgLine("-Xmx512m ${jacoco.agent.argLine}"));
		assertFalse(SurefireHelper.isHardcodedArgLine("@{failsafe.argLine}"));
	}

	@Test
	void isHardcodedArgLine_falseWhenAgentAlreadyPresent() {
		assertFalse(SurefireHelper.isHardcodedArgLine("-javaagent:/some/path/test-order-agent.jar=mode=FULL"));
	}

	@Test
	void configureIncludesAlsoSetsSurefireXmlConfig() throws MojoExecutionException {
		MavenProject project = projectWithSurefire(config());

		SurefireHelper.configureIncludes(project, List.of("com.test.X"), true);

		// Verify the Surefire plugin XML configuration was also updated
		Plugin surefire = SurefireHelper.findSurefirePlugin(project);
		Xpp3Dom dom = (Xpp3Dom) surefire.getConfiguration();
		Xpp3Dom testChild = dom.getChild("test");
		assertNotNull(testChild);
		assertEquals("com.test.X", testChild.getValue());
	}

	// ═══════════════════════════════════════════════════════════════════
	// M4: warnListenerDeactivation
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void warnListenerDeactivation_wildcard() throws MojoExecutionException {
		Xpp3Dom sysProps = child("systemPropertyVariables", null);
		sysProps.addChild(child("junit.platform.execution.listeners.deactivate", "*"));
		MavenProject project = projectWithSurefire(config(sysProps));
		Log log = mockLog();

		SurefireHelper.warnListenerDeactivation(project, log);

		verify(log).warn(contains("TelemetryListener"));
	}

	@Test
	void warnListenerDeactivation_noDeactivation() throws MojoExecutionException {
		MavenProject project = projectWithSurefire(config());
		Log log = mockLog();

		SurefireHelper.warnListenerDeactivation(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnListenerDeactivation_inConfigParams() throws MojoExecutionException {
		Xpp3Dom props = child("properties", null);
		props.addChild(child("configurationParameters",
				"junit.platform.execution.listeners.deactivate=me.bechberger.testorder.*\n"));
		MavenProject project = projectWithSurefire(config(props));
		Log log = mockLog();

		SurefireHelper.warnListenerDeactivation(project, log);

		verify(log).warn(contains("TelemetryListener"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// M12/M20: warnConflictingOrderers
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void warnConflictingOrderers_competingClassOrderer() throws MojoExecutionException {
		Xpp3Dom sysProps = child("systemPropertyVariables", null);
		sysProps.addChild(child("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer$Random"));
		MavenProject project = projectWithSurefire(config(sysProps));
		Log log = mockLog();

		SurefireHelper.warnConflictingOrderers(project, log);

		verify(log).warn(contains("competing ClassOrderer"));
	}

	@Test
	void warnConflictingOrderers_competingMethodOrderer() throws MojoExecutionException {
		Xpp3Dom sysProps = child("systemPropertyVariables", null);
		sysProps.addChild(
				child("junit.jupiter.testmethod.order.default", "org.junit.jupiter.api.MethodOrderer$Random"));
		MavenProject project = projectWithSurefire(config(sysProps));
		Log log = mockLog();

		SurefireHelper.warnConflictingOrderers(project, log);

		verify(log).warn(contains("global MethodOrderer"));
	}

	@Test
	void warnConflictingOrderers_noConflict() throws MojoExecutionException {
		MavenProject project = projectWithSurefire(config());
		Log log = mockLog();

		SurefireHelper.warnConflictingOrderers(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnConflictingOrderers_ownOrdererAccepted() throws MojoExecutionException {
		Xpp3Dom sysProps = child("systemPropertyVariables", null);
		sysProps.addChild(
				child("junit.jupiter.testclass.order.default", "me.bechberger.testorder.junit.PriorityClassOrderer"));
		MavenProject project = projectWithSurefire(config(sysProps));
		Log log = mockLog();

		SurefireHelper.warnConflictingOrderers(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnConflictingOrderers_inConfigParams() throws MojoExecutionException {
		Xpp3Dom props = child("properties", null);
		props.addChild(child("configurationParameters",
				"junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$ClassName\n"));
		MavenProject project = projectWithSurefire(config(props));
		Log log = mockLog();

		SurefireHelper.warnConflictingOrderers(project, log);

		verify(log).warn(contains("competing ClassOrderer"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// M24: Vintage parallel check in learnMode
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void learnModeRejectsVintageParallelInConfigParams() {
		Xpp3Dom props = child("properties", null);
		props.addChild(child("configurationParameters", "junit.vintage.execution.parallel.enabled=true\n"));
		MavenProject project = projectWithSurefire(config(props));

		assertThatThrownBy(() -> SurefireHelper.rejectClassLevelParallelForLearn(project, mockLog()))
				.isInstanceOf(MojoExecutionException.class).hasMessageContaining("Vintage");
	}

	@Test
	void learnModeAcceptsDisabledVintageParallel() {
		Xpp3Dom props = child("properties", null);
		props.addChild(child("configurationParameters", "junit.vintage.execution.parallel.enabled=false\n"));
		MavenProject project = projectWithSurefire(config(props));

		assertThatCode(() -> SurefireHelper.rejectClassLevelParallelForLearn(project, mockLog()))
				.doesNotThrowAnyException();
	}

	// ═══════════════════════════════════════════════════════════════════
	// C1: Order-mode parallel warning (warns but does not throw)
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void orderModeWarnsAboutJupiterClassParallel() throws MojoExecutionException {
		Xpp3Dom sysProps = child("systemPropertyVariables", null);
		sysProps.addChild(child("junit.jupiter.execution.parallel.enabled", "true"));
		sysProps.addChild(child("junit.jupiter.execution.parallel.mode.classes.default", "concurrent"));
		MavenProject project = projectWithSurefire(config(sysProps));
		Log log = mockLog();

		SurefireHelper.validateNoClassLevelParallel(project, log);

		verify(log).warn(contains("ordering guarantees"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// extractAdditionalClasspathElements — MRJAR classpath preservation
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void extractAdditionalClasspathElements_returnsEmptyWhenNoSurefire() {
		MavenProject project = new MavenProject();
		project.setBuild(new Build());

		assertTrue(SurefireHelper.extractAdditionalClasspathElements(project).isEmpty());
	}

	@Test
	void extractAdditionalClasspathElements_returnsEmptyWhenNoConfig() {
		MavenProject project = projectWithSurefire(config());

		assertTrue(SurefireHelper.extractAdditionalClasspathElements(project).isEmpty());
	}

	@Test
	void extractAdditionalClasspathElements_returnsSingleEntry() {
		Xpp3Dom elements = child("additionalClasspathElements", null);
		elements.addChild(child("additionalClasspathElement", "/some/path/META-INF/versions/11"));
		MavenProject project = projectWithSurefire(config(elements));

		List<String> result = SurefireHelper.extractAdditionalClasspathElements(project);

		assertEquals(1, result.size());
		assertEquals("/some/path/META-INF/versions/11", result.get(0));
	}

	@Test
	void extractAdditionalClasspathElements_returnsMultipleEntries() {
		Xpp3Dom elements = child("additionalClasspathElements", null);
		elements.addChild(child("additionalClasspathElement", "/path/a"));
		elements.addChild(child("additionalClasspathElement", "/path/b"));
		MavenProject project = projectWithSurefire(config(elements));

		List<String> result = SurefireHelper.extractAdditionalClasspathElements(project);

		assertEquals(2, result.size());
		assertEquals("/path/a", result.get(0));
		assertEquals("/path/b", result.get(1));
	}

	@Test
	void extractAdditionalClasspathElements_skipsBlankEntries() {
		Xpp3Dom elements = child("additionalClasspathElements", null);
		elements.addChild(child("additionalClasspathElement", "/path/a"));
		elements.addChild(child("additionalClasspathElement", "  "));
		elements.addChild(child("additionalClasspathElement", "/path/b"));
		MavenProject project = projectWithSurefire(config(elements));

		List<String> result = SurefireHelper.extractAdditionalClasspathElements(project);

		assertEquals(2, result.size());
		assertEquals("/path/a", result.get(0));
		assertEquals("/path/b", result.get(1));
	}

	// ═══════════════════════════════════════════════════════════════════
	// warnConflictingRunOrder — detects runOrder conflicts
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void warnConflictingRunOrder_noWarningForDefault() {
		MavenProject project = projectWithSurefire(config());
		Log log = mockLog();

		SurefireHelper.warnConflictingRunOrder(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnConflictingRunOrder_noWarningForFilesystem() {
		MavenProject project = projectWithSurefire(config(child("runOrder", "filesystem")));
		Log log = mockLog();

		SurefireHelper.warnConflictingRunOrder(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnConflictingRunOrder_warnsForRandom() {
		MavenProject project = projectWithSurefire(config(child("runOrder", "random")));
		Log log = mockLog();

		SurefireHelper.warnConflictingRunOrder(project, log);

		verify(log).warn(contains("runOrder"));
	}

	@Test
	void warnConflictingRunOrder_warnsForFailedFirst() {
		MavenProject project = projectWithSurefire(config(child("runOrder", "failedfirst")));
		Log log = mockLog();

		SurefireHelper.warnConflictingRunOrder(project, log);

		verify(log).warn(contains("runOrder"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// warnForkCountInLearnMode — multi-fork learn corruption
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void warnForkCount_noWarningForDefault() {
		MavenProject project = projectWithSurefire(config());
		Log log = mockLog();

		SurefireHelper.warnForkCountInLearnMode(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnForkCount_noWarningForOne() {
		MavenProject project = projectWithSurefire(config(child("forkCount", "1")));
		Log log = mockLog();

		SurefireHelper.warnForkCountInLearnMode(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnForkCount_warnsForMultipleForks() {
		MavenProject project = projectWithSurefire(config(child("forkCount", "4")));
		Log log = mockLog();

		SurefireHelper.warnForkCountInLearnMode(project, log);

		verify(log).debug(contains("forkCount"));
	}

	@Test
	void warnForkCount_warnsForCoreMultiplied() {
		MavenProject project = projectWithSurefire(config(child("forkCount", "1.5C")));
		Log log = mockLog();

		SurefireHelper.warnForkCountInLearnMode(project, log);

		verify(log).debug(contains("forkCount"));
	}

	@Test
	void warnForkCountOrderMode_warnsForMultipleForks() {
		MavenProject project = projectWithSurefire(config(child("forkCount", "2")));
		Log log = mockLog();

		SurefireHelper.warnForkCountInOrderMode(project, log);

		verify(log).warn(contains("forkCount"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// warnReuseForksFalseInLearnMode
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void warnReuseForksFalse_noWarningForDefault() {
		MavenProject project = projectWithSurefire(config());
		Log log = mockLog();

		SurefireHelper.warnReuseForksFalseInLearnMode(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnReuseForksFalse_noWarningForTrue() {
		MavenProject project = projectWithSurefire(config(child("reuseForks", "true")));
		Log log = mockLog();

		SurefireHelper.warnReuseForksFalseInLearnMode(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnReuseForksFalse_warnsForFalse() {
		MavenProject project = projectWithSurefire(config(child("reuseForks", "false")));
		Log log = mockLog();

		SurefireHelper.warnReuseForksFalseInLearnMode(project, log);

		verify(log).debug(contains("reuseForks"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// warnRerunFailingTestsInLearnMode
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void warnRerunFailingTests_noWarningForDefault() {
		MavenProject project = projectWithSurefire(config());
		Log log = mockLog();

		SurefireHelper.warnRerunFailingTestsInLearnMode(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnRerunFailingTests_noWarningForZero() {
		MavenProject project = projectWithSurefire(config(child("rerunFailingTestsCount", "0")));
		Log log = mockLog();

		SurefireHelper.warnRerunFailingTestsInLearnMode(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnRerunFailingTests_warnsForPositive() {
		MavenProject project = projectWithSurefire(config(child("rerunFailingTestsCount", "3")));
		Log log = mockLog();

		SurefireHelper.warnRerunFailingTestsInLearnMode(project, log);

		verify(log).warn(contains("rerunFailingTestsCount"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// forceClasspathModeIfNeeded — JPMS module path
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void forceClasspathMode_noActionWhenNoModuleInfo() {
		MavenProject project = projectWithSurefire(config());
		project.getModel().getBuild().setDirectory("/tmp/nonexistent-project/target");
		project.setFile(new java.io.File("/tmp/nonexistent-project/pom.xml"));
		Log log = mockLog();

		SurefireHelper.forceClasspathModeIfNeeded(project, log);

		// Should not modify config when no module-info.java exists
		Xpp3Dom config = (Xpp3Dom) SurefireHelper.findSurefirePlugin(project).getConfiguration();
		assertNull(config.getChild("useModulePath"));
	}

	@Test
	void forceClasspathMode_noActionWhenAlreadyFalse() {
		MavenProject project = projectWithSurefire(config(child("useModulePath", "false")));
		Log log = mockLog();

		SurefireHelper.forceClasspathModeIfNeeded(project, log);

		// Should not change if already false
		verify(log, never()).info(contains("JPMS"));
	}

	// ═══════════════════════════════════════════════════════════════════
	// warnSelectModeFilters — groups/excludes in select mode
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void warnSelectModeFilters_noWarningForDefault() {
		MavenProject project = projectWithSurefire(config());
		Log log = mockLog();

		SurefireHelper.warnSelectModeFilters(project, log);

		verify(log, never()).warn(anyString());
	}

	@Test
	void warnSelectModeFilters_warnsForGroups() {
		MavenProject project = projectWithSurefire(config(child("groups", "slow")));
		Log log = mockLog();

		SurefireHelper.warnSelectModeFilters(project, log);

		verify(log).warn(contains("groups"));
	}

	@Test
	void warnSelectModeFilters_warnsForExcludedGroups() {
		MavenProject project = projectWithSurefire(config(child("excludedGroups", "integration")));
		Log log = mockLog();

		SurefireHelper.warnSelectModeFilters(project, log);

		verify(log).warn(contains("excludedGroups"));
	}

	@Test
	void warnSelectModeFilters_infoForExcludesHonored() {
		// BUG-168: test-order now HONORS file-based <excludes> (drops matching classes
		// from the -Dtest selection) rather than overriding them, so the message is an
		// informational note, not a warning.
		Xpp3Dom excludes = child("excludes", null);
		excludes.addChild(child("exclude", "**/Abstract*Test.java"));
		MavenProject project = projectWithSurefire(config(excludes));
		Log log = mockLog();

		SurefireHelper.warnSelectModeFilters(project, log);

		verify(log).info(contains("excludes"));
		verify(log, never()).warn(contains("excludes"));
	}

	@Test
	void warnSelectModeFilters_noWarningForEmptyExcludes() {
		// <excludes> element present but no children
		MavenProject project = projectWithSurefire(config(child("excludes", null)));
		Log log = mockLog();

		SurefireHelper.warnSelectModeFilters(project, log);

		verify(log, never()).warn(anyString());
	}

	// ═══════════════════════════════════════════════════════════════════
	// forceSingleForkForOrdering — ensures PriorityClassOrderer can reorder
	// the selected classes within one JVM. forkCount>1 or reuseForks=false
	// would split classes across JVMs and defeat the ordering.
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void forceSingleForkForOrdering_overridesForkCountAndReuseForks() throws Exception {
		MavenProject project = projectWithSurefire(config(child("forkCount", "4"), child("reuseForks", "false")));
		Log log = mockLog();

		SurefireHelper.forceSingleForkForOrdering(project, log);

		Xpp3Dom config = (Xpp3Dom) SurefireHelper.findSurefirePlugin(project).getConfiguration();
		assertEquals("1", config.getChild("forkCount").getValue());
		assertEquals("true", config.getChild("reuseForks").getValue());
		verify(log).info(contains("forkCount=4→1"));
		verify(log).info(contains("reuseForks=false→true"));
	}

	@Test
	void forceSingleForkForOrdering_setsValuesWhenUnset() throws Exception {
		MavenProject project = projectWithSurefire(config());
		Log log = mockLog();

		SurefireHelper.forceSingleForkForOrdering(project, log);

		Xpp3Dom config = (Xpp3Dom) SurefireHelper.findSurefirePlugin(project).getConfiguration();
		assertEquals("1", config.getChild("forkCount").getValue());
		assertEquals("true", config.getChild("reuseForks").getValue());
	}

	@Test
	void forceSingleForkForOrdering_silentWhenAlreadyCompliant() throws Exception {
		MavenProject project = projectWithSurefire(config(child("forkCount", "1"), child("reuseForks", "true")));
		Log log = mockLog();

		SurefireHelper.forceSingleForkForOrdering(project, log);

		// No info log when nothing changed.
		verify(log, never()).info(anyString());
	}

	@Test
	void forceSingleForkForOrdering_respectsPreserveFlagViaProjectProperty() throws Exception {
		MavenProject project = projectWithSurefire(config(child("forkCount", "4"), child("reuseForks", "false")));
		project.getProperties().setProperty("testorder.affected.preserveForkConfig", "true");
		Log log = mockLog();

		SurefireHelper.forceSingleForkForOrdering(project, log);

		// Config left untouched.
		Xpp3Dom config = (Xpp3Dom) SurefireHelper.findSurefirePlugin(project).getConfiguration();
		assertEquals("4", config.getChild("forkCount").getValue());
		assertEquals("false", config.getChild("reuseForks").getValue());
		verify(log).info(contains("preserveForkConfig=true"));
	}

	@Test
	void forceSingleForkForOrdering_respectsPreserveFlagViaSystemProperty() throws Exception {
		MavenProject project = projectWithSurefire(config(child("forkCount", "4")));
		Log log = mockLog();

		System.setProperty("testorder.affected.preserveForkConfig", "true");
		try {
			SurefireHelper.forceSingleForkForOrdering(project, log);
		} finally {
			System.clearProperty("testorder.affected.preserveForkConfig");
		}

		Xpp3Dom config = (Xpp3Dom) SurefireHelper.findSurefirePlugin(project).getConfiguration();
		assertEquals("4", config.getChild("forkCount").getValue());
	}

	@Test
	void forceSingleForkForOrdering_throwsWhenSurefireMissing() {
		MavenProject project = new MavenProject();
		project.setBuild(new Build());
		Log log = mockLog();

		assertThrows(MojoExecutionException.class, () -> SurefireHelper.forceSingleForkForOrdering(project, log));
	}

	// ═══════════════════════════════════════════════════════════════════
	// buildJpmsAddReadsForProject — JPMS --add-reads injection
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void buildJpmsAddReads_returnsEmptyWhenNoModuleInfo(@TempDir Path tempDir) {
		MavenProject project = projectWithSurefire(config());
		project.setFile(tempDir.resolve("pom.xml").toFile());

		String result = SurefireHelper.buildJpmsAddReadsForProject(project, mockLog());

		assertEquals("", result, "No --add-reads when no module-info.java exists");
	}

	@Test
	void buildJpmsAddReads_returnsEmptyWhenModuleInfoButNoAddOpens(@TempDir Path tempDir) throws IOException {
		Files.createDirectories(tempDir.resolve("src/main/java"));
		Files.writeString(tempDir.resolve("src/main/java/module-info.java"), "module foo {}");
		MavenProject project = projectWithSurefire(config());
		project.setFile(tempDir.resolve("pom.xml").toFile());

		String result = SurefireHelper.buildJpmsAddReadsForProject(project, mockLog());

		assertEquals("", result, "No --add-reads when argLine has no --add-opens/--add-exports");
	}

	@Test
	void buildJpmsAddReads_injectsAddReadsForNamedModuleInXmlArgLine(@TempDir Path tempDir) throws IOException {
		Files.createDirectories(tempDir.resolve("src/test/java"));
		Files.writeString(tempDir.resolve("src/test/java/module-info.java"), "open module foo.test {}");
		String xmlArgLine = "--add-opens tools.jackson.core/com.fasterxml.jackson.core=tools.jackson.core.unittest"
				+ " --add-opens tools.jackson.core/com.fasterxml.jackson.core.io=tools.jackson.core.unittest";
		MavenProject project = projectWithSurefire(config(child("argLine", xmlArgLine)));
		project.setFile(tempDir.resolve("pom.xml").toFile());

		String result = SurefireHelper.buildJpmsAddReadsForProject(project, mockLog());

		assertFalse(result.isEmpty(), "Should inject --add-reads");
		assertTrue(result.contains("--add-reads tools.jackson.core.unittest=ALL-UNNAMED"),
				"Should add ALL-UNNAMED read for the named module");
		assertTrue(result.contains("--add-reads tools.jackson.core.unittest=test.order.runtime"),
				"Should add test.order.runtime read for the named module");
	}

	@Test
	void buildJpmsAddReads_deduplicatesMultipleSameModule(@TempDir Path tempDir) throws IOException {
		Files.createDirectories(tempDir.resolve("src/main/java"));
		Files.writeString(tempDir.resolve("src/main/java/module-info.java"), "module foo {}");
		String xmlArgLine = "--add-opens tools.jackson.core/pkg1=tools.jackson.core.unittest"
				+ " --add-opens tools.jackson.core/pkg2=tools.jackson.core.unittest";
		MavenProject project = projectWithSurefire(config(child("argLine", xmlArgLine)));
		project.setFile(tempDir.resolve("pom.xml").toFile());

		String result = SurefireHelper.buildJpmsAddReadsForProject(project, mockLog());

		long count = countOccurrences(result, "--add-reads tools.jackson.core.unittest=ALL-UNNAMED");
		assertEquals(1, count, "Duplicate module name should only appear once");
	}

	@Test
	void buildJpmsAddReads_skipsAllUnnamed(@TempDir Path tempDir) throws IOException {
		Files.createDirectories(tempDir.resolve("src/main/java"));
		Files.writeString(tempDir.resolve("src/main/java/module-info.java"), "module foo {}");
		String xmlArgLine = "--add-opens java.base/java.lang=ALL-UNNAMED";
		MavenProject project = projectWithSurefire(config(child("argLine", xmlArgLine)));
		project.setFile(tempDir.resolve("pom.xml").toFile());

		String result = SurefireHelper.buildJpmsAddReadsForProject(project, mockLog());

		assertEquals("", result, "ALL-UNNAMED target should not generate --add-reads");
	}

	@Test
	void buildJpmsAddReads_readsArgLineFromMavenProperty(@TempDir Path tempDir) throws IOException {
		Files.createDirectories(tempDir.resolve("src/main/java"));
		Files.writeString(tempDir.resolve("src/main/java/module-info.java"), "module foo {}");
		MavenProject project = projectWithSurefire(config());
		project.setFile(tempDir.resolve("pom.xml").toFile());
		project.getProperties().setProperty("argLine",
				"--add-opens tools.jackson.core/com.fasterxml=tools.jackson.core.unittest");

		String result = SurefireHelper.buildJpmsAddReadsForProject(project, mockLog());

		assertTrue(result.contains("--add-reads tools.jackson.core.unittest=ALL-UNNAMED"),
				"Should pick up module name from Maven argLine property");
	}

	@Test
	void buildJpmsAddReads_combinesXmlAndPropertyArgLines(@TempDir Path tempDir) throws IOException {
		Files.createDirectories(tempDir.resolve("src/main/java"));
		Files.writeString(tempDir.resolve("src/main/java/module-info.java"), "module foo {}");
		String xmlArgLine = "--add-opens tools.jackson.core/pkg=module.from.xml";
		MavenProject project = projectWithSurefire(config(child("argLine", xmlArgLine)));
		project.setFile(tempDir.resolve("pom.xml").toFile());
		project.getProperties().setProperty("argLine", "--add-exports tools.jackson.core/pkg2=module.from.property");

		String result = SurefireHelper.buildJpmsAddReadsForProject(project, mockLog());

		assertTrue(result.contains("--add-reads module.from.xml=ALL-UNNAMED"),
				"Should include module from XML argLine");
		assertTrue(result.contains("--add-reads module.from.property=ALL-UNNAMED"),
				"Should include module from Maven property");
	}

	@Test
	void buildJpmsAddReads_addExportsAlsoExtracted(@TempDir Path tempDir) throws IOException {
		Files.createDirectories(tempDir.resolve("src/test/java"));
		Files.writeString(tempDir.resolve("src/test/java/module-info.java"), "open module foo.test {}");
		String xmlArgLine = "--add-exports java.base/sun.security.x509=mytest.module";
		MavenProject project = projectWithSurefire(config(child("argLine", xmlArgLine)));
		project.setFile(tempDir.resolve("pom.xml").toFile());

		String result = SurefireHelper.buildJpmsAddReadsForProject(project, mockLog());

		assertTrue(result.contains("--add-reads mytest.module=ALL-UNNAMED"),
				"--add-exports target module should also get --add-reads");
	}

	// ═══════════════════════════════════════════════════════════════════
	// forceClasspathModeIfNeeded — extended --add-reads injection
	// ═══════════════════════════════════════════════════════════════════

	@Test
	void forceClasspathMode_injectsAddReadsWhenModuleInfoAndArgLineHasAddOpens(@TempDir Path tempDir)
			throws IOException {
		Files.createDirectories(tempDir.resolve("src/main/java"));
		Files.writeString(tempDir.resolve("src/main/java/module-info.java"), "module foo {}");
		String xmlArgLine = "--add-opens tools.jackson.core/com.fasterxml=tools.jackson.core.unittest";
		MavenProject project = projectWithSurefire(config(child("argLine", xmlArgLine)));
		project.setFile(tempDir.resolve("pom.xml").toFile());
		Log log = mockLog();

		SurefireHelper.forceClasspathModeIfNeeded(project, log);

		Plugin surefire = SurefireHelper.findSurefirePlugin(project);
		Xpp3Dom cfg = (Xpp3Dom) surefire.getConfiguration();
		assertEquals("false", cfg.getChild("useModulePath").getValue());
		String newArgLine = cfg.getChild("argLine").getValue();
		assertTrue(newArgLine.contains("--add-reads tools.jackson.core.unittest=ALL-UNNAMED"),
				"Should inject ALL-UNNAMED add-reads: " + newArgLine);
		assertTrue(newArgLine.contains("--add-reads tools.jackson.core.unittest=test.order.runtime"),
				"Should inject test.order.runtime add-reads: " + newArgLine);
	}

	@Test
	void forceClasspathMode_noAddReadsWhenArgLineOnlyHasAllUnnamed(@TempDir Path tempDir) throws IOException {
		Files.createDirectories(tempDir.resolve("src/main/java"));
		Files.writeString(tempDir.resolve("src/main/java/module-info.java"), "module foo {}");
		String xmlArgLine = "--add-opens java.base/java.lang=ALL-UNNAMED";
		MavenProject project = projectWithSurefire(config(child("argLine", xmlArgLine)));
		project.setFile(tempDir.resolve("pom.xml").toFile());
		Log log = mockLog();

		SurefireHelper.forceClasspathModeIfNeeded(project, log);

		Plugin surefire = SurefireHelper.findSurefirePlugin(project);
		Xpp3Dom cfg = (Xpp3Dom) surefire.getConfiguration();
		assertEquals("false", cfg.getChild("useModulePath").getValue(), "useModulePath still set to false");
		// argLine should not have been modified with --add-reads
		String newArgLine = cfg.getChild("argLine").getValue();
		assertFalse(newArgLine.contains("--add-reads"),
				"No --add-reads when only ALL-UNNAMED target present: " + newArgLine);
	}

	@Test
	void forceClasspathMode_doesNotDoubleInjectAddReads(@TempDir Path tempDir) throws IOException {
		Files.createDirectories(tempDir.resolve("src/main/java"));
		Files.writeString(tempDir.resolve("src/main/java/module-info.java"), "module foo {}");
		String xmlArgLine = "--add-opens a.module/pkg=target.module" + " --add-opens a.module/pkg2=target.module";
		MavenProject project = projectWithSurefire(config(child("argLine", xmlArgLine)));
		project.setFile(tempDir.resolve("pom.xml").toFile());

		SurefireHelper.forceClasspathModeIfNeeded(project, mockLog());

		Plugin surefire = SurefireHelper.findSurefirePlugin(project);
		Xpp3Dom cfg = (Xpp3Dom) surefire.getConfiguration();
		String newArgLine = cfg.getChild("argLine").getValue();
		long count = countOccurrences(newArgLine, "--add-reads target.module=ALL-UNNAMED");
		assertEquals(1, count, "Should not duplicate --add-reads for same module");
	}

	@Test
	void forceClasspathMode_setsSurefireUseModulePathProperty(@TempDir Path tempDir) throws IOException {
		Files.createDirectories(tempDir.resolve("src/main/java"));
		Files.writeString(tempDir.resolve("src/main/java/module-info.java"), "module foo {}");
		MavenProject project = projectWithSurefire(config());
		project.setFile(tempDir.resolve("pom.xml").toFile());

		SurefireHelper.forceClasspathModeIfNeeded(project, mockLog());

		assertEquals("false", project.getProperties().getProperty("surefire.useModulePath"),
				"surefire.useModulePath Maven property must be set so @{argLine} expansion "
						+ "works in Surefire 3.x module-path mode");
	}

	private static long countOccurrences(String text, String pattern) {
		long count = 0;
		int idx = 0;
		while ((idx = text.indexOf(pattern, idx)) != -1) {
			count++;
			idx += pattern.length();
		}
		return count;
	}
}
