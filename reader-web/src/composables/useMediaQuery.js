/**
 * 媒体查询 —— 给一个 CSS 查询串，返回一个跟着视口变化的 ref。
 *
 * ★★ 为什么要有它：`BasicLayout` 的导航在窄屏要**换成另一套结构**
 *   （一行横向链接 → 抽屉里竖着排），这不是「加个 @media 改改样式」
 *   能做的 —— 元素的**父子关系**都变了（抽屉得挂在 header 外面）。
 *   模板里需要一个布尔值，CSS 媒体查询给不了。
 *
 * ★ 为什么不用「监听 window.resize 再比 innerWidth」：
 *   那样得自己写断点数字，而这个数字在 CSS 里也写了一遍（`responsive.css`），
 *   两边漂移了不报错 —— 表现是「样式已经是移动端了，JS 还当自己是桌面」，
 *   于是抽屉按钮不出现、导航栏也不显示，整条导航凭空消失。
 *   用 `matchMedia` 则两边**用的是同一个查询串语义**，至少改的时候
 *   是在同一个概念上改。
 *
 * ★ SSR / 单测环境下 `window.matchMedia` 可能不存在。本仓没有这两个场景
 *   （纯 SPA + 手测），所以不做兼容 —— 加了反而是没人走的死代码。
 */
import { onBeforeUnmount, ref } from 'vue'

/**
 * ★★ 全站唯一的「窄屏」断点：**900px 及以下，整条导航收进抽屉**。
 *
 * 数字是量出来的（`.tmp-p6/probe-search.js` 逐档测搜索框宽度），
 * 不是挑的。桌面那行导航里有【两类】宽度：
 *
 *   · 硬宽度 —— 四个站点链接、两个跨应用按钮、申请作者、头像、退出。
 *     加起来约 493px，一个字都压不掉。
 *   · 唯一能压的 —— 搜索框。它 `flex: 0 1 260px` + `min-width: 110px`。
 *
 * 于是「放不放得下」只看搜索框还剩多少。实测：
 *
 *   视口      形态     搜索框宽    说明
 *   ≥1101    桌面     260px      完整（含 `/reader/` 标签和昵称文字）
 *   901~1100 桌面     260px      ★ 靠 1100 那一档收掉装饰撑住的
 *   901      桌面     257px      这一档的下边缘，仍然是完整宽度
 *   ≤900     抽屉     ——         换成抽屉，搜索框在抽屉里占满整行
 *
 * ★ 为什么不把断点压到 640（那才是真正的物理下限）：
 *   900 是这一档「导航还能舒舒服服摊开」和「已经该收起来」的分界，
 *   而且它是大家都认的那个数 —— 压到 640 只会让 700~900 之间的平板
 *   挤着一条几乎没有余量的导航。
 *
 * ★★ 这个数字在 CSS 里也出现（`styles/responsive.css`、各页的
 *   `<style scoped>`）。**两边不可能只写一遍** —— CSS 的媒体查询
 *   读不到 JS 变量，`@media (max-width: var(--x))` 是无效的。
 *   能做的是：把「概念」定在这里、给个名字（而不是在组件里散落 `900`），
 *   改的时候知道要一起改哪几处。下面 `NARROW_QUERY` 就是那个名字。
 *
 * ★ 另有第二个断点 **1100px**（只收装饰，不换结构），
 *   见 `BasicLayout.vue` 和 `NavMenu.vue` 里各自的 `@media` 注释。
 */
export const NARROW = 900

/** 上面那个数字的查询串形态，给 `useMediaQuery` 用 */
export const NARROW_QUERY = `(max-width: ${NARROW}px)`

export function useMediaQuery(query) {
  const mql = window.matchMedia(query)
  const matches = ref(mql.matches)

  const onChange = (e) => {
    matches.value = e.matches
  }
  mql.addEventListener('change', onChange)

  /*
   * ★ 必须解绑：这个 ref 活在组件的生命周期里，而 matchMedia 的
   *   listener 挂在 window 级的 MediaQueryList 上 —— 不解绑的话，
   *   组件卸载后它还在被调用，闭包里那个 ref 和它所在的组件树
   *   一起被留住（内存泄漏），而且改视口时会对着一堆已经死掉的
   *   组件各跑一遍回调。
   */
  onBeforeUnmount(() => mql.removeEventListener('change', onChange))

  return matches
}
