# sample-junit6

Smoke-test sample for JUnit 6 compatibility. Mirrors `sample-basic` but
pulls in JUnit Jupiter 6.x and the `test-order-annotations` artifact so the
`@AlwaysRun` annotation can be applied. Use it to confirm the plugin works
against the newest JUnit platform release.

## Try it

```bash
# Learn against JUnit 6
mvn -Dtestorder.mode=learn test

# Verify ordering still works on the JUnit 6 platform
mvn test-order:auto test
mvn test-order:show
```
