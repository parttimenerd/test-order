# SAP DCOM Demo Jam — "You Are Running the Wrong Tests First"

6-minute Demo Jam. 5 slides. Everything else: real commands in a real terminal.

## Before the talk

```sh
export JAVA_HOME=/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home
./prepare.sh          # builds everything, runs learn pass (~10 min first time)
./toggle-test-order-cap.sh on   # enable test-order in cap-sflight
./reset-demo.sh       # ensures clean state
cd slides && npm run dev   # starts slides at localhost:3030
# In separate terminal tab:
cd cloud-sdk-java && mvn test-order:serve -pl cloudplatform/connectivity-destination-service
# Open browser at localhost:8080 (keep tab open, it shows history from prepare.sh)
```

Terminal font: **20pt+**. Test on projector.

---

## Timing

| Time  | What                          | Where    |
|-------|-------------------------------|----------|
| 0:00  | Slide 1 — Title               | Slides   |
| 0:20  | The Pain                      | Terminal |
| 1:50  | Slide 2 — How It Works        | Slides   |
| 2:10  | The Magic Moment              | Terminal |
| 2:40  | Slide 3 — What You Just Saw   | Slides   |
| 2:50  | Dashboard live (browser)      | Browser  |
| 2:55  | Agentic Demo (VS Code)        | VS Code  |
| 4:10  | Slide 4 — AI Feedback Loop    | Slides   |
| 4:30  | Slide 5 — Closing Line        | Slides   |
| 4:45  | Slide 6 — Links               | Slides   |

---

## Actual Measured Timings (on this machine)

| Command | What happens | Wall time |
|---------|-------------|-----------|
| `mvn clean test` | Full clean build + all 469 tests | **5:00+** |
| `mvn test-order:select test -pl cloudplatform/connectivity-destination-service` | 7 affected test classes (86 tests) | **17s** |
| cap-sflight `mvn test-order:select test -pl srv` | Spring Boot startup + 18 tests | 50s |

**Key insight**: The "pain" uses `mvn clean test` from the repo root — full clean rebuild of all 65 modules. Takes 5+ minutes total. Kill after ~90 seconds. The contrast is what you waited vs 17 seconds.

---

## Live Commands (what you actually type)

### The Pain (0:15–2:00)

> "SAP Cloud SDK for Java. The real thing. 65 Maven modules.
>  I fixed a bug in DestinationService — one file."

**Make the change** (already prepped or use backup script):
```sh
cd cloud-sdk-java
./make-change.sh     # (or add comment in IDE)
```

**Run the traditional way** (full clean rebuild + all tests):
```sh
mvn clean test
```

This takes **5+ minutes** total. Talk over it while tests compile and scroll:

> "Clean build. All 65 modules. Every test in the repo. This is what CI does on every push."

**Kill after ~90 seconds** (Ctrl+C):

> "Over a minute gone. This would run for another four. I haven't seen a single test result."

### The Magic Moment (2:15–4:00)

```sh
cd /Users/i560383_1/code/experiments/test-order/demo/dcom-presentation
./toggle-test-order.sh on
```

The script prints the pom.xml diff — one plugin added, configured with `topN=5`.

> "One plugin. No annotation changes. No test rewrites.
>  It learned the dependency graph during the previous test run."

Now run select mode (no `clean`, no full rebuild — just the affected module):
```sh
cd cloud-sdk-java
mvn test-order:select test -pl cloudplatform/connectivity-destination-service
```

Expected output (~17 seconds total):
```
[test-order] Selected 7 tests, deferred 10
Running ... TransparentProxyTest
Running ... AuthTokenHeaderProviderTest
Running ... DestinationServiceAuthenticationTest
...
Tests run: 86, Failures: 0, Errors: 0
BUILD SUCCESS
Total time: 17s
```

> "7 test classes. 86 tests. 17 seconds. It knew which tests exercise DestinationService."
>
> "No clean rebuild. No guessing which module. From 3 minutes to 17 seconds."

### Dashboard Demo (2:45–2:55)

After BUILD SUCCESS at 17s — while still in terminal:

```sh
# In separate tab (already running from pre-demo setup):
# mvn test-order:serve -pl cloudplatform/connectivity-destination-service
# → localhost:8080
```

Switch to browser tab (pre-opened). Point at:

1. **Tests tab** — ranked order, which tests ran in this 17-second pass
2. **Analytics** — 6 runs from the bug-fix cycles, pass/fail alternation visible
3. **Coverage** — which source classes each test covers

> "It didn't just run the right tests — it's been tracking every run.
>  You can see which tests are most valuable. You know what your test suite is actually doing."

Keep it under 10 seconds. Switch back to Slide 3 immediately after.

---

### Agentic Demo (4:00–4:45)

Switch to **VS Code** with cap-sflight open. Show `.github/copilot-instructions.md` tab first.

