// d-com 2026 — corner cheat sheet
// Tape this to a screen corner. A7 portrait (74 × 105 mm) is a postcard-quarter.
#set page(width: 74mm, height: 105mm, margin: 4mm)
#set text(font: "Helvetica", size: 7pt)
#set par(leading: 0.45em)

#let h(body) = text(weight: "bold", size: 8pt, fill: rgb("#0b3d91"), body)
#let k(body) = raw(body)
#let red(body) = text(fill: rgb("#a4161a"), weight: "bold", body)
#let grn(body) = text(fill: rgb("#1b7f3a"), weight: "bold", body)

#align(center)[
  #text(weight: "bold", size: 9pt)[d-com · Wrong Tests First]
]
#line(length: 100%, stroke: 0.4pt + gray)

#h[Magic command]\
#k("cd cloud-sdk-java/<module>")\
#k("mvn test-order:affected test")

#h[Numbers]\
cold ≈ #red[88s] · warm ≈ #red[55s]\
red: Selected #red[8] (7+1), defer 7\
green: Selected #grn[7] (7), defer 8\
"about half the tests"

#h[Fix the bug (Copilot)]\
prompt: #emph[fix the bug] + paste error\
agent runs affected → #grn[green]

#h[Share with a colleague]\
#k("mvn test-order:diagnose")\
#k("mvn test-order:export-json")

#h[Reset / between runs]\
#k("./reset.sh")\
#k("./add-test-order.sh")\
#k("./make-change.sh")

#h[Ignore on stage (noisy WARNs)]\
- Static-analysis fallback\
- "N tests were NOT selected"\
- Surefire excludes\
- OData generator warnings

#h[Fallbacks]\
nuclear: reset → add → change\
no wifi: #k("cp -R .prepared-test-order .test-order")
