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

## Expected output

After the learn run both modules produce their own index entries. The
`diagnose` goal for each module should report something like:

```
[INFO] [test-order] Index: OK (N test classes, M dependencies)
[INFO] [test-order] State: OK
[INFO] [test-order] Change detection: uncommitted (git)
```

If `diagnose` says "Index: MISSING", the learn run did not complete for that
module — check that `<extensions>true</extensions>` is present in the parent
`pom.xml` plugin block. Running `mvn -pl web -am test-order:auto test` after a
change to any `core` class should surface `web` tests that transitively depend
on that class first.
