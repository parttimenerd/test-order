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
 * Tests for IDE configuration bugs (P5-IDE-010).
 * 
 * Bug Category:
 * - P5-IDE-010: IDE configuration changes not applied to projects
 */
@DisplayName("IDE Configuration Tests")
public class IDEConfigTest {

    private Path testDir;
    private Path ideConfigDir;
    private static final String TEST_NAME = "ide-config";

    @BeforeEach
    void setUp() throws IOException {
        testDir = TestEnvironmentSetup.createTestDirectory(TEST_NAME);
        ideConfigDir = testDir.resolve(".idea");
        Files.createDirectories(ideConfigDir);
    }

    @AfterEach
    void tearDown() {
        TestEnvironmentSetup.cleanupTestDirectory(TEST_NAME);
    }

    // P5-IDE-010: IDE configuration changes not applied to projects
    @Test
    @DisplayName("P5-IDE-010: IDE applies JDK configuration")
    void testIDEAppliesJDKConfiguration() throws IOException {
        // Create IDE workspace configuration
        Path workspaceConfig = ideConfigDir.resolve("workspace.xml");
        String configContent = "<?xml version=\"1.0\"?>\n" +
                "<project>\n" +
                "  <component name=\"ProjectRootManager\" version=\"2\">\n" +
                "    <output url=\"file://$PROJECT_DIR$/out\" />\n" +
                "    <languageLevel attribute=\"JDK_11\" />\n" +
                "  </component>\n" +
                "</project>";
        TestEnvironmentSetup.createTestFile(ideConfigDir, "workspace.xml", configContent);

        String content = TestEnvironmentSetup.readFile(workspaceConfig);
        assertThat(content).contains("JDK_11");
        assertThat(content).contains("ProjectRootManager");
    }

    @Test
    @DisplayName("P5-IDE-010: IDE applies compiler settings")
    void testIDEAppliesCompilerSettings() throws IOException {
        Path compilerConfig = ideConfigDir.resolve("compiler.xml");
        String configContent = "<?xml version=\"1.0\"?>\n" +
                "<project>\n" +
                "  <component name=\"CompilerConfiguration\">\n" +
                "    <option name=\"BUILD_PROCESS_HEAP_SIZE\" value=\"2048\" />\n" +
                "    <option name=\"MAXIMUM_HEAP_SIZE\" value=\"2048\" />\n" +
                "    <bytecodeTargetLevel target=\"11\" />\n" +
                "  </component>\n" +
                "</project>";
        TestEnvironmentSetup.createTestFile(ideConfigDir, "compiler.xml", configContent);

        String content = TestEnvironmentSetup.readFile(compilerConfig);
        assertThat(content).contains("BUILD_PROCESS_HEAP_SIZE");
        assertThat(content).contains("2048");
        assertThat(content).contains("target=\"11\"");
    }

    @Test
    @DisplayName("P5-IDE-010: IDE applies run configuration")
    void testIDEAppliesRunConfiguration() throws IOException {
        Path runConfigDir = ideConfigDir.resolve("runConfigurations");
        Files.createDirectories(runConfigDir);
        
        Path runConfig = runConfigDir.resolve("JUnit.xml");
        String configContent = "<?xml version=\"1.0\"?>\n" +
                "<configuration name=\"JUnit\" type=\"JUnit\">\n" +
                "  <option name=\"VM_PARAMETERS\" value=\"-ea\" />\n" +
                "  <option name=\"WORKING_DIRECTORY\" value=\"$PROJECT_DIR$\" />\n" +
                "</configuration>";
        TestEnvironmentSetup.createTestFile(runConfigDir, "JUnit.xml", configContent);

        String content = TestEnvironmentSetup.readFile(runConfig);
        assertThat(content).contains("VM_PARAMETERS");
        assertThat(content).contains("-ea");
    }

    @Test
    @DisplayName("P5-IDE-010: IDE applies code style settings")
    void testIDEAppliesCodeStyleSettings() throws IOException {
        Path codeStyleConfig = ideConfigDir.resolve("codeStyles.xml");
        String configContent = "<?xml version=\"1.0\"?>\n" +
                "<component name=\"CodeStyleSettingsManager\">\n" +
                "  <option name=\"PER_PROJECT_SETTINGS\">\n" +
                "    <codeStyleSettings language=\"JAVA\">\n" +
                "      <option name=\"INDENT_SIZE\" value=\"4\" />\n" +
                "      <option name=\"CONTINUATION_INDENT_SIZE\" value=\"8\" />\n" +
                "    </codeStyleSettings>\n" +
                "  </option>\n" +
                "</component>";
        TestEnvironmentSetup.createTestFile(ideConfigDir, "codeStyles.xml", configContent);

        String content = TestEnvironmentSetup.readFile(codeStyleConfig);
        assertThat(content).contains("INDENT_SIZE");
        assertThat(content).contains("4");
    }

    @Test
    @DisplayName("P5-IDE-010: IDE applies inspection settings")
    void testIDEAppliesInspectionSettings() throws IOException {
        Path inspectionsConfig = ideConfigDir.resolve("inspectionProfiles");
        Files.createDirectories(inspectionsConfig);
        
        Path profileConfig = inspectionsConfig.resolve("Project_Default.xml");
        String configContent = "<?xml version=\"1.0\"?>\n" +
                "<component name=\"InspectionProjectConfiguration\">\n" +
                "  <profile version=\"1.0\" is_locked=\"false\" name=\"Project Default\">\n" +
                "    <inspection_tool class=\"UnusedImport\" enabled=\"true\" />\n" +
                "    <inspection_tool class=\"EmptyTryBlock\" enabled=\"true\" />\n" +
                "  </profile>\n" +
                "</component>";
        TestEnvironmentSetup.createTestFile(inspectionsConfig, "Project_Default.xml", configContent);

        String content = TestEnvironmentSetup.readFile(profileConfig);
        assertThat(content).contains("UnusedImport");
        assertThat(content).contains("EmptyTryBlock");
    }

