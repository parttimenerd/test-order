# test-order-annotations

Zero-dependency annotation library for test-order. Provides two annotations for
overriding score-based test ordering without touching scoring configuration.

## Annotations

### `@TestOrder`

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
```

| Attribute | Type | Default | Effect |
|---|---|---|---|
| `priority` | `Priority` | `NORMAL` | Position pin or score boost/penalty |
| `scoreBonus` | `int` | `0` | Added to computed score unconditionally |
| `changeBonus` | `int` | `0` | Added when change-detection marks this test affected |

**`Priority` values:**

| Value | Effect |
|---|---|
| `FIRST` | Pinned before all score-driven tests |
| `HIGH` | +1 000 to computed score |
| `NORMAL` | No override (default) |
| `LOW` | −1 000 from computed score |
| `LAST` | Pinned after all score-driven tests |

### `@AlwaysRun`

Guarantees a test class or method is **always included in select-mode subsets**
(`test-order:select` / `test-order:auto`), regardless of score. In order mode,
`@AlwaysRun` classes are pinned to the front of the execution order (before all
score-driven tests). Among multiple `@AlwaysRun` classes, alphabetical order is used.

```java
@AlwaysRun
class CriticalSmokeTest { … }
```

`@AlwaysRun` can be combined with `@TestOrder`: `scoreBonus` / `changeBonus` still apply
for score-based positioning when not pinned first.

## Notes

* **Not `@Inherited`** — annotations on a test base class are not inherited by subclasses;
  annotate each class independently.