> "One file. It tells the agent: after every change, run test-order select.
>  The agent gets a result in 17 seconds — not 3 minutes."

The bug is already in the code — `>= 50` instead of `> 50` in `DeductDiscountHandler.java`.
This was planted by `prepare.sh`. Copilot's job: read the failure and fix it.

**Live prompt to Copilot** (type in chat):
```
The tests are failing. Read the failure output and fix the bug.
After the fix, run the tests using the project's test instructions.
```

**What should happen** (the demo you want):
1. Copilot runs `mvn test-order:select test -pl srv -Denforcer.skip=true` (~17s)
2. `DeductDiscountHandlerTest` fails — boundary case at exactly 50% fails
3. Copilot reads the failure, fixes `>= 50` → `> 50`
4. Copilot re-runs test-order:select — green (~17s)

> "The agent had a bug. test-order caught it in 17 seconds.
>  The agent fixed it. Green in another 17 seconds.
>  Full loop: catch → fix → done. Under 40 seconds."

> "As a Gradle DevRel once said: when your feedback loop takes twice as long,
>  you don't slow down 2x — you slow down 4x. Context switches are expensive."

**If Copilot doesn't auto-run tests** (narrate instead):

> "The instructions file tells Copilot which command to run.
>  Let me run it manually to show the speed."

```sh
cd cap-sflight
mvn test-order:select test -pl srv -Denforcer.skip=true
```

Point at the failure output, then let Copilot fix it, then re-run.

**Full manual fallback** (if Copilot doesn't cooperate at all):
```sh
cd cap-sflight
# Bug is already planted — just show the failure
mvn test-order:select test -pl srv -Denforcer.skip=true   # red, ~17s
# Fix it
sed -i '' 's/discount >= 50/discount > 50/' srv/src/main/java/com/sap/cap/sflight/processor/DeductDiscountHandler.java
# Verify
mvn test-order:select test -pl srv -Denforcer.skip=true   # green, ~17s
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
| 1:15 | *(kill mvn)* "Over a minute gone. Four more to go. No feedback yet." |
| 2:00 | "What if Maven knew which tests exercise the code you touched?" |
| 2:30 | "One plugin. It learned the dependency graph. Then it selects." |
| 3:15 | *(BUILD SUCCESS ~17s)* "Seven test classes. Seventeen seconds." |
| 3:30 | "No clean rebuild. No guessing. It knows." |
| 4:00 | "Now multiply this by an AI agent iterating 10 times." |
| 4:10 | "One instructions file. Fast feedback every loop." |
| 4:20 | *(Copilot runs tests — red ~17s)* "There's a bug. The off-by-one on the boundary — exactly 50% passes when it should fail." |
| 4:30 | *(Copilot fixes, re-runs ~17s)* "Fixed. Green. Under 40 seconds, start to finish." |
| 4:40 | "As a Gradle DevRel once said: when feedback takes 2x longer, you slow down 4x. Context switches compound." |
| 5:15 | "Maybe your test suite isn't too large." |
| 5:25 | "Maybe you're just running the wrong tests first." |

---

## Setup for Agentic Demo

1. Open `cap-sflight/` as a **separate VS Code window**
2. Run `./toggle-test-order-cap.sh on` (already done in `./prepare.sh`)
3. Have `.github/copilot-instructions.md` visible in a tab
4. The bug is already planted (`>= 50` in `DeductDiscountHandler`) — confirm with:
   ```sh
   grep "discount.*50" cap-sflight/srv/src/main/java/com/sap/cap/sflight/processor/DeductDiscountHandler.java
   ```
5. Pre-stage Copilot prompt (copy it so you can paste quickly):
   ```
   The tests are failing. Read the failure output and fix the bug.
   After the fix, run the tests using the project's test instructions.
   ```
6. Know the manual fallback — the bug is already there, just run tests manually if Copilot won't

---

## If things go wrong

- **Select says "no index"**: Run `./prepare.sh` again (learn pass needed)
- **Tests take too long**: Make sure `JAVA_HOME` is JDK 21
- **Plugin not found**: `cd ../../ && mvn install -DskipTests -pl test-order-maven-plugin -am`
- **Copilot doesn't run tests**: Narrate + run manually: `mvn test-order:select test -pl srv -Denforcer.skip=true`
- **Copilot gets it right first time**: Use manual fallback to introduce the off-by-one bug
- **Nuclear reset**: `./reset-demo.sh && ./toggle-test-order-cap.sh on`

---

## Quick Reset (between practice runs)

```sh
./reset-demo.sh
./toggle-test-order-cap.sh on
# In cap-sflight, restore DeductDiscountHandler if you touched it:
cd cap-sflight && git checkout srv/src/main/java/com/sap/cap/sflight/processor/DeductDiscountHandler.java
```

---

## Slides

Served by Slidev at `localhost:3030`. Advance with arrow keys.
5 slides — the terminal and VS Code do most of the talking.
