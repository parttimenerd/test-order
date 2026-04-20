package me.bechberger.testorder.agent;

import me.bechberger.testorder.agent.runtime.AgentLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Analyzes Maven and Gradle project structures to identify user code vs. libraries.
 * 
 * Reads pom.xml and settings.gradle to determine:
 * - Source packages (application code to instrument)
 * - Dependency packages (external libraries to skip)
 * - Module structure (multi-module projects)
 * 
 * Enables intelligent, automatic filtering without manual configuration.
 */
public class ProjectStructureAnalyzer {

    private static final Pattern MAVEN_DEP_PATTERN = Pattern.compile(
            "<dependency>.*?<groupId>(.*?)</groupId>.*?<artifactId>(.*?)</artifactId>.*?</dependency>",
            Pattern.DOTALL);
    private static final Pattern GRADLE_DEP_PATTERN = Pattern.compile(
            "(?:implementation|api|compile)\\s*['\\\"]([^:]+):([^:]+):[^'\\\"]+['\\\"]");

    private final Path projectRoot;
    private final Set<String> userPackages = new HashSet<>();
    private final Set<String> dependencyPackages = new HashSet<>();
    private final Set<String> testPackages = new HashSet<>();
    
    public ProjectStructureAnalyzer(Path projectRoot) {
        this.projectRoot = projectRoot;
        analyze();
    }
    
    /**
     * Analyze project structure: read pom.xml, settings.gradle, etc.
     */
    private void analyze() {
        // Try Maven first
        Path pomFile = projectRoot.resolve("pom.xml");
        if (Files.exists(pomFile)) {
            analyzeMavenProject(pomFile);
        }
        
        // Try Gradle
        Path buildGradle = projectRoot.resolve("build.gradle");
        Path buildGradleKts = projectRoot.resolve("build.gradle.kts");
        if (Files.exists(buildGradle)) {
            analyzeGradleProject(buildGradle);
        } else if (Files.exists(buildGradleKts)) {
            analyzeGradleProject(buildGradleKts);
        }
        
        // Fallback: scan source directories
        if (userPackages.isEmpty()) {
            scanSourceDirectories();
        }
    }
    
    /**
     * Parse Maven pom.xml to extract package info.
     */
    private void analyzeMavenProject(Path pomFile) {
        try {
            String content = Files.readString(pomFile);
            
            // Extract groupId and artifactId
            String groupId = extractProjectXmlTag(content, "groupId");
            String artifactId = extractProjectXmlTag(content, "artifactId");
            
            if (groupId != null && artifactId != null) {
                // Main package: convert com.example:my-app → com.example.myapp
                String mainPackage = groupId + "." + artifactId.replace('-', '.');
                userPackages.add(mainPackage);
            }
            
            // Extract all dependencies (to skip them)
            var matcher = MAVEN_DEP_PATTERN.matcher(content);
            while (matcher.find()) {
                String depGroupId = matcher.group(1).trim();
                String depArtifactId = matcher.group(2).trim();
                
                // Skip test dependencies
                if (content.substring(matcher.end()).contains("<scope>test</scope>")) {
                    continue;
                }
                
                String depPackage = depGroupId + "." + depArtifactId.replace('-', '.');
                dependencyPackages.add(depGroupId);  // Add groupId as package prefix
            }
            
            // Look for source directory configuration
            String sourceDir = extractXmlTag(content, "sourceDirectory");
            if (sourceDir != null && sourceDir.contains("src")) {
                scanSourceDirectories();
            }
        } catch (IOException e) {
            AgentLogger.warn("Failed to analyze pom.xml at " + pomFile + ": " + e.getMessage());
        }
    }
    
    /**
     * Parse Gradle build files to extract package info.
     */
    private void analyzeGradleProject(Path buildFile) {
        try {
            String content = Files.readString(buildFile);
            
            // Extract group and artifactId
            String group = extractGradleProperty(content, "group");
            String archivesBaseName = extractGradleProperty(content, "archivesBaseName");
            String name = extractGradleProperty(content, "name");
            
            if (group != null) {
                String appPackage = group;
                if (archivesBaseName != null) {
                    appPackage = group + "." + archivesBaseName.replace('-', '.');
                } else if (name != null) {
                    appPackage = group + "." + name.replace('-', '.');
                }
                userPackages.add(appPackage);
            }
            
            // Extract dependencies
            var matcher = GRADLE_DEP_PATTERN.matcher(content);
            while (matcher.find()) {
                String depGroupId = matcher.group(1).trim();
                dependencyPackages.add(depGroupId);
            }
            
            scanSourceDirectories();
        } catch (IOException e) {
            AgentLogger.warn("Failed to analyze Gradle build file at " + buildFile + ": " + e.getMessage());
        }
    }
    
