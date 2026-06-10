import type { ScoringWeights, TestEntry, TestOutcome } from './types'

/** Graph layout constants */
export const GRAPH = {
  FULL_MODE_NODE_LIMIT: 300,
  LINK_DISTANCE: 70,
  CHARGE_SMALL: -150,
  CHARGE_LARGE: -80,
  CHARGE_THRESHOLD: 80,
  COLLISION_RADIUS: 16,
  BUBBLE_PAD: 22,
  BUBBLE_TICK_INTERVAL: 5,
} as const

/** Scoring distribution constants */
export const DIST = {
  MAX_SCORE_BUCKETS: 20,
  MAX_DEP_BUCKETS: 15,
  TOP_FAIL_COUNT: 20,
  SET_COVER_DECAY: 0.8,
} as const

/** Shorten a FQCN to abbreviated form: com.example.util.MyClass → c.e.u.MyClass */
export function sn(fqcn: string): string {
  if (!fqcn) return '(unknown)'
  const p = fqcn.split('.')
  if (p.length <= 2) return fqcn
  // Keep last segment (class name) intact, abbreviate all package segments
  const cls = p[p.length - 1]
  const pkg = p.slice(0, -1).map(s => s.charAt(0)).join('.')
  return pkg + '.' + cls
}

/** Show p.k.g.ClassName style with last 2 segments kept fully readable */
export function snMed(fqcn: string): string {
  if (!fqcn) return '(unknown)'
  const p = fqcn.split('.')
  if (p.length <= 3) return fqcn
  const last2 = p.slice(-2).join('.')
  const prefix = p.slice(0, -2).map(s => s.charAt(0)).join('.')
  return prefix + '.' + last2
}

/**
 * Strip the longest common package prefix shared across all names in a set.
 * Returns the remainder (the part that differs). If names is empty or all share
 * the entire name, falls back to sn().
 */
export function computeCommonPrefix(names: string[]): string {
  if (!names.length) return ''
  const parts = names.map(n => n.split('.'))
  const minLen = Math.min(...parts.map(p => p.length)) - 1 // never strip the class name
  let common = 0
  outer: for (let i = 0; i < minLen; i++) {
    const seg = parts[0][i]
    for (const p of parts) { if (p[i] !== seg) break outer }
    common++
  }
  return parts[0].slice(0, common).join('.')
}

export function snStrip(fqcn: string, prefix: string): string {
  if (!prefix || !fqcn.startsWith(prefix + '.')) return sn(fqcn)
  return fqcn.substring(prefix.length + 1)
}

