<script setup>
/**
 * 注册页（移动端）。
 *
 * ★ 和桌面端阅读端注册页接的是**同一个**后端接口 `POST /api/auth/register`，
 *   写的是同一份 localStorage（`novel_token` / `novel_user`）——
 *   所以在这注册完，打开 /reader/ 也是登录态。
 *
 * ★★ 注册接口**不签发 token**（它返回 `UserVO`，不是登录接口那种 `{token, user}`）。
 *   所以「注册成功后自动登录」= 拿刚填的用户名密码**再调一次登录**。
 *   这不是绕路，是接口的分工：注册负责建号，登录负责发凭证。
 *
 * ★★ 由此产生本页最需要小心的一件事：**注册成功、自动登录失败**是个
 *   【半成功】状态 —— 库里已经有这个人了。UI 必须照实说
 *   （「注册成功，请手动登录」），既不能说成失败（用户会换个名字重注册，
 *   于是留下两个账号），也不能装作已登录（他没有 token，下一步必然 401）。
 *
 * ★ 校验为什么两处都写：前端这道是「不用等一次往返、提示能照着改」，
 *   后端 `RegisterDTO` 那道是「就算前端被绕过也挡得住」。
 */
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { useUserStore } from '@/stores/user'
import { register } from '@/api/auth'
import { goAfterLogin, withRedirect } from '@/utils/redirect'

const userStore = useUserStore()

const form = ref({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
})

/**
 * 校验失败的那一句。★ 一次只显示一条（uni 这边没有 el-form 那种逐字段标红，
 * 范式见 `pages/applyAuthor/applyAuthor.vue` 的 `formError`）——
 * 按字段顺序从上往下报，用户改完第一条再看到第二条即可。
 */
const formError = ref('')
const submitting = ref(false)

/**
 * 登录成功后要回到哪。★ 与登录页同一套语义：是【页面路径】
 * （形如 /pages/reader/reader?id=3），由 utils/auth.js 的 redirectToLogin 在 401 时带上。
 */
const redirect = ref('')

onLoad((options) => {
  // ★ 上层用 encodeURIComponent 编过（见 utils/redirect.js），这里要解回来
  redirect.value = options?.redirect ? decodeURIComponent(options.redirect) : ''
})

/**
 * 逐条校验，返回第一条不合格的说明（空串 = 通过）。
 *
 * ★ 六条的顺序是有讲究的：先报「没填」，再报「格式不对」，最后才报跨字段的
 *   「两次密码不一致」—— 一个字段里的问题没解决之前，说后面的没有意义。
 *
 * ★★ 规则逐条对齐后端 `RegisterDTO`：用户名 `^[a-zA-Z0-9_]{3,20}$`、
 *   密码 `@Size(6,32)`、昵称选填 `max 20`。**不一致的后果很具体**：
 *   前端放过去的会被后端 400 拦下，用户看到的是一句和后端实现绑定的提示，
 *   而不是「照着改就行」。
 *
 * ★ 密码一律**不 trim** —— 后端也不 trim，这里 trim 了，注册进库的密码
 *   就和用户以为的那个不是同一个，而他下次登录会被 1002 挡住。
 */
function validate() {
  const username = form.value.username.trim()
  if (!username) return '请输入用户名'
  if (!/^[a-zA-Z0-9_]{3,20}$/.test(username)) return '用户名只能是 3~20 位的字母、数字或下划线'

  const password = form.value.password
  if (!password) return '请输入密码'
  if (password.length < 6 || password.length > 32) return '密码长度需在 6~32 位之间'

  if (!form.value.confirmPassword) return '请再次输入密码'
  if (form.value.confirmPassword !== password) return '两次输入的密码不一致'

  if (form.value.nickname.trim().length > 20) return '昵称最多 20 个字'

  return ''
}

/**
 * 注册。
 *
 * ★ 这里**没有**自己的错误 toast —— utils/request.js 已经统一弹过了：
 *   用户名重复是业务码 1004，参数不合法是 400（HTTP 400），
 *   两条在 uni.request 里都进 success 分支、由封装分别判 statusCode 与 code 后弹 message。
 *   页面再弹一次，用户会看到两个一模一样的提示。
 */
