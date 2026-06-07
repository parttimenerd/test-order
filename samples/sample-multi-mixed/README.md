# sample-multi-mixed

A multi-module reactor that mixes module shapes: an `api` module and a
`service` module with different test layouts and dependency profiles. Used
to verify that per-module dependency indexes don't leak across modules and
that the plugin handles reactors where some modules have tests and others
don't.

## Try it

```bash
# Learn across both modules
mvn -Dtestorder.mode=learn test

# Show per-module rankings — they should differ
mvn -pl api     test-order:show
mvn -pl service test-order:show
```
