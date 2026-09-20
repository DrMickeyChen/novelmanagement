import { createRouter, createWebHistory } from 'vue-router'
import BasicLayout from '@/layouts/BasicLayout.vue'

/**
 * 阅读端路由。
 *
 * P0 阶段只建了首页和登录页 —— 目的是证明「三个应用能跑起来 + 代理能通 + 登录态能共享」。
 * 其余页面（分类、搜索、详情、阅读器、书架、历史…）按 项目大纲 §7.1 的清单
 * 在 P1 之后逐个补齐，对应的 views/ 子目录那时候再建。
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
    ],
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

  // 切换路由时回到页面顶部，否则从长列表点进详情会停在半中间
  scrollBehavior() {
    return { top: 0 }
  },
})

/**
 * 全局前置守卫。
 *
 * 阅读端【不做强制登录】—— 首页、分类、搜索、详情、阅读都是公开的，
 * 只有书架、历史、个人中心需要登录（那些路由上单独加 meta.requiresAuth，
 * 等 P1 加页面时再用）。
 */
router.beforeEach((to) => {
  const baseTitle = import.meta.env.VITE_APP_TITLE || '小说阅读'
  document.title = to.meta.title ? `${to.meta.title} - ${baseTitle}` : baseTitle
  return true
})

export default router
