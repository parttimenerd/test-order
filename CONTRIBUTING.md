# Contributing

## Development setup

- Java 17 or newer
- Maven 3.9+
- Git
- Optional: Gradle for `test-order-gradle-plugin`

Install dependencies and run the main validation paths from the repository root:

```bash
mvn test
mvn -Prun-its verify -pl test-order-maven-plugin
cd test-order-gradle-plugin && ./gradlew test
```

## Change guidelines

- Keep changes scoped to the module you are touching.
- Prefer targeted regression tests when fixing ordering, persistence, or instrumentation bugs.
- Preserve support for both JUnit 5 and JUnit 6 unless the change explicitly narrows compatibility.
- Treat generated state and local benchmark artifacts as disposable local output, not committed source.

## Pull requests

1. Explain the user-visible behavior change.
2. Call out compatibility risks, especially around agents, JUnit ordering, and build plugins.
3. Include the validation path you used for Maven, Gradle, or integration fixtures as appropriate.

## Repository areas

- `test-order-core/` — scoring, change detection, persistence, CLI
- `test-order-junit/` — JUnit orderers and listeners
- `test-order-maven-plugin/` — Maven plugin and IT fixtures
- `test-order-gradle-plugin/` — Gradle plugin and integration tests
- `test-order-agent/` — learn-mode Java agent
