# Documentation Index

| Document | Description |
|----------|-------------|
| [CHEAT_SHEET.md](CHEAT_SHEET.md) | **Quick reference** — commands, properties, troubleshooting in one page |
| [GETTING_STARTED.md](GETTING_STARTED.md) | Step-by-step tutorial: first run → reordering → dashboard |
| [CI.md](CI.md) | CI integration: caching, tiered pipelines, cache-key strategy |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Build from source, use the SNAPSHOT locally, common dev commands |
| [CLI_REFERENCE.md](CLI_REFERENCE.md) | Goals, properties, change-detection modes, ML configuration, CI patterns |
| [ARCHITECTURE.md](ARCHITECTURE.md) | Stable architecture, data flow, ML subsystem, and contribution guidance |
| [DETECT_DEPENDENCIES.md](DETECT_DEPENDENCIES.md) | Order-dependent test detection algorithms and configuration |
| [INDEX_FORMAT.md](INDEX_FORMAT.md) | Dependency index binary format specification |
| [KOTEST.md](KOTEST.md) | Kotest framework integration guide |
| [FRAMEWORK_COMPARISON.md](FRAMEWORK_COMPARISON.md) | JUnit 5 vs TestNG feature comparison and migration guide |
| [MAVEN_PLUGIN.md](MAVEN_PLUGIN.md) | Maven plugin detailed reference: goals, ML predictions, show command, CI setup |
| [MULTI_MODULE_SETUP.md](MULTI_MODULE_SETUP.md) | Multi-module project setup and aggregation |
| [SCORING.md](SCORING.md) | Scoring formula, weights, ML-enhanced scoring, and automatic tuning |

## Quick Links

- **New here?** Start with the [Cheat Sheet](CHEAT_SHEET.md) or the [Getting Started tutorial](GETTING_STARTED.md)
- **Building from source**: [DEVELOPMENT.md](DEVELOPMENT.md)
- **Gradle plugin**: See [test-order-gradle-plugin/README.md](https://github.com/parttimenerd/test-order/blob/main/test-order-gradle-plugin/README.md)
- **JUnit 5 module details**: [test-order-junit/README.md](https://github.com/parttimenerd/test-order/blob/main/test-order-junit/README.md)
- **TestNG module details**: [test-order-testng/README.md](https://github.com/parttimenerd/test-order/blob/main/test-order-testng/README.md)
- **JUnit 5 vs TestNG differences**: [FRAMEWORK_COMPARISON.md](FRAMEWORK_COMPARISON.md)
- **CI setup**: [CI.md](CI.md) and [ci-examples/](https://github.com/parttimenerd/test-order/tree/main/docs/ci-examples)
- **ML predictions**: [MAVEN_PLUGIN.md § ML Failure Predictions](MAVEN_PLUGIN.md#ml-failure-predictions)
- **Show command**: [MAVEN_PLUGIN.md § Show Goal](MAVEN_PLUGIN.md#show-goal)
- **Dashboard**: [CLI_REFERENCE.md § Dashboard](CLI_REFERENCE.md#dashboard)

## Where do I start?

Pick the path that matches your role:

- **New user, just want to try it** → [GETTING_STARTED.md](GETTING_STARTED.md), then poke at [`samples/sample-basic`](https://github.com/parttimenerd/test-order/tree/main/samples/sample-basic).
- **Using TestNG** → [GETTING_STARTED.md](GETTING_STARTED.md) applies equally; see [FRAMEWORK_COMPARISON.md](FRAMEWORK_COMPARISON.md) for differences from the JUnit integration.
- **Already have a project, want a quick lookup** → [CHEAT_SHEET.md](CHEAT_SHEET.md) (one-page command/property reference).
- **CI engineer wiring this into a pipeline** → [CI.md](CI.md) plus [ci-examples/](https://github.com/parttimenerd/test-order/tree/main/docs/ci-examples).
- **Multi-module reactor maintainer** → [MULTI_MODULE_SETUP.md](MULTI_MODULE_SETUP.md).
- **Tuning scoring or weights** → [SCORING.md](SCORING.md), then the dashboard's Weights tab.
- **Hunting a flaky test** → [DETECT_DEPENDENCIES.md](DETECT_DEPENDENCIES.md) + [`samples/sample-od-bugs`](https://github.com/parttimenerd/test-order/tree/main/samples/sample-od-bugs).
- **Something is broken** → [CHEAT_SHEET.md § Troubleshooting](CHEAT_SHEET.md), then `mvn test-order:diagnose`.
- **Contributing or building from source** → [DEVELOPMENT.md](DEVELOPMENT.md) and [ARCHITECTURE.md](ARCHITECTURE.md).

## Known limitations

The dependency index cannot distinguish _how heavily_ a test exercises a class — only whether it touched it at all. For highly-transitive dependencies (e.g. Jackson's `ClassUtil`, which appears in 97% of tests), the selection signal is weak. TF-IDF scoring reduces the noise but does not eliminate it for classes with near-universal coverage. Use `-Dtestorder.deps.dropFrequencyThreshold=0.8` to filter out near-universal deps from the index entirely.

---

MIT licensed — see [LICENSE](https://github.com/parttimenerd/test-order/blob/main/LICENSE) · [GitHub](https://github.com/parttimenerd/test-order) · [Report an issue](https://github.com/parttimenerd/test-order/issues/new)
