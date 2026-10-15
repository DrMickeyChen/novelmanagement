import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'

import AppLayout from '@/layouts/AppLayout.vue'
import { getToken, getUser, redirectToReaderLogin, READER_WEB_URL } from '@/utils/auth'

/**
 * 创作中心路由。
 *
 * ============================================================
 * ★ 本应用没有【自己的登录页】
 * ============================================================
 * 项目大纲 §7.5 第 3 条约定：401 时三个端统一跳【阅读端】的登录页。
 * 如果这里也做一个登录页，用户就会有三个登录入口，
 * 而且「在创作中心登录」和「在阅读端登录」写的是同一份 localStorage，
 * 属于重复实现 —— 所以这里直接复用阅读端。
 *
 * 未登录 / 角色不符时，一律 redirectToReaderLogin() 打到阅读端去。
 *
 * ============================================================
 * ★★★ 三层嵌套路由 —— P4 最关键的一处结构（大纲 §7.3 ④）
 * ============================================================
 *
 *   /novel/:novelId/work/:nodeId
 *     AppLayout          顶部导航 + 左侧作品栏
 *       └ WorkLayout     :novelId 不变 → 【不重挂载】→ ★ 大纲树挂这一层
 *           └ NodeWorkspace  :nodeId 变 → 需要重新加载
 *                            → <router-view :key="nodeId"> 强制重建
 *
 * ★★ 这里有两个【方向相反】的坑，搞混了就会得到「怎么改都不对」：
 *
 *   ① 大纲树写进页面组件 → 切节点时跟着重挂载 → 闪烁、滚动位置跳回顶部、
 *      折叠状态复位。**要把树提到 WorkLayout 这一层。**
 *   ② 右侧工作区不加 `:key` → Vue Router 默认复用同一个组件实例 →
 *      切节点时内容**不刷新**，还显示着上一章。**要加 key 强制重建。**
 *
 * 一个要「别重建」，一个要「必须重建」—— 所以它们必须在不同的层级上。
 * 这就是为什么工作台是【三层】而不是两层。
 *
 * ★ 也别把树挂到全局 AppLayout —— 那样「我的作品」「章节管理」页面
 *   也会冒出一棵大纲树。
 *
 * ★ 为什么父路由的 path 里带 `:nodeId`：为了让 WorkLayout 能用
 *   `route.params.nodeId` 知道「当前选中哪个节点」（高亮、右键菜单要用），
 *   而它自己**不会**因此重建 —— vue-router 只在【匹配到的路由记录】
 *   发生变化时才重建组件，而这里记录没变，变的只是参数。
 */

