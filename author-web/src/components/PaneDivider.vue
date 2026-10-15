<script setup>
defineProps({ label: String, width: Number, min: Number, max: Number, dragging: Boolean })
const emit = defineEmits(['start', 'nudge', 'reset'])
function keydown(event) {
  if (!['ArrowLeft', 'ArrowRight', 'Home'].includes(event.key)) return
  event.preventDefault()
  if (event.key === 'Home') emit('reset')
  else emit('nudge', (event.key === 'ArrowRight' ? 1 : -1) * (event.shiftKey ? 5 : 20))
}
</script>

<template>
  <div class="pane-divider" :class="{ dragging }" role="separator" aria-orientation="vertical" :aria-label="label" :aria-valuenow="Math.round(width)" :aria-valuemin="min" :aria-valuemax="Math.round(max)" tabindex="0" :title="`${label}：拖动调整，双击恢复默认宽度`" @pointerdown="emit('start', $event)" @keydown="keydown" @dblclick="emit('reset')"><span /></div>
</template>

<style scoped>
.pane-divider { flex:0 0 8px; width:8px; align-self:stretch; display:flex; justify-content:center; align-items:center; cursor:col-resize; touch-action:none; user-select:none; background:transparent; transition:background .15s }
.pane-divider span { height:34px;width:3px;border-radius:3px;background:#cbd3df }
.pane-divider:hover,.pane-divider.dragging { background:#e6f0ff }
.pane-divider:hover span,.pane-divider.dragging span { background:#409eff }
.pane-divider:focus-visible { outline:2px solid #409eff;outline-offset:-2px }
</style>
