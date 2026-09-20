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
 * 和 author-web 一样，P0 阶段只检查「有没有 token」，角色不符只提示不拦截，
 * 目的是先把「三端共享 key」验收掉。P1 拿到真实角色后改成硬拦截。
 */
const ENFORCE_ROLE = false // TODO(P1): 改成 true

router.beforeEach((to) => {
  const baseTitle = import.meta.env.VITE_APP_TITLE || '管理端'
  document.title = to.meta.title ? `${to.meta.title} - ${baseTitle}` : baseTitle

  if (!getToken()) {
    redirectToReaderLogin()
    return false
  }

  const user = getUser()
  if (user?.role !== 'ADMIN') {
    if (ENFORCE_ROLE) {
      ElMessage.warning('管理端仅对管理员开放')
      window.location.href = READER_WEB_URL
      return false
    }
    ElMessage.warning('当前账号不是管理员，P0 阶段先放行（P1 会硬拦截）')
  }

  return true
})

export default router
