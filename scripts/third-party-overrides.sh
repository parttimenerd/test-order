#!/usr/bin/env bash
# Per-repo overrides for scripts/third_party_test_plan.sh.
# Sourced by the main script. Define repo-specific settings here
# instead of accumulating case statements in the main script.
#
# Each function receives $repo as its first argument.

# Resolve the JAVA_HOME for a given SDKMAN candidate identifier (e.g. "21.0.6-sapmchn").
# Falls back to the same qualifier with just the major version (e.g. "21-sapmchn" when
# "21.0.6-sapmchn" is missing), then to /usr/libexec/java_home -v X on macOS.
_sdkman_java_home() {
    local candidate="$1"
    # Extract major version: "21" from "21.0.6-sapmchn" or "21-sapmchn"
    local major="${candidate%%.*}"
    major="${major%%-*}"
    # Extract qualifier suffix: "sapmchn" from "21.0.6-sapmchn" or "21-sapmchn"
    local qualifier="${candidate##*-}"
    local sdkman_dir="${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java"
    if [[ -d "$sdkman_dir/$candidate" ]]; then
        echo "$sdkman_dir/$candidate"
    elif [[ -d "$sdkman_dir/$major-$qualifier" ]]; then
        # Fallback: try "21-sapmchn" when "21.0.6-sapmchn" is missing
        echo "$sdkman_dir/$major-$qualifier"
    elif command -v /usr/libexec/java_home &>/dev/null; then
        # macOS: find the first JVM whose version starts with the major version
        /usr/libexec/java_home -v "$major" 2>/dev/null | head -1 || echo ""
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
        # joda-time targets Java 1.5 (source/target 1.5) which JDK 17+ rejects.
        # Override to release=8 which is the minimum still supported.
        joda-time) echo "-Dmaven.compiler.source=8 -Dmaven.compiler.target=8 -Dmaven.compiler.compilerVersion=8" ;;
        # jimfs targets Java 1.8 using -source 1.8 (not --release 8). JDK 17 warns about
        # "bootstrap class path not set in conjunction with -source 8" and fails the build.
        # Override with --release 8. See detect_extra_mvn_args for error-prone profile deactivation.
        jimfs) echo "-Dmaven.compiler.release=8" ;;
        # httpcomponents-client uses maven-toolchains-plugin requiring a JDK 1.8 toolchain.
        # Added 1.8 toolchain to ~/.m2/toolchains.xml pointing to JDK 17 SAP.
        # Ignore pre-existing test failures (some integration tests need a running server).
        httpcomponents-client) echo "-Dmaven.test.failure.ignore=true" ;;
        *)          echo "" ;;
    esac
}

