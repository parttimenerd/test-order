package me.bechberger.testorder.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestOrderPluginTaskRegistrationTest {

    @Test
    void applyRegistersCoreUtilityTasks() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new TestOrderPlugin().apply(project);

        assertNotNull(project.getTasks().findByName("testOrderAggregate"));
        assertNotNull(project.getTasks().findByName("testOrderDump"));
        assertNotNull(project.getTasks().findByName("testOrderExportJson"));
        assertNotNull(project.getTasks().findByName("testOrderShow"));
        assertNotNull(project.getTasks().findByName("testOrderShowOrder"));
        assertNotNull(project.getTasks().findByName("testOrderExplainOrder"));
        assertNotNull(project.getTasks().findByName("testOrderHelp"));
        assertNotNull(project.getTasks().findByName("testOrderOptimize"));
        assertNotNull(project.getTasks().findByName("testOrderAffected"));
        assertNotNull(project.getTasks().findByName("testOrderRunRemaining"));
        assertNotNull(project.getTasks().findByName("testOrderTieredSelect"));
        assertNotNull(project.getTasks().findByName("testOrderRunTier"));
        assertNotNull(project.getTasks().findByName("testOrderClean"));
        assertNotNull(project.getTasks().findByName("testOrderDashboard"));
        assertNotNull(project.getTasks().findByName("testOrderServe"));
    }

    @Test
    void applyRegistersNewTasks() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new TestOrderPlugin().apply(project);

        assertNotNull(project.getTasks().findByName("testOrderShowAll"),
                "testOrderShowAll task must be registered");
        assertNotNull(project.getTasks().findByName("testOrderShowStaticAnalysis"),
                "testOrderShowStaticAnalysis task must be registered");
        assertNotNull(project.getTasks().findByName("testOrderReactorOrder"),
                "testOrderReactorOrder task must be registered");
    }

    @Test
    void newTasksHaveDescriptionsAndGrouping() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new TestOrderPlugin().apply(project);

        var showAll = project.getTasks().findByName("testOrderShowAll");
        assertNotNull(showAll);
        assertNotNull(showAll.getDescription());
        assertTrue(showAll.getDescription().contains("all"), "testOrderShowAll description should mention 'all'");
        assertTrue("test-order".equals(showAll.getGroup()),
                "testOrderShowAll should be in 'test-order' group");

        var showSa = project.getTasks().findByName("testOrderShowStaticAnalysis");
        assertNotNull(showSa);
        assertNotNull(showSa.getDescription());
        assertTrue("test-order".equals(showSa.getGroup()),
                "testOrderShowStaticAnalysis should be in 'test-order' group");

        var reactorOrder = project.getTasks().findByName("testOrderReactorOrder");
        assertNotNull(reactorOrder);
        assertNotNull(reactorOrder.getDescription());
        assertTrue("test-order".equals(reactorOrder.getGroup()),
                "testOrderReactorOrder should be in 'test-order' group");
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
        assertNotNull(project.getTasks().findByName("testOrderShow"));
        assertNotNull(project.getTasks().findByName("testOrderHelp"));
        assertNotNull(project.getTasks().findByName("testOrderShowOrder"));
        assertNotNull(project.getTasks().findByName("testOrderShowAll"));
        assertNotNull(project.getTasks().findByName("testOrderShowStaticAnalysis"));
        assertNotNull(project.getTasks().findByName("testOrderReactorOrder"));
    }

    @Test
    void unifiedShowTaskDescriptionHighlightsCombinedView() {
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new TestOrderPlugin().apply(project);

        var showTask = project.getTasks().findByName("testOrderShow");
        assertNotNull(showTask);
        assertNotNull(showTask.getDescription());
        assertTrue(showTask.getDescription().contains("Unified view"));
        assertTrue(showTask.getDescription().contains("replaces testOrderShowOrder"));
    }
}
