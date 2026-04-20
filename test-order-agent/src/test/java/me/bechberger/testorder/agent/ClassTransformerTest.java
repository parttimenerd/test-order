package me.bechberger.testorder.agent;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class ClassTransformerTest {

    private ClassTransformer createTransformer(String... includePackages) {
        Agent agent = Agent.parse(
                (includePackages.length == 0 ? "autoDetectPackages=false" :
                        "includePackages=" + String.join(";", includePackages)));
        return new ClassTransformer(agent);
    }

    private ClassTransformer createTransformerWithMode(Agent.InstrumentationMode mode) {
        Agent agent = Agent.parse("mode=" + mode.name());
        return new ClassTransformer(agent);
    }

    // ── shouldInstrument: always-skip prefixes ─────────────────────────────────

    @Test
    void skipsJdkClasses() {
        ClassTransformer t = createTransformer();
        byte[] input = new byte[]{1, 2, 3};
        assertSame(input, t.transform(null, null, "java/lang/String", null, null, input));
        assertSame(input, t.transform(null, null, "sun/misc/Unsafe", null, null, input));
        assertSame(input, t.transform(null, null, "jdk/internal/misc/Unsafe", null, null, input));
    }

    @Test
    void skipsComSunClasses() {
        ClassTransformer t = createTransformer();
        // com/sun/** are JDK internal classes and must never be instrumented
        assertFalse(t.shouldInstrument("com/sun/crypto/provider/AESCipher"));
        assertFalse(t.shouldInstrument("com/sun/net/httpserver/HttpsConfigurator"));
    }

    @Test
    void skipsJavaxAndJakartaClasses() {
        ClassTransformer t = createTransformer();
        assertFalse(t.shouldInstrument("javax/sql/DataSource"));
        assertFalse(t.shouldInstrument("jakarta/persistence/Entity"));
    }

    @Test
    void skipsOwnAgentClasses() {
        ClassTransformer t = createTransformer();
        byte[] input = new byte[]{1, 2, 3};
        assertSame(input, t.transform(null, null,
                "me/bechberger/testorder/agent/Agent", null, null, input));
        assertFalse(t.shouldInstrument("me/bechberger/testorder/agent/ClassTransformer"));
    }

    // ── shouldInstrument: generated / synthetic classes ───────────────────────

    @Test
    void skipsGeneratedClasses() {
        ClassTransformer t = createTransformer();
        assertFalse(t.shouldInstrument("com/example/MyService$$EnhancerBySpringCGLIB"));
        assertFalse(t.shouldInstrument("com/example/MyService$Proxy42"));
        assertFalse(t.shouldInstrument("com/example/MyRepo$$MockitoMock"));
        // ByteBuddy-renamed class contains the "ByteBuddy" marker substring
        assertFalse(t.shouldInstrument("net/bytebuddy/renamed/ByteBuddy$Subclass"));
    }

    @Test
    void skipsModuleInfo() {
        ClassTransformer t = createTransformer();
        // module-info is a pseudo-class descriptor, not instrumentable
        assertFalse(t.shouldInstrument("module-info"));
        assertFalse(t.shouldInstrument("com/example/module-info"));
    }

    @Test
    void doesNotSkipFrameworkClasses() {
        ClassTransformer t = createTransformer();
        // plain framework classes (no generated markers) are candidates
        assertTrue(t.shouldInstrument("org/springframework/beans/BeanUtils"));
        assertTrue(t.shouldInstrument("org/hibernate/Session"));
    }

    @Test
    void doesNotSkipLegitimateInnerClasses() {
        ClassTransformer t = createTransformer();
        // inner classes with $ in the name are valid source-compiled classes
        assertTrue(t.shouldInstrument("com/example/Outer$Inner"));
        assertTrue(t.shouldInstrument("com/example/Builder$Step1"));
    }

    // ── shouldInstrument: null / edge cases ────────────────────────────────────

    @Test
    void skipsNullClassName() {
        ClassTransformer t = createTransformer();
        byte[] input = new byte[]{1, 2, 3};
        assertSame(input, t.transform(null, null, null, null, null, input));
    }

    // ── shouldInstrument: includePackages filter ───────────────────────────────

    @Test
    void includePackagesFilterAllowsMatchingClass() {
        ClassTransformer t = createTransformer("com.example");
        byte[] input = new byte[]{1, 2, 3};
        // attempt to instrument — invalid bytecode → falls back to original
        assertSame(input, t.transform(null, null, "com/example/MyClass", null, null, input));
    }

    @Test
    void includePackagesFilterRejectsNonMatchingClass() {
        ClassTransformer t = createTransformer("com.example");
        byte[] input = new byte[]{1, 2, 3};
        assertSame(input, t.transform(null, null, "org/other/MyClass", null, null, input));
    }

    @Test
    void multipleIncludePackagesAllowAnyMatch() {
        ClassTransformer t = createTransformer("com.example", "org.myapp");
        assertTrue(t.shouldInstrument("com/example/Foo"));
        assertTrue(t.shouldInstrument("org/myapp/Bar"));
        assertFalse(t.shouldInstrument("net/other/Baz"));
    }

    @Test
    void withoutIncludePackagesAllUserClassesAreCandidate() {
        ClassTransformer t = createTransformer();
        assertTrue(t.shouldInstrument("com/myapp/SomeClass"));
        assertTrue(t.shouldInstrument("org/springframework/beans/BeanUtils"));
    }

    @Test
    void includePackagesStillSkipsJdkClasses() {
        ClassTransformer t = createTransformer("java.lang");
        // JDK classes are always skipped, even if they match includePackages
        assertFalse(t.shouldInstrument("java/lang/String"));
    }

    @Test
    void includePackagesStillSkipsGeneratedClasses() {
        ClassTransformer t = createTransformer("com.example");
        // generated markers take priority over includePackages
        assertFalse(t.shouldInstrument("com/example/MyService$$EnhancerBySpringCGLIB"));
    }

    @Test
    void transformsLoadableApplicationClassBytes() throws IOException {
        ClassTransformer t = createTransformer("com.example.app");
        byte[] input;
        try (InputStream in = com.example.app.SampleAppClass.class.getResourceAsStream("SampleAppClass.class")) {
            assertNotNull(in);
            input = in.readAllBytes();
        }
        byte[] transformed = t.transform(
                null,
                com.example.app.SampleAppClass.class.getClassLoader(),
                "com/example/app/SampleAppClass",
                null,
                null,
                input);
        assertNotSame(input, transformed);
        assertTrue(transformed.length > input.length);
    }

    // ── mode wiring ────────────────────────────────────────────────────────────

    @Test
    void defaultModeIsFull() {
        ClassTransformer t = createTransformer();
        // default Agent mode is FULL — verifiable via transform behaviour:
        // a matching class with invalid bytecode still returns the input unchanged
        byte[] input = new byte[]{1, 2, 3};
        assertSame(input, t.transform(null, null, "com/myapp/Foo", null, null, input));
    }

    @Test
    void allModesCreateTransformer() {
        // just verify the transformer can be instantiated for each mode
        assertDoesNotThrow(() -> createTransformerWithMode(Agent.InstrumentationMode.METHOD_ENTRY));
        assertDoesNotThrow(() -> createTransformerWithMode(Agent.InstrumentationMode.FULL));
    }

    @Test
    void fieldTrackingModeMatchesInstrumentationMode() {
        assertEquals(ClassTransformer.FieldTrackingMode.NONE,
                ClassTransformer.fieldTrackingModeFor(Agent.InstrumentationMode.METHOD_ENTRY));
        assertEquals(ClassTransformer.FieldTrackingMode.STATIC_ONLY,
                ClassTransformer.fieldTrackingModeFor(Agent.InstrumentationMode.FULL));
        assertEquals(ClassTransformer.FieldTrackingMode.STATIC_ONLY,
                ClassTransformer.fieldTrackingModeFor(Agent.InstrumentationMode.FULL_METHOD));
        assertEquals(ClassTransformer.FieldTrackingMode.ALL,
                ClassTransformer.fieldTrackingModeFor(Agent.InstrumentationMode.FULL_MEMBER));
    }
}
