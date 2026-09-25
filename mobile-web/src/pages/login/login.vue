<script setup>
/**
 * 登录页（移动端）。
 *
 * ★ 接的是同一个后端接口 POST /api/auth/login，写的是同一份 localStorage
 *   （`novel_token` / `novel_user`）—— 所以在这登完，
 *   打开 /reader/ 或 /admin/ 就是已登录状态。这是判断 2 的直接应用。
 *
 * ★★ 与桌面端的一处**刻意不同**：桌面端三个应用 401 时统一跳到
 *    【阅读端】的登录页（大纲 §7.5 第 3 条），移动端跳自己的。
 *    理由写在 utils/auth.js 的 redirectToLogin 上，核心是
 *    「移动端要出小程序/App，那些运行时里 '/reader/login' 这个地址不存在」。
 */
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

const form = ref({ username: '', password: '' })
const loading = ref(false)

/**
 * 登录成功后要回到哪。
 *
 * ★ 由 utils/auth.js 的 redirectToLogin 在 401 跳转时带上，
 *   是【页面路径】（形如 /pages/reader/reader?id=3），不是地址、更不是 token。
 */
const redirect = ref('')

onLoad((options) => {
  // ★ uni 会把 query 里的值原样给出来 —— 但我们对它做了一次
  //   encodeURIComponent（见 auth.js），所以这里要解回来
  redirect.value = options?.redirect ? decodeURIComponent(options.redirect) : ''
})

/**
 * 登录。
 *
 * ★ 这里没有 try-catch 弹错误提示 —— request.js 已经统一弹过了
 *   （业务错误弹 message、401 清登录态并跳登录页）。再弹一次
 *   用户会看到两个一模一样的提示框。
 *   这里只负责「成功了做什么」和「无论如何都要关掉 loading」。
 */
async function handleLogin() {
  if (loading.value) return // 防止网络慢时连点两次，发出两个登录请求

  /*
   * 前端先挡一道空值。
   *
   * ★ 后端 LoginDTO 上有 @NotBlank，空值也能挡住 —— 但那样返回的是
   *   code 400「请求内容格式不正确」，对一个只是忘了填用户名的用户来说
   *   这个提示毫无帮助。前端校验的意义是给出「能看懂、能照着改」的提示，
   *   后端校验的意义是「就算前端被绕过也挡得住」—— 两者都要有。
   */
  if (!form.value.username || !form.value.password) {
    uni.showToast({ title: '请填写用户名和密码', icon: 'none' })
    return
  }

  loading.value = true

  try {
    const user = await userStore.login({
      username: form.value.username.trim(),
      password: form.value.password,
    })
    // ★ 移动端用 showToast 而不是 showModal：登录成功是一个"知道了"的
    //   瞬时反馈，弹一个需要点「确定」的对话框会多一次无意义的操作。
    uni.showToast({ title: `欢迎回来，${user.nickname || user.username}`, icon: 'none' })
    // ★ 稍等一下再跳，否则 toast 会被页面切换直接吞掉 ——
    //   用户只看到闪了一下，不知道成没成功
    setTimeout(goAfterLogin, 600)
  } catch {
    // 失败原因已经由 request.js 提示过；这里什么都不做，让用户留在当前页重试
  } finally {
    // ★ 放在 finally 里：失败时也必须把按钮恢复可点，
    //   否则用户输错一次密码，按钮就永远转圈了
    loading.value = false
  }
}

/**
 * 登录成功后跳到来源页。
 *
 * ★★ 只接受以 `/pages/` 开头的路径 —— 这是移动端版本的**开放重定向**防护。
 *    桌面端那边挡的是 `startsWith('/') && !startsWith('//')`（浏览器地址），
 *    这里挡的必须是 uni 的**页面路径命名空间**：`redirect` 是个页面路径，
 *    任何不以 /pages/ 开头的东西（绝对 URL、`//evil.com`、
 *    `javascript:` 之类）都不该被 reLaunch 拿去用。
 *    ★ 少了这一道，攻击者构造 ?redirect=https://钓鱼站 就能把用户带走。
 */
function goAfterLogin() {
  const target = redirect.value
  if (target && target.startsWith('/pages/')) {
    uni.reLaunch({ url: target })
    return
  }
  uni.reLaunch({ url: '/pages/index/index' })
}

/** 先随便看看 —— 读者端的浏览路径本来就不需要登录 */
function goHome() {
  uni.reLaunch({ url: '/pages/index/index' })
}
</script>

<template>
  <view class="page">
    <view class="head">
      <text class="logo">📖</text>
      <text class="title">小说阅读</text>
      <text class="sub">登录后四个端免登录切换</text>
    </view>

    <view class="form">
      <view class="field">
        <text class="label">用户名</text>
        <input
          v-model="form.username"
          class="input"
          type="text"
          placeholder="请输入用户名"
          placeholder-class="ph"
          :adjust-position="true"
          @confirm="handleLogin"
        />
      </view>

      <view class="field">
        <text class="label">密码</text>
        <!--
          ★ uni 的 input 用 `password` 属性表示密码框，不是 type="password"
            —— 这是它和 HTML 的一处差别，写错了会得到一个明文输入框，
            而且**不会有任何报错**（多余的 type 值被忽略）。
        -->
        <input
          v-model="form.password"
          class="input"
          type="text"
          password
          placeholder="请输入密码"
          placeholder-class="ph"
          :adjust-position="true"
          @confirm="handleLogin"
        />
      </view>

      <button class="submit" :disabled="loading" @click="handleLogin">
        {{ loading ? '登录中…' : '登录' }}
      </button>

      <text class="ghost" @click="goHome">先随便看看 →</text>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 0 48rpx;
  background: linear-gradient(160deg, #e8f0ff 0%, #f5f6f8 45%);
}

.head {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 120rpx 0 60rpx;
}

.logo {
  font-size: 96rpx;
  line-height: 1;
}

.title {
  margin-top: 20rpx;
  font-size: 44rpx;
  font-weight: 600;
  color: #1f2733;
}

.sub {
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #8a93a0;
}

.form {
  padding: 40rpx 32rpx;
  background: #fff;
  border-radius: 20rpx;
}

.field {
  margin-bottom: 32rpx;
}

.label {
  display: block;
  margin-bottom: 12rpx;
  font-size: 26rpx;
  color: #5a6472;
}

.input {
  height: 88rpx;
  padding: 0 24rpx;
  background: #f5f6f8;
  border-radius: 12rpx;
  font-size: 30rpx;
  color: #1f2733;
}

/*
 * ★ placeholder 的样式要用 `placeholder-class` 指定一个类名，
 *   不能在 .input 里写 `::placeholder` —— uni 在小程序端不认那个伪元素。
 */
.ph {
  color: #b6bdc7;
}

.submit {
  margin-top: 8rpx;
  height: 88rpx;
  line-height: 88rpx;
  border-radius: 12rpx;
  background: #2f6fed;
  color: #fff;
  font-size: 32rpx;
}

/* ★ 禁用态必须自己写：uni 的 button 禁用后只是不触发点击，样式要另给 */
.submit[disabled] {
  background: #a9c2f5;
  color: #fff;
}

.ghost {
  display: block;
  margin-top: 32rpx;
  text-align: center;
  font-size: 26rpx;
  color: #2f6fed;
}
</style>
