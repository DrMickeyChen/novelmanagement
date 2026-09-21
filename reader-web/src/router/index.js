import { createRouter, createWebHistory } from 'vue-router'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { useUserStore } from '@/stores/user'

/**
 * 阅读端路由。
 *
 * P0 只建了首页和登录页（证明三端能跑 + 代理能通 + 登录态共享），
 * P3 补齐了读者端的完整链路：找书 → 打开 → 读完三章 → 记住进度。
 *
 * ★★ 两条路由【不在 BasicLayout 里】，是刻意的：
 *   /login                          登录页要全屏，顶部导航栏是干扰
 *   /read/:novelId/:chapterId       阅读器要沉浸式全屏
 * 布局挂在路由父级上而不是写在 App.vue 里，就是为了让这两条能跳出去。
 *
 * ★ 阅读器脱离布局还有一个【功能性】理由（不只是好看）：
 *   阅读进度是按「滚动容器」算的（scrollTop / scrollHeight），
 *   如果外面套着 BasicLayout，那个 el-main 才是滚动容器，而它比视口矮
 *   一个导航栏的高度 —— 用 window 算出来的百分比会永远偏小，
 *   表现为「进度记不准」，而且偏多少取决于导航栏高度，
 *   换个字体、换个分辨率都会变。
 *   让阅读器自己独占一个滚动容器，这个问题就不存在了。
 */
const routes = [
  {
    path: '/',
    component: BasicLayout,
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/home/index.vue'),
        meta: { title: '首页' },
      },
      {
        /*
         * ★ 分类页带 id：/category/1。分类列表在首页拿到，
         *   点进去就是某个具体分类的作品列表。
         *   不带 id 的 /category 没有定义 —— 它没有明确的产品含义
         *   （「所有分类的书」就是首页的列表），与其做一个语义模糊的页面，
         *   不如让它走 404。
         */
        path: 'category/:id',
        name: 'category',
        component: () => import('@/views/category/index.vue'),
        meta: { title: '分类' },
      },
      {
        /*
         * ★ 三个榜单是同一个页面的三个 Tab，靠 ?type= 区分而不是三个路由。
         *   这样切 Tab 不重新挂载组件，也不会在浏览器历史里堆三条记录
         *   （用户按返回键应该回到上一个【页面】，而不是上一个 Tab）。
         */
        path: 'rank',
        name: 'rank',
        component: () => import('@/views/rank/index.vue'),
        meta: { title: '排行榜' },
      },
      {
        /*
         * ★ 关键词放 ?keyword= 而不是路径参数：
         *   搜索词里可能有斜杠、空格、中文，放路径里要处理转义，
         *   而 query 参数由 vue-router 自动编码解码。
         *   ★ 用 query 还有一个实际好处：搜索结果页可以直接分享链接。
         */
        path: 'search',
        name: 'search',
        component: () => import('@/views/search/index.vue'),
        meta: { title: '搜索' },
      },
      {
        path: 'novel/:id',
        name: 'novelDetail',
        component: () => import('@/views/novel/index.vue'),
        meta: { title: '作品详情' },
      },
      {
        /*
         * ★★ 下面两条是 P6 加的，带 `meta.requiresAuth`。
         *
         * 它是「我的数据」类页面的标记：书架每一行都带 user_id，
         * 历史同理，未登录时这两个页面**没有内容可渲染**，
         * 而不是「内容为空」。
         *
         * ★ 拦截在文件末尾的 `beforeEach` 里，判据就是这个 `requiresAuth`
         *   （P6 S14 接通的 —— 在那之前这个 meta 是惰性的，没有任何代码读它）。
         */
        path: 'shelf',
        name: 'shelf',
        component: () => import('@/views/shelf/index.vue'),
        meta: { title: '我的书架', requiresAuth: true },
      },
      {
        path: 'history',
        name: 'history',
        component: () => import('@/views/history/index.vue'),
        meta: { title: '阅读历史', requiresAuth: true },
      },
      {
        /*
         * ★ 路径是 `/profile` 不是 `/user/profile`。
         *   和上面两条（`/shelf`、`/history`）保持同一层级 ——
         *   它们都是「我的」，没有一个是别人的，多包一层 `/user/`
         *   只是在暗示将来会有 `/user/:id` 这种「看别人的主页」，
         *   而本项目没有这个东西（也刻意不做：见方案「不做」清单）。
         *
         * ★ 入口在导航栏右上角的昵称上（点头像/昵称进个人中心），
         *   不另开一个「个人中心」文字链接 —— 未登录的用户没有个人中心，
         *   给他一个点进去必跳登录的链接没有意义。
         *   （书架/历史不一样：那两个对游客是「值得先登录再回来的地方」。）
         */
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/profile/index.vue'),
        meta: { title: '个人中心', requiresAuth: true },
      },
      {
        /*
         * ★ 路径是 `/apply-author` 而不是 `/profile/apply`：
         *   它不是一个「我的资料」的子页面，而是一个**动作页** ——
         *   进来是为了交一份申请，交完之后这个地址就只剩下「查看进度」。
         *   ★ 它和 `/profile` 一样需要登录（看的是自己的申请）。
         */
        path: 'apply-author',
        name: 'applyAuthor',
        component: () => import('@/views/applyAuthor/index.vue'),
        meta: { title: '申请成为作者', requiresAuth: true },
      },
    ],
  },
  {
    /*
     * ★★ 阅读器是【顶层路由】，不在上面的 children 里。
     *   理由见文件开头那段（全屏 + 进度必须基于自己的滚动容器）。
     */
    path: '/read/:novelId/:chapterId',
    name: 'reader',
    component: () => import('@/views/reader/index.vue'),
    meta: { title: '阅读' },
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录' },
  },
  {
    // 兜底：放到最后，前面的都没匹配上才走这里
    path: '/:pathMatch(.*)*',
    name: 'notFound',
    component: () => import('@/views/error/404.vue'),
  },
]

