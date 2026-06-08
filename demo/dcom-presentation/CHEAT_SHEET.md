# Presenter Cheat Sheet — d-com 2026

One-page reference for the live demo. Verified on 2026-06-08 against
`cloud-sdk-java/connectivity-destination-service` on this laptop.

---

## Verified numbers (this machine, this state)

| Run                                | Wall   | Tests | Errors | Selection                                  |
|------------------------------------|--------|-------|--------|---------------------------------------------|
| **Cold** magic-beat (`affected test`) | ~88 s  | 311   | 8      | Selected 8 (7 scored + 1 fast-diverse), deferred 7 |
| **Warm** magic-beat (re-run)       | ~55 s  | 311   | 8      | same                                        |
| **Green** after `fix-change.sh`    | ~55 s  | 104   | 0      | Selected 7 (7 scored), deferred 8           |

> Stage budget: **assume 60 s warm, 90 s cold.** First run on the laptop
> after sleep/restart is cold. Run the warm cycle once before going on
> stage so the mvnd daemon is hot.

> Selection counts swap by 1 between red and green — the bug adds a
> fast-diverse pad slot. Don't promise an exact number on stage; say
> **"about half the tests"**.

---

## Magic command (slide 2 beat 6)

```
cd cloud-sdk-java/<some-module>     # any module — ReactorContext finds the root
mvn test-order:affected test
```

No `-pl`, no `-am`. The plugin walks up to the reactor root, finds
`.test-order/`, picks affected tests across the whole multi-module build.

---

## Three-script demo flow

```
./reset.sh           # clean repo, drop .test-order/
./add-test-order.sh  # plug the plugin into the parent POM, run learn pass
./make-change.sh     # invert tenant check (uncommitted)
# → magic beat: cd into module, mvn test-order:affected test  →  RED
./fix-change.sh      # revert
# → mvn test-order:affected test  →  GREEN
```

`./reset.sh` is destructive — it `git reset --hard` and deletes
`.test-order/`. Don't run it after `make-change.sh` if you want to
preserve the bug.

---

## Expected output to call out on stage

- **Red run:** "Selected 8 tests, deferred 7 — failures show the inverted
  tenant check. About 95 % saved."
- **Green run:** "7 selected, 8 deferred, all pass — fix verified in a
  minute."
- The selection block prints with a leading `[test-order] Selection
  Summary:` header. Point at it.

---

## Noisy WARNs to ignore (don't read aloud)

These appear in cloud-sdk-java and are unrelated to the demo:

- `Static analysis fallback used — no usage data` (first run, before
  any usage data accumulates)
- `7 tests were NOT selected and will NOT run …` (this is the deferred
  list — informational, not an error)
- `Surefire excludes …` (plugin telling Surefire which classes to skip)
- `OData generator: discriminator/operationId not set` (cloud-sdk's
  own OpenAPI generator, nothing to do with test-order)

If audience asks about a WARN, say: *"That's the plugin telling you
what it skipped — exactly what you want it to print."*

---

## Fallbacks

| If…                                  | Do this                                          |
|--------------------------------------|--------------------------------------------------|
| Magic beat takes > 90 s              | Talk over it — describe the dep graph slide      |
| `mvn` not found                      | Use `mvnd` (alias is set up in the demo terminal)|
| `.test-order/` is missing            | `./add-test-order.sh` (re-runs learn)            |
| Red run is green / green is red      | `./reset.sh && ./add-test-order.sh && ./make-change.sh` and retry |
| Network blocks artifact download     | The demo uses local-only state — should not need network |
| Warm run is unexpectedly slow        | First run after `reset.sh` is always cold, ~88 s — that's fine |

---

## Things known-broken that you should NOT fix on stage

- **README inside `demo/dcom-presentation/` claims `~25 s`** — stale,
  retest shows ~55 s warm / ~88 s cold. Don't read those numbers from
  the README aloud.
- **README/storyboard expected output `Selected 7 tests, deferred 8`**
  is the **green** numbers, not the red ones. The red run inverts to
  `8 / 7`. Quote the live terminal, not the README.

(Both are tracked for a follow-up doc fix; not blocking the talk.)

---

## Pre-flight (15 min before stage)

```
cd demo/dcom-presentation
./reset.sh
./add-test-order.sh                    # ~3-5 min, learn pass
./make-change.sh                       # introduce the bug
./start-slides.sh                      # opens slides AND dashboard side-by-side
cd cloud-sdk-java/cloudplatform/connectivity-destination-service
mvn test-order:affected test           # WARM the daemon, expect red
                                       # ~88 s first time, ~55 s second time
# leave the bug in place; the talk runs the same command live
```

After the talk:

```
cd demo/dcom-presentation
./fix-change.sh    # revert to clean
```

---

## One-liners if asked

- *"How does it know what each test touches?"* — A Java agent records
  every class touched per test, stored in `.test-order/`.
- *"What about new tests?"* — Always run. Selection only skips tests we
  have evidence for.
- *"Does it work without instrumentation?"* — Yes, falls back to static
  analysis (the WARN you may see). Less precise but no agent needed.
- *"Can I use it in CI?"* — Slide 3. Three-tier pipeline — affected →
  broader → remaining.
- *"Production-ready?"* — Experimental. Open source. Bugs welcome.