const routes = [
  {
    path: '/',
    component: AppLayout,
    children: [
      {
        /*
         * ★ 空路径重定向到作品列表。
         *   创作中心没有「首页」这个概念 —— 作者进来就是要看他自己的书。
         *   P0 的连通性自检页挪到了 /dev/ping（见下），不再占着首页。
         */
        path: '',
        redirect: '/works',
      },
      {
        path: 'works',
        name: 'works',
        component: () => import('@/views/works/index.vue'),
        meta: { title: '我的作品' },
      },
      {
        /*
         * ★ 新建和编辑【共用同一个组件】。
         *   两者的表单字段、校验、封面逻辑完全一样，
         *   差别只有「进来时要不要拉数据」和「提交时 POST 还是 PUT」。
         *   拆成两个文件 = 两份会各自腐烂的表单。
         */
        path: 'novel/create',
        name: 'novelCreate',
        component: () => import('@/views/novel/edit/index.vue'),
        meta: { title: '新建作品' },
      },
      {
        path: 'novel/:novelId/edit',
        name: 'novelEdit',
        component: () => import('@/views/novel/edit/index.vue'),
        meta: { title: '编辑作品' },
      },
      {
        path: 'novel/:novelId/chapters',
        name: 'chapterManage',
        component: () => import('@/views/chapters/index.vue'),
        meta: { title: '章节管理' },
      },
      {
        path: 'novel/:novelId/story-library',
        name: 'storyLibrary',
        component: () => import('@/views/story-library/index.vue'),
        meta: { title: '故事资料库' },
      },
      { path: 'novel/:novelId/writing-lab', name: 'writingLab', component: () => import('@/views/writing-lab/index.vue'), meta: { title: '写作资料与扩展' } },
      {
        /*
         * ★★★ 工作台 —— 三层里中间那层（见文件头那段）。
         *
         * ★★ `:nodeId` 后面那个 `?` 是【必须】的，不是可选风格：
         *
         *   从左侧作品栏点一本书时，我们**只知道 novelId，不知道根节点 id** ——
         *   根节点 id 要先 GET /outline 才知道，而那正是工作台要做的事。
         *   没有可选参数的话，左侧栏只能：
         *     · 先偷偷发一次 /outline 拿到根 id 再跳（多一次请求 + 跳转变慢）
         *     · 或者瞎编一个 id（比如字面量 "root"）跳过去再 replace 掉
         *       —— 那会让浏览器历史里多一条**永远打不开的**记录，
         *          用户按返回键就白屏
         *
         *   有了可选参数，路径就是干净的 `/novel/5/work`，
         *   WorkLayout 拉到树之后用 router.replace 补上真实 id
         *   （replace 不产生新历史记录，返回键的行为是对的）。
         *
         * ★ 顺带它也覆盖了另外两种情况，不用另写分支：
         *   · 这本书还没建大纲（roots 为空）→ 停在 /novel/5/work，显示「创建大纲」
         *   · URL 里的节点已被删除 → replace 成根节点
         *
         * ★ 这里【没有 name】：命名路由是给 router.push({name}) 用的，
         *   而跳工作台永远是一个带 novelId 的具体路径。
         */
        path: 'novel/:novelId/work/:nodeId?',
        component: () => import('@/views/work/WorkLayout.vue'),
        children: [
          {
            /*
             * `path: ''` 表示「和父路由同一个 URL」—— 这是 vue-router 的
             * index 子路由写法。有它才能让 WorkLayout 里那个 <router-view>
             * 有东西可渲染；没有它的话工作台会是一片空白（而且不报错）。
             */
            path: '',
            name: 'work',
            component: () => import('@/views/work/NodeWorkspace.vue'),
            meta: { title: '工作台' },
          },
        ],
      },
      {
        /*
         * ★ P0 的连通性自检页（/api/ping, /api/ping/db, /api/ping/redis）。
         *   它不再占着首页，但**不能删** —— 下次遇到「后端到底通没通」
         *   的问题时，这是唯一一个不敲命令行就能看出来的地方。
         *   刻意不出现在导航栏里（它是开发工具，不是功能）。
         */
        path: 'dev/ping',
        name: 'devPing',
        component: () => import('@/views/dev/ping.vue'),
        meta: { title: '连通性自检' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'notFound',
    component: () => import('@/views/error/404.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,

  /**
   * 路由切换时的滚动行为。
   *
   * ★★ P4 补上的（照抄 reader-web/src/router/index.js:133）。
   *   原来是 `return { top: 0 }` 写死了 —— 这样浏览器前进 / 后退时
   *   会【丢掉 savedPosition】，于是「作品列表（滚到第 8 本）→ 进工作台
   *   → 返回」会回到列表顶部，作者得重新往下找。
   *
   *   返回 savedPosition（由浏览器提供）才是正确行为：
   *   点了浏览器返回键 → 回到上次那个位置；
   *   点了一个新的链接（push）→ savedPosition 是 null → 回到顶部。
   *
   * ★ 慢一点也无所谓：{ left: 0 } 是为了横向滚动条也归零。
   */
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    return { top: 0, left: 0 }
  },
})

/**
 * 全局守卫：创作中心需要 AUTHOR 角色。
 *
 * P0 阶段这里【故意放宽】过 —— 只要 token 存在就放行，角色不符只提示不拦截，
 * 那是为了先验收「三端共享 key」。P1 有了真实角色之后已经收紧成硬拦截。
 *
 * ★ 注意这只是【前端体验层】的拦截。
 *   用户完全可以改 localStorage 里的 role 把自己伪装成 AUTHOR，
 *   前端拦不住 —— 真正的拦截在后端（token 里没有角色，
 *   后端每次都从 Redis 取真实角色来判）。前端这层的作用是
 *   别让用户点进来才发现处处报错。
 */
router.beforeEach((to) => {
  const baseTitle = import.meta.env.VITE_APP_TITLE || '创作中心'
  document.title = to.meta.title ? `${to.meta.title} - ${baseTitle}` : baseTitle

  // 没登录 → 打到阅读端登录页，登录完会跳回这里
  if (!getToken()) {
    redirectToReaderLogin()
    return false
  }

  const user = getUser()

  /*
   * token 在、用户信息没了 —— localStorage 被手动清了一半，
   * 或者存的 JSON 坏了（getUser 遇到坏数据会删掉 key 并返回 null）。
   * 这种残缺的登录态不能放进去用，当未登录处理，重新登一次就恢复了。
   */
  if (!user) {
    redirectToReaderLogin()
    return false
  }

  if (user.role !== 'AUTHOR' && user.role !== 'ADMIN') {
    ElMessage.warning('创作中心仅对作者开放')

    /*
     * ★ 这里跳的是【阅读端首页】而不是 redirectToReaderLogin()。
     *
     *   走 redirectToReaderLogin() 会带上 ?redirect=<创作中心地址>，
     *   而这个用户【已经是登录状态】了，只是角色不对 —— 于是：
     *   去登录页 → 登录成功 → 跳回创作中心 → 又不是作者被拦 → 再回登录页……
     *   一个转不出去的死循环。
     *   redirectToReaderLogin 只适合「该登录而没登录」，不适合「登录了但没权限」。
     *
     *   跳首页是对的：他想看创作中心，那就把他送回他能正常使用的地方。
     *
     * ★ 延时 800ms 再跳，是为了让上面那句提示能被看见 ——
     *   立刻跳走的话，ElMessage 会随着页面卸载一起消失，
     *   用户只会觉得「点了创作中心，闪了一下就回到首页」，不知道发生了什么。
     */
    setTimeout(() => {
      window.location.href = READER_WEB_URL
    }, 800)
    return false
  }

  return true
})

export default router
