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
        # Use positive selection (-pl <modules> -am) since Maven's !exclude syntax
        # does not propagate exclusion to child modules of the excluded parent.
        # NOTE: The cds-maven-plugin:cds.build goal requires @sap/cds CLI (npm install -g @sap/cds).
        # Without it, the build fails with exit 127 (command not found). Skip this project
        # if @sap/cds is not available.
        cds-feature-attachments) echo "-Dmaven.test.failure.ignore=true -pl 'cds-feature-attachments,storage-targets/cds-feature-attachments-fs,storage-targets/cds-feature-attachments-oss' -am" ;;
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
        # javaparser: heuristic picks com.github.javaparser.symbolsolver (285 files) which
        # misses the 230+ tests in javaparser-core-testing (com.github.javaparser.*).
        # Use the two-level common prefix to capture all test modules.
        javaparser) echo "com.github.javaparser" ;;
        # maven: heuristic picks org.apache.maven.it (integration tests, 745 files) which are
        # Failsafe IT tests requiring a running Maven process, not Surefire unit tests.
        # Use the 3-level prefix to capture model/core/impl tests while skipping IT infra.
        maven) echo "org.apache.maven" ;;
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
        # ScopedValue is stable on JDK 25 (no longer preview); no extra flags needed.
        # inject-java and test-suite have pre-existing JDK test failures; exclude them.
        micronaut-core) echo "--continue -x checkstyleMain -x checkstyleTest -x :micronaut-inject-java:test -x :test-suite:test" ;;
        # hibernate-orm: no checkstyle/spotbugs tasks; uses Gradle 9.5.
        hibernate-orm) echo "--continue" ;;
        # spring-boot: no checkstyle/spotbugs tasks; custom Gradle build convention.
        # antora/docs subprojects generate documentation and are slow; exclude documentation tests.
        spring-boot) echo "--continue -x :documentation:spring-boot-docs:test -x :documentation:spring-boot-actuator-docs:test" ;;
        # kafka: upgrade-system-tests-* are live-cluster system tests (no @Test methods) that
        # compile against old Kafka versions. They cannot run without a live Kafka cluster and
        # produce GradleWorkerMain errors and compileTestJava failures on JDK 25.
        # Exclude both :test and :compileTestJava for all upgrade-system-tests-* subprojects.
        kafka) echo "--continue \
-x :streams:upgrade-system-tests-0110:test -x :streams:upgrade-system-tests-0110:compileTestJava \
-x :streams:upgrade-system-tests-10:test -x :streams:upgrade-system-tests-10:compileTestJava \
-x :streams:upgrade-system-tests-11:test -x :streams:upgrade-system-tests-11:compileTestJava \
-x :streams:upgrade-system-tests-20:test -x :streams:upgrade-system-tests-20:compileTestJava \
-x :streams:upgrade-system-tests-21:test -x :streams:upgrade-system-tests-21:compileTestJava \
-x :streams:upgrade-system-tests-22:test -x :streams:upgrade-system-tests-22:compileTestJava \
-x :streams:upgrade-system-tests-23:test -x :streams:upgrade-system-tests-23:compileTestJava \
-x :streams:upgrade-system-tests-24:test -x :streams:upgrade-system-tests-24:compileTestJava \
-x :streams:upgrade-system-tests-25:test -x :streams:upgrade-system-tests-25:compileTestJava \
-x :streams:upgrade-system-tests-26:test -x :streams:upgrade-system-tests-26:compileTestJava \
-x :streams:upgrade-system-tests-27:test -x :streams:upgrade-system-tests-27:compileTestJava \
-x :streams:upgrade-system-tests-28:test -x :streams:upgrade-system-tests-28:compileTestJava \
-x :streams:upgrade-system-tests-30:test -x :streams:upgrade-system-tests-30:compileTestJava \
-x :streams:upgrade-system-tests-31:test -x :streams:upgrade-system-tests-31:compileTestJava \
-x :streams:upgrade-system-tests-32:test -x :streams:upgrade-system-tests-32:compileTestJava \
-x :streams:upgrade-system-tests-33:test -x :streams:upgrade-system-tests-33:compileTestJava \
-x :streams:upgrade-system-tests-34:test -x :streams:upgrade-system-tests-34:compileTestJava \
-x :streams:upgrade-system-tests-35:test -x :streams:upgrade-system-tests-35:compileTestJava \
-x :streams:upgrade-system-tests-36:test -x :streams:upgrade-system-tests-36:compileTestJava \
-x :streams:upgrade-system-tests-37:test -x :streams:upgrade-system-tests-37:compileTestJava \
-x :streams:upgrade-system-tests-38:test -x :streams:upgrade-system-tests-38:compileTestJava \
-x :streams:upgrade-system-tests-39:test -x :streams:upgrade-system-tests-39:compileTestJava \
-x :streams:upgrade-system-tests-40:test -x :streams:upgrade-system-tests-40:compileTestJava \
-x :streams:upgrade-system-tests-41:test -x :streams:upgrade-system-tests-41:compileTestJava" ;;
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
        # junit5 uses Gradle 9.5 with gradle-daemon-jvm.properties toolchainVersion=25.
        # The daemon JVM and project compilation both require JDK 25.
        # Gradle 9.5 works fine with JDK 25.
        junit5) _sdkman_java_home "25-sapmchn" ;;
        # mockito uses Gradle 8.14.2 which fails with Kotlin DSL compilation on JDK 25
        # (IntelliJ's JavaVersion.parse doesn't understand "25.0.x").
        mockito) _sdkman_java_home "21-sapmchn" ;;
        # micronaut-core uses Gradle 9.4; earlier SAP JDK 25 builds printed "25.0.x" which
        # IntelliJ's JavaVersion.parse couldn't parse. Current 25+36-LTS prints "25" and works.
        micronaut-core) _sdkman_java_home "25-sapmchn" ;;
        # spring-boot uses Gradle 9.4.1 and requires Java 25 for some modules
        # (enforced via javaToolchains; building with Java 21 fails for those modules).
        spring-boot) _sdkman_java_home "25-sapmchn" ;;
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
        # junit5 uses Gradle 9.5 with gradle-daemon-jvm.properties toolchainVersion=25.
        # Point Gradle to the local SAP JDK 25 so it does not try to auto-download.
        # Disable Develocity Predictive Test Selection (PTS): when PTS is active, Gradle
        # uses a custom launcher (LauncherMain) that does not register ServiceLoader-based
        # TestExecutionListeners, so TelemetryListener never fires and 0 deps are tracked.
        junit5) printf "org.gradle.java.installations.paths=%s\norg.gradle.java.installations.auto-download=false\njunit.develocity.predictiveTestSelection.enabled=false\n" "$(_sdkman_java_home "25-sapmchn")" ;;
        # hibernate-orm: index is ~58MB; the default -Xmx2g daemon heap OOMs during testOrderSelect
        # (OOM in NativeFileWatcher running in the Gradle daemon). Bump daemon heap to 6g.
        # Note: toolchain.launcher.jvmargs (test JVM) is left at default (2g) — overriding it
        # caused GradleWorkerMain classpath errors in the forked worker process.
        hibernate-orm) echo "org.gradle.jvmargs=-Dlog4j2.disableJmx=true -Xmx6g -XX:MaxMetaspaceSize=256m -Duser.language=en -Duser.country=US -Duser.timezone=UTC -Dfile.encoding=UTF-8" ;;
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

