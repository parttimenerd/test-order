import type { DashboardData } from './types'

const EMPTY: DashboardData = {
  project: { name: 'Unknown', generated: '', pluginVersion: '?', stateFilePath: '', indexFilePath: '' },
  weights: { newTest: 0, changedTest: 0, maxFailure: 0, speed: 0, speedPenalty: 0, depOverlap: 0, changeComplexity: 0, staticFieldBonus: 0, coverageBonus: 0 },
  weightDefs: [],
  config: { failureDecay: 0.3, durationAlpha: 0.85, failurePruneThreshold: 0.01, emaVarianceThreshold: 0.5, historyMaxRuns: 50 },
  medianDuration: 0,
  changedClasses: [],
  changedTestClasses: [],
  tests: [],
  runs: [],
  coverage: null,
}

export function normalizeDashboardData(raw: DashboardData | null): DashboardData {
  if (!raw) return { ...EMPTY }
  return {
    project: raw.project ?? EMPTY.project,
    weights: raw.weights ?? EMPTY.weights,
    weightDefs: raw.weightDefs ?? [],
    config: raw.config ?? EMPTY.config,
    medianDuration: raw.medianDuration ?? 0,
    changedClasses: raw.changedClasses ?? [],
    changedTestClasses: raw.changedTestClasses ?? [],
    tests: raw.tests ?? [],
    runs: raw.runs ?? [],
    coverage: raw.coverage ?? null,
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
