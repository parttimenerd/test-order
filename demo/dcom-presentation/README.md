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

| Time  | What                                  | Where    |
|-------|---------------------------------------|----------|
| 0:00  | Slide 1 — Title                       | Slides   |
| 0:20  | The Pain — mvn clean test (killed)    | VS Code  |
| 2:20  | toggle on + make-change + select      | Terminal |
| 3:00  | Slide 2 — Results (5:00→17s)         | Slides   |
| 3:20  | Slide 3 — How It Works               | Slides   |
| 3:50  | Dashboard live (browser)              | Browser  |
| 4:05  | copilot-instructions.md + Copilot     | VS Code  |
| 5:25  | Slide 4 — AI Feedback Loop            | Slides   |
| 5:50  | Slide 5 — Kicker (with pause)         | Slides   |
| 6:10  | Slide 6 — Close                       | Slides   |

---

## Actual Measured Timings (on this machine)

| Command | What happens | Wall time |
|---------|-------------|-----------|
| `mvn clean test` | Full clean build + all 469 tests | **5:00+** |
| `mvn test-order:select test` | 7 affected test classes (86 tests) — **with bug** | **17s (red)** |
| same after fix | same tests | **17s (green)** |

---

## Live Commands (what you actually type)

### The Pain (0:20–2:20)

Switch to **VS Code** (already open on cloud-sdk-java). Terminal is visible and fullscreen.

> "SAP Cloud SDK for Java. The real thing. 65 modules."
> "I changed one file in DestinationService. Let's see what that costs."

```sh
cd cloud-sdk-java
mvn clean test
```

Talk over it while tests compile and modules scroll:

> "Clean build. 65 modules. Every test in the repo."
> "This is what CI does on every push. Every PR. Every time."
> *(~60s in)* "We're still compiling. Haven't run a single test yet."
> *(~90s in)* "Some tests starting now — but we don't know if the change I made is breaking anything."

**Kill at ~2:00** (Ctrl+C):

> "Over two minutes. Three more to go. Zero signal. I don't know if my change broke anything."
> "This is the thing nobody talks about — it's not just slow. You're flying blind the whole time."

### The Magic Moment (2:20–3:00)

```sh
cd /Users/i560383_1/code/experiments/test-order/demo/dcom-presentation
./toggle-test-order.sh on    # prints the pom.xml diff — one plugin added
./make-change.sh             # inverts the tenant-routing check, commits it
cd cloud-sdk-java
mvn test-order:select test
```

**While select is running** (~17s), narrate:

> "One plugin. No annotation changes. No test rewrites."
> "It learned the dependency graph. It knows which tests exercise the code I just touched."
> "Watch — it's not running all 469 tests. It selected 7."

Expected output:
```
[test-order] Selected 7 tests, deferred 10
Running ... DestinationRetrievalStrategyResolverTest
...
Tests run: 86, Failures: 3, Errors: 0
BUILD FAILURE
Total time: 17s
```

> "Seven test classes. Seventeen seconds. Build failure."

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

### Agentic Demo (4:05–5:25)

Still in **VS Code**. Show `.github/copilot-instructions.md` tab — read it aloud, close it.

> "After every code change, run: mvn test-order:select test. That's it."
> "The agent gets a result in 17 seconds. Not 5 minutes."
> "As a Gradle DevRel once put it: 2× slower feedback → 4× slower developer.
>  An agent waiting 5 minutes makes 18× fewer fix attempts per hour."

The bug is already in the code from `./make-change.sh`. Type the prompt in Copilot chat:

```
The tests are failing. Read the failure output and fix the bug.
After the fix, run the tests using the project's test instructions.
```

**What happens** (let it run, narrate lightly):
1. Copilot runs `mvn test-order:select test` (~17s) — red
2. `DestinationRetrievalStrategyResolverTest` fails — logic inversion in `currentTenantIsProvider()`
3. Copilot reads the stack trace, fixes `!Objects.equals(...)` → `Objects.equals(...)`
4. Copilot re-runs — green (~17s)

> "It read the failure. Fixed it. 17 seconds to catch, 17 seconds to verify."

→ Switch to slides (SlideAgenticLoop).

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
| 0:20 | *(VS Code terminal)* "SAP Cloud SDK for Java. The real thing. 65 modules. I changed one file." |
| 0:30 | "Clean build. 65 modules. Every test in the repo. This is what CI does on every push." |
| 1:00 | "Still compiling. Haven't run a single test yet." |
| 1:30 | "Some tests starting — but I still don't know if my change broke anything." |
| 2:00 | *(kill)* "Two minutes. Three more to go. Zero signal. Flying blind." |
| 2:20 | *(toggle + make-change running)* "One plugin. It learned the dependency graph." |
| 2:35 | *(select running)* "Not 469 tests. It selected 7. Watch." |
| 2:52 | *(BUILD FAILURE)* "Seven test classes. Seventeen seconds. Build failure." |
| 3:00 | *(SlideResults)* "Five minutes. Seventeen seconds. Same change. Same confidence. Twenty times faster." |
| 3:20 | *(SlideHowItWorks)* "One learn pass. 12% overhead. Then every commit: git diff → intersect graph → score → select." |
| 3:45 | "Maven, Gradle, JUnit 5, JUnit 4, TestNG, Kotest. Zero config." |
| 3:50 | *(browser — 15s)* "Tracking every run. APFD — how early bugs surface. Which tests are your best signals." |
| 4:05 | *(VS Code, copilot-instructions.md)* "One file. Three lines. The entire AI integration." |
| 4:15 | "2× slower feedback → 4× slower developer. An agent waiting 5 minutes makes 18× fewer fix attempts per hour." |
| 4:30 | *(Copilot runs — red ~17s)* "There's the bug." |
| 5:05 | *(Copilot fixes, re-runs — green ~17s)* "Fixed. 17 seconds to catch, 17 to verify." |
| 5:25 | *(SlideAgenticLoop)* "Edit → caught → fixed → green. Under 40 seconds. One instructions file." |
| 5:50 | *(SlideKicker)* "Maybe your test suite isn't too large." *(3s pause)* |
| 5:56 | *(click)* "Maybe you're just running the wrong tests first." |

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
