# sample-validation

Validation/QA sample with screenshot fixtures used to spot-check the
dashboard, analytics views, and coverage rendering. The Java sources
(`Calculator`, `Formatter`, `Parser`, `Validator`) plus their tests are kept
deliberately simple so visual regressions in generated HTML stand out
clearly.

## Try it

```bash
# Standard learn → auto cycle
mvn -Dtestorder.mode=learn test
mvn test-order:auto test

# Render the dashboard and compare against the checked-in PNG fixtures
mvn test-order:dashboard
open target/test-order-dashboard/index.html
```