async function handleRegister() {
  if (submitting.value) return // 网络慢时连点两次会建出两个账号

  formError.value = validate()
  if (formError.value) return // 提示就显示在表单里，不再弹 toast（同 applyAuthor 的做法）

  /*
   * ★ 这两个值【先算进局部变量】，注册与自动登录两处共用 ——
   *   在两处各写一遍表达式，将来只改了其中一处，症状就是
   *   「注册成功但自动登录失败」这种看着像网络抖动的假故障。
   *   （与桌面端注册页同一条规矩。）
   */
  const username = form.value.username.trim()
  const password = form.value.password

  submitting.value = true

  try {
    await register({
      username,
      password,
      // ★ 空串转 undefined：后端昵称是选填，但空串和没传不是一回事 ——
      //   传 "" 进去昵称就是一个空字符串，页面上显示成一片空白
      nickname: form.value.nickname.trim() || undefined,
    })
  } catch {
    // 注册失败（1004 用户名已被注册 / 400 参数不合法）——封装已提示，
    // ★ 不清空已填内容：用户只需要改一个字段，别让他重打一遍密码
    submitting.value = false
    return
  }

  /* 走到这里 = 库里确实已经有这个人了。下面这一步失败【不能】说成「注册失败」 */
  try {
    const user = await userStore.login({ username, password })
    uni.showToast({ title: `注册成功，欢迎加入，${user.nickname || user.username}`, icon: 'none' })
    // ★ 稍等一下再跳，否则 toast 会被页面切换直接吞掉（同登录页那条）
    setTimeout(() => goAfterLogin(redirect.value), 600)
  } catch {
    // ★★ 半成功：注册成功但自动登录没成。说法必须与事实一致
    uni.showToast({ title: '注册成功，请手动登录', icon: 'none' })
    setTimeout(() => uni.reLaunch({ url: withRedirect('/pages/login/login', redirect.value) }), 600)
  } finally {
    submitting.value = false
  }
}

/** 去登录页。★ 带上 redirect，别让「详情页 → 注册 → 登录」这一圈把目标弄丢 */
function goLogin() {
  uni.reLaunch({ url: withRedirect('/pages/login/login', redirect.value) })
}
</script>

<template>
  <view class="page">
    <view class="head">
      <text class="logo">📖</text>
      <text class="title">注册</text>
      <text class="sub">注册后四个端免登录切换</text>
    </view>

    <view class="form">
      <view class="field">
        <text class="label">用户名</text>
        <input
          v-model="form.username"
          class="input"
          type="text"
          placeholder="3~20 位字母、数字或下划线"
          placeholder-class="ph"
          :adjust-position="true"
          @confirm="handleRegister"
        />
      </view>

      <view class="field">
        <text class="label">密码</text>
        <!--
          ★ uni 的 input 用 `password` 属性表示密码框，不是 type="password"
            —— 写错了会得到一个明文输入框，而且**不会有任何报错**
            （多余的 type 值被忽略）。见 pages/login/login.vue 同款注释。
        -->
        <input
          v-model="form.password"
          class="input"
          type="text"
          password
          placeholder="6~32 位"
          placeholder-class="ph"
          :adjust-position="true"
          @confirm="handleRegister"
        />
      </view>

      <view class="field">
        <text class="label">确认密码</text>
        <!--
          ★ 这一栏【没有】对应的后端字段，提交时不会发出去。
            它存在的理由是本系统**没有找回密码**：type=password 看不见输入，
            打错一个字就是一个自己永远登不进去的账号，而且没有任何自助恢复途径。
        -->
        <input
          v-model="form.confirmPassword"
          class="input"
          type="text"
          password
          placeholder="再输入一次"
          placeholder-class="ph"
          :adjust-position="true"
          @confirm="handleRegister"
        />
      </view>

      <view class="field">
        <text class="label">昵称（选填）</text>
        <input
          v-model="form.nickname"
          class="input"
          type="text"
          :maxlength="20"
          placeholder="不填就用用户名"
          placeholder-class="ph"
          :adjust-position="true"
          @confirm="handleRegister"
        />
      </view>

      <!-- 校验提示放在按钮上方：在小屏上，放在最下面会被键盘挡住 -->
      <text v-if="formError" class="err">{{ formError }}</text>

      <button class="submit" :disabled="submitting" @click="handleRegister">
        {{ submitting ? '注册中…' : '注册并登录' }}
      </button>

      <text class="ghost" @click="goLogin">已有账号？去登录</text>
    </view>
  </view>
</template>

<style scoped>
/*
 * ★ 这一套样式是照 `pages/login/login.vue` 抄的 —— 两个页面是同一个视觉家族
 *   （同样的全屏渐变、同样的白色表单卡），分成两套写只会慢慢长歪。
 *   改动时两边一起看。★ 全 rpx、scoped；没有共享登录样式文件（第三份表单页
 *   出现时才值得抽，这是既定的判断）。
 */
.page {
  min-height: 100vh;
  padding: 0 48rpx;
  background: linear-gradient(160deg, #e8f0ff 0%, #f5f6f8 45%);
}

.head {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 90rpx 0 50rpx;
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

/* 校验提示。★ 用红色文字而不是 toast：它是「这个字段不对」，要贴着表单看 */
.err {
  display: block;
  margin-bottom: 16rpx;
  font-size: 26rpx;
  color: #e45656;
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
