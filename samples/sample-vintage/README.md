# sample-vintage

JUnit 4 sample running through the JUnit Vintage engine. Used to verify
that test-order discovery, ordering, and selection work for legacy JUnit 4
test classes — the same auto-mode behavior available to JUnit 5 users.

## Try it

```bash
# Learn against JUnit 4 / Vintage
mvn -Dtestorder.mode=learn test

# Auto-mode should order vintage tests just like Jupiter ones
mvn test-order:auto test
mvn test-order:show
```
