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

  function show(name: string, event: MouseEvent) {
    if (hideTimer) { clearTimeout(hideTimer); hideTimer = null }
    testName.value = name
    x.value = event.clientX + 14
    y.value = event.clientY - 10
    visible.value = true
  }

  function hide() {
    hideTimer = setTimeout(() => { visible.value = false }, 120)
  }

  function move(event: MouseEvent) {
    x.value = event.clientX + 14
    y.value = event.clientY - 10
  }

  return { visible, testName, x, y, show, hide, move }
}