const router = createRouter({
  /*
   * ★ 用 createWebHistory（history 模式），不是 createWebHashHistory。
   *
   * 原因是 §7.5 的生产部署方案：Nginx 按路径把 /reader/ /author/ /admin/
   * 分发到三个静态目录。history 模式的地址长得正常（/reader/novel/123），
   * 而 hash 模式会带个 #（/reader/#/novel/123），又丑又不利于 SEO。
   *
   * ⚠️ history 模式的代价：生产环境的 Nginx 必须配
   *     try_files $uri $uri/ /reader/index.html;
   *    否则用户在 /reader/novel/123 上按 F5 会 404（因为那个路径在服务器上
   *    并不存在真实文件）。开发期不用管，Vite 已经处理好了。
   */
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,

  /**
   * 路由切换时的滚动行为。
   *
   * ★★ P3 修掉了 P0 留下的一个 bug：原来是 `return { top: 0 }`，写死了。
   *   这样浏览器前进 / 后退时会【丢掉 savedPosition】，于是
   *   「首页 → 列表（滚到第 5 本书）→ 详情 → 返回」会回到列表顶部，
   *   用户得重新往下找 —— 而这恰恰是找书场景里最高频的操作路径。
   *
   *   返回 savedPosition（由浏览器提供）才是正确行为：
   *   点了浏览器返回键 → 回到上次那个位置；
   *   点了一个新的链接（push）→ savedPosition 是 null → 回到顶部。
   *
   * ★ 慢一点也无所谓：{ left: 0 } 是为了横向滚动条也归零，
   *   否则某些窄屏下会保留一个横向偏移。
   */
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    return { top: 0, left: 0 }
  },
})

/**
 * 全局前置守卫。
 *
 * 阅读端【不做强制登录】—— 首页、分类、搜索、详情、阅读都是公开的
 * （这几个页面在未登录状态下必须完全可用，游客是读者端的主体）。
 * 只有书架、历史、个人中心需要登录，它们在自己的路由上带
 * `meta.requiresAuth`。
 *
 * 这里做两件事：设置标签页标题 + 拦 `meta.requiresAuth` 的页面。
 *
 * ★ 设标题放在守卫里而不是各页面 onMounted，是因为它必须在
 *   【路由切换的那一刻】就更新 —— 放在页面里的话，页面组件是异步加载的，
 *   标题会晚一拍才变，快速点几下导航能看到标题在跳。
 */
router.beforeEach((to) => {
  const baseTitle = import.meta.env.VITE_APP_TITLE || '小说阅读'
  document.title = to.meta.title ? `${to.meta.title} - ${baseTitle}` : baseTitle

  /*
   * ★★ 「我的数据」类页面的登录拦截（P6 S14 接通）。
   *
   * 拦的是「未登录直接敲 URL / 点收藏夹进来」这条路径，
   * 以及「从导航栏点进来」—— 两条其实是同一条：守卫在**路由切换的那一刻**
   * 就跑了，页面组件根本还没被创建，所以页面里的任何兜底都来不及生效。
   *
   * ★ 不拦的代价（P6 之前就是这样）：页面挂载 → 接口 401 →
   *   `request.js` 统一处理（提示 + 送登录页）。功能上「能用」，
   *   但**丢了 `redirect`** —— 用户登录完落在首页，得自己再把那一页找回来，
   *   而多半就不会再找了。
   *
   * ★★ 跳转【必须带上回来的路】。写成 `query: { redirect: to.fullPath }`
   *   交给 vue-router 去编码，**不要自己拼字符串** —— fullPath 里的
   *   `?` `&` `=` 会和 `/login?redirect=` 自己的 query 串在一起把地址截断，
   *   而且不报错（同 `LoginPrompt.vue` 里那段理由）。
   *   登录页只接受本应用内的相对路径，开放重定向防护照常生效。
   *
   * ★ 用 `route.path` 而不是 `fullPath`？——【不行】，见上一条：
   *   从「筛选过的分类页」进来的话，query 丢了，回来看到的是另一页内容。
   *
   * ★ 判据用 `userStore.isLogin` 而不是直接读 localStorage：
   *   导航栏（`BasicLayout`）和评论区（`CommentSection`）用的都是它。
   *   守卫和界面共用【同一个】判据，就不会出现「守卫放行了、
   *   界面却还显示未登录」这种自相矛盾的状态。
   *   ★ `useUserStore()` 必须在守卫【函数体内】调用 —— 模块顶层调用时
   *     Pinia 还没 `app.use()`，会直接抛错。见 `main.js` 的安装顺序。
   *
   * ★ 这里【不】像 admin-web 那样再查一次 `getUser()`：
   *   书架/历史/个人中心的接口只认 token（userId 从 JWT 里取），
   *   昵称和头像纯粹是展示。用户信息丢了顶多是导航栏少显示个名字，
   *   为此把人挡在书架外面是过度拦截。
   */
  if (to.meta.requiresAuth && !useUserStore().isLogin) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  return true
})

export default router
