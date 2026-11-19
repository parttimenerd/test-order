---
id: find-index-fidelity
title: Dependency index: class-level union-only runtime map + ephemeral static augmentation; ~12 false-negative mechanisms
kind: finding
tags: [dependency-index, instrumentation, false-negatives]
applies_to: [test-order-agent/, test-order-core/src/main/java/me/bechberger/testorder/DependencyMap.java]
source: proper-test-selection/find-index-fidelity
status: active
recorded: 2026-11-17
valid_at: 2026-11-17
---



# Dependency index: class-level union-only runtime map + ephemeral static augmentation; ~12 false-negative mechanisms

Index contents (test-order-core/src/main/java/me/bechberger/testorder/DependencyMap.java): test→depFQCN class map (:160-175), method deps for METHOD/MEMBER modes (:289-332), member bitmaps over a `class#member` dictionary (methods incl <init>, STATIC fields only — test-order-agent/src/main/java/me/bechberger/testorder/agent/AsmClassTransformer.java:299-334; instance fields deliberately not recorded). Agent records method-entry + static-field access; class bits derived from member bits at flush (BitsetTracker.deriveClassBitsFromMembers). On-disk index is NOT statically augmented — BytecodeDependencyAugmenter runs per-select-run, augment-only, gated and ephemeral (test-order-core/src/main/java/me/bechberger/testorder/ops/workflows/ChangeAnalysis.java:495-544; DependencyMap.withAugmentation).

False-negative mechanisms (a test exercises changed code but getAffectedTests misses it):
1. Metadata-only reflection: Class.forName(false), getMethod, annotation scanning — no hooks exist (zero references in test-order-agent/src/main).
2. DI field injection / bean wiring without execution — instance field writes not recorded (STATIC_ONLY).
3. Proxy/ServiceLoader load-without-invoke; proxy classes filtered as synthetic (test-order-core/src/main/java/me/bechberger/testorder/IndexCollectorServer.java:1096-1122).
4. Lambda member precision lost (ACC_SYNTHETIC skipped, test-order-agent/src/main/java/me/bechberger/testorder/agent/AsmClassTransformer.java:139-145); class-level safe.
5. Native-only entry points: ACC_NATIVE methods never instrumented.
6. Separate-thread test bodies unrecorded (documented, test-order-agent/src/main/java/me/bechberger/testorder/agent/runtime/UsageStore.java:40-45).
7. Frequency filter: dep in >threshold tests dropped from EVERY test's set before persist (test-order-core/src/main/java/me/bechberger/testorder/IndexCollectorServer.java:466-510) — changed "ubiquitous" class matches zero tests.
8. includePackages misconfiguration silently empties the index (test-order-core/src/main/java/me/bechberger/testorder/DependencyMap.java:1943-1952 warns it is "common").
9. Union-only merges: no pruning; renamed dep severs all edges until relearn; stale test entries persist until compact (IndexCompactionOperation).
10. Hard kill loses flush (test-order-agent/src/main/java/me/bechberger/testorder/agent/runtime/UsageStore.java:440-487); 3s handler window in stopAndMerge (test-order-core/src/main/java/me/bechberger/testorder/IndexCollectorServer.java:395-405).
11. Augmentation scope: only current module's testClassesDir, only tests already in the map, off when disabled (test-order-core/src/main/java/me/bechberger/testorder/changes/BytecodeDependencyAugmenter.java:65-77).
12. Cross-module: selection is per-module (filterForModule); module B's affected tests never run when building module A (test-order-core/src/main/java/me/bechberger/testorder/DependencyMap.java:493-497).

Cold start fails safe: no index → auto-learn; unindexed tests treated as new and always selected (test-order-core/src/main/java/me/bechberger/testorder/ops/ModeResolverOperation.java:173-186; test-order-core/src/main/java/me/bechberger/testorder/ops/workflows/AffectedWorkflow.java:51-58).