# Return extra Maven args appended to ALL maven invocations for a repo.
# Used for per-repo workarounds like excluding modules with pre-existing failures.
detect_extra_mvn_args() {
    local repo="$1"
    case "$repo" in
        # commons-numbers: checkstyle rejects the BUG_INJECTED comment style (trailing whitespace
        # or line-length violation). Skip checkstyle so the test phase runs during bug injection.
        commons-numbers) echo "-Dcheckstyle.skip=true" ;;
        # shiro: checkstyle rejects the BUG_INJECTED comment style. Skip checkstyle.
        shiro) echo "-Dcheckstyle.skip=true -Dmaven.test.failure.ignore=true" ;;
        # hazelcast: checkstyle rejects the BUG_INJECTED comment style.
        hazelcast) echo "-Dcheckstyle.skip=true" ;;
        # quarkus: checkstyle rejects the BUG_INJECTED comment style.
        quarkus) echo "-Dcheckstyle.skip=true -Dmaven.test.failure.ignore=true" ;;
        # javaparser-symbol-solver-testing has a pre-existing test failure:
        # "Unable to determine the current version of java running" on Java 21.
        # maven.test.failure.ignore lets the build continue past this module;
        # our bug-injection check inspects "Tests run: ... Failures:" lines directly.
        #
        # logging-log4j2 log4j-core-test fails to compile on JDK 25 due to an
        # annotation processor intentionally testing error handling (FakePluginPublicSetter).
        # Exclude it; pre-existing test failures in log4j-api-test also require ignore.
        javaparser) echo "-Dmaven.test.failure.ignore=true" ;;
        # commons-lang has pre-existing test failures in FastDateParser_TimeZoneStrategyTest
        # (locale-dependent tests) and several StringUtils tests. Ignore so all steps run.
        commons-lang) echo "-Dmaven.test.failure.ignore=true" ;;
        # logging-log4j2 log4j-core-test fails to compile on JDK 25 due to an
        # annotation processor intentionally testing error handling (FakePluginPublicSetter).
        # Exclude it; pre-existing test failures in log4j-api-test also require ignore.
        # During bug injection the selected test class (e.g. CloseableThreadContextTest) lives
        # in log4j-api-test but the patched class is in log4j-api. Surefire's default
        # failIfNoSpecifiedTests=true aborts when it can't find the test in log4j-api; disable it.
        logging-log4j2) echo "-Dmaven.test.failure.ignore=true -Dsurefire.failIfNoSpecifiedTests=false -pl '!log4j-core-test'" ;;
        # guava: guava-testlib's module-info.java (Java 9 multi-release) requires com.google.common
        # but the JPMS compile step fails to find it in the Maven module path.
        # Skip the java9 profile to avoid this; tests in guava-tests still run normally.
        # Also exclude guava-testlib from the build entirely — it requires JPMS compilation
        # that fails outside of a full build. guava-tests has its own testlib transitive dep via guava.
        guava) echo "-P '!java9' -pl '!guava-testlib'" ;;
        # spring-ai has pre-existing test failures in spring-ai-commons (DocumentTests,
        # ContentFormatterTests etc — "[DRAFT]" prefix mismatch). Ignore so steps 5/6 run.
        # Also skip spring-javaformat:apply which runs in non-CI mode and causes compilation
        # failures when our offline instrumentation modifies class files before the formatter runs.
        # Skip checkstyle: spring-ai-model checkstyle fails (missing newline in AbstractToolCallSupport).
        # spring-ai uses -Ddisable.checks=true (not -Dcheckstyle.skip) to disable checkstyle.
        spring-ai) echo "-Dmaven.test.failure.ignore=true -Denv.CI=true -Ddisable.checks=true" ;;
        # maven (Apache Maven itself): RAT license check fails on injected extensions.xml
        # because it lacks an Apache license header. Skip RAT; also ignore test failures
        # since some integration tests require a fully installed Maven.
        # maven-api-core uses DiIndexProcessor annotation processor that fails to instantiate
        # when running clean build (processor JAR from maven-api-di isn't pre-built).
        # Skip annotation processing to allow compilation past maven-api-core.
        # maven-executor needs apache-maven:zip:bin SNAPSHOT not available locally; exclude it.
        maven) echo "-Drat.skip=true -Dmaven.test.failure.ignore=true -Dmaven.compiler.proc=none -pl '!:maven-executor'" ;;
        # cds-feature-attachments: integration-tests module requires a running server;
        # exclude it and focus on the core cds-feature-attachments module.
        # Use positive selection (-pl <modules> -am) since Maven's !exclude syntax
        # does not propagate exclusion to child modules of the excluded parent.
        # NOTE: The cds-maven-plugin:cds.build goal requires @sap/cds CLI (npm install -g @sap/cds).
        # Without it, the build fails with exit 127 (command not found). Skip this project
        # if @sap/cds is not available.
        cds-feature-attachments) echo "-Dmaven.test.failure.ignore=true -pl 'cds-feature-attachments,storage-targets/cds-feature-attachments-fs,storage-targets/cds-feature-attachments-oss' -am" ;;
        # cds4j: focus on cds4j-core (1,491 @Test methods); skip archunit/spotless;
        # node/npm installs require the cds.install profile which is off by default.
        # Skip arch-unit-maven-plugin which runs in cds4j-api and checks test naming conventions.
        cds4j) echo "-Dmaven.test.failure.ignore=true -pl cds4j-core -am -Darch.test.skip=true -Dskip.archunit=true -Denforcer.skip=true" ;;
        # jimfs: error-prone profile 'errorprone-enabled' is activated on JDK >= 21 and fails with
        # NullArgumentForNonNullParameter (PathURLConnection.java:146) that cannot be suppressed.
        # Deactivate the profile to skip error-prone entirely. The '!' prefix deactivates a profile.
        jimfs) echo "-P '!errorprone-enabled'" ;;
        # gson: errorprone 2.48.0 on JDK 21 emits InvalidBlockTag warning for @Since in Javadoc;
        # failOnWarning=true turns it into a compile error. Force-activate the 'disable-error-prone'
        # profile which overrides compilerArgs to skip the error-prone annotation processor.
        gson) echo "-P disable-error-prone" ;;
        # logbook and jetty use JUnit class-level parallel execution (mode.classes.default=concurrent
        # or Surefire <parallel>classesAndMethods</parallel>).  test-order cannot track dependencies
        # when multiple test classes run simultaneously, so we override these to serial execution.
        logbook) echo "-Dparallel=none -Dmaven.test.failure.ignore=true" ;;
        jetty) echo "-Dparallel=none -Djunit.jupiter.execution.parallel.mode.classes.default=same_thread -Dmaven.test.failure.ignore=true -pl '!jetty-ee8,!jetty-demos,!jetty-p2' -am" ;;
        # byte-buddy-agent tests (ByteBuddyAgentInstallationTest) fail when test-order's
        # ClassFileTransformer agent is active — both agents compete for JVM agent attachment.
        # Use maven.test.failure.ignore so byte-buddy-dep (which depends on byte-buddy-agent
        # as a test dep) still builds and gets indexed after agent tests fail.
        byte-buddy) echo "-Dmaven.test.failure.ignore=true" ;;
        # truth (Google Truth) has pre-existing test failures in some extension modules;
        # ignore test failures so learn continues.
        truth) echo "-Dmaven.test.failure.ignore=true" ;;
        # undertow has pre-existing test failures in undertow-core (NIO/SSL tests require network);
        # ignore test failures to capture dependency index despite failures.
        undertow) echo "-Dmaven.test.failure.ignore=true" ;;
        # spring-petclinic: third-party clone has a broken .git folder (missing commits);
        # git-commit-id plugin fails even with failOnNoGitDirectory=false. Skip the plugin.
        spring-petclinic) echo "-Dmaven.gitcommitid.skip=true -Dmaven.test.failure.ignore=true" ;;
        # problem (Zalando Problem): maven-compiler-plugin uses in-process javac which loads
        # from the Maven JVM (Ubuntu OpenJDK 21 without ct.sym for --release 17). Force a
        # forked javac compilation from JAVA_HOME which has the full ct.sym.
        # Surefire pom uses <parallel>classesAndMethods</parallel> hardcoded; use
        # -Dtestorder.learn.allowParallel=true to proceed despite the parallel warning.
        problem) echo "-Dmaven.compiler.fork=true -Dmaven.test.failure.ignore=true -Dtestorder.learn.allowParallel=true" ;;
        # smallrye-mutiny: maven-compiler-plugin uses in-process javac without ct.sym for --release 17.
        # Force forked javac from JAVA_HOME (SAP JDK 21 with ct.sym).
        # Use agent mode: offline instrumentation corrupts Mocks.class (test-utils module imports
        # Mocks in tests; offline pre-instrumentation causes ClassNotFoundException at test runtime).
        smallrye-mutiny) echo "-Dmaven.compiler.fork=true -Dmaven.test.failure.ignore=true -Dtestorder.instrumentation=agent" ;;
        # commons-rng: checkstyle rejects BUG_INJECTED comment style.
        commons-rng) echo "-Dcheckstyle.skip=true" ;;
        *)          echo "" ;;
    esac
}

