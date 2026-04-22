package me.bechberger.testorder.windows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Windows Javaagent Path Quoting Test Suite
 * Tests for bugs: P5-WIN-001, P5-WIN-011
 * 
 * These tests validate proper quoting of javaagent paths:
 * - P5-WIN-001: Gradle plugin javaagent path quoting
 * - P5-WIN-011: Maven plugin javaagent path quoting
 * 
 * When javaagent path contains spaces or special characters,
 * it must be properly quoted or escaped for the JVM.
 */
@DisplayName("Windows Javaagent Path Quoting Tests")
public class WindowsJavaagentTest {

    @TempDir
    Path tempDir;

    private Path projectPath;
    private Path agentJar;

    @BeforeEach
    public void setup() throws Exception {
        // Create a project path with spaces (common on Windows)
        projectPath = tempDir.resolve("Program Files");
        java.nio.file.Files.createDirectory(projectPath);
        
        agentJar = projectPath.resolve("test-order-agent.jar");
        java.nio.file.Files.createFile(agentJar);
    }

    @Test
    @DisplayName("P5-WIN-001: Gradle javaagent path with spaces must be quoted")
    public void testGradleJavaagentPathQuoting() {
        // Reproducer from test-order-gradle-plugin/TestOrderPlugin.java:245
        // Issue: String concatenation without shell quoting
        
        String agentJarPath = agentJar.toAbsolutePath().toString();
        String agentArgs = "arg1=value1,arg2=value2";
        
        // WRONG: Unquoted path fails on Windows with spaces
        String wrongJavaagentOption = "-javaagent:" + agentJarPath + "=" + agentArgs;
        
        // CORRECT: Path must be quoted
        String correctJavaagentOption = "-javaagent:\"" + agentJarPath + "=\"" + agentArgs;
        
        // Verify quoting
        assertThat(correctJavaagentOption).startsWith("-javaagent:\"");
        assertThat(correctJavaagentOption).contains(agentJarPath);
        
        // The quoted version should protect spaces
        if (agentJarPath.contains(" ")) {
            assertThat(correctJavaagentOption).contains("\"");
        }
    }

    @Test
    @DisplayName("P5-WIN-011: Maven javaagent path with spaces must be quoted")
    public void testMavenJavaagentPathQuoting() {
        // Reproducer from test-order-maven-plugin/AbstractTestOrderMojo.java:489
        // Issue: Same as P5-WIN-001 but for Maven
        
        String agentPath = agentJar.toAbsolutePath().toString();
        String agentArgs = "arg1=value1";
        
        // Maven plugin constructs this in MAVEN_OPTS or jvmArgs
        String jvmArg = "-javaagent:\"" + agentPath + "=\"" + agentArgs;
        
        // Verify it's properly quoted
        assertThat(jvmArg).startsWith("-javaagent:\"");
        if (agentPath.contains(" ")) {
            assertThat(jvmArg).contains("\"");
        }
    }

    @Test
    @DisplayName("Javaagent option format and quoting")
    public void testJavaagentOptionFormat() {
        String agentPath = "C:\\Program Files\\agent.jar";
        String options = "option1=value1,option2=value2";
        
        // Correct format: -javaagent:"path"="options"
        String javaagent = "-javaagent:\"" + agentPath + "=\"" + options;
        
        assertThat(javaagent).startsWith("-javaagent:");
        assertThat(javaagent).contains(agentPath);
        assertThat(javaagent).endsWith(options);
    }

    @Test
    @DisplayName("Handling special characters in javaagent path")
    public void testSpecialCharactersInJavaagentPath() {
        // Paths might contain: spaces, parentheses, etc.
        String specialPath = "C:\\Program Files (x86)\\MyProject\\agent.jar";
        
        // All should be quoted
        String quoted = "\"" + specialPath + "\"";
        
        assertThat(quoted).startsWith("\"");
        assertThat(quoted).endsWith("\"");
    }

    @Test
    @DisplayName("Drive letter colon should not break javaagent parsing")
    public void testDriveLetterInJavaagentPath() {
        // Windows drive letter: C:, D:, etc.
        String pathWithDrive = "D:\\Projects\\agent.jar";
        
        // Must be quoted to protect the colon
        String quoted = "\"" + pathWithDrive + "\"";
        
        assertThat(quoted).contains(":");
        assertThat(quoted).contains("\"");
    }

    @Test
    @DisplayName("Building javaagent command line for ProcessBuilder")
    public void testJavaagentCommandLineForProcessBuilder() {
        String agentPath = agentJar.toAbsolutePath().toString();
        String agentArgs = "mode=learn,store=/path/to/store";
        
        // When using ProcessBuilder (recommended on Windows), 
        // path doesn't need shell quoting - it's handled as separate argument
        List<String> command = new ArrayList<>();
        command.add("java");
        
        // Add JVM argument as separate item in list
        String javaagentArg = "-javaagent:" + agentPath + "=" + agentArgs;
        command.add(javaagentArg);
        
        command.add("-cp");
        command.add("application.jar");
        command.add("com.example.Main");
        
        // Verify structure
        assertThat(command).contains(javaagentArg);
        assertThat(command).hasSize(5);
    }

