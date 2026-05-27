package me.bechberger.testorder.maven;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Properties;

import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

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
	void warnSelectModeFilters_warnsForExcludes() {
		Xpp3Dom excludes = child("excludes", null);
		excludes.addChild(child("exclude", "**/Abstract*Test.java"));
		MavenProject project = projectWithSurefire(config(excludes));
		Log log = mockLog();

		SurefireHelper.warnSelectModeFilters(project, log);

		verify(log).warn(contains("excludes"));
	}

	@Test
	void warnSelectModeFilters_noWarningForEmptyExcludes() {
		// <excludes> element present but no children
		MavenProject project = projectWithSurefire(config(child("excludes", null)));
		Log log = mockLog();

		SurefireHelper.warnSelectModeFilters(project, log);

		verify(log, never()).warn(anyString());
	}
}