# Return the Maven submodule to use instead of the auto-detected one, or empty string.
# Use when detect_single_module picks the wrong submodule (e.g. wrong test source dir).
# Return the sentinel "NONE" to skip -pl scoping entirely (run the full reactor).
detect_module_override() {
    local repo="$1"
    case "$repo" in
        # maven (Apache Maven itself): the heuristic picks the 'its' module (integration
        # tests that require a running Maven process). Use NONE to run the full reactor
        # (the package override to 'org.apache.maven' already filters out IT tests).
        maven) echo "NONE" ;;
        # guava: tests live in guava-tests/test/ (not src/test/java), so the heuristic
        # finds 0 test files and falls back to empty (no -pl). Explicitly use guava-tests.
        guava) echo "guava-tests" ;;
        # spring-ai: heuristic picks mcp/mcp-annotations (85 tests) but the injected bug
        # is in DefaultToolDefinition which lives in spring-ai-model (70 tests).
        spring-ai) echo "spring-ai-model" ;;
        # javaparser: heuristic picks javaparser-symbol-solver-testing (285 files) which
        # skips javaparser-core-testing (230+ tests including RangeTest). Use NONE to
        # run the full reactor; package filter 'com.github.javaparser' captures both.
        javaparser) echo "NONE" ;;
        # opentelemetry: ImmutableTraceFlags lives in :api module, but testOrderAffected
        # must run at the reactor root because the Gradle plugin is applied at root level.
        opentelemetry) echo "NONE" ;;
        # problem: tests are in submodules (problem-jackson3, problem-gson, etc.) not
        # in the 'problem' module itself. Run full reactor to capture all submodule tests.
        problem) echo "NONE" ;;
        # commons-rng: heuristic picks commons-rng-core but tests for RandomSource are in
        # commons-rng-simple. Run the full reactor to capture all modules.
        commons-rng) echo "NONE" ;;
        # smallrye-mutiny: test-utils module fails with ClassNotFoundException (Mocks.class
        # not on surefire classpath due to multi-release JAR output directory override).
        # The bug patch is in UniSpyBase (implementation module), so limit to implementation
        # and its dependencies. The test-utils module is excluded to avoid surefire failure.
        smallrye-mutiny) echo "implementation" ;;
        # mapstruct: heuristic picks mapstruct-parent but tests live in processor submodule.
        # Run the full reactor so the index is written to the root .test-order directory.
        mapstruct) echo "NONE" ;;
        *) echo "" ;;
    esac
}

