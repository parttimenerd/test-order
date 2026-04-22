import type { ScoringWeights, TestEntry, TestOutcome } from './types'

/** Shorten a FQCN to Package.ClassName */
export function sn(fqcn: string): string {
  if (!fqcn) return ''
  const p = fqcn.split('.')
  return p.length <= 2 ? fqcn : p[p.length - 2] + '.' + p[p.length - 1]
}

/** Format millisecond duration to human-readable */
export function fmtDur(ms: number | null | undefined): string {
  if (ms == null || ms < 0) return '?'
  if (ms < 1000) return ms + 'ms'
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's'
  return Math.floor(ms / 60000) + 'm ' + Math.floor((ms % 60000) / 1000) + 's'
}

/** Format timestamp to locale date + time */
export function fmtTime(ts: number): string {
  if (!ts) return ''
  const d = new Date(ts)
  return d.toLocaleDateString() + ' ' + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

/** Compute greedy set-cover bonuses for tests covering changed classes */
export function computeSetCoverBonuses(
  testsArr: TestEntry[],
  changedSet: Set<string>,
  weight: number,
): Record<string, number> {
  if (!changedSet.size || weight <= 0) return {}

  const coverage: Record<string, Set<string>> = {}
  for (const t of testsArr) {
    const covered = new Set((t.deps || []).filter(d => changedSet.has(d)))
    if (covered.size > 0) coverage[t.name] = covered
  }

  const uncovered = new Set(changedSet)
  const selected = new Set<string>()
  const bonuses: Record<string, number> = {}
  let bonus = weight

  while (uncovered.size > 0) {
    let bestTest: string | null = null
    let bestCount = 0
    for (const testName of Object.keys(coverage)) {
      if (selected.has(testName)) continue
      let count = 0
      for (const c of coverage[testName]) {
        if (uncovered.has(c)) count++
      }
      if (count > bestCount) {
        bestCount = count
        bestTest = testName
      }
    }
    if (!bestTest || bestCount === 0) break
    selected.add(bestTest)
    bonuses[bestTest] = bonus
    for (const c of coverage[bestTest]) uncovered.delete(c)
    bonus = Math.max(Math.trunc(bonus * 0.8), 1)
  }
  return bonuses
}

/** Compute score for a test entry or outcome given weights */
export function computeScore(
  t: TestEntry | TestOutcome,
  w: ScoringWeights,
  bonusMap: Record<string, number> | null,
): number {
  let s = 0
  const name = 'name' in t ? t.name : (t as TestOutcome).testClass

  if (w.coverageBonus > 0 && bonusMap) {
    s += bonusMap[name] || 0
  } else {
    if (t.depOverlap > 0 && t.depTotal > 0 && w.depOverlap > 0)
      s += Math.min(Math.ceil((t.depOverlap / Math.sqrt(t.depTotal)) * w.depOverlap), w.depOverlap)
    if (t.complexityOverlap > 0 && t.depTotal > 0 && w.changeComplexity > 0)
      s += Math.min(Math.ceil((t.complexityOverlap / Math.sqrt(t.depTotal)) * w.changeComplexity), w.changeComplexity)
  }

  if (t.isChanged) s += w.changedTest
  if (t.isNew) s += w.newTest
  if (t.speedRatio < 0) s += Math.round(Math.abs(t.speedRatio) * w.speed)
  else if (t.speedRatio > 0) s -= Math.round(t.speedRatio * w.speedPenalty)
  if (t.hasStaticFieldOverlap && w.staticFieldBonus > 0) s += w.staticFieldBonus
  if (t.failScore > 0) s += Math.min(Math.ceil(t.failScore), w.maxFailure)
  return s
}
