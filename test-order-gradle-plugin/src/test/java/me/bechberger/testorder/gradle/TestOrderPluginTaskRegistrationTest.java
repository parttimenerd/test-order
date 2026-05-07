package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class TestOrderPluginTaskRegistrationTest {

    @Test
    void applyRegistersCoreUtilityTasks() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new TestOrderPlugin().apply(project);

        assertNotNull(project.getTasks().findByName("testOrderAggregate"));
        assertNotNull(project.getTasks().findByName("testOrderDump"));
        assertNotNull(project.getTasks().findByName("testOrderExportJson"));
        assertNotNull(project.getTasks().findByName("testOrderShowOrder"));
        assertNotNull(project.getTasks().findByName("testOrderExplainOrder"));
        assertNotNull(project.getTasks().findByName("testOrderOptimize"));
        assertNotNull(project.getTasks().findByName("testOrderSelect"));
        assertNotNull(project.getTasks().findByName("testOrderRunRemaining"));
        assertNotNull(project.getTasks().findByName("testOrderTieredSelect"));
        assertNotNull(project.getTasks().findByName("testOrderRunTier"));
        assertNotNull(project.getTasks().findByName("testOrderClean"));
        assertNotNull(project.getTasks().findByName("testOrderDashboard"));
        assertNotNull(project.getTasks().findByName("testOrderServe"));
    }

    @Test
    void applyIsIdempotent() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        TestOrderPlugin plugin = new TestOrderPlugin();
        plugin.apply(project);
        // Second apply should be a no-op and not throw.
        plugin.apply(project);

        assertNotNull(project.getExtensions().findByName(TestOrderPlugin.EXTENSION_NAME));
        assertNotNull(project.getTasks().findByName("testOrderShowOrder"));
    }
}