# Return the dominant top-level test package for a repo (for includePackages).
# When empty the heuristic in detect_test_package (third_party_test_plan.sh) is used.
detect_package_override() {
    local repo="$1"
    case "$repo" in
        # Add per-repo overrides here if the heuristic picks the wrong package.
        cds-feature-attachments) echo "com.sap.cds" ;;
        cds4j) echo "com.sap.cds" ;;
        neonbee) echo "io.neonbee" ;;
        resilience4j) echo "io.github.resilience4j" ;;
        # problem: tests span multiple submodules under org.zalando.problem
        problem) echo "org.zalando.problem" ;;
        # javaparser: heuristic picks com.github.javaparser.symbolsolver (285 files) which
        # misses the 230+ tests in javaparser-core-testing (com.github.javaparser.*).
        # Use the two-level common prefix to capture all test modules.
        javaparser) echo "com.github.javaparser" ;;
        # pdfbox: all test classes live under org.apache.pdfbox
        pdfbox) echo "org.apache.pdfbox" ;;
        # maven: heuristic picks org.apache.maven.it (integration tests, 745 files) which are
        # Failsafe IT tests requiring a running Maven process, not Surefire unit tests.
        # Use the 3-level prefix to capture model/core/impl tests while skipping IT infra.
        maven) echo "org.apache.maven" ;;
        # smallrye-mutiny: tests span multiple submodules under io.smallrye.mutiny
        smallrye-mutiny) echo "io.smallrye.mutiny" ;;
        *) echo "" ;;
    esac
}

