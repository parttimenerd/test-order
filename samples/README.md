# Sample Projects

Sample projects for trying out test-order and for integration testing.
If you're new, start with **`sample-basic`** (minimal setup) or **`sample-shop`** (realistic app with multiple services).

Each sample binds the `prepare` goal in its POM and is a child module of the root `test-order` parent. See [docs/GETTING_STARTED.md](../docs/GETTING_STARTED.md) for a guided walkthrough using these samples.

| Sample | Purpose |
|---|---|
| `sample-basic` | Minimal JUnit 6 project — baseline for most IT scenarios |
| `sample-always-learn` | Always-on learn mode (agent attached on every test run) |
| `sample-fresh` | Fresh project with no prior `.test-order/` state (cold-start testing) |
| `sample-groupid-mismatch` | Artifact groupId differs from source package — tests package-detection fallback |
| `sample-junit6` | Dedicated JUnit 6 fixture |
| `sample-multi` | Multi-module Maven project — tests reactor-level aggregation |
| `sample-no-tests` | Project with zero test classes — tests graceful no-op handling |
| `sample-shop` | Small shopping app (Cart, Product, Invoice) with real source and tests |
| `sample-validation` | Exercises parameter validation edge cases |

## Running a sample manually

```bash
cd samples/sample-basic

# Learn mode (first run — records dependencies)
mvn test

# Order mode (second run — tests reordered by priority)
mvn test

# Show computed order
mvn test-order:show

# Interactive dashboard
mvn test-order:dashboard
```

> **Tip:** If you want to force learn mode, use `mvn test -Dtestorder.mode=learn`.
> To see debug output: `mvn test -Dtestorder.debug=true`.

## External test repositories

The following directories at the project root are **git submodule references**
to external open-source projects used for heavy integration testing:

- `spring-ai/` — Spring AI (used by the Maven plugin Mojo tests)
- `spring-boot/` — Spring Boot (used by the Gradle plugin `SpringBootCoreModulesIT`)
- `spring-petclinic/` — Spring Petclinic (used for overhead benchmarks and demo recordings)

These are checked out on demand; see the Gradle integration test prerequisites in the main README.
