<script setup>
/**
 * 阅读端的导航内容 —— **一份 markup，两处渲染**。
 *
 * 桌面（`BasicLayout` 的 header 里）：横排一行。
 * 移动端（同一个文件的 `el-drawer` 里）：竖排一列。
 *
 * ★★ 为什么抽成一个组件，而不是在抽屉里再写一份导航：
 *   导航条目是【会长】的 —— P3 建的时候只有首页/排行榜/搜索，
 *   P6 加了书架、历史、申请作者、个人中心。两处各写一份的话，
 *   下次加一个入口就得记得改两个地方，而**忘了改的那一处不会报错**，
 *   只是少一个入口 —— 要有人恰好拿手机打开才发现。
 *   这是本项目最典型的一类 bug：静默、且只在一种形态下出现。
 *
 * ★ 排布靠 `vertical` 这个 prop 切，不是靠媒体查询：
 *   媒体查询只能改样式，改不了「它被放在哪个父元素里」——
 *   而抽屉是挂在 header **外面**的（挂在里面会被 header 的
 *   64px 高度裁掉），所以这两份在 DOM 上就是两个位置。
 */
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { AUTHOR_WEB_URL, ADMIN_WEB_URL } from '@/utils/auth'

defineProps({
  /* true = 竖排（抽屉里用）。默认横排，保持桌面端原样 */
  vertical: { type: Boolean, default: false },
})

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/**
 * 顶部搜索框的关键词。
 *
 * ★ 初始值从当前 URL 取：用户已经在 /search?keyword=xxx 上了，
 *   输入框应该是 xxx，而不是空的 —— 否则「搜完想改一个字重搜」
 *   得先把词删了重打一遍。
 */
const keyword = ref(route.query.keyword || '')

/**
 * ★ 路由变化时把输入框同步过来。
 *
 * 为什么需要这段：用户点浏览器【后退】回到上一个搜索结果页时，
 * route.query.keyword 变了，但输入框是组件内的 ref，不会跟着变 ——
 * 页面显示 A 的搜索结果，输入框里却写着 B。
 * 不动手同步的话，这种不一致只在前进/后退时出现，很难注意到。
 *
 * ★ 只在【搜索页】同步：如果用户在别的页面（比如首页）往搜索框里打了半个词
 *   还没回车，这时路由一变就清空，会把他的输入冲掉。
 */
watch(
  () => route.query.keyword,
  (value) => {
    if (route.name === 'search') {
      keyword.value = value || ''
    }
  },
)

/** 提交搜索。空关键词不跳转 —— 服务端会返回空列表，跳过去是个没意义的空页面 */
function handleSearch() {
  const value = keyword.value.trim()
  if (!value) return
  router.push({ name: 'search', query: { keyword: value } })
}

/**
 * 跳到创作中心 / 管理端。
 *
 * ★ 用 location.href 而不是 router.push —— 这是【跨应用】跳转，
 *   本应用的路由器管不到另外两个应用（§7.5 第 2 条约定）。
 *   token 存在共用的 localStorage 里，跳过去自动就是登录态。
 *
 * ★ 必须包成一个函数，不能直接在模板里写 window.location.href ——
 *   Vue 的模板表达式【不暴露 window 这个全局对象】，模板里的标识符会被
 *   编译成 _ctx.window，运行时是 undefined，直接报错。
 *   模板里能用的全局只有 Math / Date / JSON 那一小撮白名单。
 *
 * ★ `BasicLayout` 里那个「📖 小说阅读」的 logo 也有一个同样的函数 ——
 *   两行代码，不为了共用它去开一个 utils 文件（而且 `utils/auth.js`
 *   是 md5 锁定的，一个字节都不许加）。
 */
function gotoApp(url) {
  window.location.href = url
}

/**
 * 退出登录。
 *
 * ★ 必须 await：logout() 要先请求后端把 Redis 里这条登录态删掉，
 *   不 await 的话请求可能还没发出去，页面就已经因为状态变了而重新渲染。
 *
 * ★ 退出后【不跳转】—— 阅读端的首页是公开页面，未登录也能看，
 *   停在原地正好让用户看到导航栏从「昵称 + 退出」变成了「登录」，
 *   这是最直观的反馈。
 *   （创作中心和管理端的布局不一样，那两个端退出后必须离开 —— 见那边的注释。）
 */
async function handleLogout() {
  await userStore.logout()
}
</script>

