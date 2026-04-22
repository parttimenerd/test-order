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
}

export interface TestOutcome {
  testClass: string
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
}

export interface CoverageData {
  totalSourceClasses: number
  classes: CoverageClass[]
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
}

/** Sort column definition for sidebar */
export interface SortColumn {
  key: string
  label: string
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
