import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'

import AdminLayout from '@/layout/AdminLayout.vue'
import { getToken, getUser, redirectToReaderLogin, READER_WEB_URL } from '@/utils/auth'

/**
 * 管理端路由。
 *
 * 和创作中心一样，本应用【没有自己的登录页】——
 * 未登录时统一跳到阅读端的登录页（§7.5 第 3 条约定），
 * 避免出现三个登录入口。
 *
 * P0 只建了工作台首页。§四 列的那些页面
 * （dashboard / novel / chapter / category / comment / user / author-apply / ai …）
 * 等 P1 之后按菜单逐项补齐。
 */
const routes = [
  {
    path: '/',
    component: AdminLayout,
    children: [
      {
        path: '',
        name: 'dashboard',
        component: () => import('@/views/home/index.vue'),
        meta: { title: '工作台' },
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
 * 全局守卫：管理端需要 ADMIN 角色。
 *
 * P0 阶段这里只检查「有没有 token」，角色不符只提示不拦截 ——
 * 那是为了先验收「三端共享 key」。P1 拿到真实角色后已改成硬拦截。
 *
 * ★ 和 author-web 一样，这层只是【前端体验层】的拦截，
 *   改 localStorage 就能绕过；真正的拦截在后端。
 */
router.beforeEach((to) => {
  const baseTitle = import.meta.env.VITE_APP_TITLE || '管理端'
  document.title = to.meta.title ? `${to.meta.title} - ${baseTitle}` : baseTitle

  if (!getToken()) {
    redirectToReaderLogin()
    return false
  }

  const user = getUser()
  if (!user) {
    // token 在但用户信息缺失/损坏，当未登录处理
    redirectToReaderLogin()
    return false
  }

  if (user.role !== 'ADMIN') {
    ElMessage.warning('管理端仅对管理员开放')

    /*
     * ★ 跳【阅读端首页】，不跳登录页 —— 理由和 author-web 的守卫完全相同：
     *   用户已经登录了，只是角色不对，走 redirectToReaderLogin()
     *   会在「登录页 ↔ 管理端」之间来回弹，永远出不去。
     *
     * ★ 延时 800ms 是为了让上面那句提示能被看见（立刻跳走的话
     *   ElMessage 会随页面卸载一起消失）。
     */
    setTimeout(() => {
      window.location.href = READER_WEB_URL
    }, 800)
    return false
  }

  return true
})

export default router
