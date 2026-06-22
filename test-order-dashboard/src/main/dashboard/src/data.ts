import type { DashboardData, TestEntry, MethodEntry } from './types'

const EMPTY: DashboardData = {
  project: { name: 'Unknown', generated: '', pluginVersion: '?', stateFilePath: '', indexFilePath: '' },
  weights: { newTest: 0, changedTest: 0, maxFailure: 0, speed: 0, speedPenalty: 0, depOverlap: 0, changeComplexity: 0, staticFieldBonus: 0, coverageBonus: 0, killRateBonus: 0 },
  weightDefs: [],
  config: { failureDecay: 0.3, durationAlpha: 0.85, failurePruneThreshold: 0.01, emaVarianceThreshold: 0.5, historyMaxRuns: 50 },
  medianDuration: 0,
  changedClasses: [],
  changedTestClasses: [],
  tests: [],
  runs: [],
  coverage: null,
  ml: null,
  mutation: null,
  staticAnalysis: null,
}

/**
 * Expand compressed arrays: replace integer index arrays with string arrays
 * using the shared memberDict / depDict. Mutates entries in-place.
 * Inverse of DashboardGenerator.compressMemberDeps().
 */
function expandCompressedArrays(tests: TestEntry[], raw: DashboardData): void {
  const memberDict = raw.memberDict
  const depDict = raw.depDict
  if (!memberDict?.length && !depDict?.length) return
  for (const t of tests) {
    if (memberDict && Array.isArray(t.memberDeps)) {
      ;(t as any).memberDeps = (t.memberDeps as unknown as number[]).map(i => memberDict[i])
    }
    if (depDict && Array.isArray(t.deps)) {
      ;(t as any).deps = (t.deps as unknown as number[]).map(i => depDict[i])
    }
    if (t.methods) {
      for (const m of t.methods) {
        if (memberDict && Array.isArray(m.memberDeps)) {
          ;(m as any).memberDeps = (m.memberDeps as unknown as number[]).map(i => memberDict[i])
        }
        if (depDict && Array.isArray(m.deps)) {
          ;(m as any).deps = (m.deps as unknown as number[]).map(i => depDict[i])
        }
      }
    }
  }
}

export function normalizeDashboardData(raw: DashboardData | null): DashboardData {
  if (!raw) return { ...EMPTY }
  const tests = raw.tests ?? []
  expandCompressedArrays(tests, raw)
  return {
    project: raw.project ?? EMPTY.project,
    weights: raw.weights ?? EMPTY.weights,
    weightDefs: raw.weightDefs ?? [],
    config: raw.config ?? EMPTY.config,
    medianDuration: raw.medianDuration ?? 0,
    changedClasses: raw.changedClasses ?? [],
    changedTestClasses: raw.changedTestClasses ?? [],
    tests,
    runs: raw.runs ?? [],
    coverage: raw.coverage ?? null,
    ml: raw.ml ?? null,
    mutation: raw.mutation ?? null,
    staticAnalysis: raw.staticAnalysis ?? null,
    cache: raw.cache ?? null,
    classToModule: raw.classToModule,
    memberDict: raw.memberDict,
    depDict: raw.depDict,
  }
}

export function parseDashboardData(): { data: DashboardData; error: string | null } {
  let raw: DashboardData | null = null
  let error: string | null = null
  try {
    const el = document.getElementById('dashboard-data')
    if (el) {
      raw = JSON.parse(el.textContent?.trim() ?? '{}') as DashboardData
    }
  } catch (e) {
    error = (e as Error).message
  }
  return { data: normalizeDashboardData(raw), error }
}
