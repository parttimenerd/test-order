package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.testing.Test;

final class LearnModeConfigurator {

    private final TestOrderPlugin plugin;

    LearnModeConfigurator(TestOrderPlugin plugin) {
        this.plugin = plugin;
    }

    void configure(Project project, TestOrderExtension extension, Test testTask, Configuration agentConfiguration) {
        plugin.configureLearnMode(project, extension, testTask, agentConfiguration);
    }
}
