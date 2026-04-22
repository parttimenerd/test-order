plugins {
    java
    id("me.bechberger.test-order") version "0.1.0-SNAPSHOT"
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Custom task that depends on test-order configuration
tasks.register("customTestReportTask") {
    dependsOn("test")
    doLast {
        println("Custom task executed after test")
        println("Test ordering should be applied")
    }
}

// Register custom task that depends on showOrder
tasks.register("customShowOrder") {
    dependsOn("showOrder")
    doLast {
        println("Custom task depending on showOrder")
    }
}
