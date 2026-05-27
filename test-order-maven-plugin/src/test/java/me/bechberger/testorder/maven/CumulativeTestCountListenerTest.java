package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CumulativeTestCountListenerTest {

	@Test
	void countTestcases_parsesTestcaseElements(@TempDir Path tmp) throws IOException {
		Path reports = tmp.resolve("surefire-reports");
		Files.createDirectories(reports);

		Files.writeString(
				reports.resolve("TEST-Foo.xml"), "<testsuite>\n" + "  <testcase name=\"a\"/>\n"
						+ "  <testcase name=\"b\"/>\n" + "  <testcase name=\"c\"/>\n" + "</testsuite>\n",
				StandardCharsets.UTF_8);
		Files.writeString(reports.resolve("TEST-Bar.xml"), "<testsuite><testcase name=\"x\"/></testsuite>\n",
				StandardCharsets.UTF_8);
		// Should NOT be counted (filename doesn't match pattern)
		Files.writeString(reports.resolve("not-a-test.xml"), "<testcase name=\"ignored\"/>", StandardCharsets.UTF_8);

		assertEquals(4, CumulativeTestCountListener.countTestcases(reports));
	}

	@Test
	void countTestcases_missingDir_returnsZero(@TempDir Path tmp) {
		assertEquals(0, CumulativeTestCountListener.countTestcases(tmp.resolve("nope")));
	}

	@Test
	void countTestcases_emptyDir_returnsZero(@TempDir Path tmp) throws IOException {
		Path reports = tmp.resolve("surefire-reports");
		Files.createDirectories(reports);
		assertEquals(0, CumulativeTestCountListener.countTestcases(reports));
	}

	@Test
	void countTestcases_malformedXml_returnsZeroForThatFile(@TempDir Path tmp) throws IOException {
		Path reports = tmp.resolve("surefire-reports");
		Files.createDirectories(reports);
		// Empty file → still 0
		Files.writeString(reports.resolve("TEST-Empty.xml"), "", StandardCharsets.UTF_8);
		// Garbage bytes → still 0 (not a testcase token)
		Files.writeString(reports.resolve("TEST-Garbage.xml"), "this is not xml at all", StandardCharsets.UTF_8);
		// Valid neighbour → counts as 2
		Files.writeString(reports.resolve("TEST-Ok.xml"),
				"<testsuite><testcase name=\"x\"/><testcase name=\"y\"/></testsuite>", StandardCharsets.UTF_8);
		assertEquals(2, CumulativeTestCountListener.countTestcases(reports));
	}

	@Test
	void countTestcases_selfClosingAndOpenTags_bothCounted(@TempDir Path tmp) throws IOException {
		Path reports = tmp.resolve("surefire-reports");
		Files.createDirectories(reports);
		Files.writeString(reports.resolve("TEST-Mixed.xml"),
				"<testsuite>\n" + "  <testcase name=\"a\"/>\n"
						+ "  <testcase name=\"b\"><failure>boom</failure></testcase>\n" + "</testsuite>\n",
				StandardCharsets.UTF_8);
		assertEquals(2, CumulativeTestCountListener.countTestcases(reports));
	}

	@Test
	void mojoSucceeded_nonSurefire_doesNotCount() throws IOException {
		ExecutionListener delegate = mock(ExecutionListener.class);
		CumulativeTestCountListener l = new CumulativeTestCountListener(delegate, 5);

		ExecutionEvent event = mock(ExecutionEvent.class);
		MojoExecution exec = mock(MojoExecution.class);
		when(exec.getGroupId()).thenReturn("org.apache.maven.plugins");
		when(exec.getArtifactId()).thenReturn("maven-compiler-plugin");
		when(exec.getGoal()).thenReturn("compile");
		when(event.getMojoExecution()).thenReturn(exec);

		l.mojoSucceeded(event);

		assertEquals(0, l.cumulative());
		assertFalse(l.tripped());
		verify(delegate, times(1)).mojoSucceeded(event);
	}

	@Test
	void mojoSucceeded_surefireBelowThreshold_countsButDoesNotTrip(@TempDir Path tmp) throws IOException {
		Path moduleDir = tmp.resolve("mod-a");
		Path reports = moduleDir.resolve("surefire-reports");
		Files.createDirectories(reports);
		Files.writeString(reports.resolve("TEST-A.xml"),
				"<testsuite><testcase name=\"a\"/><testcase name=\"b\"/></testsuite>", StandardCharsets.UTF_8);

		ExecutionListener delegate = mock(ExecutionListener.class);
		CumulativeTestCountListener l = new CumulativeTestCountListener(delegate, 10);

		MavenProject project = surefireProject(moduleDir);
		ExecutionEvent event = surefireEvent(project, mock(MavenSession.class));

		l.mojoSucceeded(event);

		assertEquals(2, l.cumulative());
		assertFalse(l.tripped());
		verify(delegate, times(1)).mojoSucceeded(event);
	}

	@Test
	void mojoSucceeded_threshold_trippedAndRemainingProjectsSkipped(@TempDir Path tmp) throws IOException {
		Path modA = tmp.resolve("mod-a");
		Files.createDirectories(modA.resolve("surefire-reports"));
		Files.writeString(modA.resolve("surefire-reports/TEST-A.xml"),
				"<testsuite><testcase name=\"1\"/><testcase name=\"2\"/><testcase name=\"3\"/></testsuite>",
				StandardCharsets.UTF_8);

		MavenProject pA = surefireProject(modA);
		MavenProject pB = surefireProject(tmp.resolve("mod-b"));
		MavenProject pC = surefireProject(tmp.resolve("mod-c"));

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(pA, pB, pC));

		ExecutionListener delegate = mock(ExecutionListener.class);
		CumulativeTestCountListener l = new CumulativeTestCountListener(delegate, 3);

		l.mojoSucceeded(surefireEvent(pA, session));

		assertEquals(3, l.cumulative());
		assertTrue(l.tripped());
		assertNull(pA.getProperties().getProperty("skipTests"), "the project that just finished must not be skipped");
		assertEquals("true", pB.getProperties().getProperty("skipTests"));
		assertEquals("true", pC.getProperties().getProperty("skipTests"));
	}

	@Test
	void mojoSucceeded_afterTripped_doesNothing(@TempDir Path tmp) throws IOException {
		Path modA = tmp.resolve("mod-a");
		Files.createDirectories(modA.resolve("surefire-reports"));
		Files.writeString(modA.resolve("surefire-reports/TEST-A.xml"),
				"<testsuite><testcase name=\"1\"/><testcase name=\"2\"/></testsuite>", StandardCharsets.UTF_8);

		Path modB = tmp.resolve("mod-b");
		Files.createDirectories(modB.resolve("surefire-reports"));
		Files.writeString(modB.resolve("surefire-reports/TEST-B.xml"), "<testsuite><testcase name=\"1\"/></testsuite>",
				StandardCharsets.UTF_8);

		MavenProject pA = surefireProject(modA);
		MavenProject pB = surefireProject(modB);

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(pA, pB));

		CumulativeTestCountListener l = new CumulativeTestCountListener(null, 2);

		l.mojoSucceeded(surefireEvent(pA, session));
		assertTrue(l.tripped());
		int afterFirst = l.cumulative();

		// Second call shouldn't increase the counter — early-exit short-circuits
		l.mojoSucceeded(surefireEvent(pB, session));
		assertEquals(afterFirst, l.cumulative(), "tripped listener must not keep counting");
	}

	@Test
	void mojoSucceeded_sameProjectTwice_countedOnlyOnce(@TempDir Path tmp) throws IOException {
		Path modA = tmp.resolve("mod-a");
		Files.createDirectories(modA.resolve("surefire-reports"));
		Files.writeString(modA.resolve("surefire-reports/TEST-A.xml"), "<testsuite><testcase name=\"1\"/></testsuite>",
				StandardCharsets.UTF_8);

		MavenProject pA = surefireProject(modA);
		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(pA));

		CumulativeTestCountListener l = new CumulativeTestCountListener(null, 100);

		l.mojoSucceeded(surefireEvent(pA, session));
		l.mojoSucceeded(surefireEvent(pA, session));

		assertEquals(1, l.cumulative(), "same project's tests must only count once");
	}

	@Test
	void nullDelegate_doesNotCrashOnAnyEvent() {
		CumulativeTestCountListener l = new CumulativeTestCountListener(null, 1);
		ExecutionEvent ev = mock(ExecutionEvent.class);
		// Each forwarder must tolerate null delegate
		assertDoesNotThrow(() -> {
			l.projectDiscoveryStarted(ev);
			l.sessionStarted(ev);
			l.sessionEnded(ev);
			l.projectStarted(ev);
			l.projectSucceeded(ev);
			l.projectFailed(ev);
			l.projectSkipped(ev);
			l.mojoStarted(ev);
			l.mojoFailed(ev);
			l.mojoSkipped(ev);
			l.forkStarted(ev);
			l.forkSucceeded(ev);
			l.forkFailed(ev);
			l.forkedProjectStarted(ev);
			l.forkedProjectSucceeded(ev);
			l.forkedProjectFailed(ev);
		});
	}

	@Test
	void delegate_receivesAllForwardedEvents() {
		ExecutionListener delegate = mock(ExecutionListener.class);
		CumulativeTestCountListener l = new CumulativeTestCountListener(delegate, 1);
		ExecutionEvent ev = mock(ExecutionEvent.class);

		l.projectDiscoveryStarted(ev);
		l.sessionStarted(ev);
		l.sessionEnded(ev);
		l.projectStarted(ev);
		l.projectSucceeded(ev);
		l.projectFailed(ev);
		l.projectSkipped(ev);
		l.mojoStarted(ev);
		l.mojoFailed(ev);
		l.mojoSkipped(ev);
		l.forkStarted(ev);
		l.forkSucceeded(ev);
		l.forkFailed(ev);
		l.forkedProjectStarted(ev);
		l.forkedProjectSucceeded(ev);
		l.forkedProjectFailed(ev);

		verify(delegate).projectDiscoveryStarted(ev);
		verify(delegate).sessionStarted(ev);
		verify(delegate).sessionEnded(ev);
		verify(delegate).projectStarted(ev);
		verify(delegate).projectSucceeded(ev);
		verify(delegate).projectFailed(ev);
		verify(delegate).projectSkipped(ev);
		verify(delegate).mojoStarted(ev);
		verify(delegate).mojoFailed(ev);
		verify(delegate).mojoSkipped(ev);
		verify(delegate).forkStarted(ev);
		verify(delegate).forkSucceeded(ev);
		verify(delegate).forkFailed(ev);
		verify(delegate).forkedProjectStarted(ev);
		verify(delegate).forkedProjectSucceeded(ev);
		verify(delegate).forkedProjectFailed(ev);
		// Crucially: mojoSucceeded was never invoked here
		verify(delegate, never()).mojoSucceeded(ev);
	}

	private static MavenProject surefireProject(Path moduleDir) {
		MavenProject p = mock(MavenProject.class);
		Build build = mock(Build.class);
		when(build.getDirectory()).thenReturn(moduleDir.toString());
		when(p.getBuild()).thenReturn(build);
		when(p.getProperties()).thenReturn(new Properties());
		return p;
	}

	private static ExecutionEvent surefireEvent(MavenProject project, MavenSession session) {
		ExecutionEvent event = mock(ExecutionEvent.class);
		MojoExecution exec = mock(MojoExecution.class);
		when(exec.getGroupId()).thenReturn("org.apache.maven.plugins");
		when(exec.getArtifactId()).thenReturn("maven-surefire-plugin");
		when(exec.getGoal()).thenReturn("test");
		when(event.getMojoExecution()).thenReturn(exec);
		when(event.getProject()).thenReturn(project);
		when(event.getSession()).thenReturn(session);
		return event;
	}
}
