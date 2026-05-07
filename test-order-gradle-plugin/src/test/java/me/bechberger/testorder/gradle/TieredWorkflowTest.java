package me.bechberger.testorder.gradle;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import me.bechberger.testorder.DependencyMap;
import org.gradle.api.Project;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the tiered CI tasks: testOrderTieredSelect and testOrderRunTier.
 */
class TieredWorkflowTest {

    @TempDir
    Path tempDir;

    @org.junit.jupiter.api.Test
    void tieredExtensionDefaultsAreApplied() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply("java");
        new TestOrderPlugin().apply(project);

        TestOrderExtension ext = (TestOrderExtension) project.getExtensions().getByName(TestOrderPlugin.EXTENSION_NAME);

        assertEquals(0.5, ext.getTieredTier2Fraction().get());
        assertTrue(ext.getTieredWeightByDuration().get());
        assertTrue(ext.getTieredTier1File().get().getAsFile().getAbsolutePath().contains("test-order-tier1.txt"));
        assertTrue(ext.getTieredTier2File().get().getAsFile().getAbsolutePath().contains("test-order-tier2.txt"));
        assertTrue(ext.getTieredTier3File().get().getAsFile().getAbsolutePath().contains("test-order-tier3.txt"));
    }

    @org.junit.jupiter.api.Test
    void tieredExtensionCustomValuesArePropagated() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply("java");
        new TestOrderPlugin().apply(project);

        TestOrderExtension ext = (TestOrderExtension) project.getExtensions().getByName(TestOrderPlugin.EXTENSION_NAME);
        ext.getTieredTier2Fraction().set(0.3);
        ext.getTieredWeightByDuration().set(false);

        assertEquals(0.3, ext.getTieredTier2Fraction().get());
        assertFalse(ext.getTieredWeightByDuration().get());
    }

    @org.junit.jupiter.api.Test
    void tieredSelectTaskIsRegisteredAsTestType() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply("java");
        new TestOrderPlugin().apply(project);

        assertNotNull(project.getTasks().findByName("testOrderTieredSelect"));
        assertInstanceOf(Test.class, project.getTasks().findByName("testOrderTieredSelect"));
        assertEquals("test-order", project.getTasks().findByName("testOrderTieredSelect").getGroup());
    }

    @org.junit.jupiter.api.Test
    void runTierTaskIsRegisteredAsTestType() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply("java");
        new TestOrderPlugin().apply(project);

        assertNotNull(project.getTasks().findByName("testOrderRunTier"));
        assertInstanceOf(Test.class, project.getTasks().findByName("testOrderRunTier"));
        assertEquals("test-order", project.getTasks().findByName("testOrderRunTier").getGroup());
    }

    @org.junit.jupiter.api.Test
    void tieredSelectTaskHasOrdererSystemProperty() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply("java");
        new TestOrderPlugin().apply(project);

        Test tieredTask = (Test) project.getTasks().findByName("testOrderTieredSelect");
        assertEquals("me.bechberger.testorder.junit.PriorityClassOrderer",
                tieredTask.getSystemProperties().get("junit.jupiter.testclass.order.default"));
    }

    @org.junit.jupiter.api.Test
    void runTierTaskHasOrdererSystemProperty() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply("java");
        new TestOrderPlugin().apply(project);

        Test runTierTask = (Test) project.getTasks().findByName("testOrderRunTier");
        assertEquals("me.bechberger.testorder.junit.PriorityClassOrderer",
                runTierTask.getSystemProperties().get("junit.jupiter.testclass.order.default"));
    }

    @org.junit.jupiter.api.Test
    void tierFilesAreWrittenToBuildDirectory() {
        Project project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build();
        project.getPluginManager().apply("java");
        new TestOrderPlugin().apply(project);

        TestOrderExtension ext = (TestOrderExtension) project.getExtensions().getByName(TestOrderPlugin.EXTENSION_NAME);

        Path tier1 = ext.getTieredTier1File().get().getAsFile().toPath();
        Path tier2 = ext.getTieredTier2File().get().getAsFile().toPath();
        Path tier3 = ext.getTieredTier3File().get().getAsFile().toPath();

        // All three tier files should be in the build directory
        String buildDir = project.getLayout().getBuildDirectory().get().getAsFile().getAbsolutePath();
        assertTrue(tier1.toAbsolutePath().toString().startsWith(buildDir));
        assertTrue(tier2.toAbsolutePath().toString().startsWith(buildDir));
        assertTrue(tier3.toAbsolutePath().toString().startsWith(buildDir));
    }
}
