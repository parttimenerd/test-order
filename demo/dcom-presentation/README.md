# SAP DCOM Demo Jam — "You Are Running the Wrong Tests First"

6-minute Demo Jam. 4 slides. Everything else: real commands in a real terminal.

## Before the talk

```sh
export JAVA_HOME=/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home
./prepare.sh          # builds everything, runs learn pass (~10 min first time)
./reset-demo.sh       # ensures clean state
cd slides && npm run dev   # starts slides at localhost:3030
```

Terminal font: **20pt+**. Test on projector.

---

## Timing

| Time  | What                        | Where    |
|-------|-----------------------------|----------|
| 0:00  | Slide 1 — Title             | Slides   |
| 0:15  | The Pain                    | Terminal |
| 2:00  | Slide 2 — How It Works      | Slides   |
| 2:15  | The Magic Moment            | Terminal |
| 3:30  | Slide 3 — What You Just Saw | Slides   |
| 3:45  | Slide 4 — Agentic Multiplier| Slides   |
| 4:00  | Agentic Demo                | VS Code  |
| 5:15  | Slide 5 — Closing Line      | Slides   |
| 5:30  | Slide 6 — Links             | Slides   |

---

## Actual Measured Timings (on this machine)

| Command | What happens | Wall time |
|---------|-------------|-----------|
| `mvn clean test -pl ... -am` | Recompile all upstream + run 469 tests | **3:03** |
| `mvn test -pl ...` | Just tests (warm cache) | 20s |
| `mvn test-order:select test -pl ...` (topN=5) | 7 affected test classes (86 tests) | **17s** |
| cap-sflight `mvn test-order:select test -pl srv` | Spring Boot startup + 18 tests | 50s |

**Key insight**: The "pain" must use `clean -am` to be dramatic (3 min vs 17s).
If you skip `clean -am`, it's only 20s — not enough contrast.

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

**Run the traditional way** (full clean rebuild + tests):
```sh
mvn clean test -pl cloudplatform/connectivity-destination-service -am
```

This takes **3 minutes**. Talk over it while tests compile and scroll:

> "Clean build. 469 tests. Because I touched one file.
>  On CI this is every single push. Every pull request. Every iteration."

**Kill after ~60–90 seconds** (Ctrl+C):

> "I've been waiting over a minute. It needs two more.
>  I haven't even gotten feedback on my change yet."

### The Magic Moment (2:15–4:00)

```sh
cd /Users/i560383_1/code/experiments/test-order/demo/dcom-presentation
./toggle-test-order.sh on
```

The script prints the pom.xml diff — one plugin added, configured with `topN=5`.

> "One plugin. No annotation changes. No test rewrites.
>  It learned the dependency graph during the previous test run."

Now run select mode (no `clean`, no `-am` — just the module):
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

### Agentic Demo (4:15–5:30)

Switch to **VS Code** with cap-sflight open.

> "But where this really changes the game: AI coding agents.
>  An agent that generates code and waits 3 minutes for feedback?
>  That's expensive tokens doing nothing."

**Show `.github/copilot-instructions.md`** (tab already open):

> "One file tells the agent: after changes, run test-order select.
>  The agent gets fast feedback every iteration."

**Live prompt to Copilot** (type in chat):
```
Add max discount validation to DeductDiscountHandler — reject discounts above 50%
```

Watch Copilot modify code and run tests. If it works — great. If slow, narrate:

> "The agent makes its change, runs only affected tests, gets green in seconds.
>  Not because the test suite is small — it's 469 tests.
>  Because the agent knows which tests matter for THIS change."

**Fallback** (if Copilot doesn't cooperate, type manually):
```sh
cd cap-sflight
sed -i '' '1s/^/\/\/ added max discount validation\n/' srv/src/main/java/com/sap/cap/sflight/processor/DeductDiscountHandler.java
mvn test-order:select test -pl srv -Denforcer.skip=true
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
| 1:15 | *(kill mvn)* "Over a minute gone. Two more to go. No feedback yet." |
| 2:00 | "What if Maven knew which tests exercise the code you touched?" |
| 2:30 | "One plugin. It learned the dependency graph. Then it selects." |
| 3:15 | *(BUILD SUCCESS ~17s)* "Seven test classes. Seventeen seconds." |
| 3:30 | "No clean rebuild. No guessing. It knows." |
| 4:00 | "Now multiply this by an AI agent iterating 10 times." |
| 4:15 | "One instructions file. Fast feedback every loop." |
| 5:30 | "Maybe your test suite isn't too large." |
| 5:40 | "Maybe you're just running the wrong tests first." |

---

## Setup for Agentic Demo

1. Open `cap-sflight/` as a **separate VS Code window**
2. Ensure test-order plugin is enabled: `./toggle-test-order-cap.sh on`
3. Have `.github/copilot-instructions.md` visible in a tab
4. Pre-stage Copilot prompt: "Add max discount validation to DeductDiscountHandler — reject discounts above 50%"

---

## If things go wrong

- **Select says "no index"**: Run `./prepare.sh` again (learn pass needed)
- **Tests take too long**: Make sure `JAVA_HOME` is JDK 21
- **Plugin not found**: `cd ../../ && mvn install -DskipTests -pl test-order-maven-plugin -am`
- **Copilot doesn't run tests**: Use fallback command from README
- **Nuclear reset**: `./reset-demo.sh`

---

## Quick Reset (between practice runs)

```sh
./reset-demo.sh
```

---

## Slides

Served by Slidev at `localhost:3030`. Advance with arrow keys.
Only 4 slides — the terminal does the talking.
