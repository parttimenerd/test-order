package me.bechberger.testorder.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.bechberger.testorder.DependencyMap;
import me.bechberger.testorder.TestOrderState;

class TieredSelectMojoTest {

	@TempDir
	Path tempDir;

	private TestableTieredSelectMojo mojo;
	private MavenProject project;

	@BeforeEach
	void setUp() throws Exception {
		mojo = new TestableTieredSelectMojo();
		project = projectWithSurefire(tempDir);

		MavenSession session = mock(MavenSession.class);
		when(session.getProjects()).thenReturn(List.of(project));
		when(session.getTopLevelProject()).thenReturn(project);

		inject(mojo, "project", project);
		inject(mojo, "session", session);
		inject(mojo, "indexFile", tempDir.resolve("test-dependencies.lz4").toString());
		inject(mojo, "stateFile", tempDir.resolve(".test-order/state.lz4").toString());
		inject(mojo, "depsDir", tempDir.resolve("test-order-deps").toString());
		inject(mojo, "hashFile", tempDir.resolve(".test-order/hashes.lz4").toString());
		inject(mojo, "testHashFile", tempDir.resolve(".test-order/test-hashes.lz4").toString());
		inject(mojo, "methodHashFile", tempDir.resolve(".test-order/method-hashes.lz4").toString());
		inject(mojo, "changeMode", "explicit");
		inject(mojo, "changedClasses", "com.example.ServiceA");
		inject(mojo, "changedTestClasses", "");
		inject(mojo, "tier2Fraction", 0.5d);
		inject(mojo, "weightByDuration", true);
		inject(mojo, "tier1File", tempDir.resolve("tier1.txt").toString());
		inject(mojo, "tier2File", tempDir.resolve("tier2.txt").toString());
		inject(mojo, "tier3File", tempDir.resolve("tier3.txt").toString());
	}

	@Test
	void writesThreeTierFilesAndConfiguresTierOneExecution() throws Exception {
		DependencyMap map = new DependencyMap();
		map.put("com.example.AffectedTest", Set.of("com.example.ServiceA"));
		map.put("com.example.FastTest", Set.of("com.example.ServiceB"));
		map.put("com.example.SlowTest", Set.of("com.example.ServiceC"));
		map.save(tempDir.resolve("test-dependencies.lz4"));

		assertDoesNotThrow(mojo::execute);

		Path tier1 = tempDir.resolve("tier1.txt");
		Path tier2 = tempDir.resolve("tier2.txt");
		Path tier3 = tempDir.resolve("tier3.txt");

		assertTrue(Files.exists(tier1));
		assertTrue(Files.exists(tier2));
		assertTrue(Files.exists(tier3));

		assertTrue(Files.readAllLines(tier1).contains("com.example.AffectedTest"));
		String testProp = project.getProperties().getProperty("test");
		assertNotNull(testProp, "Surefire includes should be configured for tier 1");
		assertTrue(testProp.contains("com.example.AffectedTest"));

		assertEquals(tempDir.resolve("tier2.txt").toAbsolutePath().toString(),
				project.getProperties().getProperty("testorder.tiered.tier2File"));
	}

	private static MavenProject projectWithSurefire(Path baseDir) {
		MavenProject project = mock(MavenProject.class);
		when(project.getBasedir()).thenReturn(baseDir.toFile());
		when(project.getProperties()).thenReturn(new Properties());
		when(project.getArtifactId()).thenReturn("test-artifact");

		Build build = new Build();
		build.setDirectory(baseDir.resolve("target").toString());
		build.setTestOutputDirectory(baseDir.resolve("test-classes").toString());
		when(project.getBuild()).thenReturn(build);

		Plugin surefire = new Plugin();
		surefire.setGroupId("org.apache.maven.plugins");
		surefire.setArtifactId("maven-surefire-plugin");
		surefire.setConfiguration(new Xpp3Dom("configuration"));
		when(project.getBuildPlugins()).thenReturn(List.of(surefire));
		when(project.getCompileSourceRoots()).thenReturn(List.of(baseDir.resolve("src/main/java").toString()));
		when(project.getTestCompileSourceRoots()).thenReturn(List.of(baseDir.resolve("src/test/java").toString()));
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

	private static final class TestableTieredSelectMojo extends TieredSelectMojo {
		@Override
		protected TestOrderState loadState() {
			TestOrderState state = new TestOrderState();
			state.recordDuration("com.example.FastTest", 10);
			state.recordDuration("com.example.SlowTest", 100);
			return state;
		}

		@Override
		protected Set<String> discoverAlwaysRunClasses() {
			return Set.of();
		}

		@Override
		protected void writeOrdererConfig(Set<String> changed, Set<String> changedTests, Set<String> changedMethods,
				Map<String, Integer> scoreOverrides) throws MojoExecutionException {
			// no-op in this focused unit test
		}
	}
}
