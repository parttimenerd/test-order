/** Typed interfaces matching the JSON data model from DashboardGenerator.buildData() */

export interface ProjectInfo {
  name: string
  generated: string
  pluginVersion: string
  stateFilePath: string
  indexFilePath: string
}

export interface WeightDef {
  name: string
  defaultValue: number
  min: number
  max: number
}

export interface ScoringWeights {
  newTest: number
  changedTest: number
  maxFailure: number
  speed: number
  speedPenalty: number
  depOverlap: number
  changeComplexity: number
  staticFieldBonus: number
  coverageBonus: number
  killRateBonus: number
}

export interface StateConfig {
  failureDecay: number
  durationAlpha: number
  failurePruneThreshold: number
  emaVarianceThreshold: number
  historyMaxRuns: number
}

export interface MethodEntry {
  name: string
  deps: string[]
  depCount: number
  memberDeps: string[] | null
}

export interface TestEntry {
  name: string
  rank: number
  score: number
  depOverlap: number
  depTotal: number
  failScore: number
  speedRatio: number
  complexityOverlap: number
  isNew: boolean
  isChanged: boolean
  isFast: boolean
  isSlow: boolean
  hasStaticFieldOverlap: boolean
  duration: number
  durationVariance: number
  deps: string[]
  memberDeps: string[] | null
  methods: MethodEntry[] | null
  mlPFail: number | null
  killRate: number | null
  weightedDepOverlap: number | null
  // multi-module fields (null/0 for single-module projects)
  module: string | null
  crossModuleDepCount: number
  dominantDepModule: string | null
  suspectHomeModule: boolean
}

export interface TestOutcome {
  testClass: string
  score: number
  depOverlap: number
  depTotal: number
  failScore: number
  speedRatio: number
  complexityOverlap: number
  isNew: boolean
  isChanged: boolean
  isFast: boolean
  isSlow: boolean
  failed: boolean
  hasStaticFieldOverlap: boolean
}

export interface RunRecord {
  timestamp: number
  totalTests: number
  totalFailures: number
  firstFailurePosition: number
  apfd: number
  outcomes: TestOutcome[]
}

export interface MemberCoverage {
  name: string
  testCount: number
  tests: string[]
}

export interface CoverageClass {
  name: string
  package: string
  testCount: number
  tests: string[]
  members: MemberCoverage[] | null
  totalMembers: number    // 0 when instrumentation is class-level only
  coveredMembers: number  // number of exercised members
}

export interface CoverageData {
  totalSourceClasses: number
  classes: CoverageClass[]
}

export interface MLHealthEntry {
  testClass: string
  status: string
  failRate: number
  recentTrend: string
  runsAnalyzed: number
}

export interface MLSummary {
  healthy: number
  degrading: number
  flaky: number
  failing: number
}

export interface MLData {
  enabled: boolean
  runsAnalyzed: number
  summary: MLSummary
  tests: MLHealthEntry[]
  hasPredictions: boolean
}

export interface MutationEntry {
  testClass: string
  killRate: number
}

export interface MutationSummary {
  high: number
  medium: number
  low: number
  none: number
}

export interface MutationData {
  enabled: boolean
  summary: MutationSummary
  tests: MutationEntry[]
  totalMutants?: number
  totalKilled?: number
  overallKillRate?: number
}

export type MemberChangeKind = 'ADDED' | 'REMOVED' | 'SIGNATURE' | 'BODY'

export interface StaticAnalysisMember {
  name: string
  kind: MemberChangeKind
  isStaticField: boolean
}

export interface StaticAnalysisFileSummary {
  path: string
  added: number
  removed: number
  signature: number
  body: number
  totalLines: number
}

export interface StaticAnalysisModuleSummary {
  filesChanged: number
  classesChanged: number
  membersChanged: number
  added: number
  removed: number
  signature: number
  body: number
  staticFieldChanges: number
  totalChangedLines: number
}

export interface StaticAnalysisClass {
  name: string
  depth: number | null
  parent: string | null
  hasTypeChange?: boolean
  members?: StaticAnalysisMember[]   // present for seeds (depth 0); empty/absent otherwise
  tests?: string[]                    // tests that cover this class (from coverage)
}

export interface StaticAnalysisModule {
  module: string
  count: number
  classes: StaticAnalysisClass[]
  degraded?: boolean
  seedSize?: number
  expandedSize?: number
  summary?: StaticAnalysisModuleSummary
  fileSummaries?: StaticAnalysisFileSummary[]
}

export interface StaticAnalysisData {
  enabled: boolean
  totalUncertainClasses: number
  modules: StaticAnalysisModule[]
}

export interface DashboardData {
  project: ProjectInfo
  weights: ScoringWeights
  weightDefs: WeightDef[]
  config: StateConfig
  medianDuration: number
  changedClasses: string[]
  changedTestClasses: string[]
  tests: TestEntry[]
  runs: RunRecord[]
  coverage: CoverageData | null
  ml: MLData | null
  mutation: MutationData | null
  staticAnalysis: StaticAnalysisData | null
  /** class→module map for cross-module dep coloring; absent for single-module projects */
  classToModule?: Record<string, string>
  /** shared dictionary for memberDeps compression; indices in TestEntry/MethodEntry.memberDeps */
  memberDict?: string[]
  /** shared dictionary for deps compression; indices in TestEntry/MethodEntry.deps */
  depDict?: string[]
}

/** Sort column definition for sidebar */
export interface SortColumn {
  key: string
  label: string
  tip?: string
}

/** Graph mode option */
export interface GraphMode {
  id: string
  label: string
}

/** Tab definition */
export interface TabDef {
  id: string
  label: string
}

/** Score component for breakdown display */
export interface ScoreComponent {
  label: string
  value: number
  color: string
  explanation: string
}

/** Per-test run outcome for display */
export interface TestRunOutcome {
  ts: number
  present: boolean
  failed: boolean
  outcome: TestOutcome | null
}

/** Simulation result row for weights explorer */
export interface SimResult {
  name: string
  origRank: number
  simRank: number
  delta: number
  origScore: number
  simScore: number
}

/** Selection coverage info */
export interface SelectionCoverage {
  covered: number
  total: number
  percent: number
  sources: Set<string>
}

/** Run-to-run diff entry */
export interface RunDiffEntry {
  name: string
  prevRank: number | null
  currRank: number | null
  rankDelta: number
  prevFailed: boolean
  currFailed: boolean
  status: 'improved' | 'regressed' | 'recovered' | 'newly-failed' | 'new' | 'absent' | 'unchanged'
}
