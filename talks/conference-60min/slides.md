---
theme: seriph
title: "You Are Running the Wrong Tests First"
info: |
  test-order — 60-minute conference talk.
  Local-first, zero-config test prioritization for Maven and Gradle.
class: text-center
highlighter: shiki
colorSchema: 'dark'
lineNumbers: false
drawings:
  persist: false
transition: slide-left
mdc: true
fonts:
  sans: 'Merriweather'
  serif: 'DM Serif Display'
  mono: 'JetBrains Mono'
layout: cover
---

<style>
.big-statement {
  font-size: 3rem;
  font-weight: 700;
  line-height: 1.3;
  text-align: center;
}
.quote-slide {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  text-align: center;
  padding: 6rem 4rem;
  position: relative;
}
.quote-slide::before {
  content: '\201C';
  position: absolute;
  left: 40px;
  top: 40%;
  transform: translateY(-50%);
  font-size: 15rem;
  font-weight: 900;
  color: rgba(148, 113, 217, 0.18);
  line-height: 1;
  z-index: 0;
}
.quote-text {
  font-size: 1.85rem;
  line-height: 1.7;
  font-style: italic;
  max-width: 820px;
  font-weight: 500;
  position: relative;
  z-index: 1;
}
.quote-attr {
  font-size: 1.05rem;
  opacity: 0.7;
  margin-top: 1.5rem;
  font-weight: 500;
}
.slidev-slide pre,
.slidev-slide .shiki {
  font-size: 1.05rem;
  line-height: 1.6;
  border-radius: 10px;
  padding: 18px 22px;
}
.pull-quote {
  border-left: 4px solid #f97316;
  padding: 0.9rem 1.4rem;
  margin: 1.2rem 0;
  font-size: 1.35rem;
  font-weight: 600;
  line-height: 1.45;
  color: #f1f5f9;
  background: rgba(249, 115, 22, 0.07);
  border-radius: 0 8px 8px 0;
}
.hands-up {
  display: inline-flex;
  align-items: center;
  gap: 0.6rem;
  background: rgba(250, 204, 21, 0.12);
  border: 1px solid rgba(250, 204, 21, 0.4);
  border-radius: 8px;
  padding: 0.6rem 1.2rem;
  font-size: 1.1rem;
  font-weight: 600;
  color: #fef08a;
  margin-top: 1.2rem;
}
.tag-bad {
  display: inline-block;
  background: rgba(239,68,68,0.15);
  border: 1px solid rgba(239,68,68,0.4);
  color: #fca5a5;
  border-radius: 6px;
  padding: 0.15rem 0.6rem;
  font-size: 0.85rem;
  font-weight: 700;
  font-family: 'JetBrains Mono', monospace;
  margin-right: 0.4rem;
  vertical-align: middle;
}
.tag-ok {
  display: inline-block;
  background: rgba(148,163,184,0.12);
  border: 1px solid rgba(148,163,184,0.35);
  color: #94a3b8;
  border-radius: 6px;
  padding: 0.15rem 0.6rem;
  font-size: 0.85rem;
  font-weight: 700;
  font-family: 'JetBrains Mono', monospace;
  margin-right: 0.4rem;
  vertical-align: middle;
}
</style>


<!-- ═══ SLIDE 1: COVER ════════════════════════════════════════════════════════ -->

<div class="relative z-10">

# You Are Running the Wrong Tests First

<div class="pt-6 text-base opacity-50">
  Johannes Bechberger · @parttimenerd · SAP SE
</div>

</div>

<!--
OPENING LINE (spoken): "Regression testing is an important but costly software engineering task." — Legunsen et al., FSE 2016. Pause. "And yet here we are, still running tests in alphabetical order."
-->

---
layout: image
image: /images/baselone26-sponsors.png
title: "Sponsors"
---

<!-- ═══ SLIDE 2: FEEDBACK LOOP ════════════════════════════════════════════════ -->

---
layout: default
title: "The Feedback Loop"
class: slide-base
---

## The Feedback Loop

<div class="mt-6 text-xl leading-relaxed">

**Edit** → commit → push → wait → context-switch → finally red → context-switch back → fix → repeat.

</div>

<div v-click class="pull-quote mt-8">
  The bottleneck isn't how fast your tests run.<br/>
  It's <strong>which one runs first.</strong>
</div>

<!--
Don't rush this slide. The audience recognises this loop — it's their daily life.
Let "which one runs first" land before advancing.
-->

<!-- ═══ SLIDE 3: D0 LIVE DEMO ════════════════════════════════════════════════ -->

---
layout: default
title: "D0 — CI Baseline Demo"
class: slide-base
---

<DemoCard id="D0" title="What we do today">

```bash
cd third-party/commons-collections
# patch already applied — one missing line in remove()
mvn test -Dtestorder.mode=skip
```

</DemoCard>

<!--
DEMO D0 — commons-collections with enumerationiterator patch pre-applied. mode=skip forces alphabetical.

SETUP (run prepare-demo.sh before going on stage):
- patch already applied via make-change-commons.sh
- .test-order/ absent — mode=skip would ignore it anyway

STEPS:
1. cd third-party/commons-collections
2. mvn test -Dtestorder.mode=skip  — DO NOT kill it early, let it reach pos 237

NARRATE as it runs:
- "This is Apache Commons Collections. Real open-source project. 321 tests."
- "I removed one line from EnumerationIterator.remove() — a `last = null` reset."
- "Alphabetical order. A… B… C… E… we're at test 80 out of 321… still going…"
- Keep narrating as it crawls past 150, 200, 237
- When EnumerationIteratorTest fails: "There it is. Position 237. We ran 236 tests first."
- "Every one of those was wasted time."

Let it sink in for 2 seconds. Then advance.

FALLBACK: asciinema play public/demo-d0.cast
-->

<!-- ═══ SLIDE 4: SHOW OF HANDS ════════════════════════════════════════════════ -->

---
layout: center
title: "Show of Hands"
---

<div class="big-statement">
  Who's been context-switched out of a problem<br/>because CI took too long?
</div>

<div class="hands-up mt-10">✋ Keep your hand up if it happened this week.</div>

<!--
First audience interaction. Feed the response: "Right. That's what we're fixing today."
-->

<!-- ═══ SLIDE 5: APFD TIMELINE ════════════════════════════════════════════════ -->

---
layout: default
title: "APFD Timeline"
class: slide-base
---

## The Cost of Wrong Order

<ApfdTimeline />

<!--
The chart shows first failure landing at ~80% of total runtime in alphabetical suites.
"You already felt this. Position 237 out of 321 — that's 74% of the suite run before the failure."
-->

<!-- ═══ SLIDE 6: GOOGLE STAT ════════════════════════════════════════════════ -->

---
layout: center
title: "91% Stat"
class: quote-slide slide-base
---

<div class="absolute inset-0 bg-zinc-900/52 z-0" />

<div class="quote-text relative z-10">
  91.3% of tests at Google passed and never failed.<br/><em>Not once.</em>
</div>
<div class="quote-attr relative z-10">Memon et al., Taming Google-Scale Continuous Testing, ICSE-SEIP 2017 · 5.5M targets, 500K+ code changes</div>

<!--
Pause 3 seconds. Let it land.
TRANSITION: "Which leads to a hard logical claim."
-->