# Return extra Gradle arguments (tasks to exclude, flags, etc.) for a repo.
# Applied when running ./gradlew for Gradle repos.
detect_gradle_extra_args() {
    local repo="$1"
    case "$repo" in
        # caffeine: javaVersion() defaults to 11 when no javaVersion property or JAVA_VERSION env var
        # is set. Pass -PjavaVersion=21 to use JDK 21 toolchain (avoids pre-existing JDK 11 test failures).
        # Only :caffeine has jcstress/jmh tasks (not :guava, :jcache, :simulator). Exclude them.
        caffeine) echo "--continue -PjavaVersion=21 -x :caffeine:jcstress -x :caffeine:jmh" ;;
        # resilience4j does not have checkstyle/spotbugs tasks at root level;
        # Gradle 9 errors on -x for non-existent tasks.
        # Reactive/Spring subprojects hang indefinitely on JDK 25 (Mockito + reactor
        # Futures never resolve); exclude them and test the core modules only.
        resilience4j) echo "--continue -x jmh -x :resilience4j-reactor:test -x :resilience4j-rxjava2:test -x :resilience4j-rxjava3:test -x :resilience4j-spring6:test -x :resilience4j-spring-boot3:test -x :resilience4j-spring-boot4:test" ;;
        # neonbee uses Gradle 8.5 which requires JDK ≤ 21; run with JDK 21.
        # Use CLASS instrumentation mode: MEMBER mode triggers NPE in BitsetTracker.recordMember
        # because Vert.x creates objects via Unsafe.allocateInstance (bypassing constructors),
        # so the injected $testorder$tracker field is never initialized.
        neonbee) echo "--continue -Dtestorder.instrumentation.mode=CLASS" ;;
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
        # android-test/android-test-app are only included when ANDROID_HOME or sdk.dir is set; when they are
        # not included, -x on them causes a build error. Use --continue without android exclusions.
        # okcurl requires GraalVM; exclude it directly.
        okhttp) echo "--continue -x :okcurl:test" ;;
        # mockito uses Gradle 8.14.2 which fails with Kotlin DSL compilation on JDK 25
        # (IntelliJ's JavaVersion.parse doesn't understand "25.0.x").
        # Exclude GraalVM tests (need native image); android modules are not present in this version.
        mockito) echo "--continue -x :mockito-integration-tests:graalvm-tests:test" ;;
        # micronaut-core: no spotbugsMain task (uses spotless instead); checkstyle present.
        # ScopedValue is stable on JDK 25 (no longer preview); no extra flags needed.
        # inject-java and test-suite have pre-existing JDK test failures; exclude them.
        micronaut-core) echo "--continue -x checkstyleMain -x checkstyleTest -x :micronaut-inject-java:test -x :test-suite:test" ;;
        # hibernate-orm: no checkstyle/spotbugs tasks; uses Gradle 9.5.
        # Exclude :hibernate-envers:test — pre-existing failures unrelated to test-order.
        hibernate-orm) echo "--continue -x :hibernate-envers:test" ;;
        # spring-boot: no checkstyle/spotbugs tasks; custom Gradle build convention.
        # antora/docs subprojects generate documentation and are slow; exclude documentation tests.
        # spring-boot-dependencies is a BOM-only project (no Java sources); testOrderInstrument
        # fails because 'classes' task doesn't exist in BOM projects. Exclude it from instrumentation.
        # Note: '-x :platform:spring-boot-dependencies:testOrderInstrument' won't work (task can't
        # be created, fails at config time). Work around via --continue which skips config failures.
        spring-boot) echo "--continue -x :documentation:spring-boot-docs:test -x :documentation:spring-boot-actuator-docs:test" ;;
        # kafka: upgrade-system-tests-* are live-cluster system tests (no @Test methods) that
        # compile against old Kafka versions. They cannot run without a live Kafka cluster and
        # produce GradleWorkerMain errors and compileTestJava failures on JDK 25.
        # Exclude both :test and :compileTestJava for all upgrade-system-tests-* subprojects.
        # -PcommitId=unknown: kafka's build.gradle opens Grgit on a corrupt .git dir (no HEAD).
        # Passing -PcommitId=unknown bypasses the Grgit.open() call (determineCommitId() returns
        # the property value directly without touching git).
        kafka) echo "--continue -PcommitId=unknown \
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
        # opentelemetry uses spotless/otel-conventions, not checkstyle/spotbugs.
        # Requires --no-scan to bypass Develocity Gradle plugin ToS check (non-interactive env).
        opentelemetry) echo "--continue --no-scan" ;;
        # quartz: Gradle 8.5 test executor crashes with OOM on default parallelism.
        # Reduce max workers to 1 to avoid resource exhaustion during learn/test phases.
        quartz) echo "--continue -Dorg.gradle.workers.max=1" ;;
        # reactor-core uses the nohttp Spring plugin which registers checkstyleMain at root level;
        # Gradle 9 fails the task-graph computation if that task is a dependency but not found.
        # Exclude check (which pulls in checkstyleMain) and the slow docs/benchmarks subprojects.
        # reactor-core compiles and tests with JDK 8 toolchain; -Dtestorder.overrideToolchain=true
        # resets the launcher on all Test tasks to the Gradle daemon JVM (17+) so test-order runs.
        reactor-core) echo "--continue -x check -x :docs:test -x :benchmarks:test -Dtestorder.overrideToolchain=true" ;;
        # Default: just --continue; do not exclude checkstyle/spotbugs since most modern Gradle
        # repos don't have these tasks and Gradle 9 errors on -x for non-existent task names.
        # Add per-repo exclusions above for repos with known slow/broken analysis tasks.
        *) echo "--continue" ;;
    esac
}

