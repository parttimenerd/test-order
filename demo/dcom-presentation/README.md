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
./start-slides.sh     # starts Slidev (localhost:3030) AND the cloud-sdk-java
                      # dashboard (localhost:8080), opens both in browser tabs
```

Terminal font: **20pt+**. Test on the projector before the session.

---

## Timing

| Time  | What                                          | Where     |
|-------|-----------------------------------------------|-----------|
| 0:00  | Hero image — opening line                     | Slides    |
| 0:10  | `mvn clean test` pain demo                    | Terminal  |
| 0:40  | `./add-test-order.sh` — plugin + learn data   | Terminal  |
| 1:00  | `mvn test-order:affected test` — magic beat   | VS Code   |
| 2:30  | Copilot "fix the bug" — agentic demo          | VS Code   |
| 4:30  | `mvn test-order:diagnose` — share-with-colleague beat | Terminal |
| 5:20  | Close — kicker + QR code                      | Slides    |

---

## Actual Measured Timings (on this machine)

| Command | What happens | Wall time |
|---------|-------------|-----------|
| `mvn clean test` | Full clean build + all tests | **5:00+** |
| `mvn test-order:affected test` | first run after reset (cold) | **~88s (red)** |
| `mvn test-order:affected test` | re-run, daemon warm | **~55s (red)** |
| same after fix | 7 selected, 0 errors | **~55s (green)** |

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

### Magic beat — on stage after the pain demo

```sh
cd /path/to/demo/dcom-presentation
./add-test-order.sh      # adds plugin to pom.xml, copies in learn index, shows diff
./make-change.sh         # inverts the tenant check, leaves it uncommitted
cd cloud-sdk-java
mvn test-order:affected test
```

Narrate while select runs (~55s warm, ~88s cold first time):
> "Same project. Same change. Plugin on."
> "About half the test classes. Not sixty-five modules."

Expected output (red):
```
[test-order] Selection Summary:
[test-order] Selected 8 tests (7 scored + 1 fast-diverse), deferred 7
Tests run: 311, Failures: 0, Errors: 8
BUILD FAILURE
Total time: ~55s (warm) / ~88s (cold)
```

> "About a minute. Build failure. That's the feedback."

### Agentic Demo

Switch to **VS Code**. Show `.github/copilot-instructions.md` tab.

> "Here's the entire AI integration. One file. Three lines:
>  'After every code change, run: mvn test-order:affected test'"

Type the Copilot prompt:
```
fix the bug
```
(paste the error message)

> "The agent gets a result in about a minute. Not 5 minutes."

**Run 1 — red** *(ensure failure visible before Copilot starts fixing)*:
> "There's the bug."

**Copilot fixes + run 2 — green**:
> "It read the stack trace. Fixed the negation."
> "A minute to catch. A minute to verify."

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
Two slides — Hero (open) + Close (QR). The terminal and VS Code carry everything in between.
