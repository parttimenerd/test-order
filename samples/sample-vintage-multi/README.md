# sample-vintage-multi

Multi-module reactor (`core` + `web`) where every module uses JUnit 4 via
the Vintage engine. Combines the multi-module concerns of `sample-multi`
with the legacy-engine coverage of `sample-vintage`.

## Try it

```bash
# Learn across both vintage modules
mvn -Dtestorder.mode=learn test

# Run only the core module's vintage tests in scored order
mvn -pl core -am test-order:auto test

# Inspect per-module diagnostics
mvn -pl web test-order:diagnose
```
