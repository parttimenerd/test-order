#!/usr/bin/env python3
import pathlib

pom = r'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.example</groupId>
    <artifactId>kotest-basic-tests</artifactId>
    <version>1.0.0</version>
    <name>Kotest Basic Tests</name>
    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <kotlin.version>2.1.20</kotlin.version>
        <kotest.version>5.9.1</kotest.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.jetbrains.kotlin</groupId>
            <artifactId>kotlin-stdlib</artifactId>
            <version>${kotlin.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.kotest</groupId>
            <artifactId>kotest-runner-junit5</artifactId>
            <version>${kotest.version}</version>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.kotest</groupId>
            <artifactId>kotest-assertions-core</artifactId>
            <version>${kotest.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <testSourceDirectory>${project.basedir}/src/test/kotlin</testSourceDirectory>
        <plugins>
            <plugin>
                <groupId>org.jetbrains.kotlin</groupId>
                <artifactId>kotlin-maven-plugin</artifactId>
                <version>${kotlin.version}</version>
                <configuration>
                    <jvmTarget>17</jvmTarget>
                </configuration>
                <executions>
                    <execution>
                        <id>test-compile</id>
                        <goals><goal>test-compile</goal></goals>
                        <configuration>
                            <sourceDirs>
                                <sourceDir>${project.basedir}/src/test/kotlin</sourceDir>
                            </sourceDirs>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>me.bechberger</groupId>
                <artifactId>test-order-maven-plugin</artifactId>
                <version>0.1.0-SNAPSHOT</version>
                <configuration>
                    <mode>combined</mode>
                </configuration>
                <executions>
                    <execution>
                        <goals><goal>combined</goal></goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
        </plugins>
    </build>
</project>
'''

pathlib.Path(__file__).parent.joinpath('pom.xml').write_text(pom.lstrip())
print('pom.xml written successfully')