# Return a JAVA_HOME override for running Maven in a repo, or empty string for current JDK.
# Use for repos whose Maven enforcer or compiler requires a JDK version different from the
# global default (which is pinned to JDK 17 on Linux to support --release 8/11 targets).
detect_maven_java_home() {
    local repo="$1"
    case "$repo" in
        # spring-ai enforcer requires JDK >= 21.0.8; global default is JDK 17.
        # Kotlin compiler in spring-ai-commons also requires JDK 21+.
        # Use 21.0.6-sapmchn (has javac) + enforcer is already skipped via -Denforcer.skip=true.
        spring-ai) _sdkman_java_home "21.0.6-sapmchn" ;;
        # jimfs: error-prone javac plugin requires JDK 21 (class file version 65.0 = Java 21).
        # The global default JDK 17 can't load it — use SAP JDK 21 instead.
        jimfs) _sdkman_java_home "21.0.6-sapmchn" ;;
        # gson: ProGuard obfuscation plugin (proguard-maven-plugin) needs jmods/java.base.jmod
        # to process test classes. The system JDK 21 on Linux is a JRE (no jmods/ dir).
        # SAP JDK 21.0.6 ships with full jmods/ including java.base.jmod.
        gson) _sdkman_java_home "21.0.6-sapmchn" ;;
        # jackson-databind 3.x requires Java 21 (pom.xml: maven.compiler.release=21).
        jackson-databind) _sdkman_java_home "21.0.6-sapmchn" ;;
        # maven: root pom sets javaVersion=17, which requires --release 17 (cross-compilation).
        # The Ubuntu JDK 21 lacks ct.sym for --release; SAP JDK 21 has it.
        maven) _sdkman_java_home "21.0.6-sapmchn" ;;
        # problem: uses module-info.java with Jackson 3.x module names; requires SAP JDK 21
        # with full ct.sym support for --release 17. Ubuntu JDK 21 rejects --release 17.
        problem) _sdkman_java_home "21-sapmchn" ;;
        # smallrye-mutiny: uses --release 17 in maven-compiler-plugin; requires SAP JDK 21
        # with full ct.sym. Ubuntu JDK 21 (JRE) lacks ct.sym for cross-compilation targets.
        smallrye-mutiny) _sdkman_java_home "21-sapmchn" ;;
        # mapstruct-processor tests require --release 21 (JDK 21 features in test sources).
        # Ubuntu JDK 17 cannot compile --release 21. Use SAP JDK 21.
        mapstruct) _sdkman_java_home "21-sapmchn" ;;
        *) echo "" ;;
    esac
}

