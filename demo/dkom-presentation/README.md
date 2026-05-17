# SAP d-kom Demo: Smarter CI — From Legacy Maintenance to Vibe Coding

**Target Time:** 6 Minutes  
**Demo Format:** Two self-contained projects with artificial test delays for impressive metrics

## Quick Start

```bash
# 1. Set up everything (build + learn + verify + save replay files)
cd demo/dkom-presentation && ./setup-all.sh

# 2. Run the full talk (both demos back-to-back)
./run-talk.sh

# Or run demos individually:
./run-legacy-demo.sh    # Demo 1: Legacy CVE fix
./run-vibe-demo.sh      # Demo 2: AI-generated feature with bug

# 3. If live demo hangs: use replay mode
./run-talk.sh --replay
./run-legacy-demo.sh --replay
./run-vibe-demo.sh --replay

# 4. Start slides
cd slides && npx slidev slides.md --port 3033
```

## Verified Demo Output

### Legacy Demo (olingo-style, 7 test classes)

```
[INFO] [test-order] Order mode: injecting PriorityClassOrderer
[INFO] [test-order] Detected 1 changed source classes: o.a.olingo...UriParserImpl
[INFO] [test-order]   → boosting 1 tests that depend on them
[ERROR] Tests run: 6, Errors: 1 <<< FAILURE! -- in ...UriParserImplTest
[INFO] [test-order] Run APFD: 92.9% (first failure at position 1/7)
[INFO] [test-order] ⏱️  Estimated time saved: 21s (based on default execution order)
```

### Vibe Demo (CAP sflight-style, 7 test classes)

```
[INFO] [test-order] Order mode: injecting PriorityClassOrderer
[INFO] [test-order] Detected 1 changed source classes: c.s.c.sflight...BookingService
[INFO] [test-order]   → boosting 2 tests that depend on them
[ERROR] Tests run: 3, Failures: 1 <<< FAILURE! -- in ...VipDiscountTest
[INFO] [test-order] Run APFD: 92.9% (first failure at position 1/7)
[INFO] [test-order] ⏱️  Estimated time saved: 24s (based on default execution order)
```

## Project Structure

```
demo/dkom-presentation/
├── README.md               ← this file
├── setup-all.sh            ← one-click setup (build + learn + verify + save replay)
├── run-talk.sh             ← full talk flow (Demo 1 → Demo 2)
├── run-legacy-demo.sh      ← interactive legacy demo
├── run-vibe-demo.sh        ← interactive vibe coding demo
├── record-demos.sh         ← asciinema recording wrapper
├── .replay/                ← saved output for --replay fallback (auto-generated)
├── slides/
│   └── slides.md           ← Slidev presentation (7 slides)
├── legacy-demo/            ← self-contained olingo-style project
│   ├── pom.xml
│   └── src/
│       ├── main/java/org/apache/olingo/odata2/core/
│       │   ├── annotation/AnnotationProcessor.java
│       │   ├── batch/BatchProcessor.java
│       │   ├── commons/ContentType.java
│       │   ├── edm/EdmProvider.java
│       │   ├── ep/EntityProvider.java
│       │   ├── jpa/JpaProcessor.java
│       │   └── uri/UriParserImpl.java  ← the "CVE patch" target
│       └── test/java/...
└── vibe-demo/              ← self-contained CAP sflight-style project
    ├── pom.xml
    └── src/
        ├── main/java/com/sap/cap/sflight/
        │   ├── booking/BookingService.java  ← AI modifies this
        │   ├── flight/FlightService.java
        │   ├── notification/NotificationService.java
        │   ├── passenger/PassengerService.java
        │   ├── payment/PaymentService.java
        │   └── analytics/AnalyticsService.java
        └── test/java/...
            └── vip/VipDiscountTest.java     ← AI-generated, FAILS
```

## How the Bug is Introduced

### Legacy: CVE null-check removal
```java
// BEFORE (safe):
public FilterExpression parseFilter(String filterSegment) {
    if (filterSegment == null) { return null; }  // ← removed by "patch"
    ...
// AFTER (bug): NPE on null input
```

### Vibe: AI subtracts instead of multiplies
```java
// AI wrote:    price.subtract(BigDecimal.valueOf(20))  // flat $20 off
// Spec said:   price.multiply(BigDecimal.valueOf(0.80)) // 20% off
```

## Storyboard (7 slides + 2 live demos)

| Time | Segment | What |
|------|---------|------|
| 0:00–0:30 | **Slide 1: Title** | "Smarter CI: From Legacy to Vibe Coding" |
| 0:30–1:00 | **Slide 2: The Problem** | 🐢 21s waiting · alphabetical = wrong order |
| 1:00–1:15 | **Slide 3: Demo 1 Setup** | Project context card, switch to terminal |
| 1:15–2:15 | **Demo 1: Legacy** | `run-legacy-demo.sh` — 2 Enter presses |
| 2:15–3:15 | **Slide 4: How?** | Mermaid pipeline + scoring formula |
| 3:15–3:30 | **Slide 5: Demo 2 Setup** | AI bug context, switch to terminal |
| 3:30–4:30 | **Demo 2: Vibe** | `run-vibe-demo.sh` — 2 Enter presses |
| 4:30–5:15 | **Slide 6: Scale** | 24s → 3-4min → 30-40min extrapolation |
| 5:15–6:00 | **Slide 7: Get Started** | XML snippet + GitHub link + CTA |

## Tips for Presenters

- **Use `./run-talk.sh`** for seamless back-to-back flow (one terminal)
- **Fallback:** if live demo hangs, Ctrl+C and re-run with `--replay`
- **Pre-save:** `setup-all.sh` auto-generates replay files
- **Font size**: terminal 18pt+ for auditorium visibility
- **Terminal colors**: dark background required (colors designed for dark themes)
- **The key moment**: when the green summary box appears — pause for effect
- **For bigger numbers**: increase `Thread.sleep()` values in the test files
