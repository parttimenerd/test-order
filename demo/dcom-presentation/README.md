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
| 3:50  | copilot-instructions.md + Copilot     | VS Code  |
| 5:25  | Slide 4 — AI Feedback Loop            | Slides   |
| 5:50  | Slide 5 — Kicker (with pause)         | Slides   |
| 6:10  | Slide 6 — Close                       | Slides   |

Dashboard at localhost:8080 is **optional** — show it only if you're ahead of pace at 3:45.
Skip it by default; the main argument doesn't depend on it.

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

### Dashboard Demo (optional — only if ahead of pace at 3:45)

If you finished HowItWorks before 3:45, switch to browser tab at **localhost:8080** (~15s max):

> "It's been tracking every run — APFD, flakiness, which tests are your best signals."

Switch back to VS Code immediately. If you're at or past 3:45, skip it entirely.

### Agentic Demo (3:50–5:25)

Switch to **VS Code**. Show `.github/copilot-instructions.md` tab.

> "Here's the entire AI integration. One file."

Type the Copilot prompt:

```
The tests are failing. Read the failure output and fix the bug.
After the fix, run the tests using the project's test instructions.
```

**While Copilot starts running** (first ~17s wait), narrate over it:

> "Three lines. After every change: mvn test-order:select test."
> "The agent gets a result in 17 seconds. Not 5 minutes."

**What happens:**
1. Copilot runs `mvn test-order:select test` (~17s) — red
   > "There's the bug."
2. Copilot reads the stack trace, fixes `!Objects.equals(...)` → `Objects.equals(...)`
3. Copilot re-runs (~17s) — green
   > "It read the failure. Fixed it. 17 seconds to catch, 17 to verify."

In terminal (~1s to run, ~20s to narrate):
```sh
mvn test-order:show -pl cloudplatform/connectivity-destination-service
```

> "Full ranked list. Score, dependency count, failure rate, duration."
> "StrategyResolverTest — 12 deps on the class I changed. Failure rate 1.0. That's why it ran."

Scroll down to the **Method Order** section:

> "It also ranks methods within each class. Highest failure signal runs first."
> "And there's a `detect-dependencies` goal that finds order-dependent tests — tests that only
>  fail in a specific sequence. The ones that pass in isolation but break each other in CI."
> "We don't run that live — it reruns the full suite. But it's there."
> "Everything else deferred — not skipped. Still runs in the nightly pass."

→ Switch to slides (SlideAgenticLoop).

### SlideAgenticLoop (4:59–5:19)

The slide anchors what the audience just watched with two numbers — 34s vs 10min — and the point: the bottleneck is the wait, not the intelligence.

> "34 seconds. Edit, caught, fixed, green."
> "The bottleneck isn't intelligence. It's the wait."

### Close (5:50)

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
| 3:20 | *(SlideHowItWorks)* "StrategyResolverTest was #1 because it's the only test that touches the class I changed. The graph knew." |
| 3:30 | "One learn pass. 12% overhead. git diff → intersect → score → select. Maven, Gradle, JUnit 5, zero config." |
| 3:50 | *(VS Code, copilot-instructions.md visible)* "Here's the entire AI integration. One file." |
| 3:55 | *(type Copilot prompt, first run starts)* "Three lines. After every change: mvn test-order:select test. 17 seconds, not 5 minutes." |
| 4:12 | *(Copilot run — red)* "There's the bug." |
| 4:35 | *(Copilot fixes, second run starts)* "It read the stack trace. Fixed the negation." |
| 4:52 | *(green)* "17 seconds to catch. 17 to verify." |
| 4:59 | *(terminal: mvn test-order:show)* "Full ranked list. StrategyResolverTest — 12 deps, failure rate 1.0. Methods ranked within each class too." |
| 5:00 | *(SlideAgenticLoop)* "An agent at 5 minutes makes 18× fewer fix attempts per hour. One file. That's the integration." |
| 5:25 | *(SlideKicker)* "Maybe your test suite isn't too large." *(3s pause)* |
| 5:31 | *(click)* "Maybe you're just running the wrong tests first." *(hold 5s)* |
| 5:36 | *(SlideClose)* "Star it. Drop in the plugin. Tell me how much time you saved." |

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
