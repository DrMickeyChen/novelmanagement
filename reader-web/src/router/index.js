import { createRouter, createWebHistory } from 'vue-router'
import BasicLayout from '@/layouts/BasicLayout.vue'

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
 * 只有书架、历史、个人中心需要登录，那些是 P6 的页面，
 * 到那时在各自的路由上单独加 meta.requiresAuth。
 *
 * ★ 这里只做一件事：设置浏览器标签页标题。
 *   放在守卫里而不是各页面 onMounted，是因为它必须在【路由切换的那一刻】
 *   就更新 —— 放在页面里的话，页面组件是异步加载的，
 *   标题会晚一拍才变，快速点几下导航能看到标题在跳。
 */
router.beforeEach((to) => {
  const baseTitle = import.meta.env.VITE_APP_TITLE || '小说阅读'
  document.title = to.meta.title ? `${to.meta.title} - ${baseTitle}` : baseTitle
  return true
})

export default router
