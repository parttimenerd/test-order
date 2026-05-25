# SAP DCOM Demo Jam — "You Are Running the Wrong Tests First"

6-minute Demo Jam. 6 slides. Everything else: real commands in a real terminal.
Single project: **SAP Cloud SDK for Java** (cloud-sdk-java). No cap-sflight.

## Before the talk

```sh
export JAVA_HOME=/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home
./prepare.sh          # builds everything, runs learn pass + 3 bug-fix history cycles (~15 min first time)
./reset-demo.sh       # ensures clean state
cd slides && npm run dev   # starts slides at localhost:3030
# In separate terminal tab:
cd cloud-sdk-java && mvn test-order:serve -pl cloudplatform/connectivity-destination-service
# Open browser at localhost:8080 (keep tab open, shows history from prepare.sh)
```

Terminal font: **20pt+**. Test on projector.

---

## Timing

| Time  | What                             | Where    |
|-------|----------------------------------|----------|
| 0:00  | Slide 1 — Title                  | Slides   |
| 0:20  | The Pain (mvn clean test, killed) | Terminal |
| 1:50  | toggle on + make-change + select  | Terminal |
| 2:20  | Slide 2 — Results (5:00→17s)     | Slides   |
| 2:35  | Slide 3 — How It Works           | Slides   |
| 3:00  | Dashboard live (browser)         | Browser  |
| 3:10  | Agentic Demo (VS Code)           | VS Code  |
| 4:25  | Slide 4 — AI Feedback Loop       | Slides   |
| 4:50  | Slide 5 — Closing Line           | Slides   |
| 5:05  | Slide 6 — Links                  | Slides   |

---

## Actual Measured Timings (on this machine)

| Command | What happens | Wall time |
|---------|-------------|-----------|
| `mvn clean test` | Full clean build + all 469 tests | **5:00+** |
| `mvn test-order:select test` | 7 affected test classes (86 tests) — **with bug** | **17s (red)** |
| same after fix | same tests | **17s (green)** |

---

## Live Commands (what you actually type)

### The Pain (0:15–2:00)

> "SAP Cloud SDK for Java. The real thing. 65 Maven modules.
>  I changed one file in DestinationService. Maven has to rebuild everything
>  that depends on it — that's 65 modules — and run 469 tests."

```sh
cd cloud-sdk-java
mvn clean test
```

This takes **5+ minutes** total. Talk over it while tests compile and scroll:

> "Clean build. All 65 modules. Every test in the repo. This is what CI does on every push."

**Kill after ~90 seconds** (Ctrl+C):

> "Over a minute gone. This would run for another four. I haven't seen a single test result."

### The Magic Moment (1:50–2:20)

```sh
cd /Users/i560383_1/code/experiments/test-order/demo/dcom-presentation
./toggle-test-order.sh on
./make-change.sh     # inverts the tenant-routing check in DestinationRetrievalStrategyResolver
cd cloud-sdk-java
mvn test-order:select test
```

Expected output (~17 seconds total):
```
[test-order] Selected 7 tests, deferred 10
Running ... DestinationRetrievalStrategyResolverTest
...
Tests run: 86, Failures: 3, Errors: 0
BUILD FAILURE
Total time: 17s
```

> "Seven test classes. 17 seconds. Build failure."

→ Switch to slides (SlideResults).

### Dashboard Demo (3:00–3:10)

Switch to browser tab at **localhost:8080** (already running). 10 seconds max.

Point at:
1. **Tests tab** — ranked order with scores, which 7 ran
2. **Analytics** — APFD trend, 6 runs of pass/fail history
3. Quick mention: flakiness tracking, co-failure patterns

> "It's been tracking every run. Which tests are your best early-warning signals.
>  Which ones are flaky. How early bugs surface — that's APFD."

Switch back to slides immediately.

### Agentic Demo (3:10–4:25)

Switch to **VS Code** with cloud-sdk-java open. Show `.github/copilot-instructions.md` tab.

> "One file. It tells the agent: after every change, run test-order select.
>  The agent gets a result in 17 seconds — not 5 minutes."
>
> "As a Gradle DevRel once put it: when your feedback loop takes twice as long,
>  you don't slow down 2× — you slow down 4×. Context switches compound."

The bug is already in the code from `./make-change.sh`. Copilot's job: read the failure and fix it.

**Live prompt to Copilot** (type in chat):
```
The tests are failing. Read the failure output and fix the bug.
After the fix, run the tests using the project's test instructions.
```

