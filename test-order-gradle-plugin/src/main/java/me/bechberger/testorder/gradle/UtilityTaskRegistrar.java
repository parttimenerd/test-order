package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

/**
 * Delegates utility task registration to {@link TestOrderPlugin#registerTasks}.
 */
class UtilityTaskRegistrar {

    private final TestOrderPlugin plugin;

    UtilityTaskRegistrar(TestOrderPlugin plugin) {
        this.plugin = plugin;
    }

    void register(Project project, TestOrderExtension ext, Configuration agentConf) {
        plugin.registerTasks(project, ext, agentConf);
    }
}
