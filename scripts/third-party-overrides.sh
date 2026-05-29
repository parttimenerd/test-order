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
        *)          echo "" ;;
    esac
}

# Return the dominant top-level test package for a repo (for includePackages).
# When empty the heuristic in detect_test_package (third_party_test_plan.sh) is used.
detect_package_override() {
    local repo="$1"
    case "$repo" in
        # Add per-repo overrides here if the heuristic picks the wrong package.
        *) echo "" ;;
    esac
}
