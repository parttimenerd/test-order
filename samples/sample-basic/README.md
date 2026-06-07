# sample-basic

The canonical "happy path" demo: a small Maven module with four production
classes (`Greeter`, `MathService`, `MessageFormatter`, `Validator`) and one
JUnit 5 test per class. The `test-order-maven-plugin` is bound with
`<extensions>true</extensions>`, so the standard auto-mode lifecycle is in
play. Use this sample to learn the basic learn → order → select cycle.

## Try it

```bash
# 1. Populate the dependency index (learn mode)
mvn -Dtestorder.mode=learn test

# 2. Re-run normally — auto-mode will use the index to score and order tests
mvn test-order:auto test

# 3. Inspect the ranking
mvn test-order:show
```
