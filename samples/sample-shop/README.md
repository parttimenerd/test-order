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
open target/test-order/dashboard/index.html
```
