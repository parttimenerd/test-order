import { ref, computed, reactive } from 'vue'
import type {
  DashboardData, TestEntry, MethodEntry, RunRecord,
  SortColumn, GraphMode, TabDef, ScoreComponent, TestRunOutcome,
  SimResult, SelectionCoverage, CoverageClass,
} from '../types'
import { sn, computeSetCoverBonuses, computeScore } from '../utils'

export interface DashboardState {
  dd: DashboardData
  parseError: string | null
  hasData: boolean
  hasCoverage: boolean

  // Reactive UI state
  selectedTest: ReturnType<typeof ref<TestEntry | null>>
  selectedTests: ReturnType<typeof ref<Set<string>>>
  activeTab: ReturnType<typeof ref<string>>
  lw: Record<string, number>
  searchQ: ReturnType<typeof ref<string>>
  sortKey: ReturnType<typeof ref<string>>
  sortDir: ReturnType<typeof ref<string>>
  graphMode: ReturnType<typeof ref<string>>
  covSelectedClass: ReturnType<typeof ref<CoverageClass | null>>
  selectedMethod: ReturnType<typeof ref<MethodEntry | null>>
  selectedMethods: ReturnType<typeof ref<Set<string>>>
  showChangedPanel: ReturnType<typeof ref<boolean>>
  simSortKey: ReturnType<typeof ref<string>>
  simSortDir: ReturnType<typeof ref<string>>

  // Constants
  TABS: ReturnType<typeof computed<TabDef[]>>
  SIDEBAR_SORT_COLS: SortColumn[]
  GMODES: GraphMode[]

  // Derived
  tests: TestEntry[]
  runs: RunRecord[]
  changedSet: Set<string>
  hasMethodData: ReturnType<typeof computed<boolean>>
  filteredTests: ReturnType<typeof computed<TestEntry[]>>
  selectedTestObjects: ReturnType<typeof computed<TestEntry[]>>
  latestRun: ReturnType<typeof computed<RunRecord | null>>
  avgApfd: ReturnType<typeof computed<number | null>>
  fastestTest: ReturnType<typeof computed<TestEntry | null>>
  slowestTest: ReturnType<typeof computed<TestEntry | null>>
  totalNodes: ReturnType<typeof computed<number>>
  scoreComps: ReturnType<typeof computed<ScoreComponent[]>>
  testOutcomes: ReturnType<typeof computed<TestRunOutcome[]>>
  simResults: ReturnType<typeof computed<SimResult[]>>
  covPackages: ReturnType<typeof computed<string[]>>
  covAvgTests: ReturnType<typeof computed<string>>
  covPercent: ReturnType<typeof computed<number>>
  selectionCoverage: ReturnType<typeof computed<SelectionCoverage | null>>
  origSCB: Record<string, number>

  // Actions
  selectTest: (t: TestEntry, event: MouseEvent | null) => void
  drillDown: (t: TestEntry) => void
  selectMethod: (m: MethodEntry, event: MouseEvent | null) => void
  sortBy: (key: string) => void
  simSortBy: (key: string) => void
  resetWeights: () => void
  setGraphMode: (id: string) => void
  setTab: (id: string) => void
}

export function useDashboard(dd: DashboardData, parseError: string | null): DashboardState {
  const hasData = !!(dd.tests && dd.tests.length >= 0 && dd.project)
  const hasCoverage = !!(dd.coverage && dd.coverage.classes && dd.coverage.classes.length)

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
  let lastClickedTestIndex = -1
  let lastClickedMethodIndex = -1

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
  const TABS = computed<TabDef[]>(() => [
    { id: 'tests', label: 'Tests' },
    { id: 'analytics', label: 'Analytics' },
    { id: 'weights', label: 'Weights' },
  ])

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
    arr.sort((a, b) => {
      let av: string | number = (a as Record<string, unknown>)[sortKey.value] as string | number
      let bv: string | number = (b as Record<string, unknown>)[sortKey.value] as string | number
      if (sortKey.value === 'name') { av = sn(a.name); bv = sn(b.name) }
      if (sortKey.value === 'duration') { av = av < 0 ? 1e15 : av; bv = bv < 0 ? 1e15 : bv }
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
    const scored = tests.map(t => ({ ...t, simScore: computeScore(t, lw as DashboardData['weights'], bonuses) }))
    const sorted = [...scored].sort((a, b) => b.simScore - a.simScore)
    const rankMap: Record<string, number> = {}
    sorted.forEach((t, i) => (rankMap[t.name] = i + 1))
    const rows: SimResult[] = tests.map(t => ({
      name: t.name,
      origRank: t.rank,
      simRank: rankMap[t.name],
      delta: rankMap[t.name] - t.rank,
      origScore: t.score,
      simScore: scored.find(s => s.name === t.name)!.simScore,
    }))
    const sk = simSortKey.value
    const sd = simSortDir.value
    rows.sort((a, b) => {
      const av = sk === 'name' ? sn(a.name).toLowerCase() : (a as Record<string, unknown>)[sk] as number
      const bv = sk === 'name' ? sn(b.name).toLowerCase() : (b as Record<string, unknown>)[sk] as number
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
    Object.assign(lw, dd.weights)
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

  return {
    dd, parseError, hasData, hasCoverage,
    selectedTest, selectedTests, activeTab, lw, searchQ, sortKey, sortDir,
    graphMode, covSelectedClass, selectedMethod, selectedMethods,
    showChangedPanel, simSortKey, simSortDir,
    TABS, SIDEBAR_SORT_COLS, GMODES,
    tests, runs, changedSet, hasMethodData,
    filteredTests, selectedTestObjects, latestRun, avgApfd,
    fastestTest, slowestTest, totalNodes, scoreComps, testOutcomes,
    simResults, covPackages, covAvgTests, covPercent, selectionCoverage,
    origSCB,
    selectTest, drillDown, selectMethod,
    sortBy, simSortBy: simSortByFn, resetWeights, setGraphMode, setTab,
  }
}
