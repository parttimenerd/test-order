package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import java.io.File;

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

        project.getRepositories().maven(repo -> {
            repo.setName("testOrderMavenLocal");
            repo.setUrl(new File(System.getProperty("user.home"), ".m2/repository").toURI());
            repo.mavenContent(content -> content.includeGroup(TestOrderPlugin.GROUP_ID));
        });

        Configuration agentConfiguration = plugin.createHiddenConfiguration(project,
                TestOrderPlugin.AGENT_CONFIG_NAME, false);
        project.getDependencies().add(agentConfiguration.getName(),
                TestOrderPlugin.GROUP_ID + ":test-order-agent:" + TestOrderPlugin.VERSION);
        plugin.addTestOrderTestDependencies(project);
        return new ConfiguredPlugin(extension, agentConfiguration);
    }
}
