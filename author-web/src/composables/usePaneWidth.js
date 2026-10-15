import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

// Persist user preference; constrain the displayed width to the current container.
export function usePaneWidth({ key, initial, min, max, reserve, container, direction = 1 }) {
  let saved = initial
  try { const value = Number(localStorage.getItem(key)); if (value >= min && value <= max) saved = value } catch {}
  const preferred = ref(saved), available = ref(window.innerWidth), dragging = ref(false)
  const maximum = computed(() => Math.max(min, Math.min(max, available.value - (typeof reserve === 'function' ? reserve(available.value) : reserve))))
  const width = computed(() => Math.max(min, Math.min(preferred.value, maximum.value)))
  let observer, cleanupDrag
  const persist = () => { try { localStorage.setItem(key, String(preferred.value)) } catch {} }
  const setWidth = value => { preferred.value = Math.max(min, Math.min(value, maximum.value)) }
  function nudge(delta) { setWidth(width.value + direction * delta); persist() }
  function reset() { preferred.value = initial; persist() }
  function start(event) {
    if (event.button !== 0 || event.isPrimary === false) return
    cleanupDrag?.()
    event.preventDefault()
    const pointerId = event.pointerId, startX = event.clientX, startWidth = width.value
    const target = event.currentTarget
    const previousCursor = document.body.style.cursor, previousSelection = document.body.style.userSelect
    dragging.value = true
    document.body.style.cursor = 'col-resize'; document.body.style.userSelect = 'none'
    const move = e => { if (e.pointerId === pointerId) setWidth(startWidth + direction * (e.clientX - startX)) }
    const finish = e => { if (e?.pointerId != null && e.pointerId !== pointerId) return; cleanupDrag?.() }
    cleanupDrag = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', finish)
      window.removeEventListener('pointercancel', finish)
      window.removeEventListener('blur', finish)
      try { if (target.hasPointerCapture(pointerId)) target.releasePointerCapture(pointerId) } catch {}
      document.body.style.cursor = previousCursor; document.body.style.userSelect = previousSelection
      dragging.value = false; cleanupDrag = null; persist()
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', finish)
    window.addEventListener('pointercancel', finish)
    window.addEventListener('blur', finish)
    try { target.setPointerCapture(pointerId) } catch {}
  }
  onMounted(() => {
    const element = container.value?.$el ?? container.value
    if (!element) return
    const measure = () => { const size = element.getBoundingClientRect().width; if (size > 0) available.value = size }
    measure(); observer = new ResizeObserver(measure); observer.observe(element)
  })
  onBeforeUnmount(() => { cleanupDrag?.(); observer?.disconnect() })
  return { width, maximum, dragging, start, nudge, reset }
}
