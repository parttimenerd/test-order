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
		return gid + "-" + (aid != null ? aid : "");
	}

	public static String of(String groupId, String artifactId) {
		if (groupId == null || groupId.isEmpty()) {
			return artifactId != null ? artifactId : "";
		}
		return groupId + "-" + (artifactId != null ? artifactId : "");
	}
}
