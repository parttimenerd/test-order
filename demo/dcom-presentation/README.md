# SAP DCOM Demo Jam — "You Are Running the Wrong Tests First"

6-minute Demo Jam. Single project: **SAP Cloud SDK for Java** (`cloud-sdk-java`).
Everything interesting happens in a real terminal and VS Code — slides are just the bookends.

---

## Before the talk

```sh
export JAVA_HOME=/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home
./prepare.sh        # builds everything, learn pass, 3 bug-fix history cycles (~15 min first time)
./reset-demo.sh     # ensures clean state — run this last, right before going on stage
```

In separate terminal tabs:
```sh
./start-slides.sh     # starts Slidev at localhost:3030 and opens browser
cd cloud-sdk-java && mvn test-order:serve -pl cloudplatform/connectivity-destination-service
                      # dashboard at localhost:8080
```

Terminal font: **20pt+**. Test on the projector before the session.

---

## Timing

| Time  | What                                        | Where     |
|-------|---------------------------------------------|-----------|
| 0:00  | Hero image — opening line                   | Slides    |
| 0:10  | SDK intro (Cloud SDK for Java)              | VS Code   |
| 0:40  | Pain — `mvn clean test` (kill at ~90s)      | Terminal  |
| 2:10  | Magic — toggle on + make-change + select    | Terminal  |
| 2:50  | Hero image again — reframe                  | Slides    |
| 3:00  | HowItWorks — explanation                    | Slides    |
| 3:35  | Copilot run 1 — red                         | VS Code   |
| 4:07  | Copilot fixes + run 2 — green               | VS Code   |
| 4:34  | `test-order:show` — one pass                | Terminal  |
| 4:54  | Close — kicker + QR code                    | Slides    |

---

## Actual Measured Timings (on this machine)

| Command | What happens | Wall time |
|---------|-------------|-----------|
| `mvn clean test` | Full clean build + all tests | **5:00+** |
| `mvn test-order:select test ` | 7 affected test classes — **with bug** | **~25s (red)** |
| same after fix | same 7 classes | **~25s (green)** |

---

## Live Commands

### Pain (0:40–2:10)

Switch to **VS Code** (already open on `cloud-sdk-java/`). Terminal visible.

```sh
cd cloud-sdk-java
mvn clean test
```

Narrate while it runs:
> "Every developer runs this dozens of times a day. Right now it's compiling 65 modules before a single test executes."
> *(tests start)* "Watch the clock. This is what waiting for feedback feels like."

**Kill at ~90s** (Ctrl+C):
> "I'm not going to make you sit through the whole thing."

### Magic (2:10–2:50)

```sh
cd /path/to/demo/dcom-presentation
./toggle-test-order.sh on    # prints pom.xml diff
./make-change.sh             # inverts the tenant check, leaves it uncommitted
cd cloud-sdk-java
mvn test-order:select test
```

Narrate while select runs (~25s):
> "Same project. Same change. Plugin on."
> "Seven test classes. Not sixty-five modules."

Expected output:
```
[test-order] Selected 7 tests, deferred 8
Tests run: 86, Failures: 0, Errors: 8
BUILD FAILURE
Total time: 25s
```

> "Twenty-five seconds. Build failure. That's the feedback."

### Agentic Demo (3:35–4:34)

Switch to **VS Code**. Show `.github/copilot-instructions.md` tab.

> "Here's the entire AI integration. One file. Three lines:
>  'After every code change, run: mvn test-order:select test'"

Type the Copilot prompt:
```
Implement the changes needed to fix the failing test
```

Narrate during first run (~25s wait):
> "The agent gets a result in 25 seconds. Not 5 minutes."

**Run 1 finishes — red** *(ensure failure output visible on screen before Copilot starts fixing)*:
> "There's the bug."

**Copilot fixes + run 2 — green**:
> "It read the stack trace. Fixed the negation."
> "25 seconds to catch. 25 to verify."

### test-order:show (4:34–4:54)

```sh
mvn test-order:show -pl cloudplatform/connectivity-destination-service
```

> "Full ranked list. StrategyResolverTest — 12 deps on the class I changed. Failure rate 1.0."
> *(scroll to Method Order)* "Methods ranked within each class too. Highest failure signal first."
> "There's also detect-dependencies for order-dependent tests — but that's a full rerun, not today."

---

## Quick Reset (between practice runs)

```sh
./reset-demo.sh              # restores .test-order from .baked-history/, plugin OFF, source clean
```

Then to set up the magic beat:
```sh
./toggle-test-order.sh on
./make-change.sh
```

---

## If Things Go Wrong

| Problem | Fix |
|---------|-----|
| "no index" on select | Run `./prepare.sh` again (learn pass needed) |
| Tests take too long | Check `JAVA_HOME` is JDK 21 |
| Plugin not found | `cd ../../ && mvn install -DskipTests -pl test-order-maven-plugin -am` |
| Copilot doesn't run tests | Narrate + run `mvn test-order:select test` manually |
| Bug already fixed by Copilot before demo | `./fix-change.sh && ./make-change.sh` |
| Nuclear reset | `./reset-demo.sh && ./toggle-test-order.sh on && ./make-change.sh` |

---

## Slides

Served by Slidev at `localhost:3030`. Advance with arrow keys or clicker.
4 slides — the terminal and VS Code carry the demo.