# Return Maven args to run BEFORE the main learn phase (install jars first), or empty.
# Called in phase_learn_maven and phase_bugs_maven before mvn_learn.
# Output format: "install -DskipTests <additional-args>" (Maven goals+args after 'mvn').
# The caller prepends the standard base_args (enforcer.skip, rat.skip, etc.).
detect_maven_prelearn_goals() {
    local repo="$1"
    case "$repo" in
        *) echo "" ;;
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
        # reactor-core: system java-21 on thinkstation is a JRE (no javac); use SAP JDK 21.
        reactor-core) _sdkman_java_home "21-sapmchn" ;;
        # opentelemetry: uses toolchain languageVersion=21; system JDK may be a JRE.
        # SAP JDK 25 can compile with --release 21 and has javac on PATH.
        opentelemetry) _sdkman_java_home "25-sapmchn" ;;
        # micronaut-core uses Gradle 9.4; earlier SAP JDK 25 builds printed "25.0.x" which
        # IntelliJ's JavaVersion.parse couldn't parse. Current 25+36-LTS prints "25" and works.
        micronaut-core) _sdkman_java_home "25-sapmchn" ;;
        # spring-boot uses Gradle 9.4.1 and requires Java 25 for some modules
        # (enforced via javaToolchains; building with Java 21 fails for those modules).
        spring-boot) _sdkman_java_home "25-sapmchn" ;;
        # hibernate-orm: settings.gradle requires at least JDK 25 (jdks-settings plugin).
        hibernate-orm) _sdkman_java_home "25-sapmchn" ;;
        # resilience4j: targets Java 21; system JDK may be a JRE (no javac); use SAP JDK 21.
        resilience4j) _sdkman_java_home "21-sapmchn" ;;
        # caffeine: defaults to JDK 11 toolchain; we override to JDK 21 via -PjavaVersion=21
        # (see detect_gradle_extra_args). Run the Gradle wrapper itself on JDK 21 too.
        caffeine) _sdkman_java_home "21-sapmchn" ;;
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
        okhttp|reactor-core|opentelemetry) echo "foojay-resolver-convention" ;;
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
        # opentelemetry: uses two-level project structure (e.g. api/all, api/incubator).
        # The first path segment "api" is a parent project without test tasks.
        # Map "api" → ":api:all:" where ImmutableTraceFlags and TraceFlagsTest live.
        opentelemetry) case "$dir_name" in
            api) echo ":api:all:" ;;
            *) echo "ROOT" ;;
          esac ;;
        # spring-boot: uses a two-level project structure (e.g. :core:spring-boot).
        # The first path segment alone is insufficient to scope to the right leaf project.
        # Use ROOT so all leaf projects' testOrderSelect tasks are candidates.
        spring-boot) echo "ROOT" ;;
        # mockito: MockitoExtension lives in mockito-extensions/mockito-junit-jupiter.
        # The indexed tests (org.mockitousage.*) live in mockito-extensions/mockito-subclass.
        # Both are under the mockito-extensions parent; use ROOT so all subprojects are candidates.
        mockito) echo "ROOT" ;;
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

# Return "true" if the repo already has test-order configured via pom.xml extensions,
# so inject_maven_plugin can skip creating .mvn/extensions.xml (which would double-load it).
detect_plugin_already_configured() {
    local repo="$1"
    case "$repo" in
        # cds4j: test-order-maven-plugin is declared with <extensions>true</extensions> in
        # the root pom.xml, so it already acts as a lifecycle participant.
        cds4j) echo "true" ;;
        *) echo "" ;;
    esac
}
