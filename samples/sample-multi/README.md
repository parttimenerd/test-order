# sample-multi

A two-module reactor (`core` + `web`) used to verify multi-module behavior:
each module gets its own `.test-order/` directory and its own dependency
index, but the plugin is configured once at the parent. Use this sample to
exercise reactor-aware features like per-module learn state and
`-am` / `-pl` interactions.

## Try it

```bash
# Learn for the entire reactor
mvn -Dtestorder.mode=learn test

# Run only the web module's tests in scored order
mvn -pl web -am test-order:auto test

# Inspect each module's index independently
mvn -pl core test-order:diagnose
mvn -pl web  test-order:diagnose
```
