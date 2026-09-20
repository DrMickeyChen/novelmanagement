<script setup>
/**
 * 阅读端登录页。
 *
 * ★ 三个应用的登录页都跳这里 —— 见 项目大纲 §7.5 第 3 条约定：
 *   401 时统一跳回【阅读端】登录页，而不是各端跳各自的，
 *   否则用户会看到三个登录入口，不知道用哪个。
 *
 * P1 起这里接的是真实的后端接口 POST /api/auth/login，
 * 成功后拿到真正的 JWT 和当前用户的角色，再写进 localStorage，
 * 这样跳到另外两个端就是已登录状态。
 */
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const form = ref({ username: '', password: '' })
const loading = ref(false)

/**
 * 登录。
 *
 * ★ 这里没有 try-catch 弹错误提示 —— 因为 request.js 的响应拦截器
 *   已经统一弹过了（业务错误弹 message、401 清登录态并跳登录页）。
 *   再弹一次用户会看到两个一模一样的提示框。
 *   这里只负责「成功了做什么」和「无论如何都要关掉 loading」。
 */
async function handleLogin() {
  if (loading.value) return // 防止网络慢时连点两次，发出两个登录请求

  /*
   * 前端先挡一道空值。
   *
   * ★ 后端 LoginDTO 上有 @NotBlank，空值也能挡住 —— 但那样返回的是
   *   code 400「请求内容格式不正确」，对一个只是忘了填用户名的用户来说
   *   这个提示毫无帮助（他不知道自己哪里格式不对）。
   *   前端校验的意义是给出「能看懂、能照着改」的提示，
   *   后端校验的意义是「就算前端被绕过也挡得住」—— 两者都要有，缺一不可。
   */
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请填写用户名和密码')
    return
  }

  loading.value = true

  try {
    const user = await userStore.login({
      username: form.value.username.trim(),
      password: form.value.password,
    })
    ElMessage.success(`欢迎回来，${user.nickname || user.username}`)
    goAfterLogin()
  } catch {
    // 失败原因已经由拦截器提示过；这里什么都不做，让用户留在当前页重试
  } finally {
    // ★ 放在 finally 里：失败时也必须把按钮恢复可点，
    //   否则用户输错一次密码，按钮就永远转圈了
    loading.value = false
  }
}

/**
 * 登录成功后跳回来源页。
 *
 * redirect 是 auth.js 里 401 跳转时带上的参数 ——
 * 用户在创作中心 token 过期被踢回来，重新登录后应该回到创作中心，
 * 而不是停在阅读端首页。
 *
 * ★ 这里只接受【本应用内】的相对路径：如果直接把外部 URL 拿来跳，
 *   就是个开放重定向漏洞（攻击者构造 ?redirect=https://钓鱼站 骗用户点）。
 *   所以用 startsWith('/') 卡一道，「//evil.com」这种也要一起挡掉。
 */
function goAfterLogin() {
  const redirect = route.query.redirect

  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
    router.replace(redirect)
    return
  }

  if (typeof redirect === 'string' && redirect.startsWith('http')) {
    // 从另外两个端跳回来的，是完整地址，且在自己的 localhost 域内
    try {
      const url = new URL(redirect)
      if (['localhost', '127.0.0.1'].includes(url.hostname)) {
        window.location.href = redirect
        return
      }
    } catch {
      // redirect 是个不成形的地址（比如手写的 ?redirect=httpfoo）。
      // new URL() 会抛 TypeError —— 不接住的话这个异常会冒到 handleLogin
      // 的 catch 里被静默吞掉，用户看到「欢迎回来」却停在原地不动。
      // 落到下面走默认跳转即可。
    }
  }

  router.replace('/')
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-head">
        <h2>📖 小说阅读</h2>
        <p class="sub">登录后可在三个端之间免登录切换</p>
      </div>

      <el-form :model="form" label-position="top" @submit.prevent="handleLogin">
        <el-form-item label="用户名">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            autocomplete="username"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            show-password
            autocomplete="current-password"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-button type="primary" class="submit" :loading="loading" @click="handleLogin">
          登录
        </el-button>
      </el-form>

      <el-divider />

      <div class="footer-links">
        <router-link to="/">← 返回首页</router-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #e8f0ff 0%, #f5f7fa 100%);
}

.login-card {
  width: 380px;
}

.login-head {
  text-align: center;
  margin-bottom: 16px;
}

.login-head h2 {
  margin: 0 0 6px;
  color: #409eff;
}

.sub {
  margin: 0;
  font-size: 13px;
  color: #909399;
}

.submit {
  width: 100%;
}

.footer-links {
  text-align: center;
  font-size: 13px;
}
</style>
