package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

/**
 * Activation logic for {@link CollectorLifecycleParticipant#isReorderEnabled}.
 * Default-on for {@code :affected} / {@code :auto} / {@code :run-tier*} CLI
 * goals; otherwise off; explicit flag always wins.
 */
class CollectorLifecycleParticipantActivationTest {

	private final CollectorLifecycleParticipant participant = new CollectorLifecycleParticipant();

	private MavenSession sessionWithGoals(Properties userProps, List<String> goals) {
		MavenSession s = mock(MavenSession.class);
		lenient().when(s.getUserProperties()).thenReturn(userProps);
		lenient().when(s.getSystemProperties()).thenReturn(new Properties());
		lenient().when(s.getTopLevelProject()).thenReturn(null);
		lenient().when(s.getGoals()).thenReturn(goals);
		// Top-level project null → readProp falls back to user/system only.
		MavenProject top = mock(MavenProject.class);
		lenient().when(top.getProperties()).thenReturn(new Properties());
		return s;
	}

	@Test
	void plainTestGoal_noReorderByDefault() {
		MavenSession s = sessionWithGoals(new Properties(), List.of("test"));
		assertFalse(participant.isReorderEnabled(s));
	}

	@Test
	void affectedGoal_triggersReorderByDefault() {
		MavenSession s = sessionWithGoals(new Properties(), List.of("test-order:affected", "test"));
		assertTrue(participant.isReorderEnabled(s));
	}

	@Test
	void autoGoal_triggersReorderByDefault() {
		MavenSession s = sessionWithGoals(new Properties(), List.of("test-order:auto", "test"));
		assertTrue(participant.isReorderEnabled(s));
	}

	@Test
	void runTierGoal_triggersReorderByDefault() {
		MavenSession s = sessionWithGoals(new Properties(), List.of("test-order:run-tier1"));
		assertTrue(participant.isReorderEnabled(s));
	}

	@Test
	void fullyQualifiedAffectedGoal_alsoTriggers() {
		MavenSession s = sessionWithGoals(new Properties(), List.of("me.bechberger:test-order-maven-plugin:affected"));
		assertTrue(participant.isReorderEnabled(s));
	}

	@Test
	void explicitFlagFalse_disablesEvenWithAffectedGoal() {
		Properties p = new Properties();
		p.setProperty("testorder.reactorReorder", "false");
		MavenSession s = sessionWithGoals(p, List.of("test-order:affected"));
		assertFalse(participant.isReorderEnabled(s), "explicit false must override default-on for affected goals");
	}

	@Test
	void explicitFlagTrue_enablesForPlainTest() {
		Properties p = new Properties();
		p.setProperty("testorder.reactorReorder", "true");
		MavenSession s = sessionWithGoals(p, List.of("test"));
		assertTrue(participant.isReorderEnabled(s), "explicit true must enable reorder even without an affected goal");
	}

	@Test
	void explicitFlagBareKey_treatedAsTrue() {
		Properties p = new Properties();
		p.setProperty("testorder.reactorReorder", "");
		MavenSession s = sessionWithGoals(p, List.of("test"));
		assertTrue(participant.isReorderEnabled(s));
	}

	@Test
	void emptyGoals_noReorder() {
		MavenSession s = sessionWithGoals(new Properties(), List.of());
		assertFalse(participant.isReorderEnabled(s));
	}

	@Test
	void unrelatedSimilarGoal_doesNotTrigger() {
		// "auto" trigger must match goal NAME exactly (after the colon),
		// not be a substring of an unrelated goal.
		MavenSession s = sessionWithGoals(new Properties(), List.of("clean", "verify"));
		assertFalse(participant.isReorderEnabled(s));
	}
}