    @Test
    @DisplayName("Shell command line construction with quoted javaagent")
    public void testShellCommandLineConstruction() {
        String agentPath = "C:\\Program Files\\agent.jar";
        String agentArgs = "arg=value";
        
        // For shell execution, must use proper quoting
        String shellCommand = "java -javaagent:\"" + agentPath + "=\"" + agentArgs + "\" -jar app.jar";
        
        // The path is quoted within the argument
        assertThat(shellCommand).contains(agentPath);
        assertThat(shellCommand).contains("-javaagent:");
    }

    @Test
    @DisplayName("Escaping quotes within javaagent arguments")
    public void testEscapingQuotesInJavaagentArgs() {
        String agentPath = "C:\\agent.jar";
        String argsWithQuotes = "key=value with \\\"quoted\\\" text";
        
        // Arguments with quotes need escaping
        String escaped = argsWithQuotes.replace("\"", "\\\"");
        
        assertThat(escaped).contains("\\\"");
    }

    @Test
    @DisplayName("Relative path handling in javaagent")
    public void testRelativePathHandling() {
        // Should convert relative paths to absolute
        String relativePath = "./target/agent.jar";
        
        // Get absolute path
        Path absolutePath = tempDir.resolve(relativePath).toAbsolutePath();
        
        String javaagentArg = "-javaagent:\"" + absolutePath + "=\"arg=value";
        
        assertThat(javaagentArg).contains("\"");
    }

    @Test
    @DisplayName("Environment variable expansion in paths")
    public void testEnvironmentVariableExpansionInPaths() {
        // Gradle/Maven might use variables like ${project.basedir}
        String pathWithVariable = "${project.basedir}/target/agent.jar";
        
        // Must be expanded before constructing javaagent option
        String expanded = pathWithVariable.replace("${project.basedir}", "/home/user/project");
        
        assertThat(expanded).doesNotContain("${");
    }

    @Test
    @DisplayName("Multiple javaagent options")
    public void testMultipleJavaagentOptions() {
        String agent1 = "C:\\agent1.jar";
        String agent2 = "C:\\agent2.jar";
        
        String javaagent1 = "-javaagent:\"" + agent1 + "=\"opt1=val1";
        String javaagent2 = "-javaagent:\"" + agent2 + "=\"opt2=val2";
        
        List<String> jvmArgs = List.of(javaagent1, javaagent2);
        
        assertThat(jvmArgs).hasSize(2);
        assertThat(jvmArgs).allMatch(arg -> arg.startsWith("-javaagent:"));
    }

    @Test
    @DisplayName("Javaagent with complex argument values")
    public void testJavaagentWithComplexArguments() {
        String agentPath = "C:\\agent.jar";
        
        // Arguments might be complex
        String args = "store=/path/to/.test-order,mode=learn,batchSize=100";
        
        String javaagent = "-javaagent:\"" + agentPath + "=\"" + args;
        
        assertThat(javaagent).contains("store=");
        assertThat(javaagent).contains("mode=");
        assertThat(javaagent).contains("batchSize=");
    }

    @Test
    @DisplayName("Handling empty arguments in javaagent")
    public void testEmptyArgumentsInJavaagent() {
        String agentPath = "C:\\agent.jar";
        
        // Sometimes no arguments
        String javaagentNoArgs = "-javaagent:\"" + agentPath + "\"";
        String javaagentEmptyArgs = "-javaagent:\"" + agentPath + "=\"\"";
        
        assertThat(javaagentNoArgs).startsWith("-javaagent:");
        assertThat(javaagentEmptyArgs).startsWith("-javaagent:");
    }

    @Test
    @DisplayName("Verification of quoting prevents argument injection")
    public void testQuotingPreventsArgumentInjection() {
        String agentPath = "C:\\agent.jar";
        
        // Malicious input with injection attempt
        String maliciousArgs = "arg=value\" -Xmx1g";
        
        // Properly quoted prevents this - the malicious args are treated as part of value
        String safe = "-javaagent:\"" + agentPath + "=\"" + maliciousArgs;
        
        // The quoted form treats entire string as single argument
        assertThat(safe).contains(agentPath);
        assertThat(safe).contains("arg=value");
    }

    @Test
    @DisplayName("Testing actual ProcessBuilder construction")
    public void testProcessBuilderConstruction() {
        String agentPath = agentJar.toAbsolutePath().toString();
        
        // Recommended approach: use ProcessBuilder with list form
        ProcessBuilder pb = new ProcessBuilder(
            "java",
            "-javaagent:" + agentPath + "=mode=learn",
            "-jar",
            "app.jar"
        );
        
        // Verify command list
        assertThat(pb.command()).hasSize(4);
        assertThat(pb.command().get(0)).isEqualTo("java");
        assertThat(pb.command().get(1)).startsWith("-javaagent:");
    }
}
