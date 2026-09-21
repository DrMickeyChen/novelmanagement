<script setup>
/**
 * ECharts 的薄封装。用完就扔，图上的一切都从 `option` 进来。
 *
 * ★ 这个组件**不懂任何业务**：它不知道什么叫「作品」、什么叫「状态分布」。
 *   饼图/折线图都是在页面里拼好 option 传进来的。
 *   这样加一种新图不用动这个文件，只要在 utils/echarts.js 里补一行注册。
 *
 * ------------------------------------------------------------
 * ★★ 它存在的理由是「容器宽度为 0」和「内存泄漏」这两件事
 * ------------------------------------------------------------
 * 直接在页面里 `echarts.init(...)` 也能画出来，但下面这四件事
 * 每一件都要写对，而且写错的表现**都不是报错**：
 *
 *   ① 宽度为 0 时不能 init —— 会建出一个 0×0 的 canvas，
 *      之后容器变宽了它也不会自己重画，结果是**一块空白**。
 *   ② 窗口/容器尺寸变化要 resize —— 不 resize 的话图停在旧尺寸上，
 *      右边被裁掉一块，看起来像「数据画到一半没了」。
 *   ③ 卸载要 dispose —— 不 dispose 会累积 canvas 和 window 上的监听，
 *      表现是「来回切几次页面之后越来越卡」。
 *   ④ option 变化要用 notMerge 重设 —— 见下面 render()。
 *
 * 四条都封装在这里，页面里就不用各自记得一遍。
 */
import { ref, shallowRef, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import echarts from '@/utils/echarts'

const props = defineProps({
  /**
   * ECharts 的 option。★ 用 computed 包着传进来。
   *   不要在父组件里手动改这个对象的字段 —— 换一个新的对象进来才会触发重绘。
   */
  option: { type: Object, required: true },
  /** 图表高度。**宽度由父容器决定**（永远是 100%），不用传 */
  height: { type: String, default: '320px' },
})

const box = ref(null)

/**
 * ★★ `shallowRef`，不是 `ref`。
 *
 * echarts 实例内部是几百个互相引用的对象（zrender 的整棵渲染树）。
 * 用 `ref()` 包起来，Vue 会**递归地**给这棵树里每个属性套上响应式代理 ——
 * 两个后果：
 *   ① 慢：建图那一下要给几千个属性建 Proxy；
 *   ② 更糟：echarts 内部大量用 `===` 比对象身份，代理之后
 *      「传进去的对象」和「存下来的对象」不再是同一个，
 *      出现「图能画出来，但交互（hover、点击、tooltip）全失效」
 *      这种**从现象完全联想不到原因**的问题。
 *
 * shallowRef 只代理最外层（`.value` 的赋值），正是这里要的。
 */
const chart = shallowRef(null)

/** ResizeObserver 的句柄。★ 必须留着，卸载时要 disconnect */
let observer = null

/** 把 option 应用到实例上。实例还没建好（宽度为 0）时直接返回，由 observer 补 */
function render() {
  if (!chart.value) return

  /*
   * ★★ 第二个参数 `notMerge = true`，不能省。
   *
   * 默认是 merge：新 option 里**没提到的字段会保留上一次的值**。
   * 对「结构永远固定」的图无所谓 —— 但这个组件是复用的。
   *
   * 真实会踩的场景：从「作品饼图（5 个扇区，含已下架/已驳回）」切到
   * 「章节饼图（4 个扇区，没有已下架）」。merge 会把作品那边的第 5 个
   * 扇区**原样留在图上**，而图例里已经没有它了 ——
   * 看起来像「章节多出来一个状态」，实际是上一次的残留。
   *
   * ★ 折线图同理：天数从 90 切到 7 时，merge 出来的是 90 个点。
   */
  chart.value.setOption(props.option, true)
}

/** 建实例。只有在容器已经有非零宽度时才会真的建出来 */
function init() {
  if (chart.value || !box.value) return

  /*
   * ★★ 宽度为 0 时【不能 init】。
   *
   * echarts 按容器**当前**的像素尺寸建 canvas。宽度 0 时它会建一个
   * 0×0 的 canvas，在控制台留一句警告（很容易被淹没），
   * 之后即使容器变宽了它也不会自己重画 —— 结果是一块空白。
   *
   * 什么时候宽度会是 0：图表放在**还没激活的 el-tab** 里、
   * 折叠面板里、`display:none` 的祖先下，或者放在
   * 「先 loading 后显示」的容器里。
   *
   * ★ 本项目的大盘页是首屏直接渲染，宽度不会是 0 ——
   *   但下一个用这个组件的人未必，而这个坑不报错，所以在这里堵死。
   */
  if (box.value.clientWidth === 0) return

  chart.value = echarts.init(box.value)
  render()
}

onMounted(async () => {
  /*
   * ★ nextTick：父级的 el-card / el-row 可能还没布局完，
   *   这时 clientWidth 读出来是 0。等一个 tick 再量。
   */
  await nextTick()
  init()

  /*
   * ★★ ResizeObserver 一次兜住三件事：
   *   ① 上面那条「一开始宽度为 0」—— 容器变宽时它会回调，这时才真正 init；
   *   ② 窗口缩放 / 左侧菜单折叠 —— echarts 不会自己跟着变；
   *   ③ 上面的提示条出现或消失导致容器高度变化。
   *
   * ★ 观察的是 box **本身**，不是 window：
   *   `window.addEventListener('resize')` 抓不到「窗口没变、但容器变了」
   *   的情况（比如 el-card 里加了一行说明文字）。
   */
  observer = new ResizeObserver(() => {
    // 上一次因为宽度为 0 没建成 —— 这次有机会了
    if (!chart.value) {
      init()
      return
    }
    chart.value.resize()
  })
  observer.observe(box.value)
})

/*
 * ★ deep: true 是保险。正常用法下父组件用 computed 每次给一个新对象，
 *   引用变化就能触发；但如果有人直接改传进来的对象里的数组
 *   （`trend.novels[0] = 5`），不加 deep 就不重绘了。
 */
watch(() => props.option, render, { deep: true })

onBeforeUnmount(() => {
  /*
   * ★★ 两个都要释放，漏任何一个都会泄漏，而且都不报错。
   *
   *   observer.disconnect() —— 它持有 box 这个 DOM 节点的引用。
   *     不断开的话，被移除的节点不会被 GC 回收。
   *   chart.dispose() —— echarts 实例持有 canvas、绑定了 window 上的事件。
   *     不 dispose 的话，反复进出这一页会不断累积。
   *
   * ★ 顺序无所谓，但两个都要在【实例还活着】的时候做 ——
   *   下面的 `= null` 只是为了让这个组件实例不再引用它们。
   */
  observer?.disconnect()
  observer = null
  chart.value?.dispose()
  chart.value = null
})
</script>

<template>
  <div ref="box" class="chart" :style="{ height }" />
</template>

<style scoped>
/*
 * ★ width: 100% 是必须的 —— echarts 靠容器的像素宽度决定 canvas 尺寸，
 *   没有这一行的话 div 的宽度由内容决定，而内容是空的，
 *   于是宽度是 0（又回到上面那个「0×0 canvas」的坑）。
 */
.chart {
  width: 100%;
}
</style>
