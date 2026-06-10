import { ref, type Ref } from 'vue'

export interface TestHoverState {
  visible: Ref<boolean>
  testName: Ref<string | null>
  x: Ref<number>
  y: Ref<number>
  show: (name: string, event: MouseEvent) => void
  hide: () => void
  move: (event: MouseEvent) => void
}

export function useTestHover(): TestHoverState {
  const visible = ref(false)
  const testName = ref<string | null>(null)
  const x = ref(0)
  const y = ref(0)
  let hideTimer: ReturnType<typeof setTimeout> | null = null

  const CARD_W = 360
  const CARD_H = 180

  function clamp(event: MouseEvent) {
    const cx = Math.min(event.clientX + 14, window.innerWidth - CARD_W - 4)
    const cy = Math.min(event.clientY - 10, window.innerHeight - CARD_H - 4)
    return { cx, cy }
  }

  function show(name: string, event: MouseEvent) {
    if (hideTimer) { clearTimeout(hideTimer); hideTimer = null }
    testName.value = name
    const { cx, cy } = clamp(event)
    x.value = cx
    y.value = cy
    visible.value = true
  }

  function hide() {
    hideTimer = setTimeout(() => { visible.value = false }, 120)
  }

  function move(event: MouseEvent) {
    const { cx, cy } = clamp(event)
    x.value = cx
    y.value = cy
  }

  return { visible, testName, x, y, show, hide, move }
}