**What should happen**:
1. Copilot runs `mvn test-order:select test` (~17s)
2. `DestinationRetrievalStrategyResolverTest` fails — logic inversion in `currentTenantIsProvider()`
3. Copilot reads the failure, fixes `!Objects.equals(...)` → `Objects.equals(...)`
4. Copilot re-runs test-order:select — green (~17s)

> "The agent read the failure. Fixed it. 17 seconds to catch, 17 seconds to verify.
>  Under 40 seconds, start to finish."

**If Copilot doesn't auto-run tests** (narrate instead):
```sh
cd cloud-sdk-java
mvn test-order:select test
```

Point at the failure output, let Copilot fix it, then re-run.

**Full manual fallback**:
```sh
cd /Users/i560383_1/code/experiments/test-order/demo/dcom-presentation
./fix-change.sh    # removes the negation: !Objects.equals → Objects.equals
cd cloud-sdk-java
mvn test-order:select test   # green, ~17s
```

### Close (5:30)

> "Maybe your test suite isn't too large."
> *(pause)*
> "Maybe you're just running the wrong tests first."

**Stop. No "thank you." No roadmap. Walk off.**

---

## Talk Track (key lines)

| When | Say |
|------|-----|
| 0:00 | "The most expensive thing in software delivery is waiting for feedback." |
| 0:15 | "SAP Cloud SDK for Java. 65 modules. I changed one file." |
| 0:30 | "Clean build, full test suite. This is what CI does on every push." |
| 1:15 | *(kill mvn)* "Over a minute gone. Four more to go. Zero signal. I don't know if anything's broken." |
| 1:50 | *(toggle on + make-change + select running)* "One plugin. It learned the dependency graph." |
| 2:10 | *(BUILD FAILURE ~17s)* "Seven test classes. 17 seconds." |
| 2:20 | *(SlideResults)* "5 minutes. 17 seconds. Same change. Same confidence. Twenty times faster." |
| 2:35 | *(SlideHowItWorks)* "Learn the dependency graph once — one pass, 12% overhead, then you're done. git diff on every commit. Intersect. Score. Select." |
| 2:50 | "One learn run. Every commit after that: signal in seconds." |
| 3:00 | *(browser)* "It's been tracking every run. Which tests are your best early-warning signals. Which ones are flaky." |
| 3:10 | "One instructions file. That's the entire AI integration." |
| 3:20 | "As a Gradle DevRel once put it: 2× slower feedback → 4× slower developer." |
| 3:30 | *(Copilot runs tests — red ~17s)* "There's the bug. 17 seconds." |
| 4:10 | *(Copilot fixes, re-runs ~17s)* "Green. Under 40 seconds, start to finish." |
| 4:50 | "Maybe your test suite isn't too large." *(pause)* "Maybe you're just running the wrong tests first." |

---

## Setup for Agentic Demo

1. Open `cloud-sdk-java/` as a **VS Code window**
2. Have `.github/copilot-instructions.md` visible in a tab
3. The bug is introduced by `./make-change.sh` — confirm with:
   ```sh
   grep "Objects.equals" cloud-sdk-java/cloudplatform/connectivity-destination-service/src/main/java/com/sap/cloud/sdk/cloudplatform/connectivity/DestinationRetrievalStrategyResolver.java
   ```
   Should show `return !Objects.equals(...)` (the bug)
4. Pre-stage Copilot prompt:
   ```
   The tests are failing. Read the failure output and fix the bug.
   After the fix, run the tests using the project's test instructions.
   ```
5. Know the manual fallback: `./fix-change.sh`

---

## If things go wrong

- **Select says "no index"**: Run `./prepare.sh` again (learn pass needed)
- **Tests take too long**: Make sure `JAVA_HOME` is JDK 21
- **Plugin not found**: `cd ../../ && mvn install -DskipTests -pl test-order-maven-plugin -am`
- **Copilot doesn't run tests**: Narrate + run manually
- **Nuclear reset**: `./reset-demo.sh && ./make-change.sh && ./toggle-test-order.sh on`

---

## Quick Reset (between practice runs)

```sh
./reset-demo.sh           # restores .test-order from .baked-history/, plugin OFF, source clean
./toggle-test-order.sh on # re-enable plugin
./make-change.sh          # re-introduce the bug
```

---

## Slides

Served by Slidev at `localhost:3030`. Advance with arrow keys.
6 slides — the terminal and VS Code do most of the talking.
