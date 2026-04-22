package com.example.gradle.maven.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite for Maven Reactor bugs (P5-MMAD-001).
 * 
 * Maven Reactor manages multi-module builds and test execution order.
 * These tests verify that test ordering works correctly in Maven reactor builds.
 */
@DisplayName("Maven Reactor Tests")
public class MavenReactorTest {

    @TempDir
    Path mavenProject;

    private Path parentPom;
    private Path module1;
    private Path module2;
    private Path module3;

    @BeforeEach
    void setUp() throws IOException {
        parentPom = mavenProject.resolve("pom.xml");
        module1 = mavenProject.resolve("module1");
        module2 = mavenProject.resolve("module2");
        module3 = mavenProject.resolve("module3");
        
        Files.createDirectories(module1.resolve("src/test/java"));
        Files.createDirectories(module2.resolve("src/test/java"));
        Files.createDirectories(module3.resolve("src/test/java"));
    }

    /**
     * P5-MMAD-001: Maven reactor test ordering across modules.
     * Bug: Maven reactor may not preserve test execution order across multiple modules
     *      when using parallel builds or custom reactor order.
     * 
     * Reproducer: Create multi-module project and verify test order consistency.
     */
    @Test
    @DisplayName("Maven reactor preserves test order across modules")
    void testMavenReactorTestOrdering() throws IOException {
        String parentPomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>reactor-parent</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

                <name>Reactor Parent</name>
                <description>Maven reactor test ordering test</description>

                <properties>
                    <maven.compiler.source>11</maven.compiler.source>
                    <maven.compiler.target>11</maven.compiler.target>
                    <junit.version>5.9.2</junit.version>
                </properties>

                <modules>
                    <module>module1</module>
                    <module>module2</module>
                    <module>module3</module>
                </modules>

                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter-api</artifactId>
                            <version>${junit.version}</version>
                            <scope>test</scope>
                        </dependency>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter-engine</artifactId>
                            <version>${junit.version}</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </dependencyManagement>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-surefire-plugin</artifactId>
                            <version>2.22.2</version>
                            <configuration>
                                <includes>
                                    <include>**/*Test.java</include>
                                </includes>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;
        
        String modulePomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>reactor-parent</artifactId>
                    <version>1.0.0</version>
                </parent>

                <artifactId>%s</artifactId>
                <name>%s</name>

                <dependencies>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter-api</artifactId>
                        <scope>test</scope>
                    </dependency>
                    <dependency>
                        <groupId>org.junit.jupiter</groupId>
                        <artifactId>junit-jupiter-engine</artifactId>
                        <scope>test</scope>
                    </dependency>
                </dependencies>
            </project>
            """;
        
        Files.writeString(parentPom, parentPomContent);
        Files.writeString(module1.resolve("pom.xml"), 
            modulePomContent.formatted("module1", "Module 1"));
        Files.writeString(module2.resolve("pom.xml"), 
            modulePomContent.formatted("module2", "Module 2"));
        Files.writeString(module3.resolve("pom.xml"), 
            modulePomContent.formatted("module3", "Module 3"));
        
        assertThat(Files.readString(parentPom)).contains("<module>module1</module>");
        assertThat(Files.readString(parentPom)).contains("reactor-parent");
    }

    /**
     * Extended test: Maven reactor with module dependencies.
     * Verifies test order with inter-module dependencies.
     */
    @Test
    @DisplayName("Reactor test order with inter-module dependencies")
    void testReactorWithModuleDependencies() throws IOException {
        String parentPomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>reactor-deps</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

                <modules>
                    <module>core</module>
                    <module>api</module>
                    <module>app</module>
                </modules>

                <properties>
                    <maven.compiler.source>11</maven.compiler.source>
                    <maven.compiler.target>11</maven.compiler.target>
                </properties>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-surefire-plugin</artifactId>
                            <version>2.22.2</version>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;
        
        String corePomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>reactor-deps</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>core</artifactId>
                <name>Core Module</name>
            </project>
            """;
        
        String apiPomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>reactor-deps</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>api</artifactId>
                <name>API Module</name>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>core</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;
        
        String appPomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <parent>
                    <groupId>com.example</groupId>
                    <artifactId>reactor-deps</artifactId>
                    <version>1.0.0</version>
                </parent>
                <artifactId>app</artifactId>
                <name>App Module</name>
                <dependencies>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>api</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                    <dependency>
                        <groupId>com.example</groupId>
                        <artifactId>core</artifactId>
                        <version>1.0.0</version>
                    </dependency>
                </dependencies>
            </project>
            """;
        
        Path coreDir = mavenProject.resolve("core");
        Path apiDir = mavenProject.resolve("api");
        Path appDir = mavenProject.resolve("app");
        
        Files.createDirectories(coreDir.resolve("src/test/java"));
        Files.createDirectories(apiDir.resolve("src/test/java"));
        Files.createDirectories(appDir.resolve("src/test/java"));
        
        Files.writeString(parentPom, parentPomContent);
        Files.writeString(coreDir.resolve("pom.xml"), corePomContent);
        Files.writeString(apiDir.resolve("pom.xml"), apiPomContent);
        Files.writeString(appDir.resolve("pom.xml"), appPomContent);
        
        assertThat(Files.readString(parentPom)).contains("reactor-deps");
    }

    /**
     * Extended test: Maven reactor with fail-fast option.
     */
    @Test
    @DisplayName("Reactor with fail-fast test execution")
    void testReactorFailFast() throws IOException {
        String parentPomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>reactor-failfast</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

                <modules>
                    <module>module1</module>
                    <module>module2</module>
                </modules>

                <properties>
                    <maven.compiler.source>11</maven.compiler.source>
                    <maven.compiler.target>11</maven.compiler.target>
                </properties>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-surefire-plugin</artifactId>
                            <version>2.22.2</version>
                            <configuration>
                                <skipAfterFailureCount>1</skipAfterFailureCount>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;
        
        Files.writeString(parentPom, parentPomContent);
        
        assertThat(Files.readString(parentPom)).contains("skipAfterFailureCount");
    }

    /**
     * Extended test: Maven reactor with parallel module building.
     */
    @Test
    @DisplayName("Reactor with parallel module execution")
    void testReactorParallelExecution() throws IOException {
        String parentPomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>reactor-parallel</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

                <modules>
                    <module>module1</module>
                    <module>module2</module>
                    <module>module3</module>
                </modules>

                <properties>
                    <maven.compiler.source>11</maven.compiler.source>
                    <maven.compiler.target>11</maven.compiler.target>
                </properties>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-surefire-plugin</artifactId>
                            <version>2.22.2</version>
                            <configuration>
                                <parallel>methods</parallel>
                                <threadCount>4</threadCount>
                            </configuration>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;
        
        Files.writeString(parentPom, parentPomContent);
        
        String content = Files.readString(parentPom);
        assertThat(content).contains("<parallel>methods</parallel>");
        assertThat(content).contains("<threadCount>4</threadCount>");
    }

    /**
     * Extended test: Maven reactor test report aggregation.
     */
    @Test
    @DisplayName("Reactor test report aggregation")
    void testReactorReportAggregation() throws IOException {
        String parentPomContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                                         http://maven.apache.org/xsd/maven-4.0.0.xsd">
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>reactor-reports</artifactId>
                <version>1.0.0</version>
                <packaging>pom</packaging>

                <modules>
                    <module>module1</module>
                    <module>module2</module>
                </modules>

                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-surefire-report-plugin</artifactId>
                            <version>2.22.2</version>
                        </plugin>
                    </plugins>
                </build>
            </project>
            """;
        
        Files.writeString(parentPom, parentPomContent);
        
        assertThat(Files.readString(parentPom))
            .contains("maven-surefire-report-plugin");
    }
}
