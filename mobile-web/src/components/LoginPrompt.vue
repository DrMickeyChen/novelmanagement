<script setup>
/**
 * 未登录引导条（移动端）—— 书评区 / 章评区共用。
 *
 * ★ 移植自 `reader-web/src/components/LoginPrompt.vue`。**四条规矩原样保留**，
 *   因为它们说的是"怎么对待游客"，和屏幕大小无关：
 *
 *   ① **不隐藏功能入口，把入口换成说明 + 「去登录」**。
 *      直接不渲染的话，游客根本不知道这里本来有什么，
 *      也就不知道登录能换来什么 —— 他只会觉得「这个页面是空的」。
 *   ② **引导语说「登录后能做什么」，不说「请先登录」**。
 *      后者只描述了我们的要求，没给用户任何理由。
 *   ③ **不自动跳转**。跳转永远由用户点这个按钮触发。
 *      读到一半被弹去登录页是最糟的一种体验。
 *   ④ **登录后回到原地**（带 redirect）。不带的话用户登录完落在首页，
 *      想做的事（发这条评论）得自己重新找回来 —— 那多半就不会再做了。
 *
 * ★★ 为什么在移动端**仍然要单独一个组件**（而不是在 CommentSection 里写两行）：
 *   评论组件在**详情页和阅读器**各挂一次（书评 / 章评），
 *   引导条就会出现在两处。写两份的话两处迟早长得不一样，
 *   而"长得不一样"不报错。
 */
defineProps({
  /** 引导语。★ 写「登录后可以发表书评」，别写「请先登录」 */
  text: {
    type: String,
    required: true,
  },
})

/**
 * 当前页面的完整地址（路径 + query），用作登录后的回跳目标。
 *
 * ★★ **在点击时算，不在 setup 时算** —— 这一条和桌面端不同，是刻意的：
 *   桌面端用 `route.fullPath`（响应式，页面参数一变它就变）。
 *   uni-app 里没有可订阅的当前路由对象，只能问 `getCurrentPages()`，
 *   而它给的是**调用那一刻**的页面栈。
 *   在 setup 时算的话，阅读器里翻到第 3 章之后再点登录，
 *   回跳目标还是**组件挂载时那一章** —— 登录完回到第 1 章，
 *   而用户以为自己一直在第 3 章。
 *   （这个组件在阅读器里不会随章节变化重新挂载，所以这个差别是真的会发生。）
 *
 * ★ query 要**重新编码**：`cur.options` 里的值是已经解码过的，
 *   直接拼进 URL 会造出 `?id=1 2` 这种带空格的地址。
 */
function currentFullPath() {
  const pages = getCurrentPages()
  const cur = pages[pages.length - 1]
  if (!cur) return ''
  const query = Object.entries(cur.options || {})
    .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
    .join('&')
  return `/${cur.route}${query ? `?${query}` : ''}`
}

/**
 * 去登录。
 *
 * ★★ `encodeURIComponent` 不能省：回跳地址自己带 `?` `&` `=`，
 *   不编码就会和 `/pages/login/login?redirect=` 自己的 query 串在一起，
 *   登录页只收到 `redirect=/pages/novel/novel`，后面那半截变成
 *   登录页自己的查询参数 —— 表现是「登录完跳回一个没有筛选条件的页面」，
 *   **不报错**。
 *   （登录页那边的开放重定向防护照常生效，这里只是别把地址拼坏。）
 */
function goLogin() {
  const target = currentFullPath()
  const url = target
    ? `/pages/login/login?redirect=${encodeURIComponent(target)}`
    : '/pages/login/login'
  uni.navigateTo({ url })
}
</script>

<template>
  <view class="prompt">
    <text class="prompt__text">{{ text }}</text>
    <view class="prompt__btn" hover-class="prompt__btn--hover" @click="goLogin">
      <text class="prompt__btn-text">去登录</text>
    </view>
  </view>
</template>

<style scoped>
.prompt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20rpx 24rpx;
  background-color: #f0f4ff;
  border-radius: 12rpx;
}

.prompt__text {
  flex: 1;
  min-width: 0;
  font-size: 26rpx;
  color: #5a6472;
}

.prompt__btn {
  flex-shrink: 0;
  margin-left: 16rpx;
  padding: 8rpx 24rpx;
  background-color: #2f6fed;
  border-radius: 999rpx;
}

.prompt__btn--hover {
  opacity: 0.85;
}

.prompt__btn-text {
  font-size: 26rpx;
  color: #ffffff;
}
</style>