    /**
     * Scan src/ directory structure to find actual source packages.
     */
    private void scanSourceDirectories() {
        if (userPackages.isEmpty()) {
            Set<String> foundPackages = new HashSet<>();
            
            // Check common source directories
            String[] sourceDirs = {
                "src/main/java",
                "src/main/kotlin",
                "src/java",
                "src"
            };
            
            for (String srcDir : sourceDirs) {
                Path srcPath = projectRoot.resolve(srcDir);
                if (Files.isDirectory(srcPath)) {
                    // Scan for package directories (first few levels)
                    scanPackages(srcPath, "", foundPackages, 0);
                }
            }
            
            if (!foundPackages.isEmpty()) {
                userPackages.addAll(foundPackages);
            }
        }
        
        // Also scan test directories
        String[] testDirs = {
            "src/test/java",
            "src/test/kotlin",
            "test/java",
            "test"
        };
        
        for (String testDir : testDirs) {
            Path testPath = projectRoot.resolve(testDir);
            if (Files.isDirectory(testPath)) {
                Set<String> found = new HashSet<>();
                scanPackages(testPath, "", found, 0);
                testPackages.addAll(found);
            }
        }
    }
    
    /**
     * Recursively scan directory for Java packages.
     */
    private void scanPackages(Path dir, String packagePrefix, Set<String> result, int depth) {
        if (depth > 4) return; // Limit depth to avoid infinite recursion
        
        try {
            boolean hasJavaFiles = false;
            boolean hasSubdirs = false;

            File[] children = dir.toFile().listFiles();
            if (children == null) {
                AgentLogger.warn("Skipping unreadable directory during package scan: " + dir);
                return;
            }

            for (File file : children) {
                if (file.isFile() && (file.getName().endsWith(".java") || file.getName().endsWith(".kt"))) {
                    hasJavaFiles = true;
                } else if (file.isDirectory() && !file.getName().startsWith(".")) {
                    hasSubdirs = true;
                    String newPrefix = packagePrefix.isEmpty() ? file.getName() : packagePrefix + "." + file.getName();
                    scanPackages(file.toPath(), newPrefix, result, depth + 1);
                }
            }
            
            if (hasJavaFiles && !packagePrefix.isEmpty()) {
                result.add(packagePrefix);
            }
        } catch (RuntimeException e) {
            AgentLogger.warn("Failed while scanning packages in " + dir + ": " + e.getMessage());
        }
    }
    
    private static final HashMap<String, Pattern> xmlTagPatterns = new HashMap<>();
    private static final HashMap<String, Pattern> gradlePropertyPatterns = new HashMap<>();

    /**
     * Extract XML tag value from content.
     */
    private String extractXmlTag(String content, String tagName) {
        Pattern pattern = xmlTagPatterns.computeIfAbsent(tagName, tag ->
                Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">"));
        var matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Extract a project-level XML tag from a POM, skipping nested sections
     * ({@code <parent>}, {@code <dependencies>}, {@code <build>}) that may
     * contain the same tag name. Falls back to {@code <parent>} value for
     * {@code groupId} when the project inherits it.
     */
    private String extractProjectXmlTag(String content, String tagName) {
        // Strip sections that contain nested groupId/artifactId to avoid false matches
        String stripped = content
                .replaceAll("(?s)<parent>.*?</parent>", "")
                .replaceAll("(?s)<dependencies>.*?</dependencies>", "")
                .replaceAll("(?s)<build>.*?</build>", "")
                .replaceAll("(?s)<profiles>.*?</profiles>", "");
        String result = extractXmlTag(stripped, tagName);
        if (result != null) {
            return result;
        }
        // For groupId, fall back to parent's groupId (Maven inheritance)
        if ("groupId".equals(tagName)) {
            return extractXmlTag(content, tagName);
        }
        return null;
    }
    
    /**
     * Extract Gradle property value from content.
     */
    private String extractGradleProperty(String content, String propertyName) {
        Pattern pattern = gradlePropertyPatterns.computeIfAbsent(propertyName, prop ->
                Pattern.compile(prop + "\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]"));
        var matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
    
    /**
     * Get user packages (application code to instrument).
     */
    public Set<String> getUserPackages() {
        return new HashSet<>(userPackages);
    }
    
    /**
     * Get dependency packages (libraries to skip).
     */
    public Set<String> getDependencyPackages() {
        return new HashSet<>(dependencyPackages);
    }
    
    /**
     * Get test packages.
     */
    public Set<String> getTestPackages() {
        return new HashSet<>(testPackages);
    }
    
    /**
     * Build an IntelligentClassFilter based on detected project structure.
     */
    public IntelligentClassFilter buildFilter() {
        IntelligentClassFilter.Builder builder = new IntelligentClassFilter.Builder()
                .strategy(IntelligentClassFilter.Strategy.SMART)
                .useHeuristics(true)
                .skipTestClasses(true);
        
        // Add user packages as includes
        for (String pkg : userPackages) {
            builder.explicitInclude(pkg);
        }
        
        // Add dependency packages as excludes
        for (String pkg : dependencyPackages) {
            builder.explicitExclude(pkg);
        }
        
        // Add test packages as excludes
        for (String pkg : testPackages) {
            builder.explicitExclude(pkg);
        }
        
        return builder.build();
    }
    
    @Override
    public String toString() {
        return String.format("ProjectStructure{userPkgs=%s, depPkgs=%s, testPkgs=%s}",
                userPackages, dependencyPackages, testPackages);
    }
}
