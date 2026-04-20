package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

final class UtilityTaskRegistrar {

    private final TestOrderPlugin plugin;

    UtilityTaskRegistrar(TestOrderPlugin plugin) {
        this.plugin = plugin;
    }

    void register(Project project, TestOrderExtension extension, Configuration agentConfiguration) {
        plugin.registerTasks(project, extension, agentConfiguration);
    }
}
