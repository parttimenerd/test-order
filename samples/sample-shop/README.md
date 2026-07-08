# sample-shop

A "user-perspective" integration-test sample modeling a tiny e-commerce
domain (cart, pricing, checkout). Used in demos and the storyboard to show
realistic scoring output where some tests touch many classes (broad
integration) and others touch few (focused unit tests). Good for
showcasing the dashboard and `top scorer` output.

## Try it

```bash
# Build the dependency index
mvn -Dtestorder.mode=learn test

# Run with auto-mode — the pre-test summary highlights the top scorer
mvn test-order:auto test

# Generate the HTML dashboard
mvn test-order:dashboard
open target/test-order-dashboard/index.html
```

## Expected output

After the learn run you should see several test classes merged into the index.
After `test-order:auto test` with no changes the pre-test summary line looks
like:

```
[INFO] [test-order] Order mode — 0 changed classes | 8 tests ranked | top scorer: CheckoutServiceTest (score 0)
```

Modify any production class (e.g. add a line to `CartService.java`), then
re-run `mvn test-order:auto test`. The test(s) that cover `CartService` should
appear first with a positive score, and the summary should report the number
of changed classes detected. The dashboard shows per-test score breakdowns,
run history, and dependency edges — use it to understand which tests cover
which production code.
