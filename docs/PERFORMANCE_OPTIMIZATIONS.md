# Performance Architecture

This document explains the key design decisions that keep test-order's overhead low.

## Learn-mode overhead

Learn runs instrument production bytecode to record which source classes each test
accesses. The instrumentation overhead on `spring-petclinic` (baseline ~4.9 s, 5 runs):

| Mode | Mean | Median | Std dev |
|---|---:|---:|---:|
| none (baseline) | 4.93 s | 4.97 s | 0.21 s |
| `CLASS` | 5.55 s | 5.67 s | 0.19 s |
| `METHOD` | 5.47 s | 5.44 s | 0.11 s |
| `MEMBER` (default) | 5.57 s | 5.45 s | 0.28 s |

**Order mode adds zero instrumentation overhead** — no agent is attached during normal
ordered runs. Re-learn is only needed after major refactors or on a periodic CI schedule.

## Dependency data collection: socket-based server

During a learn run the Maven/Gradle plugin starts an `IndexCollectorServer` (a local
socket listener) before the test JVM launches. When the test JVM shuts down, it sends
all dependency data over the socket in a single binary batch rather than writing
hundreds of individual `.deps` files or reloading and resaving the full index on each
forked JVM.

This avoids two earlier bottlenecks:
- **Full index round-trip per fork**: Previously each forked Surefire JVM loaded the
  entire dependency index, merged new data, and re-saved it. For large projects this
  added 100–500 ms per fork.
- **Per-test-class file writes**: Previously up to 800 `open→write→close` cycles per
  fork in `MEMBER` mode. Now: one binary batch over the socket.

The per-file fallback is still present for environments where no collector port is
available (standalone agent without the Maven/Gradle plugin).

## In-JVM index caching

`DependencyMap.load()` is called once per module in a Maven reactor (`affected`,
`auto`, `tiered-select`, etc.). For a 100-module build sharing a single index file
this would add 50–150 ms × 100 = 5–15 s of redundant LZ4 decompression and
deserialization.

A static `ConcurrentHashMap<(path, mtime, size), DependencyMap>` cache avoids this:
`load()` checks the cache with a single `getLastModifiedTime()` call (~0.1 ms) before
deserializing. `save()` evicts the entry for the written path so the next load after
an update reads fresh data.

The same pattern is used for `TestOrderState` (`state.lz4`) to avoid repeated
deserialization when multiple CLI commands or plugin goals run in the same JVM.

## Selective learn (`testorder.learn.selective`)

For projects where full-suite instrumentation is expensive, selective learn limits
the agent to classes **reachable from the current changes** — changed classes plus
their transitive callees up to 4 hops in the static call graph. When no structural
changes are detected the uncertain-class set is empty and the agent is not attached
at all, giving zero overhead on unchanged runs.

Combine with `testorder.auto.alwaysLearn=true` for background incremental index
maintenance on every ordered run:

```bash
mvn test -Dtestorder.auto.alwaysLearn=true -Dtestorder.learn.selective=true
```

## Near-universal dependency filtering

A handful of utility classes (e.g. Jackson's `ClassUtil`, logging facades) can appear
in nearly every test's dependency set. Such classes carry no useful selection signal
but inflate the index and dilute scoring.

Set `testorder.deps.dropFrequencyThreshold=0.8` to drop any dep class that appears
in more than 80% of test entries at index-write time. This shrinks the index and
improves the signal-to-noise ratio for tests that touch genuinely unique code paths.

See the [Known limitations](README.md#known-limitations) note in the documentation
index for when this matters most.

## Benchmarking

To verify overhead before and after any change to the learn or ordering path:

```bash
bash scripts/bench_learn_modes_multiproject.sh --quick --repeat 3
```

Use `--repeat 3` (not just `--quick` alone) — single-pass variance is ~2× due to JVM
GC noise on small projects. Compare minimum times across modes rather than averages.
