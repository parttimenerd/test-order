import { Chart, registerables, type ChartConfiguration } from 'chart.js'

// Register all Chart.js components once
if (Array.isArray(registerables)) {
  Chart.register(...registerables)
}

const CI: Record<string, Chart> = {}

export function mkChart(id: string, cfg: ChartConfiguration): Chart | null {
  if (CI[id]) {
    try { CI[id].destroy() } catch { /* ignore */ }
    delete CI[id]
  }
  const el = document.getElementById(id) as HTMLCanvasElement | null
  if (!el) return null
  try {
    CI[id] = new Chart(el, cfg)
  } catch (e) {
    console.error(`[dashboard] Chart '${id}' init failed:`, e)
    const ctx = el.getContext('2d')
    if (ctx) {
      ctx.fillStyle = '#64748b'
      ctx.font = '11px sans-serif'
      ctx.fillText('Chart error — see console', 8, 20)
    }
    return null
  }
  return CI[id]
}

export function destroyCharts(...ids: string[]) {
  ids.forEach(id => {
    if (CI[id]) {
      try { CI[id].destroy() } catch { /* ignore */ }
      delete CI[id]
    }
  })
}

export function getChartInstance(id: string): Chart | undefined {
  return CI[id]
}

/** Default chart options with dark theme */
export function chartOpts(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return Object.assign(
    {
      responsive: true,
      maintainAspectRatio: false,
      animation: { duration: 150 },
      plugins: {
        legend: { display: false },
        tooltip: {
          backgroundColor: '#1e293b',
          borderColor: '#475569',
          borderWidth: 1,
          titleColor: '#e2e8f0',
          bodyColor: '#94a3b8',
          padding: 8,
        },
      },
      scales: {
        x: { ticks: { color: '#475569', font: { size: 10 } }, grid: { color: 'rgba(71,85,105,.25)' } },
        y: { ticks: { color: '#475569', font: { size: 10 } }, grid: { color: 'rgba(71,85,105,.25)' } },
      },
    },
    extra,
  )
}

/** Timeline chart IDs for crosshair sync */
export const TL_IDS = ['tl-apfd', 'tl-fail', 'tl-ffp', 'tl-cnt'] as const

// Register timeline crosshair sync plugin
Chart.register({
  id: 'tlSync',
  afterEvent(chart: Chart, args: { event: { type: string } }) {
    if (!TL_IDS.includes(chart.canvas?.id as typeof TL_IDS[number])) return
    const ev = args.event
    if (ev.type === 'mousemove') {
      const pts = (chart as unknown as { tooltip?: { dataPoints?: { dataIndex: number }[] } }).tooltip?.dataPoints
      if (!pts?.length) return
      const idx = pts[0].dataIndex
      TL_IDS.forEach(id => {
        const c = CI[id]
        if (!c || c === chart) return
        const ae = c.data.datasets
          .map((_, di) => ({ datasetIndex: di, index: idx }))
          .filter(p => p.index < (c.data.datasets[p.datasetIndex]?.data?.length || 0))
        ;(c as unknown as { tooltip: { setActiveElements(a: unknown[], b: unknown): void } }).tooltip.setActiveElements(ae, { x: 0, y: 0 })
        c.update('none')
      })
    } else if (ev.type === 'mouseout') {
      TL_IDS.forEach(id => {
        const c = CI[id]
        if (!c || c === chart) return
        ;(c as unknown as { tooltip: { setActiveElements(a: unknown[], b: unknown): void } }).tooltip.setActiveElements([], { x: 0, y: 0 })
        c.update('none')
      })
    }
  },
})