# Return a path to a Gradle init script to pass via --init-script, or empty string.
# Used for per-repo workarounds that require build-logic changes (e.g. compiler flags).
# The script directory is resolved relative to this file's location.
detect_gradle_init_script() {
    local repo="$1"
    case "$repo" in
        *) echo "" ;;
    esac
}

# Map a subproject directory name to its Gradle project path segment.
# Used by phase_bugs_gradle when the build's useStandardizedProjectNames (or similar)
# renames subprojects so that the directory name differs from the project path.
# Return the full ":project-path:" prefix (with colons), or empty to use the dir name.
# Return the sentinel "ROOT" to skip subproject scoping and use the root-level task.
detect_gradle_subproject_prefix() {
    local repo="$1"
    local dir_name="$2"   # first path segment from the patch +++ line
    case "$repo" in
        # micronaut-core: HttpStatus lives in "http" module, but the integration tests that
        # assert HttpStatus.OK.getCode()==200 live in "http-client". Scoping to :micronaut-http:
        # would only run the http module's unit tests (none of which test status codes directly).
        # Use root-level testOrderSelect so all modules' tests are candidates.
        micronaut-core) echo "ROOT" ;;
        # spring-boot: uses a two-level project structure (e.g. :core:spring-boot).
        # The first path segment alone is insufficient to scope to the right leaf project.
        # Use ROOT so all leaf projects' testOrderSelect tasks are candidates.
        spring-boot) echo "ROOT" ;;
        *) echo "" ;;
    esac
}

# Used to exclude tasks that are spuriously pulled into the execution graph during bug injection.
# These exclusions are intentionally NOT applied to the learn/order/select phases.
detect_gradle_bugs_extra_args() {
    local repo="$1"
    case "$repo" in
        # hibernate-orm: when the plugin is applied to all subprojects, :hibernate-testing:test
        # is pulled into the graph even when scoped to :hibernate-core:testOrderSelect.
        # :hibernate-testing:test has no direct link to MathHelper and its failure blocks
        # :hibernate-core:testOrderSelect from running. Exclude it for bug injection only.
        hibernate-orm) echo "-x :hibernate-testing:test" ;;
        *) echo "" ;;
    esac
}

# Return extra Gradle args applied ONLY to the learn phase (not order/select/bugs).
# Used for repos where offline in-place class instrumentation races with downstream
# compileTestJava tasks under high parallelism.
detect_gradle_learn_extra_args() {
    local repo="$1"
    case "$repo" in
        # spring-boot: offline instrumentation modifies main class files in doFirst of
        # each test task. Under Gradle's default parallel execution, downstream modules'
        # compileTestJava tasks fingerprint those class files at the same time, producing
        # NoSuchFileException. Serialise execution with --max-workers=1 for the learn run.
        spring-boot) echo "--max-workers=1" ;;
        *) echo "" ;;
    esac
}
