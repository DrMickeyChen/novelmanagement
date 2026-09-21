<script setup>
/**
 * 阅读端整体布局：顶部导航（含搜索框） + 内容区。
 *
 * 登录页和阅读器不走这个布局（它们要全屏），所以布局挂在路由的父级上，
 * 而不是写在 App.vue 里。见 router/index.js。
 *
 * ★ 导航的【内容】不在这里，在 `components/NavMenu.vue` ——
 *   桌面横排和移动端抽屉竖排是同一份 markup，抽出去才不会改一处漏一处。
 *   这里只管「把它放在哪」：宽屏放在 header 里，窄屏塞进抽屉。
 */
import { ref } from 'vue'
import { useUserStore } from '@/stores/user'
import { READER_WEB_URL } from '@/utils/auth'
import { useMediaQuery, NARROW_QUERY } from '@/composables/useMediaQuery'
import NavMenu from '@/components/NavMenu.vue'

const userStore = useUserStore()

/**
 * ★★ 窄屏判定。断点数字**不在这里**，在 `useMediaQuery.js` 里
 *   （`NARROW_QUERY`），因为 CSS 那边也要用同一个数字 —— 见那个文件的注释。
 */
const isNarrow = useMediaQuery(NARROW_QUERY)

/** 抽屉开关。只在 `isNarrow` 为真时用得上 */
const menuVisible = ref(false)

/**
 * 跳到创作中心 / 管理端（这里是给左上角 logo 用的，跳到阅读端自己的首页）。
 *
 * ★ 必须包成一个函数，不能直接在模板里写 window.location.href ——
 *   Vue 的模板表达式【不暴露 window 这个全局对象】，模板里的标识符会被
 *   编译成 _ctx.window，运行时是 undefined，直接报错。
 *   模板里能用的全局只有 Math / Date / JSON 那一小撮白名单。
 */
function gotoApp(url) {
  window.location.href = url
}

/**
 * 抽屉里点到任何【可点的东西】就把抽屉收起来。
 *
 * ★ 为什么用事件委托而不是给每个链接挂 @click：
 *   NavMenu 是同一个组件渲染两份，给它加入口就得再加一个 emit、
 *   再让里面每个 <router-link> 都写一遍 —— 而「点完收起」这件事
 *   **只对抽屉这一份有意义**（横排那份没有东西可收）。
 *   委托只看「点到的是不是链接或按钮」，和 NavMenu 内部结构解耦。
 *
 * ★ 判据是 `a, button` 而不是「点哪儿都收」：点搜索框（`<input>`）
 *   不该收 —— 用户点了输入框就是要打字，抽屉一收他连字都打不了。
 *   而 `el-input` 那个「搜索」按钮是 `<button>`，点它会收，这也对。
 *
 * ★ 用 `closest` 从 `e.target` 往上找：链接里还包着 `<span>` / `<el-avatar>`，
 *   直接拿 `e.target` 判会漏掉（点昵称文字或头像就不收了）。
 */
function handleDrawerClick(e) {
  if (e.target.closest('a, button')) {
    menuVisible.value = false
  }
}
</script>

<template>
  <el-container class="layout">
    <el-header class="header">
      <div class="brand" @click="gotoApp(READER_WEB_URL)">
        📖 小说阅读
        <el-tag size="small" type="info" effect="plain">/reader/</el-tag>
      </div>

      <!--
        宽屏：一行铺开。
        ★ 这一行要 ~1000px 才放得下（搜索框 260 + 四个站点链接 +
          两个跨应用按钮 + 申请作者 + 账号区），窄屏放不下 —— 见下面的抽屉。
      -->
      <NavMenu v-if="!isNarrow" />

      <!--
        ★★ 窄屏：整条导航收成一枚按钮，内容进抽屉。
          为什么是「收进抽屉」而不是「让导航换行 / 缩小字号」：
          导航栏是**入口**，挤成两行会把 header 撑高、把内容区往下推，
          而且字号一小就点不中了；抽屉里每一项都能有 44px 的点击高度。

        ★ 必须 `v-if` / `v-else` 二选一，**不能**「都渲染、用 CSS 藏一个」：
          藏起来的那份里，`el-input` / `el-avatar` 一样会挂载、一样会绑事件、
          一样会跑 `matchMedia` 之外的一切逻辑，页面上还多两个同 id 的输入框
          （移动端键盘弹出时焦点跑到看不见的那个上去）。
      -->
      <el-button
        v-else
        class="menu-btn"
        link
        aria-label="打开菜单"
        @click="menuVisible = true"
      >
        ☰ 菜单
      </el-button>
    </el-header>

    <!--
      ★ 抽屉挂在 header 【外面】。挂在里面的话它会被 header 的高度
        （64px，且 overflow 处理不受我们控制）裁掉大半。
      ★ `:with-header="false"`：默认那个标题栏写着「Drawer」，是英文的，
        而且我们不需要标题 —— 点遮罩或点条目自己就关了。
    -->
    <el-drawer
      v-model="menuVisible"
      class="nav-drawer"
      direction="rtl"
      size="280px"
      :with-header="false"
    >
      <div class="drawer-inner" @click="handleDrawerClick">
        <NavMenu vertical />
      </div>
    </el-drawer>

    <el-main class="main">
      <router-view />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100%;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  /*
   * ★ 高度从 56px 放到 64px 是为了容纳搜索框（el-input 默认 32px 高，
   *   加上下留白正好需要这么多）。同时 el-header 的默认高度也是 60px，
   *   写死一个值免得不同 Element Plus 版本表现不一致。
   */
  height: 64px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 600;
  cursor: pointer;
  color: #409eff;
  white-space: nowrap;
}

/*
 * ★ 窄屏那枚「☰ 菜单」。`el-button link` 默认字号是 14px，
 *   而 ☰ 这个字符画出来偏小，单独放大一点。
 * ★ 只放大字号不动 padding：`el-button.is-link` 的 `padding: 2px`
 *   是它和旁边文字基线对齐的原因（见 NavMenu 里 `.action-link` 的注释），
 *   改了这里，按钮文字又会和 logo 差半个像素。
 */
.menu-btn {
  font-size: 16px;
}

/*
 * ★★ 900~1100px 这一档：导航还放得下，但**搜索框会被压到 110px 宽**
 *   （实测 901px 时只剩 ~102px —— 一个字都看不全，只剩一个光标在里面）。
 *
 *   这一档【不】提前切抽屉：900px 以上的屏幕明明放得下整条导航，
 *   为了一个输入框把整条导航藏进汉堡菜单是过度反应。
 *   正确的做法是**先把纯装饰的宽度还回来** ——
 *   品牌后面那枚 `/reader/` 标签是给「同时开着三四个端」的开发/演示场景看的，
 *   日常阅读完全不需要，却占着 64px。
 *   （昵称文字那一处在 `NavMenu.vue` 里，同一个断点，还回来约 112px。）
 *
 * ★ 为什么是 1100 而不是 1050：实测搜索框要拿满 260px，
 *   视口需要 859（导航含搜索框的总宽）+ 160（品牌）+ 40（左右内边距）≈ 1060。
 *   取 1100 留一点余量给昵称长短不同的账号。
 */
@media (max-width: 1100px) {
  .brand :deep(.el-tag) {
    display: none;
  }
}

.main {
  max-width: 960px;
  width: 100%;
  margin: 0 auto;
}
</style>
