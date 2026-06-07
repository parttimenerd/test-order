# sample-od-bugs

Demonstrates *order-dependent* test bugs: tests that pass in one order and
fail in another (`VictimTest` only succeeds after `SetupTest` initializes a
shared registry, etc.). Use this sample to verify that test-order's scoring
flags fragile tests, and to experiment with `optimize` and `struct-diff`
subcommands that surface order-dependence.

## Try it

```bash
# Learn — note that test-order's reported "ordering" should mitigate flakes
mvn -Dtestorder.mode=learn test

# Run in selected order — victim tests should now pass
mvn test-order:auto test

# Use the CLI to inspect order-dependence signals
mvn test-order:advise
```
