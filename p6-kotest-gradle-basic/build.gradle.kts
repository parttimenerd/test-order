plugins {
    kotlin("jvm") version "1.8.21"
    id("me.bechberger.test-order-gradle") version "0.1.0-SNAPSHOT"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("stdlib"))
    testImplementation("io.kotest:kotest-runner-junit5:5.6.2")
    testImplementation("io.kotest:kotest-assertions-core:5.6.2")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
