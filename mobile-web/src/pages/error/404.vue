<script setup>
/**
 * 404 页 —— 兜住"跳到一个不存在的页面"。
 *
 * ------------------------------------------------------------
 * ★★ 移动端的 404 和 Web 完全是两件事
 * ------------------------------------------------------------
 * 桌面上，用户敲一个不存在的地址，是浏览器的 404 —— 一排浏览器原生页面，
 * 而且 vue-router 有 `path: '/:pathMatch(.*)*'` 这种 catch-all 兜底。
 *
 * uni 没有这个东西：`pages.json` 是张**静态路由表**，没有 catch-all 语法。
 * `uni.navigateTo({url: '/pages/不存在/x'})` 的默认行为是把用户
 * **丢到首页**（`pages` 数组里的第一项）—— 于是"页面不存在"这件事
 * 在界面上**完全看不出来**，用户只会觉得"我点了个东西，跳回首页了"。
 *
 * 所以必须显式处理：由 `App.vue` 的 `onPageNotFound` 生命周期把它接到这里。
 *
 * ★ 这个页面**没有地址参数**（uni 不给 onPageNotFound 回传原始路径）——
 *   所以文案里不能写"你要找的 xxx 不存在"，只能说"这个页面不存在"。
 *   写一个具体的路径上去，等于编了一句我们没有的信息。
 */
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'

/**
 * ★ 从详情页/阅读器跳过来时的目标。
 *
 * `onPageNotFound` 有一个 `query` 字段可以带上原目标 —— 但那是
 * `uni.navigateTo` 发起的跳转才有的，用户手动改地址栏（H5）时没有。
 * 有就显示一个「返回上一页」，没有就只有「回首页」。
 */
const hasBack = ref(false)

onLoad(() => {
  // ★ getCurrentPages().length > 1 说明页面栈里还有别的页面，能返回
  const pages = getCurrentPages()
  hasBack.value = Array.isArray(pages) && pages.length > 1
})

/**
 * ★★ 回首页必须用 `reLaunch` 或 `switchTab`，不能用 `navigateTo` ——
 *    首页是 tabBar 页面，`navigateTo` 到 tabBar 页会**静默失败**。
 *    这里用 `switchTab`（它本来就是为了切 tab 页设计的）。
 */
function goHome() {
  uni.switchTab({ url: '/pages/index/index' })
}

/** 返回上一页。★ 页面栈里没有上一页时（直接进的 404）按钮不渲染 */
function goBack() {
  uni.navigateBack({
    // ★ 栈里只有一页时 navigateBack 会失败（没得退），兜到首页
    fail: () => uni.switchTab({ url: '/pages/index/index' }),
  })
}
</script>

<template>
  <view class="not-found">
    <text class="code">404</text>
    <text class="title">这个页面不存在</text>
    <text class="sub">它可能已经被删掉了，或者你手上的链接是旧的。</text>

    <view class="ops">
      <text class="btn btn--primary" @click="goHome">回首页</text>
      <text v-if="hasBack" class="btn" @click="goBack">返回上一页</text>
    </view>
  </view>
</template>

<style scoped>
.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-height: 100vh;
  padding: 180rpx 48rpx 0;
  background: #f5f6f8;
}

.code {
  font-size: 120rpx;
  font-weight: 700;
  line-height: 1;
  color: #d8dfe9;
}

.title {
  margin-top: 24rpx;
  font-size: 34rpx;
  font-weight: 600;
  color: #1f2733;
}

.sub {
  margin-top: 16rpx;
  font-size: 26rpx;
  color: #9aa3b0;
  text-align: center;
  line-height: 1.6;
}

.ops {
  display: flex;
  margin-top: 60rpx;
}

.btn {
  padding: 18rpx 48rpx;
  margin: 0 12rpx;
  border-radius: 999rpx;
  font-size: 28rpx;
  color: #5a6472;
  background: #fff;
}

.btn--primary {
  color: #fff;
  background: #2f6fed;
}
</style>
