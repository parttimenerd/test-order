# Sample Projects

Internal sample projects used by integration tests and for manual testing of the
`test-order-maven-plugin`. Each sample binds the `prepare` goal in its POM and
is a child module of the root `test-order` parent.

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

# Learn mode
mvn test -Dtestorder.mode=learn -Dspotless.check.skip=true

# Auto mode
mvn test-order:auto test -Dspotless.check.skip=true

# Show computed order
mvn test-order:show-order -Dspotless.check.skip=true
```

## External test repositories

The following directories at the project root are **git submodule references**
to external open-source projects used for heavy integration testing:

- `spring-ai/` — Spring AI (used by the Maven plugin Mojo tests)
- `spring-boot/` — Spring Boot (used by the Gradle plugin `SpringBootCoreModulesIT`)
- `spring-petclinic/` — Spring Petclinic (used for overhead benchmarks and demo recordings)

These are checked out on demand; see the Gradle integration test prerequisites in the main README.
