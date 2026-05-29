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

# Return the dominant top-level test package for a repo (for includePackages).
# When empty the heuristic in detect_test_package (third_party_test_plan.sh) is used.
detect_package_override() {
    local repo="$1"
    case "$repo" in
        # Add per-repo overrides here if the heuristic picks the wrong package.
        *) echo "" ;;
    esac
}
