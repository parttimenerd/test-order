# sample-no-tests

A minimal sample with production code (`Greeter.java`) but no test sources.
Used as a regression for graceful no-tests behavior: the plugin should not
fail the build, should not write an empty index, and should produce a
helpful message via `test-order:diagnose`.

## Try it

```bash
# Should succeed without errors and report "no tests"
mvn test-order:auto test

# Diagnose explains why no work was done
mvn test-order:diagnose
```
