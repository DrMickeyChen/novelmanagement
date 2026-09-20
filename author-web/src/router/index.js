import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import 'element-plus/es/components/message/style/css'

import AppLayout from '@/layouts/AppLayout.vue'
import { getToken, getUser, clearAuth, redirectToReaderLogin, READER_WEB_URL } from '@/utils/auth'

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
 * P0 阶段为了能验收到「三端共享 key」，这里【故意放宽】了 ——
 * 只要 token 存在就放行，角色不符只是提示不拦截。
 * 等 P1 的真登录接口做完、能签发真实角色后再收紧成硬拦截。
 * 收紧要改的就是下面这一行，注释写在这里免得以后忘。
 */
const ENFORCE_ROLE = false // TODO(P1): 改成 true

router.beforeEach((to) => {
  const baseTitle = import.meta.env.VITE_APP_TITLE || '创作中心'
  document.title = to.meta.title ? `${to.meta.title} - ${baseTitle}` : baseTitle

  const token = getToken()

  // 没登录 → 打到阅读端登录页，登录完会跳回这里
  if (!token) {
    redirectToReaderLogin()
    return false
  }

  const user = getUser()
  const allowed = user?.role === 'AUTHOR' || user?.role === 'ADMIN'

  if (!allowed) {
    if (ENFORCE_ROLE) {
      ElMessage.warning('创作中心仅对作者开放')
      window.location.href = READER_WEB_URL
      return false
    }
    ElMessage.warning('当前账号不是作者，P0 阶段先放行（P1 会硬拦截）')
  }

  return true
})

export default router
