<script setup>
/**
 * 列表的三种「非正常」状态 —— 加载中 / 本来就没有 / 暂时拿不到。
 *
 * ============================================================
 * ★★ 这个组件存在的理由：让「说谎」在结构上不可能
 * ============================================================
 *
 * 起因是一次实测（M5）：停掉后端，搜索页显示的是【「没有找到相关的作品」】
 * —— 一个彻头彻尾的谎话，而它长得和「这个词真的搜不到东西」一模一样，
 * **用户没有任何办法分辨**。根源是 `catch` 把失败写成了「空」。
 *
 * 修法**不是**在每个页面里写 `v-if="!failed"` —— 页面多了迟早有一个忘掉
 * 那半个条件，而「忘掉」不会有任何报错，只是那个页面又开始说谎。
 *
 * 所以这里把三个状态收进**一个组件**：页面只要把 `usePagedList` 给的
 * `isEmpty` / `failed` 原样传进来，就**没有机会**把失败渲染成空态 ——
 * 因为模板里根本没有"只判 list.length"的那条路可走。
 *
 * ★ 三个属性的互斥由 `usePagedList` 的构造保证（`isEmpty` 里含 `!failed`），
 *   这里只是按顺序渲染，不重复判断 —— 重复判断意味着两处规则可能不一致。
 *
 * ------------------------------------------------------------
 * ★ 加载中为什么也要在这里：首屏的「空窗期」
 * ------------------------------------------------------------
 * 首次进入时 `loading` 是 false（请求还没发）、list 是空的 ——
 * 少了 `loaded` 那一层，页面会先闪一下「暂无数据」再显示内容。
 * `usePagedList` 用 `loaded` 挡住了这件事，而这里的 `loading` 分支
 * 负责把那段"还不知道"的时间如实说成"加载中"。
 */
defineProps({
  /** 正在请求，且还没有任何结果可显示 */
  loading: { type: Boolean, default: false },
  /** 查完了但没有数据（`usePagedList` 的 isEmpty） */
  isEmpty: { type: Boolean, default: false },
  /** 查完了但失败了（`usePagedList` 的 failed） */
  failed: { type: Boolean, default: false },
  emptyText: { type: String, default: '这里还没有内容' },
  failedText: { type: String, default: '暂时加载失败' },
  loadingText: { type: String, default: '加载中…' },
})

const emit = defineEmits(['retry'])
</script>

<template>
  <!--
    ★ 三个分支的顺序：先"加载中"，再"空"，最后"失败"。
      `loading && !isEmpty && !failed` 这半个条件是必要的 ——
      追加模式下 `loading` 会在列表已经有内容时为 true（正在加载下一页），
      那种时候**不该**把整屏替换成"加载中"，底部的提示条才是对的。
  -->
  <view v-if="loading && !isEmpty && !failed" class="hint">
    <text class="hint__text">{{ loadingText }}</text>
  </view>

  <!--
    ★ 空态带一个插槽：有些页面在"本来就没有"之后还要给一条出路
      （书架空了 →「去逛逛」），而失败态给的是「重试」——
      两者的下一步动作不同，所以是两个不同的位置，不是一个通用的插槽。
      ★ 不传插槽时它就不渲染任何东西，对版面零影响。
  -->
  <view v-else-if="isEmpty" class="hint">
    <text class="hint__text">{{ emptyText }}</text>
    <view class="hint__action">
      <slot name="empty-action" />
    </view>
  </view>

  <!-- ★★ 失败态：和空态长得**明显不一样**，并且一定带重试 -->
  <view v-else-if="failed" class="hint">
    <text class="hint__text hint__text--failed">{{ failedText }}</text>
    <text class="hint__retry" @click="emit('retry')">重试</text>
  </view>
</template>

<style scoped>
.hint {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 80rpx 40rpx;
}

.hint__text {
  font-size: 26rpx;
  color: #9aa3b0;
}

/*
 * ★ 失败态的文案颜色和空态不同（暖色 vs 灰）——
 *   两者在**文字内容**上已经分开了，颜色是第二重区分。
 *   只靠颜色是不行的（色觉障碍用户分不出），但作为叠加的线索是合适的。
 */
.hint__text--failed {
  color: #d98b1f;
}

.hint__retry {
  margin-top: 24rpx;
  padding: 10rpx 40rpx;
  border: 2rpx solid #2f6fed;
  border-radius: 999rpx;
  font-size: 26rpx;
  color: #2f6fed;
}

/*
 * ★ 插槽外面包一层 view 而不是直接在 slot 上写样式：
 *   `<slot>` 本身不会被渲染成节点，样式挂在它身上是无效的 ——
 *   而这个"无效"不会报错，只会让那段间距消失。
 */
.hint__action {
  display: flex;
  justify-content: center;
  margin-top: 24rpx;
}
</style>