/** Escape HTML special characters to prevent XSS when inserting into .html() */
export function esc(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
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

/** Display a filesystem path without leaking full local machine details */
export function displayPath(path: string | null | undefined): string {
  if (!path) return '(not set)'
  const norm = String(path).replace(/\\/g, '/')
  const parts = norm.split('/').filter(Boolean)
  if (parts.length === 0) return '(not set)'
  if (parts.length === 1) return parts[0]
  if (parts.length === 2) return parts.join('/')
  return `.../${parts.slice(-2).join('/')}`
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
    // When memberDeps are available, derive covered classes from them
    // (matches backend StructuralChangeAnalyzer.computeOverlapClasses behaviour)
    let covered: Set<string>
    if (t.memberDeps && t.memberDeps.length > 0) {
      covered = new Set<string>()
      for (const md of t.memberDeps) {
        const h = md.indexOf('#')
        const cls = h >= 0 ? md.substring(0, h) : md
        if (changedSet.has(cls)) covered.add(cls)
      }
      // Also include class-level deps for classes without member detail
      for (const d of (t.deps || [])) {
        if (changedSet.has(d)) covered.add(d)
      }
    } else {
      covered = new Set((t.deps || []).filter(d => changedSet.has(d)))
    }
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
  const killRate = ('killRate' in t ? t.killRate : null) ?? -1
  const killMultiplier = killRate >= 0 ? (0.5 + killRate * 0.5) : 1.0

  if (w.coverageBonus > 0 && bonusMap) {
    s += bonusMap[name] || 0
  } else {
    const effectiveDepOverlap = ('weightedDepOverlap' in t && (t as any).weightedDepOverlap != null)
      ? (t as any).weightedDepOverlap as number : t.depOverlap
    if (effectiveDepOverlap > 0 && t.depTotal > 0 && w.depOverlap > 0)
      s += Math.round(Math.min(Math.ceil((effectiveDepOverlap / Math.sqrt(t.depTotal)) * w.depOverlap * killMultiplier), w.depOverlap))
    if (t.complexityOverlap > 0 && t.depTotal > 0 && w.changeComplexity > 0)
      s += Math.round(Math.min(Math.ceil((t.complexityOverlap / Math.sqrt(t.depTotal)) * w.changeComplexity), w.changeComplexity) * killMultiplier)
  }

  if (killRate >= 0 && w.killRateBonus > 0) s += Math.round(killRate * w.killRateBonus)
  if (t.isChanged) s += w.changedTest
  if (t.isNew) s += w.newTest
  if (t.speedRatio < 0) s += Math.round(Math.abs(t.speedRatio) * w.speed)
  else if (t.speedRatio > 0) s -= Math.round(t.speedRatio * w.speedPenalty)
  if (t.hasStaticFieldOverlap && w.staticFieldBonus > 0) s += w.staticFieldBonus
  if (t.failScore > 0) s += Math.min(Math.ceil(t.failScore), w.maxFailure)
  return s
}

/** Compute APFD from an ordered list of outcomes */
export function computeApfd(orderedOutcomes: TestOutcome[]): number | null {
  const n = orderedOutcomes.length
  if (n === 0) return null
  const failPositions: number[] = []
  orderedOutcomes.forEach((o, i) => { if (o.failed) failPositions.push(i + 1) })
  const m = failPositions.length
  if (m === 0) return null
  const posSum = failPositions.reduce((s, p) => s + p, 0)
  return 1 - posSum / (n * m) + 1 / (2 * n)
}

/** A single scored component in the breakdown */
export interface ScoreComponent {
  label: string
  contribution: number   // signed points; positive boosts rank, negative is penalty
  rawDetail: string      // human-readable raw value, e.g. "3/8 deps changed"
}

/** Structured score breakdown returned by computeScoreBreakdown */
export interface ScoreBreakdown {
  total: number
  components: ScoreComponent[]
  changedDeps: { className: string; members: string[] }[]
  hiddenChangedDepsCount: number  // changed deps not in truncated deps array
  methodTouches: { method: string; changedDeps: string[] }[]
  nonOverlappingCount: number
  flags: { newTest: boolean; changedTest: boolean; staticFieldOverlap: boolean; isFast: boolean; isSlow: boolean }
}

/** Compute a fully-structured score breakdown for use in ScoreBreakdownPanel */
export function computeScoreBreakdown(
  t: TestEntry | TestOutcome,
  w: ScoringWeights,
  bonusMap: Record<string, number> | null,
  medianDuration: number,
  changedClasses: string[],
): ScoreBreakdown {
  const name = 'name' in t ? t.name : (t as TestOutcome).testClass
  const changedSet = new Set(changedClasses)
  const deps: string[] = 'deps' in t && t.deps ? t.deps : []
  const overlapping = deps.filter(d => changedSet.has(d)).sort()
  const te = 'name' in t ? t as TestEntry : null
  const killRate = (te?.killRate ?? -1)
  const killMultiplier = killRate >= 0 ? (0.5 + killRate * 0.5) : 1.0

  // dep overlap / set-cover
  let depContrib = 0, depDetail = ''
  if (w.coverageBonus > 0 && bonusMap) {
    depContrib = bonusMap[name] || 0
    depDetail = 'set-cover bonus'
  } else {
    const effectiveDepOverlap = (te?.weightedDepOverlap != null) ? te.weightedDepOverlap : t.depOverlap
    depContrib = effectiveDepOverlap > 0 && t.depTotal > 0 && w.depOverlap > 0
      ? Math.round(Math.min(Math.ceil((effectiveDepOverlap / Math.sqrt(t.depTotal)) * w.depOverlap * killMultiplier), w.depOverlap)) : 0
    depDetail = `${t.depOverlap}/${t.depTotal} deps changed`
    if (killRate >= 0) depDetail += ` · kill rate ${(killRate * 100).toFixed(0)}%`
    if (te?.weightedDepOverlap != null && te.weightedDepOverlap !== t.depOverlap)
      depDetail += ` · IDF-weighted ${te.weightedDepOverlap.toFixed(2)}`
  }
  const cmplx = t.complexityOverlap > 0 && t.depTotal > 0 && w.changeComplexity > 0
    ? Math.round(Math.min(Math.ceil((t.complexityOverlap / Math.sqrt(t.depTotal)) * w.changeComplexity), w.changeComplexity) * killMultiplier) : 0

  const killBonus = killRate >= 0 && w.killRateBonus > 0 ? Math.round(killRate * w.killRateBonus) : 0

  // changed deps tree (class → member list)
  const membersByClass = new Map<string, string[]>()
  for (const md of te?.memberDeps ?? []) {
    const h = md.indexOf('#'); if (h < 0) continue
    const cls = md.substring(0, h); if (!changedSet.has(cls)) continue
    const mem = md.substring(h + 1)
    if (!membersByClass.has(cls)) membersByClass.set(cls, [])
    membersByClass.get(cls)!.push(mem)
  }
  const changedDeps = overlapping.map(dep => ({
    className: dep, members: (membersByClass.get(dep) ?? []).sort(),
  }))

  // per-method touches
  const methodTouches = (te?.methods ?? [])
    .map(m => {
      const mChanged = (m.deps ?? []).filter(d => {
        const h = d.indexOf('#'); const cls = h >= 0 ? d.substring(0, h) : d
        return changedSet.has(cls)
      })
      return { method: m.name, changedDeps: mChanged }
    })
    .filter(m => m.changedDeps.length > 0)

  // speed
  let speedPts = 0
  if (t.speedRatio < 0) speedPts = Math.round(Math.abs(t.speedRatio) * w.speed)
  else if (t.speedRatio > 0) speedPts = -Math.round(t.speedRatio * w.speedPenalty)
  const dur = te?.duration ?? -1
  const durStr = dur >= 0 ? `${fmtDur(dur)} vs median ${fmtDur(medianDuration)}` : 'unknown'

  const fail = t.failScore > 0 ? Math.min(Math.ceil(t.failScore), w.maxFailure) : 0

  const raw: ScoreComponent[] = [
    { label: 'Dep overlap',       contribution: depContrib,                  rawDetail: depDetail },
    { label: 'Change complexity', contribution: cmplx,                       rawDetail: `score ${t.complexityOverlap.toFixed(2)}` },
    { label: 'Kill rate bonus',   contribution: killBonus,                   rawDetail: killRate >= 0 ? `kill rate ${(killRate * 100).toFixed(0)}%` : 'no data' },
    { label: 'Failure history',   contribution: fail,                        rawDetail: `raw ${t.failScore.toFixed(2)}, cap ${w.maxFailure}` },
    { label: 'New test',          contribution: t.isNew ? w.newTest : 0,     rawDetail: t.isNew ? 'yes' : 'no' },
    { label: 'Changed test',      contribution: t.isChanged ? w.changedTest : 0, rawDetail: t.isChanged ? 'yes' : 'no' },
    { label: 'Static field',      contribution: t.hasStaticFieldOverlap ? w.staticFieldBonus : 0, rawDetail: t.hasStaticFieldOverlap ? 'yes' : 'no' },
    { label: 'Speed',             contribution: speedPts,                    rawDetail: durStr },
  ]
  const components = raw
    .filter(c => c.contribution !== 0)
    .sort((a, b) => Math.abs(b.contribution) - Math.abs(a.contribution))

  return {
    total: components.reduce((s, c) => s + c.contribution, 0),
    components,
    changedDeps,
    hiddenChangedDepsCount: Math.max(0, t.depOverlap - overlapping.length),
    methodTouches,
    nonOverlappingCount: deps.filter(d => !changedSet.has(d)).length,
    flags: {
      newTest: t.isNew, changedTest: t.isChanged, staticFieldOverlap: t.hasStaticFieldOverlap,
      isFast: t.isFast ?? false, isSlow: t.isSlow ?? false,
    },
  }
}

/** Build an explain-mode-style tooltip for a test's score breakdown */
export function scoreTooltip(
  t: TestEntry | TestOutcome,
  w: ScoringWeights,
  bonusMap: Record<string, number> | null,
  medianDuration: number,
  changedClasses: string[],
): string {
  const name = 'name' in t ? t.name : (t as TestOutcome).testClass
  const lines: string[] = []
  const changedSet = new Set(changedClasses)
  const deps: string[] = 'deps' in t && t.deps ? t.deps : []
  const overlapping = deps.filter(d => changedSet.has(d)).sort()
  const isTestEntry = 'name' in t
  const te = isTestEntry ? t as TestEntry : null

  // dep overlap / set-cover
  const killRate = te?.killRate ?? -1
  const killMultiplier = killRate >= 0 ? (0.5 + killRate * 0.5) : 1.0
  if (w.coverageBonus > 0 && bonusMap) {
    const scBonus = bonusMap[name] || 0
    lines.push(`Set-cover bonus:       ${signed(scBonus)}`)
  } else {
    const effectiveDepOverlap = (te?.weightedDepOverlap != null) ? te.weightedDepOverlap : t.depOverlap
    const depOv = effectiveDepOverlap > 0 && t.depTotal > 0 && w.depOverlap > 0
      ? Math.round(Math.min(Math.ceil((effectiveDepOverlap / Math.sqrt(t.depTotal)) * w.depOverlap * killMultiplier), w.depOverlap)) : 0
    const idfNote = te?.weightedDepOverlap != null && te.weightedDepOverlap !== t.depOverlap
      ? `, IDF-weighted ${te.weightedDepOverlap.toFixed(2)}` : ''
    lines.push(`Dependency overlap:    ${signed(depOv)}  (${t.depOverlap}/${t.depTotal} deps overlap${idfNote}${killRate >= 0 ? `, kill-rate ×${killMultiplier.toFixed(2)}` : ''})`)
    const cmplx = t.complexityOverlap > 0 && t.depTotal > 0 && w.changeComplexity > 0
      ? Math.round(Math.min(Math.ceil((t.complexityOverlap / Math.sqrt(t.depTotal)) * w.changeComplexity), w.changeComplexity) * killMultiplier) : 0
    lines.push(`Change complexity:     ${signed(cmplx)}  (complexity: ${t.complexityOverlap.toFixed(2)})`)
  }
  if (killRate >= 0 && w.killRateBonus > 0) {
    const kb = Math.round(killRate * w.killRateBonus)
    lines.push(`Kill-rate bonus:       ${signed(kb)}  (${(killRate * 100).toFixed(0)}% kill rate)`)
  }

  // changed deps listing with member-level detail
  if (overlapping.length > 0) {
    const memberDeps = te?.memberDeps || []
    // group memberDeps by class: "com.example.Foo#bar" → Foo → [bar]
    const membersByClass = new Map<string, string[]>()
    for (const md of memberDeps) {
      const hashIdx = md.indexOf('#')
      if (hashIdx < 0) continue
      const cls = md.substring(0, hashIdx)
      if (!changedSet.has(cls)) continue
      const member = md.substring(hashIdx + 1)
      let arr = membersByClass.get(cls)
      if (!arr) { arr = []; membersByClass.set(cls, arr) }
      arr.push(member)
    }

    const hiddenCount = t.depOverlap - overlapping.length
    lines.push(`Changed dependencies (${t.depOverlap}):`)
    for (const dep of overlapping) {
      const members = membersByClass.get(dep)
      if (members && members.length > 0) {
        members.sort()
        lines.push(`  - ${shortClass(dep)}  [${members.join(', ')}]`)
      } else {
        lines.push(`  - ${shortClass(dep)}`)
      }
    }
    if (hiddenCount > 0) lines.push(`  + ${hiddenCount} more (not in index)`)
  }

  // per-test-method breakdown (when method-level deps are available)
  if (te?.methods && te.methods.length > 0) {
    const methodsWithChanged = te.methods
      .map(m => {
        const mChanged = (m.deps || []).filter(d => {
          const hashIdx = d.indexOf('#')
          const cls = hashIdx >= 0 ? d.substring(0, hashIdx) : d
          return changedSet.has(cls)
        })
        return { name: m.name, total: m.depCount, changed: mChanged }
      })
      .filter(m => m.changed.length > 0)
    if (methodsWithChanged.length > 0) {
      lines.push(`Test methods touching changed code:`)
      for (const m of methodsWithChanged) {
        const shortDeps = m.changed.map(d => shortMember(d)).join(', ')
        lines.push(`  ${m.name}() → ${shortDeps}`)
      }
    }
  }

  // non-overlapping deps (only show count to keep tooltip manageable)
  const nonOverlapping = deps.filter(d => !changedSet.has(d))
  if (nonOverlapping.length > 0) {
    lines.push(`Other dependencies:    ${nonOverlapping.length}`)
  }

  // changed test
  const chg = t.isChanged ? w.changedTest : 0
  lines.push(`Changed test source:   ${signed(chg)}${t.isChanged ? '  (yes)' : ''}`)

  // static field overlap
  const stf = t.hasStaticFieldOverlap ? w.staticFieldBonus : 0
  lines.push(`Static field overlap:  ${signed(stf)}${t.hasStaticFieldOverlap ? '  (yes)' : ''}`)

  // failure history
  const fail = t.failScore > 0 ? Math.min(Math.ceil(t.failScore), w.maxFailure) : 0
  lines.push(`Failure history:       ${signed(fail)}  (raw: ${t.failScore.toFixed(2)}, cap: ${w.maxFailure})`)

  // new test
  const isNew = t.isNew ? w.newTest : 0
  lines.push(`New test bonus:        ${signed(isNew)}${t.isNew ? '  (yes)' : ''}`)

  // speed
  let speedPts = 0
  if (t.speedRatio < 0) speedPts = Math.round(Math.abs(t.speedRatio) * w.speed)
  else if (t.speedRatio > 0) speedPts = -Math.round(t.speedRatio * w.speedPenalty)
  const dur = 'duration' in t ? (t as TestEntry).duration : -1
  const durStr = dur >= 0 ? `${fmtDur(dur)} (median: ${fmtDur(medianDuration)}, ratio: ${t.speedRatio >= 0 ? '+' : ''}${t.speedRatio.toFixed(2)})` : 'unknown'
  lines.push(`Speed:                 ${signed(speedPts)}  (${durStr})`)

  return lines.join('\n')
}

function signed(n: number): string {
  return n > 0 ? `+${n}` : n === 0 ? '+0' : `${n}`
}

function shortClass(fqcn: string): string {
  const p = fqcn.split('.')
  return p.length <= 2 ? fqcn : p.slice(-2).join('.')
}

function shortMember(ref: string): string {
  const hashIdx = ref.indexOf('#')
  if (hashIdx < 0) return shortClass(ref)
  return shortClass(ref.substring(0, hashIdx)) + '#' + ref.substring(hashIdx + 1)
}

/** Export test list as CSV string */
export function exportTestsCsv(tests: TestEntry[]): string {
  const header = 'rank,name,score,duration_ms,deps,dep_overlap,fail_score,is_new,is_changed,is_fast,is_slow,static_overlap'
  const rows = tests.map(t =>
    [t.rank, '"' + t.name.replace(/"/g, '""') + '"', t.score, t.duration, t.depTotal, t.depOverlap,
     t.failScore.toFixed(2), t.isNew, t.isChanged, t.isFast, t.isSlow, t.hasStaticFieldOverlap].join(',')
  )
  return header + '\n' + rows.join('\n')
}
