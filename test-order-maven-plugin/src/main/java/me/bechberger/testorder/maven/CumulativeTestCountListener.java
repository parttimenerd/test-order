package me.bechberger.testorder.maven;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.execution.AbstractExecutionListener;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.execution.ExecutionListener;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/**
 * Wraps an existing {@link ExecutionListener} and watches surefire's
 * {@code mojoSucceeded} events. After each surefire:test run, it tallies the
 * number of test methods executed in that module by counting {@code <testcase>}
 * elements in {@code target/surefire-reports/TEST-*.xml}. Once cumulative tests
 * across the build reach {@code threshold}, every {@link MavenProject} in
 * {@code session.getProjects()} that hasn't already run gets
 * {@code skipTests=true}, halting further test execution while letting the
 * lifecycle wind down cleanly.
 */
public final class CumulativeTestCountListener extends AbstractExecutionListener {

	private static final String SUREFIRE_GROUP = "org.apache.maven.plugins";
	private static final String SUREFIRE_ARTIFACT = "maven-surefire-plugin";
	private static final String SUREFIRE_GOAL = "test";

	private final ExecutionListener delegate;
	private final int threshold;
	private final Set<MavenProject> finished = new HashSet<>();
	private int cumulative = 0;
	private boolean tripped = false;

	public CumulativeTestCountListener(ExecutionListener delegate, int threshold) {
		this.delegate = delegate;
		this.threshold = threshold;
	}

	int cumulative() {
		return cumulative;
	}

	boolean tripped() {
		return tripped;
	}

	@Override
	public void mojoSucceeded(ExecutionEvent event) {
		try {
			countTests(event);
		} catch (Exception ignored) {
			// best-effort — never break the build
		}
		if (delegate != null) {
			delegate.mojoSucceeded(event);
		}
	}

	private void countTests(ExecutionEvent event) {
		if (tripped) {
			return;
		}
		if (!isSurefireTest(event)) {
			return;
		}
		MavenProject project = event.getProject();
		if (project == null || finished.contains(project)) {
			return;
		}
		finished.add(project);
		Path reports = Path.of(project.getBuild().getDirectory(), "surefire-reports");
		int count = countTestcases(reports);
		cumulative += count;
		if (cumulative >= threshold) {
			tripped = true;
			MavenSession session = event.getSession();
			if (session == null) {
				return;
			}
			List<MavenProject> remaining = session.getProjects();
			if (remaining == null) {
				return;
			}
			boolean past = false;
			int skipped = 0;
			for (MavenProject p : remaining) {
				if (!past) {
					if (p == project) {
						past = true;
					}
					continue;
				}
				p.getProperties().setProperty("skipTests", "true");
				skipped++;
			}
			System.err.println("[test-order] reactor early-exit: cumulative tests=" + cumulative + " >= threshold="
					+ threshold + "; set skipTests=true on " + skipped + " remaining module(s)");
		}
	}

	private static boolean isSurefireTest(ExecutionEvent event) {
		if (event.getMojoExecution() == null) {
			return false;
		}
		String gid = event.getMojoExecution().getGroupId();
		String aid = event.getMojoExecution().getArtifactId();
		String goal = event.getMojoExecution().getGoal();
		return SUREFIRE_GROUP.equals(gid) && SUREFIRE_ARTIFACT.equals(aid) && SUREFIRE_GOAL.equals(goal);
	}

	static int countTestcases(Path reportsDir) {
		if (!Files.isDirectory(reportsDir)) {
			return 0;
		}
		int total = 0;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, "TEST-*.xml")) {
			for (Path xml : stream) {
				total += countTestcasesIn(xml);
			}
		} catch (Exception ignored) {
			// best-effort
		}
		return total;
	}

	private static int countTestcasesIn(Path xml) {
		try {
			String content = Files.readString(xml);
			int count = 0;
			int idx = 0;
			while ((idx = content.indexOf("<testcase", idx)) != -1) {
				count++;
				idx += 9;
			}
			return count;
		} catch (Exception e) {
			return 0;
		}
	}

	// Forward all other lifecycle events to the delegate so we don't break
	// existing logging / reporting.

	@Override
	public void projectDiscoveryStarted(ExecutionEvent event) {
		if (delegate != null)
			delegate.projectDiscoveryStarted(event);
	}

	@Override
	public void sessionStarted(ExecutionEvent event) {
		if (delegate != null)
			delegate.sessionStarted(event);
	}

	@Override
	public void sessionEnded(ExecutionEvent event) {
		if (delegate != null)
			delegate.sessionEnded(event);
	}

	@Override
	public void projectSkipped(ExecutionEvent event) {
		if (delegate != null)
			delegate.projectSkipped(event);
	}

	@Override
	public void projectStarted(ExecutionEvent event) {
		if (delegate != null)
			delegate.projectStarted(event);
	}

	@Override
	public void projectSucceeded(ExecutionEvent event) {
		if (delegate != null)
			delegate.projectSucceeded(event);
	}

	@Override
	public void projectFailed(ExecutionEvent event) {
		if (delegate != null)
			delegate.projectFailed(event);
	}

	@Override
	public void mojoSkipped(ExecutionEvent event) {
		if (delegate != null)
			delegate.mojoSkipped(event);
	}

	@Override
	public void mojoStarted(ExecutionEvent event) {
		if (delegate != null)
			delegate.mojoStarted(event);
	}

	@Override
	public void mojoFailed(ExecutionEvent event) {
		if (delegate != null)
			delegate.mojoFailed(event);
	}

	@Override
	public void forkStarted(ExecutionEvent event) {
		if (delegate != null)
			delegate.forkStarted(event);
	}

	@Override
	public void forkSucceeded(ExecutionEvent event) {
		if (delegate != null)
			delegate.forkSucceeded(event);
	}

	@Override
	public void forkFailed(ExecutionEvent event) {
		if (delegate != null)
			delegate.forkFailed(event);
	}

	@Override
	public void forkedProjectStarted(ExecutionEvent event) {
		if (delegate != null)
			delegate.forkedProjectStarted(event);
	}

	@Override
	public void forkedProjectSucceeded(ExecutionEvent event) {
		if (delegate != null)
			delegate.forkedProjectSucceeded(event);
	}

	@Override
	public void forkedProjectFailed(ExecutionEvent event) {
		if (delegate != null)
			delegate.forkedProjectFailed(event);
	}
}
