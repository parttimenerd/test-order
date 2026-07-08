# sample-vintage-gradle

The only Gradle sample in the repository. Combines the JUnit 4 / Vintage
engine with the `test-order-gradle-plugin` so contributors can verify
parity between Maven and Gradle plugin surfaces (auto, affected, explain,
diagnose).

> **Gradle vs Maven syntax:** Gradle uses `-P` to pass project properties
> (e.g., `-Ptestorder.mode=learn`). Maven uses `-D` for system properties
> (e.g., `-Dtestorder.mode=learn`). Use the tool-specific prefix — the two
> are not interchangeable.

## Try it

```bash
# Learn mode under Gradle
./gradlew -Ptestorder.mode=learn test

# Run the affected/auto path
./gradlew testOrderAffected test

# Explain why a particular test was scored
./gradlew testOrderExplain -Ptest=com.myapp.GreeterTest
```
