<script setup>
import { computed, ref, watch } from 'vue'
import { assetUrl } from '@/utils/asset'

const props = defineProps({
  cover: { type: String, default: '' },
  title: { type: String, default: '' },
})
const src = computed(() => assetUrl(props.cover))
const broken = ref(false)
watch(src, () => { broken.value = false })
</script>

<template>
  <view class="novel-cover">
    <image v-if="src && !broken" class="cover-image" :src="src" mode="aspectFill" @error="broken = true" />
    <text v-else>{{ (title || '书').charAt(0) }}</text>
  </view>
</template>

<style scoped>
.novel-cover {
  width: 120rpx;
  height: 160rpx;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  border-radius: 8rpx;
  background: #eef2f6;
  color: #5a6472;
  font-size: 48rpx;
}
.cover-image { width: 100%; height: 100%; }
</style>
