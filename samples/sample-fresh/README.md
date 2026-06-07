# sample-fresh

A clean copy of `sample-basic` with no `test-dependencies.lz4` checked in. Use
this sample when you want to demonstrate the *first-run* experience:
auto-mode detecting the missing index, falling back to learn mode, and
producing the index for subsequent runs. Also handy when comparing scoring
output before and after a learn cycle (a custom `my-weights.txt` is included
to show how scoring weights can be overridden).

## Try it

```bash
# First run: no index → auto-mode learns
mvn test-order:auto test

# Second run: index now exists → tests are scored and ordered
mvn test-order:auto test

# Inspect the ranking with the custom weights file
mvn test-order:show -Dtestorder.weights.file=my-weights.txt
```
