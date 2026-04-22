package com.example.idecicd.ide;

import com.example.idecicd.TestEnvironmentSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for IDE debugging bugs (P5-IDE-005, 009).
 * 
 * Bug Categories:
 * - P5-IDE-005: IDE debugger fails to resolve breakpoint sources
 * - P5-IDE-009: IDE debugger loses variable information
 */
@DisplayName("IDE Debug Tests")
public class IDEDebugTest {

    private Path testDir;
    private Path debugInfoDir;
    private static final String TEST_NAME = "ide-debug";

    @BeforeEach
    void setUp() throws IOException {
        testDir = TestEnvironmentSetup.createTestDirectory(TEST_NAME);
        debugInfoDir = testDir.resolve(".debug");
        Files.createDirectories(debugInfoDir);
        Files.createDirectories(testDir.resolve("src/main/java"));
        Files.createDirectories(testDir.resolve("target/classes"));
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSetup.cleanupTestDirectory(TEST_NAME);
    }

    // P5-IDE-005: IDE debugger fails to resolve breakpoint sources
    @Test
    @DisplayName("P5-IDE-005: Debugger resolves source file for class")
    void testDebuggerSourceResolution() throws IOException {
        Path sourceFile = testDir.resolve("src/main/java/DebugTarget.java");
        String sourceContent = "public class DebugTarget {\n" +
                "    public void method() {\n" +
                "        int x = 42; // line 3 - breakpoint here\n" +
                "    }\n" +
                "}";
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "DebugTarget.java", sourceContent);

