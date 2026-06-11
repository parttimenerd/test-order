package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.plugins.JavaPlugin;

final class TestOrderExtensionConfigurator {

    record ConfiguredPlugin(TestOrderExtension extension, Configuration agentConfiguration) {}

    private final TestOrderPlugin plugin;

    TestOrderExtensionConfigurator(TestOrderPlugin plugin) {
        this.plugin = plugin;
    }

    ConfiguredPlugin configure(Project project) {
        TestOrderExtension extension = project.getExtensions()
                .create(TestOrderPlugin.EXTENSION_NAME, TestOrderExtension.class);
        extension.applyDefaults(project);

        // Add mavenLocal() so test-order artifacts (published to local Maven) can be
        // resolved. Filtered to only the test-order group so ~/.m2 JARs don't shadow
        // the project's own versions of JUnit etc.
        //
        // Timing note: for multi-project builds using `subprojects { apply(plugin) }`,
        // this plugin is applied during ROOT project evaluation — BEFORE the subproject's
        // own build file runs. A plain afterEvaluate() fires before the subproject's
        // plugins (e.g. antora-conventions) add their project-level repositories.
        // We therefore defer to gradle.projectsEvaluated, which fires after ALL projects
        // have been fully configured, so all repos are present.
        //
        // When project repos are empty at that point, Gradle's dependencyResolutionManagement
        // (settings-level repos) is active for this project — skip adding a project-level
        // repo (which would override settings repos). Instead rely on mavenLocal() being
        // in dependencyResolutionManagement, which our script injection adds.
        project.getGradle().projectsEvaluated(gradle -> {
            project.getLogger().info("[test-order] projectsEvaluated for {}: repos={}",
                    project.getPath(), project.getRepositories().getNames());
            if (!project.getRepositories().isEmpty()
                    && project.getRepositories().findByName("testOrderMavenLocal") == null) {
                try {
                    project.getRepositories().mavenLocal(repo -> {
                        repo.setName("testOrderMavenLocal");
                        repo.content(content -> content.includeGroup(TestOrderPlugin.GROUP_ID));
                    });
                    project.getLogger().info("[test-order] Added testOrderMavenLocal to {}", project.getPath());
                } catch (Exception e) {
                    project.getLogger().warn("[test-order] Could not add testOrderMavenLocal to {}: {}",
                            project.getPath(), e.getMessage());
                }
            }
        });

        Configuration agentConfiguration = plugin.createHiddenConfiguration(project,
                TestOrderPlugin.AGENT_CONFIG_NAME, false);
        project.getDependencies().add(agentConfiguration.getName(),
                TestOrderPlugin.GROUP_ID + ":test-order-agent:" + TestOrderPlugin.VERSION);

        // Defer testRuntimeOnly additions until the java plugin is applied.
        // When applied via init-script, the java plugin may not exist yet.
        if (project.getPlugins().hasPlugin(JavaPlugin.class)) {
            plugin.addTestOrderTestDependencies(project);
        } else {
            project.getPlugins().withType(JavaPlugin.class, javaPlugin ->
                    plugin.addTestOrderTestDependencies(project));
        }
        return new ConfiguredPlugin(extension, agentConfiguration);
    }
}
