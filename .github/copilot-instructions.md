# Workspace Instructions

## Test-Order Plugin: Running Tests Efficiently

This project is `test-order`, a Maven/Gradle plugin for affected-test selection.
It maintains a dependency index (`.test-order/test-dependencies.lz4`) that maps
which production classes each test exercises, then uses git change detection to
run only the tests affected by recent code changes.

### Modes

| Mode | When to use | Command |
|------|-------------|---------|
| **auto** (default) | Normal development; learns if no index exists, otherwise selects | `mvn test` |
| **learn** | After large refactors or dependency changes; rebuilds the index | `mvn test-order:learn test` or `mvn test -Dtestorder.mode=learn` |
| **select** | Quick check: run only tests affected by uncommitted changes | `mvn test-order:select test` |
| **run-remaining** | Run the deferred (non-selected) tests afterwards | `mvn test-order:run-remaining test` |

### Quick-check workflow (few files changed)

```sh
mvn test-order:select test
```

This detects uncommitted git changes, looks up which tests depend on those
classes, and runs only those tests. Fast feedback for small edits.

### After large changes (new dependencies, major refactor)

```sh
mvn test-order:learn test
```

This re-instruments all tests and rebuilds the dependency index.
Follow-up runs can then use `select`/`auto` again.

### Change detection modes (`-Dtestorder.changeMode=...`)

- `uncommitted` (default) — git working-tree + staged changes
- `since-last-commit` — diff vs previous commit (good for CI)
- `since-last-run` — hash snapshot comparison (no git needed)
- `explicit` — provide `-Dtestorder.changed.classes=com.example.Foo,...`

### Other useful flags

- `-Dtestorder.select.topN=20` — limit to top 20 affected tests
- `-Dtestorder.skip=true` — disable the plugin entirely
- `mvn test-order:diagnose` — check plugin setup and configuration
- `mvn test-order:show` — inspect test order & scoring without running

---

## Dot-test file cleanup

When cleanup of `.test` files is requested, use the Python script:

`python scripts/delete_dot_test_files.py <folder>`

For preview-only mode:

`python scripts/delete_dot_test_files.py <folder> --dry-run`

Do not delete `.test` files manually if this script can be used.
