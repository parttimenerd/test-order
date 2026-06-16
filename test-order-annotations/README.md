# test-order-annotations

Zero-dependency annotation library for overriding score-based test ordering.

## `@TestOrder`

Fine-grained control over position and score. Can be placed on a test class or method.

```java
// Pin to the very front
@TestOrder(priority = Priority.FIRST)
class CriticalSmokeTest { … }

// Pin to the very back
@TestOrder(priority = Priority.LAST)
class SlowIntegrationTest { … }

// Flat score boost (unconditional)
@TestOrder(scoreBonus = 200)
class ImportantTest { … }

// Extra points only when change-detection marks this test as affected
@TestOrder(changeBonus = 150)
class PaymentServiceTest { … }

// Combined: unconditional boost + extra push when changed
@TestOrder(scoreBonus = 50, changeBonus = 150)
class AuthTest { … }
```

| Attribute     | Type       | Default  | Effect                                               |
|---------------|------------|----------|------------------------------------------------------|
| `priority`    | `Priority` | `NORMAL` | Position pin or score boost/penalty                  |
| `scoreBonus`  | `int`      | `0`      | Added to computed score unconditionally              |
| `changeBonus` | `int`      | `0`      | Added when change-detection marks this test affected |

**`Priority` values:**

| Value    | Effect                                                                      |
|----------|-----------------------------------------------------------------------------|
| `FIRST`  | Pinned before all score-driven tests (ordering only — see `@AlwaysRun`)    |
| `HIGH`   | +1 000 to computed score                                                    |
| `NORMAL` | No override (default)                                                       |
| `LOW`    | −1 000 from computed score                                                  |
| `LAST`   | Pinned after all score-driven tests                                         |

## `@AlwaysRun`

Guarantees a test class or method is **always included in affected-mode subsets**
(`test-order:affected` / `test-order:auto`) regardless of score, **and** pins it before all
score-driven tests in the execution order.

```java
@AlwaysRun
class CriticalSmokeTest { … }
```

**How it differs from `@TestOrder(priority = Priority.FIRST)`:**
`Priority.FIRST` only affects ordering — the test can still be omitted when running a
subset. `@AlwaysRun` adds a selection guarantee: the class is unconditionally included even
if it has no dependency overlap with changed code and does not make the top-N cut.

**Ordering among multiple `@AlwaysRun` / `FIRST`-pinned classes:**
Sorted by `scoreBonus` descending (from a combined `@TestOrder`), then alphabetically by
class name. A class with `@AlwaysRun @TestOrder(scoreBonus = 100)` runs before one with
only `@AlwaysRun`.

`@AlwaysRun` can be combined with `@TestOrder`: `scoreBonus` / `changeBonus` still apply
for relative ordering within the pinned group.

## Notes

* **Not `@Inherited`** — subclasses of an annotated test base class are not annotated
  implicitly; each class must be annotated independently.