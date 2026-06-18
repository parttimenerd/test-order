# Kotlin & Kotest Support

`test-order` has **limited but tested** support for Kotlin projects using Kotest with the JUnit Platform runner.

## What Works

- ✅ Kotest `StringSpec`, `FunSpec`, and other spec styles via JUnit Platform runner (kotest-runner-junit5)
- ✅ Kotlin source detection in `src/main/kotlin` and `src/test/kotlin`
- ✅ Dependency tracking on compiled Kotlin bytecode (no language-specific handling needed)
- ✅ Change detection on `.kt` source files
- ✅ Learn mode builds dependency indices for Kotest tests
- ✅ Class-level test ordering applied to Kotest test specs

## Limitations

- 🔶 **Method-level ordering**: Kotest's DSL-based test definitions map to a single test spec class; method-level ordering may not align with test case structure.
- 🔶 **Inline functions**: Kotlin `inline fun` calls are erased by the compiler (bytecode is copied into the call site). In `MEMBER` mode (the default), the agent cannot track the inlined call, so dependency precision is reduced. Consider switching to `CLASS` mode for Kotlin projects — it tracks at class level and is not affected by inlining: `-Dtestorder.instrumentation.mode=CLASS`.
- 🔶 **Tested with**: Kotest 5.9.1 + JUnit Platform runner. Other Kotest configurations may behave differently.
- 🔶 **Not supported**: Kotest tests run directly (without JUnit Platform runner) will not be reordered.

## Maven Example

```xml
<dependency>
    <groupId>io.kotest</groupId>
    <artifactId>kotest-runner-junit5-jvm</artifactId>
    <version>5.9.1</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.kotest</groupId>
    <artifactId>kotest-assertions-core-jvm</artifactId>
    <version>5.9.1</version>
    <scope>test</scope>
</dependency>
```

Verify Kotest integration with:
```bash
mvn clean test-order:auto test
```

## Working Examples

- [test-order-example-kotlin](https://github.com/parttimenerd/test-order/tree/main/test-order-example/test-order-example-kotlin) — Kotlin Maven example
- [fixture-kotest](https://github.com/parttimenerd/test-order/tree/main/test-fixtures/fixture-kotest) — Kotest fixture tests
