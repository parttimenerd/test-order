import { isClient, useLocalStorage, usePreferredDark, useToggle } from '@vueuse/core'
import { computed, watch } from 'vue'

const preferredDark = usePreferredDark()
const store = useLocalStorage('slidev-color-schema', 'auto')

export const isDark = computed<boolean>({
  get() {
    return store.value === 'auto' ? preferredDark.value : store.value === 'dark'
  },
  set(v: boolean) {
    store.value = v === preferredDark.value ? 'auto' : v ? 'dark' : 'light'
  },
})

export const toggleDark = useToggle(isDark)

// Mirror the html.dark class so CSS selectors work — same as Slidev's own dark.ts watcher.
if (isClient) {
  watch(isDark, (v) => {
    const html = document.documentElement
    html.classList.toggle('dark', v)
    html.classList.toggle('light', !v)
  }, { immediate: true })
}
