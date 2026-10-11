<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  cover: { type: String, default: '' },
  title: { type: String, default: '' },
})
const broken = ref(false)
watch(() => props.cover, () => { broken.value = false })
</script>

<template>
  <div class="novel-cover">
    <img v-if="cover && !broken" :src="cover" :alt="`${title}封面`" @error="broken = true" />
    <span v-else>{{ (title || '书').charAt(0) }}</span>
  </div>
</template>

<style scoped>
.novel-cover {
  width: 72px;
  height: 96px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 6px;
  background: #eef2f6;
  color: #606266;
  font-size: 28px;
}
img { width: 100%; height: 100%; object-fit: cover; }
</style>
