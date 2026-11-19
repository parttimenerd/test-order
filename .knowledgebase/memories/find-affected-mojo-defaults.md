---
id: find-affected-mojo-defaults
title: Maven `affected` default: topN=-1 (ALL affected tests), randomM=0 (no padding)
kind: finding
tags: [selection, maven, defaults]
applies_to: [test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/AffectedMojo.java]
source: proper-test-selection/find-affected-mojo-defaults
status: active
recorded: 2026-11-17
valid_at: 2026-11-17
---



# Maven `affected` default: topN=-1 (ALL affected tests), randomM=0 (no padding)

`AffectedMojo` defaults (test-order-maven-plugin/src/main/java/me/bechberger/testorder/maven/AffectedMojo.java:29-45): `topN` default `-1` ("include all change-affected tests"; `topN=0` is rejected as ambiguous), `randomM` default `0` ("only tests with a real change signal run"). The mojo logs an info line when topN=-1 explains it runs ALL affected tests and defers unaffected ones.

So the default `mvn test-order:affected test` selection = always-run + new + every class-level-affected test + changed test classes, in priority order. The default is coverage-complete ONLY to the extent that (a) the changed-class set is complete and (b) the dependency index has no false negatives — both investigated separately. Caps (topN>0), diversity padding (randomM>0), cache-skip, and member filtering are the opt-in levers that can drop an affected test from the selected subset.