<template>
  <div class="nav-menu" :class="{ vertical }">
    <!--
      搜索框。
      ★ 用 @keyup.enter 而不是包一个 <form> 提交：
        表单提交会让浏览器整页刷新（丢掉 SPA 的登录态以外的所有状态），
        还得额外 preventDefault。
      ★ 分类没有放在这里 —— 它没有独立的落地页（只有 /category/:id），
        入口在首页的分类区块里。理由见相关文档。
    -->
    <el-input
      v-model="keyword"
      class="search-input"
      placeholder="搜书名或作者名"
      clearable
      @keyup.enter="handleSearch"
    >
      <template #append>
        <el-button @click="handleSearch">搜索</el-button>
      </template>
    </el-input>

    <router-link to="/" class="nav-link">首页</router-link>
    <router-link to="/rank" class="nav-link">排行榜</router-link>

    <!--
      ★ 书架 / 历史（P6 加）。**未登录也显示**，不做 v-if ——
        它们是阅读端的标准功能，藏起来的话游客根本不知道有这回事；
        点进去由路由守卫送去登录页再送回来（S14）。
        反过来，如果在这里 v-if="isLogin"，那两条路由的守卫
        就永远只能靠手敲 URL 触发，「登录后回到原页面」这条
        也就没人会路过。
    -->
    <router-link to="/shelf" class="nav-link">书架</router-link>
    <router-link to="/history" class="nav-link">历史</router-link>

    <!--
      跨应用跳转：这两个是【另外两个独立应用】。
      地址走网关（同源），跳过去能读到本应用写的登录态。
    -->
    <el-button link type="primary" @click="gotoApp(AUTHOR_WEB_URL)">创作中心</el-button>
    <el-button link type="primary" @click="gotoApp(ADMIN_WEB_URL)">管理端</el-button>

    <!--
      ★★ 「申请作者」（P6 加）。**只对「登录着、且还不是作者」的人显示。**
        两个条件都不是装饰：
        · 游客没有申请资格（提交接口要登录），给他看只会点进登录页；
        · **已经是作者的人不需要它** —— 申请入口对他是死的，
          留着只会让人怀疑「我是不是还要再申请一次」。
      ★ 判据用 `isAuthor` 而不是「有没有提交过申请」：
        管理员手工加的角色同样算作者，而那个人`/mine` 是 null。
      ★ 审批通过会把申请人**踢下线**（后端 `grantAuthorRole`），
        所以他重新登录之后这个链接才会消失 —— 这是对的：
        那一刻他的角色才真正刷新成 AUTHOR。

      ★★ 类名是 `action-link` 而**不是** `nav-link`。这两个类是导航栏里的
        【两族】样式，选错了不会报错，只会「看着不像一套」：
          · `.nav-link`   —— 左侧那组站点导航（首页/排行榜/书架/历史），深灰、400 字重
          · `.action-link` —— 右侧这组（创作中心/管理端），和 `el-button link` 同款
        这条夹在两个蓝按钮中间，套 `.nav-link` 就会比左右两边淡一圈、轻一档。
    -->
    <router-link
      v-if="userStore.isLogin && !userStore.isAuthor"
      to="/apply-author"
      class="action-link"
    >
      申请作者
    </router-link>

    <!--
      ★ 竖排（抽屉）时这条分隔线要转 90°：`el-divider` 的 vertical 是给
        横排用的（一条 1em 高的竖线），放在一列里会变成一个莫名其妙的小短杠。
    -->
    <el-divider :direction="vertical ? 'horizontal' : 'vertical'" />

    <template v-if="userStore.isLogin">
      <!--
        ★★ 昵称是个【链接】，进个人中心（P6 加）。
          用「点自己的名字」而不是另开一个「个人中心」文字链接：
          未登录的用户没有个人中心，给他一个点进去必跳登录页的入口
          没有意义（书架/历史不一样 —— 那两个对游客是「值得先登录
          再回来的地方」，所以它们无条件显示）。

        ★ 头像也放在这里，而且它**不是装饰**：这是「换头像立刻生效」
          最直接的证据 —— 个人中心里传完图，不刷新、不跳转，
          这一小块立刻跟着变（`syncFromStorage()` 重读了 localStorage）。
          没设过头像的老账号会走 el-avatar 的默认插槽，显示昵称首字。

        ★ `userInfo?.avatar` 里那个 `?.` 是必须的：这份数据来自
          `localStorage[novel_user]`，而 P6 之前写下的那份 JSON 里
          **压根没有 avatar 这个 key**（是 undefined，不是 null）。
      -->
      <router-link to="/profile" class="me" title="个人中心">
        <el-avatar :size="26" :src="userStore.userInfo?.avatar || ''" alt="头像">
          {{ (userStore.nickname || '我').charAt(0) }}
        </el-avatar>
        <span class="me-name">{{ userStore.nickname || '已登录' }}</span>
      </router-link>
      <el-button link @click="handleLogout">退出</el-button>
    </template>
    <template v-else>
      <router-link to="/login" class="nav-link">登录</router-link>
    </template>
  </div>
