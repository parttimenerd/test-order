---
id: find-change-detection-holes
title: Changed-class set is class-granular and silently ignores all non-source inputs
kind: finding
tags: [change-detection, granularity, gaps]
applies_to: [test-order-core/src/main/java/me/bechberger/testorder/changes/]
source: proper-test-selection/find-change-detection-holes
status: active
recorded: 2026-11-17
valid_at: 2026-11-17
---



# Changed-class set is class-granular and silently ignores all non-source inputs

Changed-class detection (test-order-core/src/main/java/me/bechberger/testorder/changes/ChangeDetector.java:56-96, 4 modes: SINCE_LAST_RUN / SINCE_LAST_COMMIT / UNCOMMITTED / EXPLICIT) produces a Set<String> of FQCNs. Files map to classes via source parsing (SourceFileModel.filesToClassNames, test-order-core/src/main/java/me/bechberger/testorder/changes/SourceFileModel.java:2025-2071). Method-level precision exists in two layers: test methods via MethodHashStore (`fqcn#method` SHA over normalized bodies, test-order-core/src/main/java/me/bechberger/testorder/changes/MethodHashStore.java:74-80) and prod methods via BytecodeHashStore (opcode+operand hashes, test-order-core/src/main/java/me/bechberger/testorder/changes/BytecodeHashStore.java:473-571); LineDiff is only used for change-complexity scoring, never selection (test-order-core/src/main/java/me/bechberger/testorder/changes/ChangeComplexity.java:126-169).

Holes where a change never enters the changed-class set (evidence in ops/workflows/ChangeAnalysis.java unless noted):
1. Non-source inputs ignored EVERYWHERE: resources, pom.xml/build.gradle, .properties, YAML — filtered by `isSourceFile` (.java/.kt only) in git modes (test-order-core/src/main/java/me/bechberger/testorder/changes/GitChangeDetector.java:92,136), hash scan (test-order-core/src/main/java/me/bechberger/testorder/changes/FileHashStore.java:49-51), and structural diff (test-order-core/src/main/java/me/bechberger/testorder/changes/StructuralDiff.java:143-146). A behavioral config change maps to zero changed classes (mitigation since 2026-11-17: NonSourceChangeDetector triggers module select-all on the selection paths); bytecode cross-check only catches it when compiled output changes AND a baseline exists (test-order-core/src/main/java/me/bechberger/testorder/ops/workflows/ChangeAnalysis.java:307-345).
2. Bytecode baseline misses: first run adds no bytecode classes (test-order-core/src/main/java/me/bechberger/testorder/ops/workflows/ChangeAnalysis.java:322-336); v1 file without method hashes yields empty changed-method keys (test-order-core/src/main/java/me/bechberger/testorder/changes/BytecodeHashStore.java:311-314).
3. Silent degradation: bytecode scan and augmentation failures swallowed at debug level (test-order-core/src/main/java/me/bechberger/testorder/ops/workflows/ChangeAnalysis.java:342-344, 543-545); since-last-run mode has NO structural analysis at all (resolveStructuralDiffMode returns null, test-order-core/src/main/java/me/bechberger/testorder/ops/workflows/ChangeAnalysis.java:574-588).
4. Explicit changedClasses property overrides all real detection (test-order-core/src/main/java/me/bechberger/testorder/changes/ChangeDetectionSupport.java:124-141).
5. Deleted files: primary class recovered via git cat-file; secondary top-level types in a deleted/parsed-failing file fall back to path-derived name only (test-order-core/src/main/java/me/bechberger/testorder/changes/GitChangeDetector.java:116-130; test-order-core/src/main/java/me/bechberger/testorder/changes/SourceFileModel.java:2061).
6. Untracked new directories in UNCOMMITTED mode appear as `?? dir/` and fail isSourceFile (test-order-core/src/main/java/me/bechberger/testorder/changes/GitChangeDetector.java:70-71).
7. Cross-module sibling roots are only scanned in git modes, not since-last-run (test-order-core/src/main/java/me/bechberger/testorder/ops/workflows/ChangeAnalysis.java:258-263).
8. Library-namespace filter (ClassNameFilter) would drop project code living under java/javax/kotlin prefixes (test-order-core/src/main/java/me/bechberger/testorder/changes/ClassNameFilter.java:19-22).
