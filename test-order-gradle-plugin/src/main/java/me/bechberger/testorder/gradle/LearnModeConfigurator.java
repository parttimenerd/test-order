package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.testing.Test;

/**
 * Delegates learn-mode task configuration to {@link TestOrderPlugin#configureLearnMode}.
 */
class LearnModeConfigurator {

    private final TestOrderPlugin plugin;

    LearnModeConfigurator(TestOrderPlugin plugin) {
        this.plugin = plugin;
    }

    void configure(Project project, TestOrderExtension ext, Test testTask, Configuration agentConf) {
        plugin.configureLearnMode(project, ext, testTask, agentConf);
    }
}
