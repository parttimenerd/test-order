# SAP d-com Mannheim Demo Jam — "You Are Running the Wrong Tests First"

6-minute Demo Jam. Single project: **SAP Cloud SDK for Java** (`cloud-sdk-java`).
Everything interesting happens in a real terminal and VS Code — slides are just the bookends.

---

## Before the talk

```sh
export JAVA_HOME=/Users/i560383_1/Library/Java/JavaVirtualMachines/sapmachine-21/Contents/Home
./prepare.sh    # builds everything, learn pass, 3 bug-fix history cycles (~15 min first time)
./reset.sh      # ensures clean state — run this last, right before going on stage
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

| Time  | What                                          | Where     |
|-------|-----------------------------------------------|-----------|
| 0:00  | Hero image — opening line                     | Slides    |
| 0:10  | "normally we run all tests" — BeforePlugin    | Slides    |
| 0:30  | DepGraph — learn pass explanation             | Slides    |
| 1:00  | `./add-test-order.sh` — plugin + learn data   | Terminal  |
| 1:20  | `mvn test-order:affected test` — magic beat     | VS Code   |
| 2:00  | Copilot "fix the bug" — agentic demo          | VS Code   |
| 3:00  | LocalWorkflow slide                           | Slides    |
| 3:20  | CIWorkflow slide                              | Slides    |
| 3:40  | Close — kicker + QR code                     | Slides    |

---

## Actual Measured Timings (on this machine)

| Command | What happens | Wall time |
|---------|-------------|-----------|
| `mvn clean test` | Full clean build + all tests | **5:00+** |
| `mvn test-order:affected test` | 7 affected test classes — **with bug** | **~25s (red)** |
| same after fix | same 7 classes | **~25s (green)** |

---

## Live Commands

### Pain (shown before slides — audience watches full suite crawl)

Switch to **VS Code** (already open on `cloud-sdk-java/`). Terminal visible.

```sh
cd cloud-sdk-java
mvn clean test
```

> "Every developer runs this dozens of times a day. Right now it's compiling 65 modules before a single test executes."
> *(tests start)* "Watch the clock. This is what waiting for feedback feels like."

**Kill at ~90s** (Ctrl+C).

### Magic beat — on stage after SlideDepGraph

```sh
cd /path/to/demo/dcom-presentation
./add-test-order.sh      # adds plugin to pom.xml, copies in learn index, shows diff
./make-change.sh         # inverts the tenant check, leaves it uncommitted
cd cloud-sdk-java
mvn test-order:affected test
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

### Agentic Demo

Switch to **VS Code**. Show `.github/copilot-instructions.md` tab.

> "Here's the entire AI integration. One file. Three lines:
>  'After every code change, run: mvn test-order:affected test'"

Type the Copilot prompt:
```
fix the bug
```
(paste the error message)

> "The agent gets a result in 25 seconds. Not 5 minutes."

**Run 1 — red** *(ensure failure visible before Copilot starts fixing)*:
> "There's the bug."

**Copilot fixes + run 2 — green**:
> "It read the stack trace. Fixed the negation."
> "25 seconds to catch. 25 to verify."

---

## Quick Reset (between practice runs)

```sh
./reset.sh          # plugin OFF, source clean, .test-order restored from .baked-history/
./make-change.sh    # re-introduce bug for the magic beat
```

---

## If Things Go Wrong

| Problem | Fix |
|---------|-----|
| "no index" on select | Run `./prepare.sh` again (learn pass needed) |
| Tests take too long | Check `JAVA_HOME` is JDK 21 |
| Plugin not found | `cd ../../ && mvn install -DskipTests -pl test-order-maven-plugin -am` |
| Copilot doesn't run tests | Narrate + run `mvn test-order:affected test` manually |
| Bug already fixed before demo | `./reset.sh && ./make-change.sh` |
| Nuclear reset | `./reset.sh && ./add-test-order.sh && ./make-change.sh` |

---

## Slides

Served by Slidev at `localhost:3030`. Advance with arrow keys or clicker.
6 slides — the terminal and VS Code carry the demo.
