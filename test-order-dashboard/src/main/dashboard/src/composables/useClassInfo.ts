import { ref, type Ref } from 'vue'

export interface ClassInfo {
  className: string
  javadoc?: string
  methods?: string[]
}

// Module-level cache populated by preloadClassInfo()
const cache = new Map<string, ClassInfo | null>()
let preloadDone = false
let apiAvailable = true  // set to false after first network failure; suppresses further requests

/** Call once at app startup with all known class names to bulk-fetch and cache. */
export async function preloadClassInfo(classNames: string[]): Promise<void> {
  if (preloadDone || classNames.length === 0) return
  preloadDone = true
  try {
    const res = await fetch('/api/classinfo/bulk', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(classNames),
    })
    if (!res.ok) return
    const data = await res.json() as Record<string, ClassInfo | null>
    for (const [name, info] of Object.entries(data)) {
      cache.set(name, info)
    }
  } catch {
    apiAvailable = false  // server not present; suppress all future API requests
  }
}

export interface ClassHoverState {
  visible: Ref<boolean>
  info: Ref<ClassInfo | null>
  x: Ref<number>
  y: Ref<number>
  show: (className: string, event: MouseEvent) => void
  hide: () => void
  move: (event: MouseEvent) => void
}

export function useClassHover(): ClassHoverState {
  const visible = ref(false)
  const info = ref<ClassInfo | null>(null)
  const x = ref(0)
  const y = ref(0)
  let hideTimer: ReturnType<typeof setTimeout> | null = null

  function show(className: string, event: MouseEvent) {
    if (hideTimer) { clearTimeout(hideTimer); hideTimer = null }

    x.value = event.clientX + 14
    y.value = event.clientY - 10

    const cached = cache.get(className)
    if (cached !== undefined) {
      // Instant: serve from preloaded cache
      info.value = cached
      if (cached) visible.value = true
      return
    }

    // Fallback: fetch individually (standalone HTML without server, or class not preloaded)
    if (!apiAvailable) return
    info.value = null
    fetch(`/api/classinfo?class=${encodeURIComponent(className)}`)
      .then(r => r.ok ? r.json() : null)
      .then((data: ClassInfo | null) => {
        cache.set(className, data)
        info.value = data
        if (data) visible.value = true
      })
      .catch(() => {})
  }

  function hide() {
    hideTimer = setTimeout(() => {
      visible.value = false
    }, 120)
  }

  function move(event: MouseEvent) {
    x.value = event.clientX + 14
    y.value = event.clientY - 10
  }

  return { visible, info, x, y, show, hide, move }
}
