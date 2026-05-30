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
}
