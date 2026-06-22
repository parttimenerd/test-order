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
        assertNotNull(project.getTasks().findByName("testOrderInstrument"),
                "testOrderInstrument task must be registered");
        assertNotNull(project.getTasks().findByName("testOrderRunTiered"),
                "testOrderRunTiered task must be registered");
        assertNotNull(project.getTasks().findByName("testOrderPrepare"),
                "testOrderPrepare task must be registered");
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

        var instrument = project.getTasks().findByName("testOrderInstrument");
        assertNotNull(instrument);
        assertNotNull(instrument.getDescription());
        assertTrue("test-order".equals(instrument.getGroup()),
                "testOrderInstrument should be in 'test-order' group");

        var runTiered = project.getTasks().findByName("testOrderRunTiered");
        assertNotNull(runTiered);
        assertNotNull(runTiered.getDescription());
        assertTrue("test-order".equals(runTiered.getGroup()),
                "testOrderRunTiered should be in 'test-order' group");

        var prepare = project.getTasks().findByName("testOrderPrepare");
        assertNotNull(prepare);
        assertNotNull(prepare.getDescription());
        assertTrue("test-order".equals(prepare.getGroup()),
                "testOrderPrepare should be in 'test-order' group");
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
        assertNotNull(project.getTasks().findByName("testOrderInstrument"));
        assertNotNull(project.getTasks().findByName("testOrderRunTiered"));
        assertNotNull(project.getTasks().findByName("testOrderPrepare"));
    }

    @Test
    void offlineRestoreTaskRegisteredEagerlyAtApplyTime() {
        // Regression guard for commit 162642c0: testOrderOfflineRestore must be registered
        // up-front in registerTasks(), not lazily inside configureOfflineLearnMode (which
        // runs from configureEach). Gradle 8.14+ forbids tasks.register() from inside
        // configureEach/register actions; if a future change moves the registration back
        // there, ProjectBuilder will still apply the plugin successfully but the task
        // won't exist until a Test task is configured — this test fails fast on that.
        Project project = ProjectBuilder.builder().build();
        project.getPluginManager().apply("java");

        new TestOrderPlugin().apply(project);

        var restore = project.getTasks().findByName("testOrderOfflineRestore");
        assertNotNull(restore, "testOrderOfflineRestore must be registered eagerly in registerTasks()");
        assertTrue("test-order".equals(restore.getGroup()),
                "testOrderOfflineRestore should be in 'test-order' group");
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
