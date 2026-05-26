# Bugs Found While Dog-Fooding test-order

## Status Summary

| # | Bug | Severity | Status |
|---|-----|----------|--------|
| BUG-4 | JUnit 4 Vintage: `NoClassDefFoundError: MethodOrderer` | High | Already fixed (try/catch guard in TelemetryListener) |
| BUG-6 | Method reordering breaks order-dependent tests | High | Won't-fix — PER_CLASS lifecycle already guarded |
| BUG-7 | Invalid weights file silently ignored | Medium | Already fixed (WeightResolverOperation warns) |
| BUG-8 | `@Nested` classes not executed | High | Open — needs root cause investigation |
| BUG-9 | `@Order` without `@TestMethodOrder` silently ignored | Medium | FIXED — PriorityMethodOrderer applies @Order directly |
| BUG-10 | Offline learn never creates index | High | FIXED — fallback processed unconditionally |
| BUG-11 | `topN=0` selects wrong number | Low | FIXED — warns clearly |
| BUG-12 | CLI aggregate opaque error on missing dir | Low | FIXED |
| BUG-13 | CLI explicit mode without `--classes` silent | Low | FIXED |
| BUG-14 | Gradle: `test-order-annotations` missing from test classpath | High | FIXED — added testImplementation dep |
| ERR-1..9 | 9 error message improvements | Low-Med | FIXED |

---

## BUG-9: @Order Without @TestMethodOrder — FIXED

Root cause: JUnit 5 only honours @Order when @TestMethodOrder(MethodOrderer.OrderAnnotation.class) is also present. Plugin warned but did not fix it.

Fix: PriorityMethodOrderer.applyJUnitOrderAnnotation() now sorts methods by their @Order value directly when @TestMethodOrder is absent.

File: test-order-junit/src/main/java/me/bechberger/testorder/junit/PriorityMethodOrderer.java

Reproducer:
  // No @TestMethodOrder — before: ran in arbitrary order; after: runs 1->2->3
  public class OrderedTest {
      @Test @Order(3) void third() {}
      @Test @Order(1) void first() {}
      @Test @Order(2) void second() {}
  }

---

## BUG-14: Gradle Plugin Missing test-order-annotations on Test Classpath — FIXED

Symptom: Using @AlwaysRun or @TestOrder in Gradle project fails:
  error: cannot find symbol: class AlwaysRun

Root cause: TestOrderPlugin.addTestOrderTestDependencies() added test-order-junit
and test-order-core to testRuntimeOnly but never added test-order-annotations
(needed at compile time for @AlwaysRun, @TestOrder).

Fix: Added testImplementation on test-order-annotations.

File: test-order-gradle-plugin/src/main/java/me/bechberger/testorder/gradle/TestOrderPlugin.java

---

## BUG-8: @Nested Classes Not Executed (Open)

Symptom: @Nested inner class tests are indexed but don't run when plugin is active.

Most likely cause: ASM transformer modifies inner class constructors (removes outer-class
reference), breaking JUnit's ability to instantiate them.

Reproducer:
  public class OuterTest {
      @Test void outerTest() {}        // runs OK
      @Nested
      class InnerTest {
          @Test void innerTest() {}    // disappears when plugin active
      }
  }

Next step: run with -Dtestorder.debug=true and check transformation of OuterTest$InnerTest.

---

## Error Message Improvements (ERR-1..9) — FIXED

Nine messages improved with correct recovery commands and diagnose pointer:
1. AggregateMojo — no deps dir: "Run learn mode first: mvn test -Dtestorder.mode=learn"
2. ExportJsonMojo — no index: added recovery command
3. RunTierMojo — missing tier file: guidance to run tiered-select first
4. DiagnosticOperation — INDEX_NOT_FOUND: added recovery hints
5. DiagnosticOperation — INDEX_EMPTY: fixed Gradle task name (testOrderAggregate -> mvn test-order:aggregate)
6. DiagnosticOperation — INDEX_NEEDS_REBUILD: added explicit command
7. DiagnosticOperation — STATE_NOT_FOUND: clarified when state file is created
8. DiagnosticOperation — DEPS_NOT_FOUND: added explicit command
9. ReactorOrderOperation — no index: added both command forms

---

## Previously Fixed (Earlier Sessions)

- Collector fallback processed unconditionally
- Wrong "did you mean" property suggestions
- Duplicate deprecation warnings suppressed
- Kotlin class dir detection in Gradle IT
- Corrupt index recovery in Gradle IT
- CI Maven ITs parallelized (3-way matrix)
- TieredWorkflowIT aggregate conditional
- SourceFileModelUtilTest timing threshold relaxed (200ms -> 1000ms)
- Semicolon separator fallback in parseExplicitClasses
- ChangeDetectionSupport auto-mode explicit-classes precedence fix
