#!/usr/bin/env bash
# Per-repo overrides for scripts/third_party_test_plan.sh.
# Sourced by the main script. Define repo-specific settings here
# instead of accumulating case statements in the main script.
#
# Each function receives $repo as its first argument.

# Resolve the JAVA_HOME for a given SDKMAN candidate identifier (e.g. "21-sapmchn").
# Falls back to /usr/libexec/java_home -v X if SDKMAN is not available.
_sdkman_java_home() {
    local candidate="$1"
    local version="${candidate%%-*}"   # e.g. "21" from "21-sapmchn"
    if [[ -d "${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java/$candidate" ]]; then
        echo "${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java/$candidate"
    elif command -v /usr/libexec/java_home &>/dev/null; then
        /usr/libexec/java_home -v "$version" 2>/dev/null || echo ""
    else
        echo ""
    fi
}

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
        # junit5's platform-tooling-support-tests need pre-built JARs in a specific
        # local Maven repo layout. Skip them — they test distribution packaging, not
        # unit functionality, and fail with "Failed to find JAR file" on dev machines.
        # jupiter-tests:ExtensionRegistryTests counts auto-detected extensions and
        # expects exactly 9; our agent registers itself via ServiceLoader making it 10.
        # Since this is meta-testing JUnit internals (not user code), exclude it.
        junit5) echo "--continue -x :platform-tooling-support-tests:test -x :jupiter-tests:test" ;;
        # okhttp uses foojay-resolver for JDK toolchain provisioning AND a gradle-daemon-jvm.properties
        # that locks the daemon vendor to ADOPTIUM (Eclipse Temurin) which is unavailable on aarch64 macOS.
        # Declare SAPMachine JDK 21 and disable auto-download; the daemon-jvm file is patched by inject_gradle_plugin.
        # android/graal/module-test modules are conditionally included (gradle.properties flags);
        # android-test is included when ANDROID_HOME is set (it is on this machine) but the build
        # fails due to conflicting SDK paths. Exclude android test tasks; okcurl requires GraalVM.
        okhttp) echo "--continue -x :okcurl:test -x :android-test:testDebugUnitTest -x :android-test-app:testDebugUnitTest" ;;
        # mockito uses Gradle 8.14.2 which fails with Kotlin DSL compilation on JDK 25
        # (IntelliJ's JavaVersion.parse doesn't understand "25.0.x").
        # Exclude android modules (need Android SDK) and GraalVM tests (need native image).
        # Note: :mockito-integration-tests:android-tests uses testDebugUnitTest, not :test.
        mockito) echo "--continue -x :mockito-extensions:mockito-android:test -x :mockito-integration-tests:android-tests:testDebugUnitTest -x :mockito-integration-tests:graalvm-tests:test" ;;
        # micronaut-core: no spotbugsMain task (uses spotless instead); checkstyle present.
        # The :micronaut-core subproject uses ScopedValue (JDK preview API); needs --enable-preview.
        # inject-java and test-suite have pre-existing JDK test failures; exclude them.
        micronaut-core) echo "--continue -x checkstyleMain -x checkstyleTest -x :micronaut-inject-java:test -x :test-suite:test -Pcompiler.args=--enable-preview" ;;
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
        # Fall back to JDK 21.
        neonbee) _sdkman_java_home "21-sapmchn" ;;
        # junit5 requires JDK 21 (its Gradle build targets Java 8–17 source but the
        # Gradle wrapper version needs JDK ≤ 21 to run reliably).
        junit5) _sdkman_java_home "21-sapmchn" ;;
        # mockito uses Gradle 8.14.2 which fails with Kotlin DSL compilation on JDK 25
        # (IntelliJ's JavaVersion.parse doesn't understand "25.0.x").
        mockito) _sdkman_java_home "21-sapmchn" ;;
        # micronaut-core uses Gradle 9.4 whose bundled Kotlin compiler also fails to parse
        # JDK 25 version string "25.0.x"; run under JDK 21.
        micronaut-core) _sdkman_java_home "21-sapmchn" ;;
        *) echo "" ;;
    esac
}

# Return extra lines to append to gradle.properties during injection.
# Used when a Gradle build property (not a JVM arg) is needed for the build to run.
# The caller (inject_gradle_plugin) handles backup/restore of gradle.properties.
detect_gradle_properties_extra() {
    local repo="$1"
    case "$repo" in
        # okhttp uses foojay-resolver for JDK toolchain provisioning AND a gradle-daemon-jvm.properties
        # that locks the daemon vendor to ADOPTIUM (Eclipse Temurin) which is unavailable on aarch64 macOS.
        # Declare SAPMachine JDK 21 and disable auto-download; the daemon-jvm file is patched by inject_gradle_plugin.
        okhttp) printf "org.gradle.java.installations.paths=%s\norg.gradle.java.installations.auto-download=false\n" "$(_sdkman_java_home "21-sapmchn")" ;;
        *) echo "" ;;
    esac
}

# Return a vendor string to override in gradle/gradle-daemon-jvm.properties.
# If non-empty, inject_gradle_plugin will replace toolchainVendor in that file.
detect_gradle_daemon_jvm_vendor() {
    local repo="$1"
    case "$repo" in
        # okhttp specifies toolchainVendor=ADOPTIUM (Eclipse Temurin) in gradle-daemon-jvm.properties.
        # Eclipse Temurin is unavailable on aarch64 macOS; use SAP SE (sapmachine-21).
        okhttp) echo "SAP" ;;
        *) echo "" ;;
    esac
}
# Used when a settings plugin causes build failures on this machine.
# The pattern is passed to `sed -i '' "/pattern/d"`.
detect_gradle_settings_remove() {
    local repo="$1"
    case "$repo" in
        # foojay-resolver-convention tries to download Eclipse Temurin from foojay.io,
        # which fails on aarch64 macOS (no download URL). Remove it so Gradle falls back
        # to using the JDK specified via org.gradle.java.installations.paths (in gradle.properties).
        okhttp) echo "foojay-resolver-convention" ;;
        *) echo "" ;;
    esac
}