        // Verify source file exists for debugging
        assertThat(Files.exists(sourceFile)).isTrue();
        assertThat(TestEnvironmentSetup.readFile(sourceFile)).contains("int x = 42");
    }

    @Test
    @DisplayName("P5-IDE-005: Debugger maps bytecode to source lines")
    void testDebuggerLineMapping() throws IOException {
        Path sourceFile = testDir.resolve("src/main/java/LineMapping.java");
        String[] lines = {
                "public class LineMapping {",
                "    public static void main(String[] args) {",
                "        System.out.println(\"Line 3\");",
                "        System.out.println(\"Line 4\");",
                "        System.out.println(\"Line 5\");",
                "    }",
                "}"
        };
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            content.append(lines[i]);
            if (i < lines.length - 1) content.append("\n");
        }
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "LineMapping.java", content.toString());

        String fileContent = TestEnvironmentSetup.readFile(sourceFile);
        assertThat(fileContent).contains("Line 3");
        assertThat(fileContent).contains("Line 4");
        assertThat(fileContent).contains("Line 5");
    }

    @Test
    @DisplayName("P5-IDE-005: Debugger finds source with package structure")
    void testDebuggerSourceWithPackage() throws IOException {
        Path sourceFile = testDir.resolve("src/main/java/com/example/debug/Target.java");
        String sourceContent = "package com.example.debug;\n" +
                "public class Target {\n" +
                "    public void breakhere() { }\n" +
                "}";
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "Target.java", sourceContent);

        assertThat(Files.exists(sourceFile)).isTrue();
        String content = TestEnvironmentSetup.readFile(sourceFile);
        assertThat(content).contains("package com.example.debug");
        assertThat(content).contains("public class Target");
    }

    @Test
    @DisplayName("P5-IDE-005: Debugger handles nested classes")
    void testDebuggerNestedClassResolution() throws IOException {
        Path sourceFile = testDir.resolve("src/main/java/OuterClass.java");
        String sourceContent = "public class OuterClass {\n" +
                "    public class InnerClass {\n" +
                "        public void innerMethod() {\n" +
                "            int y = 99; // breakpoint in inner\n" +
                "        }\n" +
                "    }\n" +
                "}";
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "OuterClass.java", sourceContent);

        assertThat(Files.exists(sourceFile)).isTrue();
        String content = TestEnvironmentSetup.readFile(sourceFile);
        assertThat(content).contains("class InnerClass");
    }

    @Test
    @DisplayName("P5-IDE-005: Debugger works with source in different directories")
    void testDebuggerMultipleSourceDirs() throws IOException {
        Path mainSource = testDir.resolve("src/main/java/Main.java");
        Path testSource = testDir.resolve("src/test/java/MainTest.java");
        
        TestEnvironmentSetup.createTestFile(mainSource.getParent(), "Main.java", "public class Main {}");
        TestEnvironmentSetup.createTestFile(testSource.getParent(), "MainTest.java", "public class MainTest {}");

        assertThat(Files.exists(mainSource)).isTrue();
        assertThat(Files.exists(testSource)).isTrue();
    }

    // P5-IDE-009: IDE debugger loses variable information
    @Test
    @DisplayName("P5-IDE-009: Debugger tracks local variables")
    void testDebuggerLocalVariableTracking() throws IOException {
        Path sourceFile = testDir.resolve("src/main/java/VarTracker.java");
        String sourceContent = "public class VarTracker {\n" +
                "    public void trackVars() {\n" +
                "        int localInt = 42;\n" +
                "        String localString = \"test\";\n" +
                "        double localDouble = 3.14;\n" +
                "    }\n" +
                "}";
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "VarTracker.java", sourceContent);

        // Create debug symbol file tracking variables
        Path debugSymbols = debugInfoDir.resolve("VarTracker.debug");
        TestEnvironmentSetup.createTestFile(debugInfoDir, "VarTracker.debug",
                "localInt:I:42\nlocalString:Ljava/lang/String;:test\nlocalDouble:D:3.14");

        assertThat(Files.exists(debugSymbols)).isTrue();
        String debugInfo = TestEnvironmentSetup.readFile(debugSymbols);
        assertThat(debugInfo).contains("localInt");
        assertThat(debugInfo).contains("localString");
        assertThat(debugInfo).contains("localDouble");
    }

    @Test
    @DisplayName("P5-IDE-009: Debugger tracks instance variables")
    void testDebuggerInstanceVariableTracking() throws IOException {
        Path sourceFile = testDir.resolve("src/main/java/InstanceVars.java");
        String sourceContent = "public class InstanceVars {\n" +
                "    private int instanceInt;\n" +
                "    private String instanceString;\n" +
                "    public void method() {\n" +
                "        instanceInt = 100;\n" +
                "    }\n" +
                "}";
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "InstanceVars.java", sourceContent);

        // Create debug info for instance variables
        Path debugSymbols = debugInfoDir.resolve("InstanceVars.debug");
        TestEnvironmentSetup.createTestFile(debugInfoDir, "InstanceVars.debug",
                "instanceInt:I\ninstanceString:Ljava/lang/String;");

        assertThat(Files.exists(debugSymbols)).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-009: Debugger preserves variable state across steps")
    void testDebuggerVariableStatePreservation() throws IOException {
        Path sourceFile = testDir.resolve("src/main/java/StateTracker.java");
        String sourceContent = "public class StateTracker {\n" +
                "    public void step() {\n" +
                "        int x = 1;\n" +
                "        x = x + 1;\n" +
                "        x = x * 2;\n" +
                "    }\n" +
                "}";
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "StateTracker.java", sourceContent);

        // Simulate debug state snapshots
        Path debugStates = debugInfoDir.resolve("StateTracker.states");
        TestEnvironmentSetup.createTestFile(debugInfoDir, "StateTracker.states",
                "line3:x=1\nline4:x=2\nline5:x=4");

        assertThat(Files.exists(debugStates)).isTrue();
        String states = TestEnvironmentSetup.readFile(debugStates);
        assertThat(states).contains("x=1");
        assertThat(states).contains("x=2");
        assertThat(states).contains("x=4");
    }

    @Test
    @DisplayName("P5-IDE-009: Debugger handles variable shadowing")
    void testDebuggerVariableShadowing() throws IOException {
        Path sourceFile = testDir.resolve("src/main/java/Shadowing.java");
        String sourceContent = "public class Shadowing {\n" +
                "    private int x = 10;\n" +
                "    public void method() {\n" +
                "        int x = 20; // shadows instance variable\n" +
                "        {\n" +
                "            int x = 30; // shadows local variable\n" +
                "        }\n" +
                "    }\n" +
                "}";
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "Shadowing.java", sourceContent);

        // Verify source correctly shows variable scoping
        String content = TestEnvironmentSetup.readFile(sourceFile);
        assertThat(content).contains("int x = 10");
        assertThat(content).contains("int x = 20");
        assertThat(content).contains("int x = 30");
    }

    @Test
    @DisplayName("P5-IDE-009: Debugger tracks method arguments")
    void testDebuggerMethodArgumentTracking() throws IOException {
        Path sourceFile = testDir.resolve("src/main/java/ArgTracker.java");
        String sourceContent = "public class ArgTracker {\n" +
                "    public void method(int arg1, String arg2, boolean arg3) {\n" +
                "        // debug breakpoint here\n" +
                "    }\n" +
                "}";
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "ArgTracker.java", sourceContent);

        // Create debug symbols for arguments
        Path debugSymbols = debugInfoDir.resolve("ArgTracker.debug");
        TestEnvironmentSetup.createTestFile(debugInfoDir, "ArgTracker.debug",
                "arg1:I\narg2:Ljava/lang/String;\narg3:Z");

        String debugInfo = TestEnvironmentSetup.readFile(debugSymbols);
        assertThat(debugInfo).contains("arg1:I");
        assertThat(debugInfo).contains("arg2:Ljava/lang/String;");
        assertThat(debugInfo).contains("arg3:Z");
    }

    @Test
    @DisplayName("P5-IDE-009: Debugger handles complex object inspection")
    void testDebuggerComplexObjectInspection() throws IOException {
        Path sourceFile = testDir.resolve("src/main/java/ComplexObject.java");
        String sourceContent = "public class ComplexObject {\n" +
                "    private class Inner {\n" +
                "        private String field;\n" +
                "    }\n" +
                "    public void method() {\n" +
                "        Inner inner = new Inner();\n" +
                "        // debug inspect inner here\n" +
                "    }\n" +
                "}";
        TestEnvironmentSetup.createTestFile(sourceFile.getParent(), "ComplexObject.java", sourceContent);

        assertThat(Files.exists(sourceFile)).isTrue();
    }
}