</template>

<style scoped>
.nav-menu {
  display: flex;
  align-items: center;
  gap: 12px;
}

/*
 * ★★ 搜索框是这一行里【唯一能压缩】的东西。
 *   其余每一项都是「有几个字就多宽」的硬宽度（链接、按钮、头像+昵称），
 *   只有输入框可以在窄一点的笔记本上少显示几个字而不影响使用。
 *   → 给它 `flex: 0 1 260px`（能缩、但不主动长）+ 一个下限，
 *     这样 900px 上下那一档不用整个切到抽屉形态。
 *   ★ 不这么写的话（原来就是死宽 `width: 260px`），
 *     窗口一窄就整条导航横向溢出 —— 溢出的部分是**看不见也点不到**的，
 *     而且是 `position: fixed` 元素定位跑偏的根因（见 responsive.css）。
 */
.search-input {
  flex: 0 1 260px;
  min-width: 110px;
}

.nav-link {
  color: #303133;
  font-size: 14px;
  white-space: nowrap;
}

/*
 * ★★ 「申请作者」这类的【动作链接】—— 长得要和旁边的 `<el-button link>` 一样。
 *
 * 导航栏里其实有【两族】入口，判据是「点了是换页面，还是去干一件事」：
 *   · 左族 `.nav-link`   站点导航（首页/排行榜/书架/历史），深灰 #303133、400 字重
 *   · 右族 本类 + `el-button link`（创作中心/管理端/申请作者），蓝色、500 字重
 * 两族的差别不只是颜色：**字重和内边距也不一样**，所以「顺手用同一个类」
 * 会得到一个看着像掉队过来的条目 —— 不报错，只是不像一套。
 *
 * ★ 下面这几条是量出来的（CDP 取 `getComputedStyle`），不是照感觉抄的：
 *     el-button.is-link → color #409eff / font-weight 500 / line-height 1
 *                         / padding 2px / height auto
 *   `line-height: 1` 和 `padding: 2px` 必须一起写：只改颜色不改行高的话，
 *   文字中心会比按钮低 0.5px —— 单独看毫无问题，和旁边并排就是「没齐平」。
 */
.action-link {
  color: #409eff;
  font-weight: 500;
  font-size: 14px;
  line-height: 1;
  padding: 2px;
  white-space: nowrap;
}

/*
 * ★★ Element Plus 自带一条 `.el-button + .el-button { margin-left: 12px }`，
 *   而外层 `.nav-menu` 已经有 `gap: 12px` —— 两个相邻的 link 按钮之间会变成
 *   【24px】，而导航栏里其余每一对都是 12px。
 *
 *   实测（改之前）：创作中心→管理端 24px，管理端→申请作者 12px。
 *   两个按钮被撑开、第三个又贴回去，看着就是「没对齐」。
 *
 * ★ 用 `:deep()`：`el-button` 是子组件渲染出来的根节点，虽然它带得上
 *   父组件的 scope 属性，但 `+` 组合选择器编译后只在最后一段追加
 *   `[data-v-x]`，写 `:deep()` 省得去赌这一版 Vue 的编译结果。
 */
.nav-menu :deep(.el-button + .el-button) {
  margin-left: 0;
}

/*
 * ★ 当前路由高亮。用 :deep() 是因为 .router-link-active 是 vue-router
 *   加在 <a> 上的类，而 <a> 是模板里的元素、不是子组件 ——
 *   理论上不需要 :deep()。但 el-button 那种组件渲染出来的链接就需要，
 *   统一写法省得记。
 *
 * ★ exact-active 用于首页：不加 exact 的话，首页的"/"会匹配所有路由，
 *   于是在任何一个页面上「首页」都是高亮的。
 */
.nav-link.router-link-active {
  color: #409eff;
  font-weight: 600;
}

/*
 * 右上角「头像 + 昵称」那一块，点了进个人中心。
 *
 * ★ 它是一个 <router-link>（渲染成 <a>），而 <a> 在全局样式里是
 *   `color: inherit` —— 不写 color 的话它会继承 body 的 #303133，
 *   和旁边「退出」按钮的蓝色链接色不一致，看起来像两个不相干的元素。
 */
.me {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #409eff;
  font-size: 14px;
  white-space: nowrap;
}

/*
 * ★ 昵称可能长达 20 字，会把整条导航挤到换行。
 *   截断到 8em（约 8 个汉字）+ 省略号 —— 导航栏是入口不是展示区，
 *   完整的昵称在个人中心里看。
 */
