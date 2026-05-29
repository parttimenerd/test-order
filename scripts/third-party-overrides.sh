#!/usr/bin/env bash
# Per-repo overrides for scripts/third_party_test_plan.sh.
# Sourced by the main script. Define repo-specific settings here
# instead of accumulating case statements in the main script.
#
# Each function receives $repo as its first argument.

# Return extra Maven args required by a repo to compile on the current JDK.
detect_compiler_args() {
    local repo="$1"
    case "$repo" in
        # javaparser 3.x: NodeList.getFirst()/getLast() return Optional<N> which
        # conflicts with Java 21+ List.getFirst()/getLast() returning N.
        # --release=11 hides the new API and allows compilation.
        javaparser) echo "-Dmaven.compiler.release=11" ;;
        *)          echo "" ;;
    esac
}

# Return extra Maven args appended to ALL maven invocations for a repo.
# Used for per-repo workarounds like excluding modules with pre-existing failures.
detect_extra_mvn_args() {
    local repo="$1"
    case "$repo" in
        # javaparser-symbol-solver-testing has a pre-existing test failure:
        # "Unable to determine the current version of java running" on Java 21.
        # maven.test.failure.ignore lets the build continue past this module;
        # our bug-injection check inspects "Tests run: ... Failures:" lines directly.
        #
        # logging-log4j2 log4j-core-test fails to compile on JDK 25 due to an
        # annotation processor intentionally testing error handling (FakePluginPublicSetter).
        # Exclude it; pre-existing test failures in log4j-api-test also require ignore.
        javaparser) echo "-Dmaven.test.failure.ignore=true" ;;
        logging-log4j2) echo "-Dmaven.test.failure.ignore=true -pl '!log4j-core-test'" ;;
        # spring-ai has pre-existing test failures in spring-ai-commons (DocumentTests,
        # ContentFormatterTests etc — "[DRAFT]" prefix mismatch). Ignore so steps 5/6 run.
        spring-ai) echo "-Dmaven.test.failure.ignore=true" ;;
        # cds-feature-attachments: integration-tests module requires a running server;
        # exclude it and focus on the core cds-feature-attachments module.
        # Also exclude coverage-report (report-only, no tests) and the samples module.
        cds-feature-attachments) echo "-Dmaven.test.failure.ignore=true -pl '!integration-tests,!coverage-report'" ;;
        *)          echo "" ;;
    esac
}

# Return the dominant top-level test package for a repo (for includePackages).
# When empty the heuristic in detect_test_package (third_party_test_plan.sh) is used.
detect_package_override() {
    local repo="$1"
    case "$repo" in
        # Add per-repo overrides here if the heuristic picks the wrong package.
        cds-feature-attachments) echo "com.sap.cds" ;;
        neonbee) echo "io.neonbee" ;;
        resilience4j) echo "io.github.resilience4j" ;;
        *) echo "" ;;
    esac
}

# Return extra Gradle arguments (tasks to exclude, flags, etc.) for a repo.
# Applied when running ./gradlew for Gradle repos.
detect_gradle_extra_args() {
    local repo="$1"
    case "$repo" in
        # resilience4j does not have checkstyle/spotbugs tasks at root level;
        # Gradle 9 errors on -x for non-existent tasks.
        # Reactive/Spring subprojects hang indefinitely on JDK 25 (Mockito + reactor
        # Futures never resolve); exclude them and test the core modules only.
        resilience4j) echo "--continue -x jmh -x :resilience4j-reactor:test -x :resilience4j-rxjava2:test -x :resilience4j-rxjava3:test -x :resilience4j-spring6:test -x :resilience4j-spring-boot3:test -x :resilience4j-spring-boot4:test" ;;
        # neonbee uses Gradle 8.5 which requires JDK ≤ 21; run with JDK 21.
        # KNOWN LIMITATION: Both offline and online instrumentation fail with NPE in
        # BitsetTracker.recordMember because Vert.x creates objects via Unsafe.allocateInstance
        # bypassing constructors, so the injected $testorder$tracker field is never initialized.
        # Neonbee learn will produce an index only for classes that avoid these paths.
        neonbee) echo "--continue" ;;
        # Default: exclude common static-analysis tasks that may slow or break the build.
        # Most Gradle repos have these tasks; resilience4j is the known exception.
        *) echo "--continue -x checkstyleMain -x checkstyleTest -x spotbugsMain" ;;
    esac
}

# Return a JAVA_HOME override for running Gradle in a repo, or empty string for current JDK.
# Use for repos whose Gradle wrapper version is incompatible with the current JDK.
detect_gradle_java_home() {
    local repo="$1"
    case "$repo" in
        # neonbee uses Gradle 8.5 which does not support JDK 25 (class file major version 69).
        # Fall back to JDK 21 (sapmachine-21 is available on this machine).
        neonbee) echo "/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home" ;;
        *) echo "" ;;
    esac
}
