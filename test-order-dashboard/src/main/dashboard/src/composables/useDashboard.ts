import { ref, computed, reactive, watch, onMounted, type Ref, type ComputedRef } from 'vue'
import type {
  DashboardData, TestEntry, MethodEntry, RunRecord, ScoringWeights,
  SortColumn, GraphMode, TabDef, ScoreComponent, TestRunOutcome,
  SimResult, SelectionCoverage, CoverageClass, RunDiffEntry,
} from '../types'
import { sn, computeSetCoverBonuses, computeScore, computeApfd, exportTestsCsv, scoreTooltip } from '../utils'

type ScoreMode = 'orig' | 'sim'

export interface DashboardState {
  dd: DashboardData
  parseError: string | null
  hasData: boolean
  hasCoverage: boolean
  hasML: boolean

  // Reactive UI state
  selectedTest: Ref<TestEntry | null>
  selectedTests: Ref<Set<string>>
  activeTab: Ref<string>
  lw: Record<string, number>
  searchQ: Ref<string>
  sortKey: Ref<string>
  sortDir: Ref<string>
  graphMode: Ref<string>
  covSelectedClass: Ref<CoverageClass | null>
  selectedMethod: Ref<MethodEntry | null>
  selectedMethods: Ref<Set<string>>
  showChangedPanel: Ref<boolean>
  simSortKey: Ref<string>
  simSortDir: Ref<string>
  badgeFilter: Ref<string | null>
  focusedTestIndex: Ref<number>
  covSearchQ: Ref<string>
  scoreModalOpen: Ref<boolean>
  scoreModalTitle: Ref<string>
  scoreModalBody: Ref<string>

  // Constants
  TABS: ComputedRef<TabDef[]>
  SIDEBAR_SORT_COLS: SortColumn[]
  GMODES: GraphMode[]

  // Derived
  tests: TestEntry[]
  runs: RunRecord[]
  changedSet: Set<string>
  hasMethodData: ComputedRef<boolean>
  filteredTests: ComputedRef<TestEntry[]>
  selectedTestObjects: ComputedRef<TestEntry[]>
  latestRun: ComputedRef<RunRecord | null>
  avgApfd: ComputedRef<number | null>
  fastestTest: ComputedRef<TestEntry | null>
  slowestTest: ComputedRef<TestEntry | null>
  totalNodes: ComputedRef<number>
  scoreComps: ComputedRef<ScoreComponent[]>
  testOutcomes: ComputedRef<TestRunOutcome[]>
  simResults: ComputedRef<SimResult[]>
  covPackages: ComputedRef<string[]>
  covAvgTests: ComputedRef<string>
  covPercent: ComputedRef<number>
  selectionCoverage: ComputedRef<SelectionCoverage | null>
  origSCB: Record<string, number>
  simSetCoverBonuses: ComputedRef<Record<string, number>>
  runDiff: ComputedRef<RunDiffEntry[]>
  simApfd: ComputedRef<number | null>
  filteredCovClasses: ComputedRef<CoverageClass[]>

  // Actions
  selectTest: (t: TestEntry, event: MouseEvent | null) => void
  drillDown: (t: TestEntry) => void
  selectMethod: (m: MethodEntry, event: MouseEvent | null) => void
  sortBy: (key: string) => void
  simSortBy: (key: string) => void
  resetWeights: () => void
  setGraphMode: (id: string) => void
  setTab: (id: string) => void
  setBadgeFilter: (filter: string | null) => void
  navigateTest: (dir: 'up' | 'down') => void
  activateFocusedTest: () => void
  clearSelection: () => void
  navigateToTestFromCov: (testName: string) => void
  exportCsv: () => void
  getScoreBreakdown: (testName: string, mode: ScoreMode) => string
  openScoreModal: (testName: string, mode: ScoreMode, sourceLabel?: string) => void
  closeScoreModal: () => void

  // Server connection
  serverConnected: Ref<boolean>
  optimizing: Ref<boolean>
  optimizeError: Ref<string | null>
  optimizeResult: Ref<OptimizeApiResult | null>
  optimizeWeights: () => Promise<void>
}

