package me.bechberger.testorder.gradle;

import static org.junit.jupiter.api.Assertions.*;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

class TestOrderExtensionTest {

    @Test
    void applyDefaultsSetsConventionsFromProjectLayout() {
        Project project = ProjectBuilder.builder().build();
        TestOrderExtension extension = project.getExtensions().create("testOrder", TestOrderExtension.class);

        extension.applyDefaults(project);

        assertEquals("auto", extension.getMode().get());
        assertEquals("MEMBER", extension.getInstrumentationMode().get());
        assertEquals(project.getLayout().getProjectDirectory().file(".test-order/test-dependencies.lz4").getAsFile(),
                extension.getIndexFile().get().getAsFile());
        assertEquals(project.getLayout().getBuildDirectory().dir("test-order-deps").get().getAsFile(),
                extension.getDepsDir().get().getAsFile());
        assertEquals(-1, extension.getSelectTopN().get());
        assertEquals(10, extension.getSelectRandomM().get());
        assertFalse(extension.getMethodOrderingEnabled().get());
        assertFalse(extension.getTdd().get());
        assertFalse(extension.getScoreNewTest().isPresent());
    }

    @Test
    void applyDefaultsDoesNotOverrideExplicitValues() {
        Project project = ProjectBuilder.builder().build();
        TestOrderExtension extension = project.getExtensions().create("testOrder", TestOrderExtension.class);
        extension.getMode().set("learn");
        extension.getIncludePackages().set("com.example");
        extension.getSelectTopN().set(7);
        extension.getFilterByGroupId().set(false);

        extension.applyDefaults(project);

        assertEquals("learn", extension.getMode().get());
        assertEquals("com.example", extension.getIncludePackages().get());
        assertEquals(7, extension.getSelectTopN().get());
        assertFalse(extension.getFilterByGroupId().get());
    }

    @Test
    void multiProjectSubmoduleGetsPerModuleHashFiles() {
        Project root = ProjectBuilder.builder().withName("root").build();
        Project sub = ProjectBuilder.builder().withName("service-a").withParent(root).build();

        TestOrderExtension ext = sub.getExtensions().create("testOrder", TestOrderExtension.class);
        ext.applyDefaults(sub);

        // Index and state are still shared at root level
        String rootDotTestOrder = root.getLayout().getProjectDirectory()
                .dir(".test-order").getAsFile().getAbsolutePath();
        assertTrue(ext.getIndexFile().get().getAsFile().getAbsolutePath()
                .startsWith(rootDotTestOrder), "Index should be under root/.test-order");

        // Hash files are per-module: <root>/.test-order/hashes/<name>-hashes.lz4
        String hashPath = ext.getHashFile().get().getAsFile().getAbsolutePath();
        assertTrue(hashPath.contains("hashes") && hashPath.endsWith("service-a-hashes.lz4"),
                "Subproject hash file should be per-module, got: " + hashPath);

        String testHashPath = ext.getTestHashFile().get().getAsFile().getAbsolutePath();
        assertTrue(testHashPath.endsWith("service-a-test-hashes.lz4"),
                "Subproject test-hash file should be per-module, got: " + testHashPath);
    }

    @Test
    void singleProjectUsesFlatHashFiles() {
        Project project = ProjectBuilder.builder().withName("my-app").build();
        TestOrderExtension ext = project.getExtensions().create("testOrder", TestOrderExtension.class);
        ext.applyDefaults(project);

        // Single-project: flat names for backward compatibility
        String hashPath = ext.getHashFile().get().getAsFile().getAbsolutePath();
        assertTrue(hashPath.endsWith("hashes.lz4") && !hashPath.contains("hashes/"),
                "Single-project hash file should use flat name, got: " + hashPath);
    }
}
