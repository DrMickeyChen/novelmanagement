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
// ★ 跳转（含开放重定向防护）抽在 utils 里，注册页用的是同一份 —— 见那个文件头
import { goAfterLogin, withRedirect } from '@/utils/loginRedirect'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref()
const form = ref({ username: '', password: '' })
const loading = ref(false)

/**
 * 校验规则。
 *
 * ★★ **故意比注册页松** —— 注册页有 `min: 6`，这里没有。
 *   后端 `LoginDTO` 上同样只有 `@Size(max = 64)`、**没有 min**，理由写在
 *   `LoginDTO.java:29-37`：登录不该校验密码复杂度，否则历史短密码账号会被
 *   自己的登录页挡在门外，而且「至少 6 位」这句提示本身就泄露了密码策略。
 *   ★ 别为了「两页一致」把它抄过来。
 *
 * ★ 长度上限那条也不是装饰：`@Size(max = 50/64)` 是后端真实存在的约束，
 *   前端挡住超长输入可以省掉一次注定失败的往返。
 */
const rules = {
  username: [
    { required: true, message: '请填写用户名', trigger: 'blur' },
    { max: 50, message: '用户名过长', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请填写密码', trigger: 'blur' },
    // ★ 和后端 `@Size(max = 64)` 一致。★ 没有 min —— 见上面那段说明
    { max: 64, message: '密码过长', trigger: 'blur' },
  ],
}

/** 去注册页。★ 带上 redirect —— 别让「详情页 → 登录 → 注册」这一圈把目标弄丢 */
const registerPath = withRedirect('/register', route)

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
   * 前端先挡一道。
   *
   * ★ 后端 LoginDTO 上有 @NotBlank，空值也能挡住 —— 但那样返回的是
   *   code 400「请求内容格式不正确」，对一个只是忘了填用户名的用户来说
   *   这个提示毫无帮助（他不知道自己哪里格式不对）。
   *   前端校验的意义是给出「能看懂、能照着改」的提示，
   *   后端校验的意义是「就算前端被绕过也挡得住」—— 两者都要有，缺一不可。
   *
   * ★ 这里由「手写判空」改成 el-form 的 rules（对齐 `applyAuthor/index.vue`
   *   的范式）：判空只能说「不对」，rules 能把提示挂在**具体那个字段**下面。
   */
  try {
    await formRef.value.validate()
  } catch {
    return // 校验没过，el-form 已经在字段下面标红了
  }

  loading.value = true

  try {
    const user = await userStore.login({
      username: form.value.username.trim(),
      password: form.value.password,
    })
    ElMessage.success(`欢迎回来，${user.nickname || user.username}`)
    goAfterLogin(router, route)
  } catch {
    // 失败原因已经由拦截器提示过；这里什么都不做，让用户留在当前页重试
  } finally {
    // ★ 放在 finally 里：失败时也必须把按钮恢复可点，
    //   否则用户输错一次密码，按钮就永远转圈了
    loading.value = false
  }
}

/*
 * 「登录成功后跳回来源页」这段逻辑（含开放重定向防护）已经搬到
 * `utils/loginRedirect.js` —— 注册页要用同一份。
 * ★ 那是安全逻辑，复制两份的代价不是「多几十行」，而是
 *   「以后只会修其中一份，另一份还是漏的」。
 */
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-head">
        <h2>📖 小说阅读</h2>
        <p class="sub">登录后可在三个端之间免登录切换</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="handleLogin"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            autocomplete="username"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item label="密码" prop="password">
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
        <!--
          ★ 带上 redirect：从详情页点「去登录」、再点这里转去注册的人，
            注册完应该回到那本书，而不是被丢在首页（同 LoginPrompt 规矩 ④）。
        -->
        <router-link :to="registerPath">还没有账号？立即注册</router-link>
        <span class="sep">·</span>
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

/* 注册链接与「返回首页」之间的分隔点 */
.footer-links .sep {
  margin: 0 6px;
  color: #dcdfe6;
}
</style>
