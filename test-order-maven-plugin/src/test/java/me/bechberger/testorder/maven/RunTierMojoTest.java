package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunTierMojoTest {

    @TempDir
    Path tempDir;

    private TestableRunTierMojo mojo;
    private MavenProject project;

    @BeforeEach
    void setUp() throws Exception {
        mojo = new TestableRunTierMojo();
        project = projectWithSurefire(tempDir);

        MavenSession session = mock(MavenSession.class);
        when(session.getProjects()).thenReturn(List.of(project));
        when(session.getTopLevelProject()).thenReturn(project);

        inject(mojo, "project", project);
        inject(mojo, "session", session);
        inject(mojo, "skip", false);
        inject(mojo, "currentTier", 2);
        inject(mojo, "tier2File", tempDir.resolve("tier2.txt").toString());
        inject(mojo, "tier3File", tempDir.resolve("tier3.txt").toString());
    }

    @Test
    void missingTierFileSkipsTests() {
        assertDoesNotThrow(mojo::execute);
        assertEquals("true", project.getProperties().getProperty("skipTests"));
    }

    @Test
    void tierFileConfiguresSurefireIncludes() throws Exception {
        Files.writeString(tempDir.resolve("tier2.txt"), "com.example.FastTest\ncom.example.OtherTest\n");

        assertDoesNotThrow(mojo::execute);

        String testProp = project.getProperties().getProperty("test");
        assertTrue(testProp.contains("com.example.FastTest"));
        assertTrue(testProp.contains("com.example.OtherTest"));
        assertEquals("true", project.getProperties().getProperty("testorder.auto.active"));
    }

    @Test
    void invalidTierFailsFast() throws Exception {
        inject(mojo, "currentTier", 1);
        MojoExecutionException ex = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(ex.getMessage().contains("must be 2 or 3"));
    }

    private static MavenProject projectWithSurefire(Path baseDir) {
        MavenProject project = mock(MavenProject.class);
        when(project.getBasedir()).thenReturn(baseDir.toFile());
        when(project.getProperties()).thenReturn(new Properties());

        Build build = new Build();
        build.setDirectory(baseDir.resolve("target").toString());
        build.setTestOutputDirectory(baseDir.resolve("test-classes").toString());
        when(project.getBuild()).thenReturn(build);

        Plugin surefire = new Plugin();
        surefire.setGroupId("org.apache.maven.plugins");
        surefire.setArtifactId("maven-surefire-plugin");
        surefire.setConfiguration(new Xpp3Dom("configuration"));
        when(project.getBuildPlugins()).thenReturn(List.of(surefire));

        return project;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field not found in class hierarchy: " + fieldName);
    }

    private static final class TestableRunTierMojo extends RunTierMojo {
        @Override
        protected Path[] resolveOrdererClasspath() {
            return new Path[0];
        }

        @Override
        protected void injectTestClasspath(Path... jars) {
            // no-op
        }

        @Override
        protected void ensureListenerServiceFile(Path classpathRoot) {
            // no-op
        }

        @Override
        protected boolean isTestNGOnTestClasspath() {
            return false;
        }

        @Override
        protected void ensureTestNGListenerServiceFile(Path classpathRoot) {
            // no-op
        }
    }
}