.me-name {
  max-width: 8em;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ★ 头像没图时走默认插槽（昵称首字）。底色用主题蓝的浅色，和页面其余部分同族 */
.me :deep(.el-avatar) {
  background: #ecf5ff;
  color: #409eff;
  font-size: 13px;
}

/*
 * ★ 900~1100px：把昵称文字收掉，只留头像（同一个断点在 `BasicLayout.vue`，
 *   那里解释了为什么设这一档 —— 为的是把宽度还给搜索框）。
 *
 * ★ 收掉文字不影响可达性：这一块本身就是 `title="个人中心"` 的链接、
 *   点进去就是个人中心，头像不是装饰（默认插槽里还写着昵称首字）。
 *   腾出来的约 112px（`max-width: 8em`）正好够搜索框不被压扁。
 */
@media (max-width: 1100px) {
  .me-name {
    display: none;
  }
}

/* ==================== 竖排（移动端抽屉里） ==================== */

/*
 * ★ `gap: 0`：竖排时每一项自己带内边距（下面），条目之间的分隔靠内边距本身，
 *   不再额外加间距 —— 否则「分隔线在哪」和「点击区到哪」是两回事，
 *   点空白处不响应，看着像坏了。
 */
.nav-menu.vertical {
  flex-direction: column;
  align-items: stretch;
  gap: 0;
}

/*
 * ★★ 这些条目是【手指点的目标】，要有 44px 上下的高度
 *   （iOS 人机界面指南给的最小值，低于这个数手机上就经常点不中）。
 *
 * ★★ `min-height: 44px` 必须写在【每一个】条目上，不能只靠 padding 撑：
 *   这几类的文字行高本来就不一样 ——
 *   `.nav-link` 是 `line-height: normal`（≈19px），
 *   `.action-link` 是 `line-height: 1`（=14px），
 *   `.me` 里装着一个 26px 的头像。
 *   光加 padding 量出来是 43 / 38 / 40 / 50 四个不同的高度
 *   （改之前实测就是这个数），一列看下来参差不齐、有的还不到 44。
 *   给一个统一的 `min-height`，四类条目就一样高了。
 */
.nav-menu.vertical .nav-link,
.nav-menu.vertical .action-link,
.nav-menu.vertical .me,
.nav-menu.vertical :deep(.el-button.is-link) {
  min-height: 44px;
}

/*
 * ★ `display: flex; align-items: center` 是为了让文字在 44px 里**居中**：
 *   `<a>` 被 flex 容器 blockify 之后是块级盒子，光有 min-height 的话
 *   文字贴在顶部，看着像没对齐。
 */
.nav-menu.vertical .nav-link,
.nav-menu.vertical .action-link {
  display: flex;
  align-items: center;
}

.nav-menu.vertical .nav-link,
.nav-menu.vertical .action-link,
.nav-menu.vertical .me {
  padding: 12px 8px;
  border-radius: 4px;
  /* 上面那些 `white-space: nowrap` 是给横排的，竖排有整屏宽度可用 */
  white-space: normal;
}

/* `.me` 本来就是 flex（头像 + 昵称），不用再改 display */
.nav-menu.vertical .me {
  align-items: center;
}

/*
 * ★ 竖排时把搜索框提到最前（`order: -1`，flex 的排序属性）。
 *   它在文档顺序里排在「历史」和「创作中心」中间，横排时读着很自然，
 *   竖排时却会把导航条目【劈成两半】—— 上半是站点导航、下半是动作入口，
 *   中间横着一个输入框，两组的关系就看不出来了。
 */
.nav-menu.vertical .search-input {
  order: -1;
  width: 100%;
  margin-bottom: 8px;
  /*
   * ★★ 必须把横排那条 `flex: 0 1 260px` 撤掉，而且**不只是为了宽度**：
   *   flex 的 basis 跟着主轴走 —— 横排时主轴是水平（basis = 宽 260px），
   *   竖排时主轴变成垂直，同一个 `260px` 就变成了**高 260px** 的输入框。
   *   页面上是一个顶天立地的巨型搜索框，而样式表里看不出任何错。
   */
  flex: none;
}

.nav-menu.vertical .nav-link:active,
.nav-menu.vertical .action-link:active,
.nav-menu.vertical .me:active {
  background: #f5f7fa;
}

/*
 * ★ 竖排时 `el-button link` 会被 `align-items: stretch` 拉成整行宽，
 *   而它内部默认是 `justify-content: center` —— 文字会飘在这一行的中间，
 *   和旁边左对齐的链接完全对不上。
 * ★ `height: auto` 也是必须的：`.el-button` 的基础样式写死了 `height: 32px`，
 *   不放开的话它比链接矮一截。
 */
.nav-menu.vertical :deep(.el-button.is-link) {
  justify-content: flex-start;
  height: auto;
  padding: 12px 8px;
  font-size: 14px;
}
</style>
