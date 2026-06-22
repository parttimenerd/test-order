package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

class ModuleIdsTest {

	@Test
	void of_groupAndArtifact_joinedWithDash() {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn("com.example");
		when(p.getArtifactId()).thenReturn("foo");
		assertEquals("com.example-foo", ModuleIds.of(p));
	}

	@Test
	void of_emptyGroup_returnsArtifactOnly() {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn("");
		when(p.getArtifactId()).thenReturn("foo");
		assertEquals("foo", ModuleIds.of(p));
	}

	@Test
	void of_nullGroup_returnsArtifactOnly() {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn(null);
		when(p.getArtifactId()).thenReturn("foo");
		assertEquals("foo", ModuleIds.of(p));
	}

	@Test
	void of_strings_match() {
		assertEquals("com.example-foo", ModuleIds.of("com.example", "foo"));
		assertEquals("foo", ModuleIds.of(null, "foo"));
		assertEquals("foo", ModuleIds.of("", "foo"));
	}

	@Test
	void of_nullArtifactId_returnsEmpty() {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn("com.example");
		when(p.getArtifactId()).thenReturn(null);
		assertEquals("com.example-", ModuleIds.of(p));
	}

	@Test
	void of_emptyArtifactId_returnsEmptyAfterDash() {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn("com.example");
		when(p.getArtifactId()).thenReturn("");
		assertEquals("com.example-", ModuleIds.of(p));
	}

	@Test
	void of_bothNull_returnsEmpty() {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn(null);
		when(p.getArtifactId()).thenReturn(null);
		assertEquals("", ModuleIds.of(p));
	}

	@Test
	void of_bothEmpty_returnsEmpty() {
		assertEquals("", ModuleIds.of("", ""));
		assertEquals("", ModuleIds.of(null, null));
	}

	/**
	 * IDs are formed as {@code groupId + "-" + artifactId}. Projects whose
	 * coordinates straddle the separator differently can collide:
	 * {@code ("g", "a-b")} and {@code ("g-a", "b")} both produce {@code "g-a-b"}.
	 * This test documents the known collision rather than hiding it — callers must
	 * use fully-qualified coordinates that don't share this ambiguity.
	 */
	@Test
	void of_separatorCollision_isDocumented() {
		String id1 = ModuleIds.of("g", "a-b");
		String id2 = ModuleIds.of("g-a", "b");
		// Both resolve to the same string — this is a known limitation.
		assertEquals(id1, id2, "coordinates that straddle the '-' separator produce the same ID");
	}

	@Test
	void of_strings_nullArtifact_returnsGroupWithTrailingDash() {
		assertEquals("com.example-", ModuleIds.of("com.example", null));
	}

	@Test
	void of_strings_bothNull_returnsEmpty() {
		assertEquals("", ModuleIds.of(null, null));
	}
}
