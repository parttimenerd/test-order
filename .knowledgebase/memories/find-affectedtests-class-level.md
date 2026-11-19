---
id: find-affectedtests-class-level
title: getAffectedTests is a plain class-level inverted-index intersection
kind: finding
tags: [selection, dependency-map, granularity]
applies_to: [test-order-core/src/main/java/me/bechberger/testorder/DependencyMap.java]
source: proper-test-selection/find-affectedtests-class-level
status: active
recorded: 2026-11-17
valid_at: 2026-11-17
---



# getAffectedTests is a plain class-level inverted-index intersection

`DependencyMap.getAffectedTests(Set<String> changedClasses)` (test-order-core/src/main/java/me/bechberger/testorder/DependencyMap.java:753) returns the union, over each changed class, of the tests whose dependency set contains that class — built from a lazily-constructed inverted index (`buildInvertedIndex()`, DependencyMap.java:~826). The index also aliases nested-class deps to their top-level class (`Foo$Bar` also indexed under `Foo`).

So affected-ness at the class level is EXACTLY: test deps ∩ changed classes ≠ ∅. There is no transitive closure over callers/callees here, no member intersection, and no type-hierarchy closure in this method itself; those live elsewhere (BytecodeDependencyAugmenter augments the changed set / dep map upstream, see separate nodes). Any coverage-completeness argument must therefore be made about (changedClasses × index) fidelity, not about this lookup.
