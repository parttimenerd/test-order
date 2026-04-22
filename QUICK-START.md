# Test-Order Fail-Fast Demo - Quick Start

## Play the Recording

```bash
cd /Users/i560383_1/code/experiments/test-order
asciinema play test-order-fail-fast.cast
```

## What You'll See (2:50)

1. **Dependency Learning** - test-order learns which tests depend on which code
2. **Baseline Order** - Tests ranked by speed (StringUtilsTest: Score 1)
3. **Code Change** - Breaking modification to StringUtils.capitalize()
4. **Intelligent Reordering** - StringUtilsTest jumps to Score 8 (highest priority!)
5. **Fail-Fast** - StringUtilsTest fails IMMEDIATELY, not buried in results

## The Story

> You change StringUtils. test-order detects this and reorders tests so StringUtilsTest runs first. It fails immediately. Without test-order, you'd run dozens of unrelated tests before discovering the failure.

## Key Insight

✨ **Failures surface in seconds instead of minutes**

## Documentation

- **TEST-ORDER-FAIL-FAST-DEMO.md** — Full guide with playback instructions
- **plan.md** — Implementation strategy
- **narrative-breakdown.md** — Detailed screenplay

## Speed Control

```bash
# Slower (better for analysis)
asciinema play test-order-fail-fast.cast --speed=0.7

# Faster (tighter presentation)
asciinema play test-order-fail-fast.cast --speed=1.5
```

---

For full details, see TEST-ORDER-FAIL-FAST-DEMO.md