export interface OptimizeApiResult {
  weights: Record<string, number>
  trainScore: number
  validationScore: number
  overfit: boolean
  folds: number
  error?: string
}

export function useDashboard(dd: DashboardData, parseError: string | null): DashboardState {
  const hasData = !!(dd.tests && dd.tests.length > 0)
  const hasCoverage = !!(dd.coverage && dd.coverage.classes && dd.coverage.classes.length)
  const hasML = !!(dd.ml && dd.ml.enabled)

  const selectedTest = ref<TestEntry | null>(null)
  const selectedTests = ref<Set<string>>(new Set())
  const activeTab = ref('tests')
  const lw: Record<string, number> = reactive(Object.assign({}, dd.weights))
  const searchQ = ref('')
  const sortKey = ref('rank')
  const sortDir = ref('asc')
  const graphMode = ref('focus')
  const covSelectedClass = ref<CoverageClass | null>(null)
  const selectedMethod = ref<MethodEntry | null>(null)
  const selectedMethods = ref<Set<string>>(new Set())
  const showChangedPanel = ref(false)
  const simSortKey = ref('simScore')
  const simSortDir = ref('desc')
  const badgeFilter = ref<string | null>(null)
  const focusedTestIndex = ref(-1)
  const covSearchQ = ref('')
  const scoreModalOpen = ref(false)
  const scoreModalTitle = ref('')
  const scoreModalBody = ref('')
  let lastClickedTestIndex = -1
  let lastClickedMethodIndex = -1

  // Server connection state
  const serverConnected = ref(false)
  const optimizing = ref(false)
  const optimizeError = ref<string | null>(null)
  const optimizeResult = ref<OptimizeApiResult | null>(null)

  // Detect if served by test-order server
  function detectServer() {
    fetch('/api/ping', { method: 'GET' })
      .then(r => r.ok ? r.json() : Promise.reject())
      .then(d => { if (d && d.server === 'test-order') serverConnected.value = true })
      .catch(() => { serverConnected.value = false })
  }
  // Run detection on next tick (after mount)
  if (typeof window !== 'undefined') {
    setTimeout(detectServer, 0)
  }

  async function optimizeWeightsFn() {
    optimizing.value = true
    optimizeError.value = null
    optimizeResult.value = null
    try {
      const resp = await fetch('/api/optimize', { method: 'POST' })
      const data = await resp.json()
      if (data.error) {
        optimizeError.value = data.error
        return
      }
      optimizeResult.value = data as OptimizeApiResult
      // Apply optimized weights to sliders
      if (data.weights) {
        for (const [k, v] of Object.entries(data.weights)) {
          if (k in lw) lw[k] = v as number
        }
      }
    } catch (e) {
      optimizeError.value = (e as Error).message || 'Network error'
    } finally {
      optimizing.value = false
    }
  }

  const SIDEBAR_SORT_COLS: SortColumn[] = [
    { key: 'rank', label: 'Rank' },
    { key: 'name', label: 'Name' },
    { key: 'score', label: 'Score' },
    { key: 'duration', label: 'Dur' },
  ]
  const GMODES: GraphMode[] = [
    { id: 'focus', label: 'Focus' },
    { id: 'changed', label: 'Changed subgraph' },
    { id: 'full', label: 'Full' },
  ]
  const TABS = computed<TabDef[]>(() => {
    const tabs: TabDef[] = [
      { id: 'tests', label: 'Tests' },
      { id: 'analytics', label: 'Analytics' },
      { id: 'weights', label: 'Weights' },
    ]
    if (hasML) tabs.push({ id: 'ml', label: 'ML Health' })
    return tabs
  })

  const tests = dd.tests
  const runs = [...(dd.runs || [])].sort((a, b) => a.timestamp - b.timestamp)
  const changedSet = new Set(dd.changedClasses || [])
  const origSCB = computeSetCoverBonuses(dd.tests, new Set(dd.changedClasses || []), dd.weights?.coverageBonus || 0)

  const hasMethodData = computed(() => tests.some(t => t.methods && t.methods.length > 0))
  const simSetCoverBonuses = computed(() => computeSetCoverBonuses(tests, changedSet, lw.coverageBonus))
  const selectedTestObjects = computed(() =>
    tests.filter(t => selectedTests.value.has(t.name)).sort((a, b) => a.rank - b.rank),
  )

  const filteredTests = computed(() => {
    let arr = [...tests]
    if (searchQ.value) {
      const q = searchQ.value.toLowerCase()
      arr = arr.filter(t => t.name.toLowerCase().includes(q))
    }
    if (badgeFilter.value === 'changed') arr = arr.filter(t => t.isChanged)
    else if (badgeFilter.value === 'new') arr = arr.filter(t => t.isNew)
    else if (badgeFilter.value === 'failing') arr = arr.filter(t => t.failScore > 0)
    else if (badgeFilter.value === 'static') arr = arr.filter(t => t.hasStaticFieldOverlap)
    arr.sort((a, b) => {
      let av: string | number = (a as unknown as Record<string, string | number>)[sortKey.value]
      let bv: string | number = (b as unknown as Record<string, string | number>)[sortKey.value]
      if (sortKey.value === 'name') { av = sn(a.name); bv = sn(b.name) }
      if (sortKey.value === 'duration') { av = (av as number) < 0 ? 1e15 : av; bv = (bv as number) < 0 ? 1e15 : bv }
      const d = av < bv ? -1 : av > bv ? 1 : 0
      return sortDir.value === 'asc' ? d : -d
    })
    return arr
  })

  const latestRun = computed(() => (runs.length ? runs[runs.length - 1] : null))
  const avgApfd = computed(() => (runs.length ? runs.reduce((s, r) => s + r.apfd, 0) / runs.length : null))
  const fastestTest = computed(() => {
    const w = tests.filter(t => t.duration >= 0)
    return w.length ? w.reduce((a, b) => (a.duration < b.duration ? a : b)) : null
  })
  const slowestTest = computed(() => {
    const w = tests.filter(t => t.duration >= 0)
    return w.length ? w.reduce((a, b) => (a.duration > b.duration ? a : b)) : null
  })
  const totalNodes = computed(() => {
    const s = new Set<string>()
    tests.forEach(t => { s.add(t.name); (t.deps || []).forEach(d => s.add(d)) })
    return s.size
  })

  const scoreComps = computed<ScoreComponent[]>(() => {
    const t = selectedTest.value
    if (!t) return []
    const w = dd.weights
    let depOv = 0, cmplx = 0, scBonus = 0
    if (w.coverageBonus > 0) {
      scBonus = origSCB[t.name] || 0
    } else {
      depOv = t.depOverlap > 0 && t.depTotal > 0 && w.depOverlap > 0
        ? Math.min(Math.ceil((t.depOverlap / Math.sqrt(t.depTotal)) * w.depOverlap), w.depOverlap) : 0
      cmplx = t.complexityOverlap > 0 && t.depTotal > 0 && w.changeComplexity > 0
        ? Math.min(Math.ceil((t.complexityOverlap / Math.sqrt(t.depTotal)) * w.changeComplexity), w.changeComplexity) : 0
    }
    const chg = t.isChanged ? w.changedTest : 0
    const isNew = t.isNew ? w.newTest : 0
    const spd = t.speedRatio < 0 ? Math.round(Math.abs(t.speedRatio) * w.speed) : 0
    const pen = t.speedRatio > 0 ? -Math.round(t.speedRatio * w.speedPenalty) : 0
    const stf = t.hasStaticFieldOverlap ? w.staticFieldBonus : 0
    const fail = t.failScore > 0 ? Math.min(Math.ceil(t.failScore), w.maxFailure) : 0
    return [
      { label: 'Set-cover', value: scBonus, color: '#10b981', explanation: 'Coverage bonus from greedy set-cover' },
      { label: 'Dep Overlap', value: depOv, color: '#3b82f6', explanation: `${t.depOverlap}/${t.depTotal} deps overlap changed` },
      { label: 'Complexity', value: cmplx, color: '#6366f1', explanation: `${t.complexityOverlap} complex-change overlaps` },
      { label: 'Changed', value: chg, color: '#f59e0b', explanation: `Test class modified (+${w.changedTest})` },
      { label: 'New', value: isNew, color: '#22c55e', explanation: `New test class (+${w.newTest})` },
      { label: 'Speed+', value: spd, color: '#06b6d4', explanation: 'Faster than median' },
      { label: 'Speed-', value: pen, color: '#f97316', explanation: 'Slower than median' },
      { label: 'Static', value: stf, color: '#a855f7', explanation: 'Static field overlap' },
      { label: 'Failures', value: fail, color: '#ef4444', explanation: `failScore=${t.failScore.toFixed(2)}` },
    ]
  })

  const testOutcomes = computed<TestRunOutcome[]>(() => {
    if (!selectedTest.value) return []
    const name = selectedTest.value.name
    return runs.map(r => {
      const o = (r.outcomes || []).find(o => o.testClass === name)
      return { ts: r.timestamp, present: !!o, failed: o ? o.failed : false, outcome: o || null }
    })
  })

  const simResults = computed<SimResult[]>(() => {
    const bonuses = simSetCoverBonuses.value
    const scored = tests.map(t => ({ ...t, simScore: computeScore(t, lw as unknown as ScoringWeights, bonuses) }))
    const sorted = [...scored].sort((a, b) => b.simScore - a.simScore)
    const rankMap: Record<string, number> = {}
    const scoreMap = new Map<string, number>()
    sorted.forEach((t, i) => (rankMap[t.name] = i + 1))
    scored.forEach(s => scoreMap.set(s.name, s.simScore))
    const rows: SimResult[] = tests.map(t => ({
      name: t.name,
      origRank: t.rank,
      simRank: rankMap[t.name],
      delta: rankMap[t.name] - t.rank,
      origScore: t.score,
      simScore: scoreMap.get(t.name) ?? 0,
    }))
    const sk = simSortKey.value
    const sd = simSortDir.value
    rows.sort((a, b) => {
      const av = sk === 'name' ? sn(a.name).toLowerCase() : (a as unknown as Record<string, number>)[sk]
      const bv = sk === 'name' ? sn(b.name).toLowerCase() : (b as unknown as Record<string, number>)[sk]
      const d = av < bv ? -1 : av > bv ? 1 : 0
      return sd === 'asc' ? d : -d
    })
    return rows
  })

  const covPackages = computed(() => {
    if (!dd.coverage?.classes) return []
    const pkgs = new Set(dd.coverage.classes.map(c => c.package))
    return [...pkgs].sort()
  })
  const covAvgTests = computed(() => {
    if (!dd.coverage?.classes?.length) return '0'
    return (dd.coverage.classes.reduce((s, c) => s + c.testCount, 0) / dd.coverage.classes.length).toFixed(1)
  })
  const covPercent = computed(() => {
    if (!dd.coverage?.classes || !dd.coverage.totalSourceClasses) return 0
    const covered = dd.coverage.classes.filter(c => c.testCount > 0).length
    return Math.round((covered / dd.coverage.totalSourceClasses) * 100)
  })
  const selectionCoverage = computed<SelectionCoverage | null>(() => {
    if (!dd.coverage?.classes) return null
    const selNames = selectedTests.value
    const selMethodNames = selectedMethods.value
    if (selNames.size === 0) return null
    const coveredSources = new Set<string>()
    for (const tName of selNames) {
      const t = tests.find(x => x.name === tName)
      if (!t) continue
      if (selMethodNames.size > 0 && t.methods?.length) {
        for (const m of t.methods) {
          if (selMethodNames.has(m.name)) {
            (m.deps || []).forEach(d => coveredSources.add(d))
          }
        }
      } else {
        (t.deps || []).forEach(d => coveredSources.add(d))
      }
    }
    const total = dd.coverage!.totalSourceClasses
    const covered = dd.coverage!.classes.filter(c => coveredSources.has(c.name)).length
    return { covered, total, percent: total > 0 ? Math.round((covered / total) * 100) : 0, sources: coveredSources }
  })

  // ── Run diff (compare last 2 runs) ──────────────────────────
  const runDiff = computed<RunDiffEntry[]>(() => {
    if (runs.length < 2) return []
    const prev = runs[runs.length - 2]
    const curr = runs[runs.length - 1]
    const prevScored = (prev.outcomes || []).map(o => ({ name: o.testClass, score: computeScore(o, dd.weights, origSCB), failed: o.failed }))
    const currScored = (curr.outcomes || []).map(o => ({ name: o.testClass, score: computeScore(o, dd.weights, origSCB), failed: o.failed }))
    prevScored.sort((a, b) => b.score - a.score)
    currScored.sort((a, b) => b.score - a.score)
    const prevRankMap = new Map<string, number>()
    const prevFailMap = new Map<string, boolean>()
    prevScored.forEach((t, i) => { prevRankMap.set(t.name, i + 1); prevFailMap.set(t.name, t.failed) })
    const currRankMap = new Map<string, number>()
    const currFailMap = new Map<string, boolean>()
    currScored.forEach((t, i) => { currRankMap.set(t.name, i + 1); currFailMap.set(t.name, t.failed) })
    const allNames = new Set([...prevRankMap.keys(), ...currRankMap.keys()])
    const entries: RunDiffEntry[] = []
    for (const name of allNames) {
      const pr = prevRankMap.get(name) ?? null
      const cr = currRankMap.get(name) ?? null
      const pf = prevFailMap.get(name) ?? false
      const cf = currFailMap.get(name) ?? false
      const delta = pr !== null && cr !== null ? cr - pr : 0
      let status: RunDiffEntry['status'] = 'unchanged'
      if (pr === null) status = 'new'
      else if (cr === null) status = 'removed'
      else if (!pf && cf) status = 'newly-failed'
      else if (pf && !cf) status = 'recovered'
      else if (delta < -3) status = 'improved'
      else if (delta > 3) status = 'regressed'
      entries.push({ name, prevRank: pr, currRank: cr, rankDelta: delta, prevFailed: pf, currFailed: cf, status })
    }
    entries.sort((a, b) => {
      const order: Record<string, number> = { 'newly-failed': 0, 'recovered': 1, 'new': 2, 'removed': 3, 'improved': 4, 'regressed': 5, 'unchanged': 6 }
      return (order[a.status] ?? 9) - (order[b.status] ?? 9) || a.rankDelta - b.rankDelta
    })
    return entries
  })

  // ── Simulated APFD (replay last run with current slider weights) ──
  const simApfd = computed<number | null>(() => {
    const lastRun = runs.length ? runs[runs.length - 1] : null
    if (!lastRun?.outcomes?.length) return null
    const bonuses = simSetCoverBonuses.value
    const scored = lastRun.outcomes.map(o => ({ ...o, simScore: computeScore(o, lw as unknown as ScoringWeights, bonuses) }))
    scored.sort((a, b) => b.simScore - a.simScore)
    return computeApfd(scored)
  })

  // ── Coverage search ─────────────────────────────────────────
  const filteredCovClasses = computed<CoverageClass[]>(() => {
    if (!dd.coverage?.classes) return []
    if (!covSearchQ.value) return dd.coverage.classes
    const q = covSearchQ.value.toLowerCase()
    return dd.coverage.classes.filter(c => c.name.toLowerCase().includes(q) || c.package.toLowerCase().includes(q))
  })

  function getScoreBreakdown(testName: string, mode: ScoreMode): string {
    const t = tests.find(x => x.name === testName)
    if (!t) return 'No score data available for this test.'
    const weights = mode === 'sim' ? (lw as unknown as ScoringWeights) : dd.weights
    const bonuses = mode === 'sim' ? simSetCoverBonuses.value : origSCB
    return scoreTooltip(t, weights, bonuses, dd.medianDuration, dd.changedClasses)
  }

  function openScoreModal(testName: string, mode: ScoreMode, sourceLabel?: string) {
    const t = tests.find(x => x.name === testName)
    if (!t) return
    const score = mode === 'sim'
      ? computeScore(t, lw as unknown as ScoringWeights, simSetCoverBonuses.value)
      : t.score
    const sourceSuffix = sourceLabel ? ` (${sourceLabel})` : ''
    scoreModalTitle.value = `${sn(t.name)}${sourceSuffix} - ${mode === 'sim' ? 'Simulated' : 'Original'} score: ${score}`
    scoreModalBody.value = getScoreBreakdown(testName, mode)
    scoreModalOpen.value = true
  }

  function closeScoreModal() {
    scoreModalOpen.value = false
  }

  // ── URL hash sync ───────────────────────────────────────────
  function applyHash() {
    try {
      const h = window.location.hash.slice(1)
      if (!h) return
      const params = new URLSearchParams(h)
      if (params.has('tab')) {
        const tab = params.get('tab')!
        if (['tests', 'analytics', 'weights'].includes(tab)) activeTab.value = tab
      }
      if (params.has('test')) {
        const tName = params.get('test')!
        const t = tests.find(x => x.name === tName)
        if (t) { selectedTest.value = t; selectedTests.value = new Set([t.name]) }
      }
      if (params.has('filter')) badgeFilter.value = params.get('filter')
    } catch { /* ignore hash parse errors */ }
  }
  function syncHash() {
    const params = new URLSearchParams()
    if (activeTab.value !== 'tests') params.set('tab', activeTab.value)
    if (selectedTest.value && selectedTests.value.size === 1) params.set('test', selectedTest.value.name)
    if (badgeFilter.value) params.set('filter', badgeFilter.value)
    const hash = params.toString()
    window.history.replaceState(null, '', hash ? '#' + hash : window.location.pathname)
  }
  applyHash()
  watch([activeTab, selectedTest, badgeFilter], syncHash)

  // ── Actions ─────────────────────────────────────────────────
  function selectTest(t: TestEntry, event: MouseEvent | null) {
    const ctrl = event && (event.ctrlKey || event.metaKey)
    const shift = event && event.shiftKey
    const list = filteredTests.value
    const clickedIdx = list.findIndex(x => x.name === t.name)

    if (shift && lastClickedTestIndex >= 0 && lastClickedTestIndex !== clickedIdx) {
      const lo = Math.min(lastClickedTestIndex, clickedIdx)
      const hi = Math.max(lastClickedTestIndex, clickedIdx)
      const newSet = new Set(selectedTests.value)
      for (let i = lo; i <= hi; i++) newSet.add(list[i].name)
      selectedTests.value = newSet
      selectedTest.value = t
      selectedMethod.value = null
      selectedMethods.value = new Set()
    } else if (ctrl) {
      const newSet = new Set(selectedTests.value)
      if (newSet.has(t.name)) newSet.delete(t.name)
      else newSet.add(t.name)
      selectedTests.value = newSet
      selectedTest.value = newSet.size > 0 ? t : null
      selectedMethod.value = null
      selectedMethods.value = new Set()
      lastClickedTestIndex = clickedIdx
    } else {
      if (selectedTest.value && selectedTest.value.name === t.name && selectedTests.value.size <= 1) {
        selectedTest.value = null
        selectedTests.value = new Set()
        selectedMethod.value = null
        selectedMethods.value = new Set()
      } else {
        selectedTest.value = t
        selectedTests.value = new Set([t.name])
        selectedMethod.value = null
        selectedMethods.value = new Set()
      }
      lastClickedTestIndex = clickedIdx
    }
  }

  function drillDown(t: TestEntry) {
    if (!t.methods?.length) return
    selectedTest.value = t
    selectedTests.value = new Set([t.name])
    selectedMethod.value = null
    selectedMethods.value = new Set()
    if (activeTab.value !== 'tests') activeTab.value = 'tests'
  }

  function selectMethod(m: MethodEntry, event: MouseEvent | null) {
    const ctrl = event && (event.ctrlKey || event.metaKey)
    const shift = event && event.shiftKey
    const methods = selectedTest.value?.methods ?? []
    const clickedIdx = methods.findIndex(x => x.name === m.name)

    if (shift && lastClickedMethodIndex >= 0 && lastClickedMethodIndex !== clickedIdx) {
      const lo = Math.min(lastClickedMethodIndex, clickedIdx)
      const hi = Math.max(lastClickedMethodIndex, clickedIdx)
      const newSet = new Set(selectedMethods.value)
      for (let i = lo; i <= hi; i++) newSet.add(methods[i].name)
      selectedMethods.value = newSet
      selectedMethod.value = m
    } else if (ctrl) {
      const newSet = new Set(selectedMethods.value)
      if (newSet.has(m.name)) newSet.delete(m.name)
      else newSet.add(m.name)
      selectedMethods.value = newSet
      selectedMethod.value = newSet.size > 0 ? m : null
      lastClickedMethodIndex = clickedIdx
    } else {
      if (selectedMethod.value?.name === m.name && selectedMethods.value.size <= 1) {
        selectedMethod.value = null
        selectedMethods.value = new Set()
      } else {
        selectedMethod.value = m
        selectedMethods.value = new Set([m.name])
      }
      lastClickedMethodIndex = clickedIdx
    }
  }

  function sortBy(key: string) {
    if (sortKey.value === key) sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
    else { sortKey.value = key; sortDir.value = 'asc' }
  }

  function resetWeights() {
    for (const wd of dd.weightDefs) {
      lw[wd.name] = wd.defaultValue
    }
  }

  function simSortByFn(key: string) {
    if (simSortKey.value === key)
      simSortDir.value = simSortDir.value === 'asc' ? 'desc' : 'asc'
    else {
      simSortKey.value = key
      simSortDir.value = key === 'name' || key === 'origRank' || key === 'simRank' ? 'asc' : 'desc'
    }
  }

  function setGraphMode(id: string) {
    graphMode.value = id
  }

  function setTab(id: string) {
    activeTab.value = id
    selectedMethod.value = null
    selectedMethods.value = new Set()
  }

  function setBadgeFilter(filter: string | null) {
    badgeFilter.value = badgeFilter.value === filter ? null : filter
  }

  function navigateTest(dir: 'up' | 'down') {
    const list = filteredTests.value
    if (!list.length) return
    if (dir === 'down') {
      focusedTestIndex.value = Math.min(focusedTestIndex.value + 1, list.length - 1)
    } else {
      focusedTestIndex.value = Math.max(focusedTestIndex.value - 1, 0)
    }
  }

  function activateFocusedTest() {
    const list = filteredTests.value
    const idx = focusedTestIndex.value
    if (idx >= 0 && idx < list.length) {
      selectTest(list[idx], null)
    }
  }

  function clearSelection() {
    selectedTest.value = null
    selectedTests.value = new Set()
    selectedMethod.value = null
    selectedMethods.value = new Set()
    focusedTestIndex.value = -1
  }

  function navigateToTestFromCov(testName: string) {
    const t = tests.find(x => x.name === testName)
    if (!t) return
    activeTab.value = 'tests'
    selectedTest.value = t
    selectedTests.value = new Set([t.name])
    selectedMethod.value = null
    selectedMethods.value = new Set()
  }

  function exportCsvFn() {
    const csv = exportTestsCsv(filteredTests.value)
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `test-order-${dd.project.name || 'export'}.csv`
    a.click()
    URL.revokeObjectURL(url)
  }

  return {
    dd, parseError, hasData, hasCoverage, hasML,
    selectedTest, selectedTests, activeTab, lw, searchQ, sortKey, sortDir,
    graphMode, covSelectedClass, selectedMethod, selectedMethods,
    showChangedPanel, simSortKey, simSortDir, badgeFilter,
    focusedTestIndex, covSearchQ,
    scoreModalOpen, scoreModalTitle, scoreModalBody,
    TABS, SIDEBAR_SORT_COLS, GMODES,
    tests, runs, changedSet, hasMethodData,
    filteredTests, selectedTestObjects, latestRun, avgApfd,
    fastestTest, slowestTest, totalNodes, scoreComps, testOutcomes,
    simResults, covPackages, covAvgTests, covPercent, selectionCoverage,
    origSCB, simSetCoverBonuses, runDiff, simApfd, filteredCovClasses,
    selectTest, drillDown, selectMethod,
    sortBy, simSortBy: simSortByFn, resetWeights, setGraphMode, setTab, setBadgeFilter,
    navigateTest, activateFocusedTest, clearSelection, navigateToTestFromCov,
    getScoreBreakdown, openScoreModal, closeScoreModal,
    exportCsv: exportCsvFn,
    serverConnected, optimizing, optimizeError, optimizeResult,
    optimizeWeights: optimizeWeightsFn,
  }
}