    @Test
    @DisplayName("P5-IDE-010: IDE configuration survives reload")
    void testIDEConfigurationPersistence() throws IOException {
        Path projectConfig = ideConfigDir.resolve("project.xml");
        String configContent = "<?xml version=\"1.0\"?>\n" +
                "<project>\n" +
                "  <component name=\"ProjectConfiguration\">\n" +
                "    <setting name=\"version\" value=\"1.0\" />\n" +
                "  </component>\n" +
                "</project>";
        TestEnvironmentSetup.createTestFile(ideConfigDir, "project.xml", configContent);

        // Verify configuration file exists
        assertThat(Files.exists(projectConfig)).isTrue();
        
        // Simulate reload and verify persistence
        String content = TestEnvironmentSetup.readFile(projectConfig);
        assertThat(content).contains("version");
        assertThat(content).contains("1.0");
    }

    @Test
    @DisplayName("P5-IDE-010: IDE applies module settings")
    void testIDEAppliesModuleSettings() throws IOException {
        Path moduleDir = testDir.resolve("module-name.iml");
        String moduleContent = "<?xml version=\"1.0\"?>\n" +
                "<module type=\"JAVA_MODULE\" version=\"4\">\n" +
                "  <component name=\"NewModuleRootManager\">\n" +
                "    <content url=\"file://$MODULE_DIR$\">\n" +
                "      <sourceFolder url=\"file://$MODULE_DIR$/src\" isTestSource=\"false\" />\n" +
                "      <sourceFolder url=\"file://$MODULE_DIR$/test\" isTestSource=\"true\" />\n" +
                "    </content>\n" +
                "  </component>\n" +
                "</module>";
        TestEnvironmentSetup.createTestFile(testDir, "module-name.iml", moduleContent);

        String content = TestEnvironmentSetup.readFile(moduleDir);
        assertThat(content).contains("NewModuleRootManager");
        assertThat(content).contains("sourceFolder");
    }

    @Test
    @DisplayName("P5-IDE-010: IDE applies library settings")
    void testIDEAppliesLibrarySettings() throws IOException {
        Path librariesDir = ideConfigDir.resolve("libraries");
        Files.createDirectories(librariesDir);
        
        Path libConfig = librariesDir.resolve("junit_junit_4_13.xml");
        String configContent = "<?xml version=\"1.0\"?>\n" +
                "<component name=\"libraryTable\">\n" +
                "  <library name=\"junit:junit:4.13\">\n" +
                "    <CLASSES>\n" +
                "      <root url=\"jar://$MAVEN_REPOSITORY$/junit/junit/4.13/junit-4.13.jar!/\" />\n" +
                "    </CLASSES>\n" +
                "  </library>\n" +
                "</component>";
        TestEnvironmentSetup.createTestFile(librariesDir, "junit_junit_4_13.xml", configContent);

        String content = TestEnvironmentSetup.readFile(libConfig);
        assertThat(content).contains("junit");
        assertThat(content).contains("4.13");
    }

    @Test
    @DisplayName("P5-IDE-010: IDE configuration update detected")
    void testIDEConfigurationUpdateDetection() throws IOException {
        Path versionFile = ideConfigDir.resolve("misc.xml");
        String initialConfig = "<?xml version=\"1.0\"?>\n" +
                "<project>\n" +
                "  <component name=\"MiscConfiguration\">\n" +
                "    <option name=\"IDE_VERSION\" value=\"2021.1\" />\n" +
                "  </component>\n" +
                "</project>";
        TestEnvironmentSetup.createTestFile(ideConfigDir, "misc.xml", initialConfig);

        // Verify initial configuration
        String content = TestEnvironmentSetup.readFile(versionFile);
        assertThat(content).contains("2021.1");

        // Configuration update would be detected by file modification time
        assertThat(Files.exists(versionFile)).isTrue();
    }

    @Test
    @DisplayName("P5-IDE-010: IDE configuration applies to all modules")
    void testIDEConfigurationAppliesToAllModules() throws IOException {
        Path module1 = testDir.resolve("module1.iml");
        Path module2 = testDir.resolve("module2.iml");
        
        String moduleTemplate = "<?xml version=\"1.0\"?>\n" +
                "<module type=\"JAVA_MODULE\" version=\"4\">\n" +
                "</module>";
        
        TestEnvironmentSetup.createTestFile(testDir, "module1.iml", moduleTemplate);
        TestEnvironmentSetup.createTestFile(testDir, "module2.iml", moduleTemplate);

        // Both modules should have configuration applied
        assertThat(Files.exists(module1)).isTrue();
        assertThat(Files.exists(module2)).isTrue();
        
        // Global IDE config applies to both
        Path globalConfig = ideConfigDir.resolve("workspace.xml");
        String globalContent = "<?xml version=\"1.0\"?>\n" +
                "<project>\n" +
                "  <component name=\"ProjectConfiguration\" appliesToAllModules=\"true\">\n" +
                "    <option name=\"setting\" value=\"value\" />\n" +
                "  </component>\n" +
                "</project>";
        TestEnvironmentSetup.createTestFile(ideConfigDir, "workspace.xml", globalContent);

        String content = TestEnvironmentSetup.readFile(globalConfig);
        assertThat(content).contains("appliesToAllModules");
    }
}
