/**
 * 用户状态（Pinia）。
 *
 * ★ 三个前端各有一份【内容相同】的 stores/user.js，
 *   它们读写的是同一份 localStorage（见 utils/auth.js 的说明）。
 *
 * 这里只是一层响应式包装：真正的存储在 utils/auth.js 的纯函数里。
 * 这么分是因为 axios 拦截器要在组件树外面调存储，那边用不了 Pinia。
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as auth from '@/utils/auth'

export const useUserStore = defineStore('user', () => {
  // ---------- state ----------
  // 初始值直接从 localStorage 读 —— 这样刷新页面登录态不会丢
  const token = ref(auth.getToken() || '')
  const userInfo = ref(auth.getUser())

  // ---------- getters ----------
  const isLogin = computed(() => !!token.value)
  const nickname = computed(() => userInfo.value?.nickname || '')
  const role = computed(() => userInfo.value?.role || '')
  const permissions = computed(() => userInfo.value?.permissions || [])

  const isAuthor = computed(() => role.value === 'AUTHOR' || role.value === 'ADMIN')
  const isAdmin = computed(() => role.value === 'ADMIN')

  // ---------- actions ----------

  /** 登录成功后调用（P1 接入真实登录接口时用） */
  function setLoginInfo(newToken, user) {
    token.value = newToken
    userInfo.value = user
    auth.setToken(newToken)
    auth.setUser(user)
  }

  /** 退出登录 */
  function logout() {
    token.value = ''
    userInfo.value = null
    auth.clearAuth()
  }

  /** 有没有某个权限点。例：hasPerm('novel:audit') */
  function hasPerm(code) {
    return permissions.value.includes(code)
  }

  /** 是不是某个角色 */
  function hasRole(target) {
    return role.value === target
  }

  /**
   * 从 localStorage 重新同步。
   *
   * 场景：用户在阅读端登录，然后在【另一个标签页】打开创作中心。
   * 那个新标签是全新加载，本来就会读到最新的 —— 所以这个方法主要给
   * 下面的 storage 事件用。
   */
  function syncFromStorage() {
    token.value = auth.getToken() || ''
    userInfo.value = auth.getUser()
  }

  /**
   * ★ 监听 storage 事件，实现「跨标签页登录态同步」。
   *
   * storage 事件有个关键特性：只有【别的标签页】改了 localStorage 才会触发，
   * 当前标签页自己改的不触发（否则会和上面的 setLoginInfo 重复处理）。
   *
   * 这正好是我们想要的：用户在阅读端标签页点了登录，
   * 已经开着的创作中心标签页会立刻跟着变成已登录，不用手动刷新。
   *
   * 没这个监听也能用（刷新一下就行），但加上之后体验明显更顺 ——
   * 而且这是「多端应用」面试里很容易被追问到的细节。
   */
  window.addEventListener('storage', (e) => {
    if (e.key === auth.TOKEN_KEY || e.key === auth.USER_KEY) {
      syncFromStorage()
    }
  })

  return {
    token,
    userInfo,
    isLogin,
    nickname,
    role,
    permissions,
    isAuthor,
    isAdmin,
    setLoginInfo,
    logout,
    hasPerm,
    hasRole,
    syncFromStorage,
  }
})
