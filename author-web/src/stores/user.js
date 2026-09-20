/**
 * 用户状态（Pinia）。
 *
 * ★ 三个前端各有一份【内容相同】的 stores/user.js，
 *   它们读写的是同一份 localStorage（见 utils/auth.js 的说明）。
 *
 * 这里只是一层响应式包装：真正的存储在 utils/auth.js 的纯函数里。
 * 这么分是因为 axios 拦截器要在组件树外面调存储，那边用不了 Pinia。
 *
 * P1 起，这里多了 login() / logout() 两个 action —— 登录态终于
 * 是真的了（P0 阶段是往 localStorage 写假 token 验证「三端共享 key」）。
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as auth from '@/utils/auth'
import * as authApi from '@/api/auth'

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

  /**
   * 全部角色。
   *
   * ★ 后端的 userInfo 里同时给了 roles（数组，真实数据）和
   *   role（单个，后端按 ADMIN > AUTHOR > READER 算好的最高角色）。
   *   上面几个 getter 用的都是 role —— 这样 P0 阶段已经验收过的
   *   判断逻辑一行都不用改。roles 留着给 P2 做多角色界面用。
   */
  const roles = computed(() => userInfo.value?.roles || [])

  // ---------- actions ----------

  /** 登录成功后调用（P1 接入真实登录接口时用） */
  function setLoginInfo(newToken, user) {
    token.value = newToken
    userInfo.value = user
    auth.setToken(newToken)
    auth.setUser(user)
  }

  /**
   * 登录。
   *
   * 成功后写入 token 和用户信息；失败时【不吞异常】——
   * request.js 的拦截器已经弹过提示了，这里把异常继续抛出去，
   * 让调用方知道失败了（比如登录页要停止 loading 状态）。
   *
   * @returns {Promise<object>} 登录成功的用户信息
   */
  async function login(payload) {
    const data = await authApi.login(payload)
    setLoginInfo(data.token, data.user)
    return data.user
  }

  /**
   * 退出登录。
   *
   * ★ 顺序很重要：先调后端接口（让服务端把 Redis 里这条登录态删掉），
   *   再清本地。反过来的话，本地已经清空了、token 也没了，
   *   那个请求就发不出去（请求拦截器取不到 token）——
   *   结果是「前端看起来退出了，服务端那条记录还留着，
   *   直到 7 天后自然过期」。那个 token 如果被人拿到，依然能用。
   *
   * ★ 接口失败也必须清本地（finally 里做）：
   *   用户点「退出」的诉求是「让我下线」，只要本地清干净了，
   *   这个诉求就已经达成；后端那条记录删不掉是次要问题，
   *   不该反过来把用户卡在「点了退出还是登录状态」的窘境里。
   *   而且最常见的失败原因恰恰是 token 已过期（后端返回 401），
   *   那种情况下本地本来也该清。
   */
  async function logout() {
    try {
      if (token.value) {
        await authApi.logout()
      }
    } catch {
      // 静默：见上面第二点。失败原因（网络不通 / token 已过期）都无需打扰用户
    } finally {
      token.value = ''
      userInfo.value = null
      auth.clearAuth()
    }
  }

  /** 有没有某个权限点。例：hasPerm('content:novel:list') */
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
    roles,
    permissions,
    isAuthor,
    isAdmin,
    setLoginInfo,
    login,
    logout,
    hasPerm,
    hasRole,
    syncFromStorage,
  }
})
