package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

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

        project.getRepositories().mavenLocal(repo -> repo.setName("testOrderMavenLocal"));

        Configuration agentConfiguration = plugin.createHiddenConfiguration(project,
                TestOrderPlugin.AGENT_CONFIG_NAME, false);
        project.getDependencies().add(agentConfiguration.getName(),
                TestOrderPlugin.GROUP_ID + ":test-order-agent:" + TestOrderPlugin.VERSION);
        plugin.addTestOrderTestDependencies(project);
        return new ConfiguredPlugin(extension, agentConfiguration);
    }
}
