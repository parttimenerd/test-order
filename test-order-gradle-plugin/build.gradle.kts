plugins {
    `java-gradle-plugin`
    `maven-publish`
}

group = "me.bechberger"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Core ordering/telemetry logic — used at plugin configuration time
    // for change detection, aggregation, and show-order tasks
    implementation("me.bechberger:test-order-core:${version}")

    // Agent module — needed for offline instrumentation at build time
    implementation("me.bechberger:test-order-agent:${version}")

    // CI artifact downloading (GitHub Actions, GitLab CI, HTTP)
    implementation("me.bechberger:test-order-ci:${version}")

    // Shared dashboard resources (template, CSS, JS, bundled libraries)
    implementation("me.bechberger:test-order-dashboard:${version}")

    // Integration tests
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.2")
}

gradlePlugin {
    plugins {
        create("testOrder") {
            id = "me.bechberger.test-order"
            implementationClass = "me.bechberger.testorder.gradle.TestOrderPlugin"
            displayName = "Test Order Plugin"
            description = "Gradle plugin for JUnit test class priority ordering based on runtime dependency telemetry"
        }
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Tests need these artifacts in mavenLocal
    dependsOn("publishToMavenLocal")

    testLogging {
        events("started", "passed", "failed", "skipped")
        showStandardStreams = true
    }

    listOf(
        "testorder.it",
        "testorder.java.17.home",
        "testorder.java.21.home",
        "testorder.java.24.home",
        "testorder.java.25.home",
        "testorder.java.25plus.home",
        "testorder.java.26.home"
    ).forEach { key ->
        System.getProperty(key)?.let { value ->
            systemProperty(key, value)
        }
    }
}
