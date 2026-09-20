<script setup>
/**
 * 阅读端登录页。
 *
 * ★ 三个应用的登录页都跳这里 —— 见 项目大纲 §7.5 第 3 条约定：
 *   401 时统一跳回【阅读端】登录页，而不是各端跳各自的，
 *   否则用户会看到三个登录入口，不知道用哪个。
 *
 * P0 阶段这里只有个占位表单：真正的登录要等 P1 做完
 * Spring Security + JWT 的认证接口。现在点登录走的是「模拟登录」，
 * 写一份和首页那个按钮一样的假数据，用来验证三端共享。
 */
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const form = ref({ username: 'admin', password: '' })
const loading = ref(false)

function handleLogin() {
  loading.value = true

  // TODO(P1): 换成真实的 POST /api/auth/login
  setTimeout(() => {
    userStore.setLoginInfo('mock-token-for-p0-verification', {
      id: 1,
      username: form.value.username || 'admin',
      nickname: '系统管理员',
      role: 'ADMIN',
      permissions: ['novel:audit', 'user:manage'],
    })
    loading.value = false
    ElMessage.success('登录成功（P0 模拟登录，真实认证在 P1）')

    /*
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
    const redirect = route.query.redirect
    if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) {
      router.replace(redirect)
    } else if (typeof redirect === 'string' && redirect.startsWith('http')) {
      // 从另外两个端跳回来的，是完整地址，且在自己的 localhost 域内
      const url = new URL(redirect)
      if (['localhost', '127.0.0.1'].includes(url.hostname)) {
        window.location.href = redirect
        return
      }
      router.replace('/')
    } else {
      router.replace('/')
    }
  }, 400)
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-head">
        <h2>📖 小说阅读</h2>
        <p class="sub">登录后可在三个端之间免登录切换</p>
      </div>

      <el-alert type="warning" :closable="false" class="notice">
        P0 阶段是模拟登录，真实认证接口在 P1 实现
      </el-alert>

      <el-form :model="form" label-position="top" @submit.prevent="handleLogin">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="admin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="P0 阶段随便填"
            show-password
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

.notice {
  margin-bottom: 16px;
}

.submit {
  width: 100%;
}

.footer-links {
  text-align: center;
  font-size: 13px;
}
</style>
