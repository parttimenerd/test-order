package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;

/**
 * Delegates order-mode task configuration and mode resolution to
 * {@link TestOrderPlugin#configureOrderMode} and {@link TestOrderPlugin#resolveMode}.
 */
class OrderModeConfigurator {

    private final TestOrderPlugin plugin;

    OrderModeConfigurator(TestOrderPlugin plugin) {
        this.plugin = plugin;
    }

    String resolveMode(TestOrderExtension ext, Project project) {
        return plugin.resolveMode(ext, project);
    }

    void configure(Project project, TestOrderExtension ext, Test testTask) {
        plugin.configureOrderMode(project, ext, testTask);
    }
}
