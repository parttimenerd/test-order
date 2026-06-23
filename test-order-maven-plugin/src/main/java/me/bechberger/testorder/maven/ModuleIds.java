package me.bechberger.testorder.maven;

import org.apache.maven.project.MavenProject;

public final class ModuleIds {

	private ModuleIds() {
	}

	public static String of(MavenProject project) {
		String gid = project.getGroupId();
		String aid = project.getArtifactId();
		if (gid == null || gid.isEmpty()) {
			return aid != null ? aid : "";
		}
		// Use ":" as separator — it is illegal in Maven groupId/artifactId and therefore
		// unambiguous. A "-" separator collides when groupId ends with a segment that
		// mirrors the start of artifactId (e.g. "g-a" + "b" == "g" + "a-b" == "g-a-b").
		return gid + ":" + (aid != null ? aid : "");
	}

	public static String of(String groupId, String artifactId) {
		if (groupId == null || groupId.isEmpty()) {
			return artifactId != null ? artifactId : "";
		}
		return groupId + ":" + (artifactId != null ? artifactId : "");
	}
}
