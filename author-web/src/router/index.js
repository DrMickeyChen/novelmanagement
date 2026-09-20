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
 */
const routes = [
  {
    path: '/',
    component: AppLayout,
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/home/index.vue'),
        meta: { title: '创作中心' },
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
