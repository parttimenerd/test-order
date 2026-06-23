package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

class ModuleIdsTest {

	@Test
	void of_groupAndArtifact_joinedWithColon() {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn("com.example");
		when(p.getArtifactId()).thenReturn("foo");
		assertEquals("com.example:foo", ModuleIds.of(p));
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
		assertEquals("com.example:foo", ModuleIds.of("com.example", "foo"));
		assertEquals("foo", ModuleIds.of(null, "foo"));
		assertEquals("foo", ModuleIds.of("", "foo"));
	}

	@Test
	void of_nullArtifactId_returnsEmptyAfterColon() {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn("com.example");
		when(p.getArtifactId()).thenReturn(null);
		assertEquals("com.example:", ModuleIds.of(p));
	}

	@Test
	void of_emptyArtifactId_returnsEmptyAfterColon() {
		MavenProject p = mock(MavenProject.class);
		when(p.getGroupId()).thenReturn("com.example");
		when(p.getArtifactId()).thenReturn("");
		assertEquals("com.example:", ModuleIds.of(p));
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
	 * The ":" separator is illegal in Maven groupId/artifactId so it cannot appear
	 * in either coordinate — no collision is possible regardless of how dashes
	 * appear in the coordinates.
	 */
	@Test
	void of_noSeparatorCollision_withColonSeparator() {
		// Old "-" separator: ("g", "a-b") and ("g-a", "b") both produced "g-a-b".
		// New ":" separator: ("g", "a-b") produces "g:a-b", ("g-a", "b") produces
		// "g-a:b" — these are distinct.
		String id1 = ModuleIds.of("g", "a-b");
		String id2 = ModuleIds.of("g-a", "b");
		assertNotEquals(id1, id2, "colon separator must not collide for dash-heavy coordinates");
		assertEquals("g:a-b", id1);
		assertEquals("g-a:b", id2);
	}

	@Test
	void of_strings_nullArtifact_returnsGroupWithTrailingColon() {
		assertEquals("com.example:", ModuleIds.of("com.example", null));
	}

	@Test
	void of_strings_bothNull_returnsEmpty() {
		assertEquals("", ModuleIds.of(null, null));
	}
}
