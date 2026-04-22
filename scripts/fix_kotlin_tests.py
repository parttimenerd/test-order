#!/usr/bin/env python3
"""Fix Kotlin integration tests in TestOrderPluginIntegrationTest.java."""
import pathlib

file = pathlib.Path(__file__).resolve().parent.parent / \
    "test-order-gradle-plugin/src/test/java/me/bechberger/testorder/gradle/TestOrderPluginIntegrationTest.java"

content = file.read_text()

# Fix the "mixed Java and Kotlin tests" build.gradle.kts
# Replace the whole block: remove explicit JvmTarget import/config, add jvmToolchain(21)
old_mixed = '''        writeFile("build.gradle.kts", """
                import org.jetbrains.kotlin.gradle.dsl.JvmTarget
                import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
                
                plugins {
                    id("java")
                    kotlin("jvm") version "2.3.20"
                    id("me.bechberger.test-order") version "0.1.0-SNAPSHOT"
                }
                
                group = "com.example"
                version = "1.0.0"
                
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                
                dependencies {
                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
                    implementation("org.jetbrains.kotlin:kotlin-stdlib")
                }
                
                tasks.test {
                    useJUnitPlatform()
                }
                
                tasks.withType<KotlinCompile> {
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_17)
                    }
                }
                """);'''

new_mixed = '''        writeFile("build.gradle.kts", """
                plugins {
                    id("java")
                    kotlin("jvm") version "2.3.20"
                    id("me.bechberger.test-order") version "0.1.0-SNAPSHOT"
                }
                
                group = "com.example"
                version = "1.0.0"
                
                kotlin {
                    jvmToolchain(21)
                }
                
                repositories {
                    mavenLocal()
                    mavenCentral()
                }
                
                dependencies {
                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
                    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
                    implementation("org.jetbrains.kotlin:kotlin-stdlib")
                }
                
                tasks.test {
                    useJUnitPlatform()
                }
                """);'''

assert old_mixed in content, f"Could not find old_mixed block. Content around line 650:\n{chr(10).join(content.splitlines()[648:688])}"
content = content.replace(old_mixed, new_mixed, 1)

file.write_text(content)
print("OK — Kotlin mixed test build.gradle.kts simplified with jvmToolchain(21)")
