/**
 * 阅读器设置（Pinia）：字号 / 行距 / 背景色。
 *
 * ------------------------------------------------------------
 * ★★ 为什么设置进 Pinia，而阅读进度【不进】
 * ------------------------------------------------------------
 * 这是 P3 特意做的一个区分，两边看起来都是「阅读器的状态」，但传播范围完全不同：
 *
 *   设置：全局一份，阅读器、将来的「个人中心」都要读，
 *         改了要立刻反映到 CSS 变量上 —— 天生是全局响应式状态。
 *
 *   进度：按 novelId 分片，而且【只被阅读器这一个页面读】。
 *         放进全局 store，每次滚动上报都会改动一份全局响应式状态，
 *         触发所有订阅它的组件重新求值 —— 而实际上没有任何别的组件关心它。
 *         所以进度存在 utils/readerStorage.js 里，由阅读器页面自己管，
 *         读完就扔，不进任何全局状态。
 *
 * 一句话：**全局可见的进 store，页面私有的进页面**。
 * 判断标准不是「它是不是状态」，而是「除了当前页面还有谁需要它」。
 *
 *
 * ------------------------------------------------------------
 * ★ 设置的作用范围是【全局】的，跨书、跨登录态
 * ------------------------------------------------------------
 * 换一本书、退出登录、换账号，字号都还是那个字号 —— 这是阅读器的常识，
 * 也是「读者调了一次就不想再调第二次」的地方。
 * 所以这份状态既不带 novelId、也不带 userId，
 * 并且【不随退出登录清空】（utils/auth.js 的 clearAuth 不认识这个 key）。
 */
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import {
  DEFAULT_SETTINGS,
  FONT_SIZES,
  LINE_HEIGHTS,
  THEMES,
  loadSettings,
  saveSettings,
} from '@/utils/readerStorage'

export const useReaderStore = defineStore('reader', () => {
  // ---------- state ----------
  // ★ 初始值直接读 localStorage —— loadSettings() 保证返回的一定是完整合法的对象，
  //   所以这里不需要 ?? DEFAULT_SETTINGS 兜底（见那个函数的说明）
  const settings = ref(loadSettings())

  // ---------- getters ----------

  /** 当前字号对应的 CSS 值。★ 找不到时回退到默认档，绝不返回 undefined */
  const fontSizeValue = computed(
    () =>
      FONT_SIZES.find((o) => o.key === settings.value.fontSize)?.value ??
      FONT_SIZES.find((o) => o.key === DEFAULT_SETTINGS.fontSize).value,
  )

  const lineHeightValue = computed(
    () =>
      LINE_HEIGHTS.find((o) => o.key === settings.value.lineHeight)?.value ??
      LINE_HEIGHTS.find((o) => o.key === DEFAULT_SETTINGS.lineHeight).value,
  )

  const theme = computed(
    () => THEMES.find((o) => o.key === settings.value.theme) ?? THEMES[0],
  )

  /**
   * 绑到阅读器根节点上的行内 CSS 变量。
   *
   * ★★ 为什么用 CSS 变量而不是 class 组合：
   *   4 档字号 × 3 档行距 × 4 种背景 = 48 种组合，用 class 就要写 48 条规则，
   *   而且每加一档（比如再加一档字号）就要重写一遍所有组合 —— 是乘法级增长。
   *   用变量则是加法级：4 个变量各管各的，加一档只是多一个选项。
   *
   * ★ 变量名统一加 --reader- 前缀：它们是绑在阅读器根节点上的，
   *   不应该影响页面上别的组件（Element Plus 自己也用了大量 CSS 变量）。
   *
   * ★ 返回一个普通对象即可 —— Vue 3 对 style 绑定对象里的自定义属性支持得很好，
   *   会原样写进行内样式。
   */
  const styleVars = computed(() => ({
    '--reader-font-size': fontSizeValue.value,
    '--reader-line-height': lineHeightValue.value,
    '--reader-bg': theme.value.bg,
    '--reader-fg': theme.value.fg,
  }))

  // ---------- actions ----------

  /**
   * 改一项设置并立刻落盘。
   *
   * ★ 用「合并一个键」而不是「整个对象替换」：
   *   调用方写 update('fontSize', 'lg')，改不动另外两项。
   *   整个替换的话，调用处必须先把当前三项都读出来再传进去，
   *   漏了一项就把它重置成默认值 —— 而这种 bug 只在特定操作顺序下出现。
   *
   * ★ 每次改都存：这三个设置加起来也就几十个字节，
   *   没必要为了省一次 localStorage 写而做防抖 —— 而防抖会让
   *   「改完立刻关掉标签页」丢掉最后那次修改。
   *
   * @param {'fontSize'|'lineHeight'|'theme'} key
   * @param {string} value 必须是 utils/readerStorage.js 里那几个选项表里的 key
   */
  function update(key, value) {
    settings.value = { ...settings.value, [key]: value }
    saveSettings(settings.value)
  }

  /** 恢复默认设置 */
  function reset() {
    settings.value = { ...DEFAULT_SETTINGS }
    saveSettings(settings.value)
  }

  return {
    settings,
    // 选项表也导出：页面要渲染这几个选项按钮，直接从 store 拿，
    // 不用再 import 一遍 readerStorage（避免同一份「真相」有两个入口）
    FONT_SIZES,
    LINE_HEIGHTS,
    THEMES,
    fontSizeValue,
    lineHeightValue,
    theme,
    styleVars,
    update,
    reset,
  }
})
