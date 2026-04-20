package me.bechberger.testorder.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTest {

    @TempDir
    Path tempDir;

    @Test
    void parseBlankArgsUsesDefaults() {
        Agent agent = Agent.parse("  ");

        assertEquals(Path.of("target/test-order-deps"), agent.getOutputDir());
        assertEquals(Agent.InstrumentationMode.FULL, agent.getMode());
        assertTrue(agent.getIncludePackages().isEmpty());
        assertTrue(agent.isAutoDetectPackages());
        assertNull(agent.getVerboseFile());
    }

    @Test
    void parseValidArgsBindsProperties() {
        Agent agent = Agent.parse("outputDir=build/deps,includePackages=com.example;org.demo,excludePackages=org.skip,mode=FULL_MEMBER,autoDetectPackages=false,skipTestClasses=false,useHeuristics=false,projectRoot=workspace,indexFile=deps.lz4,verboseFile=verbose.log");

        assertEquals(Path.of("build/deps"), agent.getOutputDir());
        assertEquals(List.of("com.example", "org.demo"), agent.getIncludePackages());
        assertEquals(List.of("org.skip"), agent.getExcludePackages());
        assertEquals(Agent.InstrumentationMode.FULL_MEMBER, agent.getMode());
        assertFalse(agent.isAutoDetectPackages());
        assertFalse(agent.isSkipTestClasses());
        assertFalse(agent.isUseHeuristics());
        assertEquals(Path.of("workspace"), agent.getProjectRoot());
        assertEquals(Path.of("deps.lz4"), agent.getIndexFile());
        assertEquals(Path.of("verbose.log"), agent.getVerboseFile());
    }

    @Test
    void parseInvalidArgsFailsFast() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Agent.parse("mode=NOT_A_MODE"));

        assertTrue(error.getMessage().contains("Invalid agent arguments"));
        assertTrue(error.getMessage().contains("mode=NOT_A_MODE"));
    }

    @Test
    void extractRuntimeJarFailsWhenResourceMissing() {
        ClassLoader noResourceLoader = new ClassLoader(null) {
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                return null;
            }
        };

        RuntimeException error = assertThrows(RuntimeException.class,
                () -> Agent.extractRuntimeJar(noResourceLoader));

        assertEquals("Could not find test-order-runtime.jar", error.getMessage());
    }

    @Test
    void appendRuntimeJarAddsBootstrapJar(@TempDir Path dir) throws Exception {
        Path runtimeJar = dir.resolve("test-order-runtime.jar");
        createJar(runtimeJar);
        RecordingInstrumentation recorder = new RecordingInstrumentation();
        ClassLoader loader = new SingleResourceClassLoader(runtimeJar);

        Path extracted = Agent.appendRuntimeJarToBootstrap(recorder.proxy(), loader);

        assertNotNull(recorder.bootstrapJar);
        assertEquals(extracted.toRealPath(), Path.of(recorder.bootstrapJar.getName()).toRealPath());
        assertTrue(Files.exists(extracted));
    }

    @Test
    void configureAgentLoggerUsesProvidedClassLoader() throws Exception {
        Path verboseFile = tempDir.resolve("agent.log");

        Agent.configureAgentLogger(verboseFile, Agent.class.getClassLoader());

        assertTrue(Files.exists(verboseFile));
        assertTrue(Files.readString(verboseFile).contains("Verbose logging enabled"));
    }

    private static final class SingleResourceClassLoader extends ClassLoader {
        private final Path runtimeJar;

        private SingleResourceClassLoader(Path runtimeJar) {
            super(null);
            this.runtimeJar = runtimeJar;
        }

        @Override
        public java.io.InputStream getResourceAsStream(String name) {
            if (!"test-order-runtime.jar".equals(name)) {
                return null;
            }
            try {
                return Files.newInputStream(runtimeJar);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static final class RecordingInstrumentation implements InvocationHandler {
        private JarFile bootstrapJar;
        private final List<ClassFileTransformer> transformers = new ArrayList<>();

        private Instrumentation proxy() {
            return (Instrumentation) Proxy.newProxyInstance(
                    Instrumentation.class.getClassLoader(),
                    new Class<?>[]{Instrumentation.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "appendToBootstrapClassLoaderSearch" -> {
                    bootstrapJar = (JarFile) args[0];
                    yield null;
                }
                case "addTransformer" -> {
                    transformers.add((ClassFileTransformer) args[0]);
                    yield null;
                }
                case "removeTransformer" -> false;
                case "isRetransformClassesSupported", "isRedefineClassesSupported",
                        "isModifiableClass", "isNativeMethodPrefixSupported",
                        "isModifiableModule" -> false;
                case "getAllLoadedClasses", "getInitiatedClasses" -> new Class<?>[0];
                case "getObjectSize" -> 0L;
                case "retransformClasses", "redefineClasses", "appendToSystemClassLoaderSearch",
                        "setNativeMethodPrefix", "redefineModule" -> null;
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static void createJar(Path runtimeJar) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(runtimeJar))) {
            out.putNextEntry(new JarEntry("marker.txt"));
            out.write("ok".getBytes());
            out.closeEntry();
        }
    }
}
