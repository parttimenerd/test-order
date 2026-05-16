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

        project.getRepositories().mavenLocal(repo -> {
            repo.setName("testOrderMavenLocal");
            // Only resolve test-order artifacts from mavenLocal to avoid
            // stale JUnit Platform JARs in ~/.m2 shadowing project versions
            repo.content(content -> content.includeGroup(TestOrderPlugin.GROUP_ID));
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
