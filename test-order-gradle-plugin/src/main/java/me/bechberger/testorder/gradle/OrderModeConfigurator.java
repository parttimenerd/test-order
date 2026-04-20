package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;

final class OrderModeConfigurator {

    private final TestOrderPlugin plugin;

    OrderModeConfigurator(TestOrderPlugin plugin) {
        this.plugin = plugin;
    }

    String resolveMode(TestOrderExtension extension, Project project) {
        return plugin.resolveMode(extension, project);
    }

    void configure(Project project, TestOrderExtension extension, Test testTask) {
        plugin.configureOrderMode(project, extension, testTask);
    }
}
