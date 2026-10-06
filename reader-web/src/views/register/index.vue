<script setup>
/**
 * 阅读端注册页。
 *
 * ★ 后端 `POST /api/auth/register` 从 P1 就交付了（匿名可访问、网关 order:1 放行），
 *   一直缺的是这个页面 —— `docs/P1-认证权限.md:237` 当时写明了「故意不做注册页」。
 *   所以这一页**不需要任何后端改动**，只是把接口接上。
 *
 * ★★ 注册接口**不签发 token**（它返回的是 `UserVO`，不是登录接口那种
 *   `{token, user}`）。所以「注册成功后自动登录」= 拿刚填的用户名密码
 *   **再调一次登录**。这不是绕路，是接口的分工：注册负责建号，登录负责发凭证。
 *
 * ★★ 由此产生本页最需要小心的一件事：**注册成功、自动登录失败**是个
 *   【半成功】状态 —— 库里已经有这个人了。UI 必须照实说（「注册成功，请登录」），
 *   既不能说成失败（用户会换个名字再注册一次，于是留下两个账号），
 *   也不能装作已登录（他其实没有 token，下一步任何操作都会 401）。
 *
 * ★ 校验为什么两处都写：前端这道是「不用等一次往返、提示能照着改」，
 *   后端 `RegisterDTO` 那道是「就算前端被绕过也挡得住」。见登录页同款注释。
 */
import { reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { register } from '@/api/auth'
import { useUserStore } from '@/stores/user'
import { goAfterLogin, withRedirect } from '@/utils/loginRedirect'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref()
const submitting = ref(false)

const form = reactive({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
})

/**
 * 确认密码。
 *
 * ★ 它【没有】对应的后端字段 —— 后端只收 username / password / nickname。
 *   这一栏纯粹是为了「打错一个字就多出一个自己登不进去的账号」而存在的，
 *   提交时不会发出去。
 */
function validateConfirm(rule, value, callback) {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
    return
  }
  callback()
}

const rules = {
  username: [
    { required: true, message: '请填写用户名', trigger: 'blur' },
    // ★ 这条正则和后端 `RegisterDTO` 的 @Pattern 逐字一致。
    //   不一致的后果很具体：前端放过去的会被后端 400 拦下，
    //   用户看到的是一句和后端实现细节绑定的提示，而不是「照着改就行」。
    {
      pattern: /^[a-zA-Z0-9_]{3,20}$/,
      message: '用户名只能是 3~20 位的字母、数字或下划线',
      trigger: 'blur',
    },
  ],
  password: [
    { required: true, message: '请填写密码', trigger: 'blur' },
    // ★ 6~32 对齐后端 `RegisterDTO` 的 @Size(min = 6, max = 32)。
    //   ★★ 注意登录页**没有**这条 min —— 那边刻意不校验密码复杂度，
    //      理由见 `LoginDTO.java`（会挡住历史短密码账号，且反向泄露密码策略）。
    //      两页的规则不一样是【有意的】，别顺手统一。
    { min: 6, max: 32, message: '密码长度需要 6~32 位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再输入一次密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' },
  ],
  // 选填：不填时后端拿 username 兜底（`AuthServiceImpl` 里那一句），
  // 所以这里不需要 required，只要卡住长度上限。
  nickname: [{ max: 20, message: '昵称最多 20 个字', trigger: 'blur' }],
}

/*
 * ★ 「先填了确认密码、又回头改上面的密码」时，确认框底下的红字得跟着刷新。
 *   el-form 只在字段自己的 blur/change 时校验，不盯别人的变化 ——
 *   不加这一段，用户会看到一个已经不对的红字一直挂着，直到他再点一次提交。
 *
 * ★ 只在确认框【已经填了东西】时才校验：空的时候去 validate 会凭空弹出一句
 *   「请再输入一次密码」，那是在用户还没填完时指责他。
 */
watch(
  () => form.password,
  () => {
    if (!form.confirmPassword) return
    // validateField 校验不过会 reject —— 不接住就是一个「没人处理的 Promise」警告
    Promise.resolve(formRef.value?.validateField('confirmPassword')).catch(() => {})
  },
)

/**
 * 注册。
 *
 * ★ 这里【没有】自己的错误提示 —— request.js 的响应拦截器已经统一弹过了：
 *   用户名重复是业务码 1004（HTTP 200），参数不合法是 400（HTTP 400），
 *   两条路径都会带着后端那句 message 弹一次。再弹一次用户会看到两个框。
 */
async function handleRegister() {
  if (submitting.value) return // 网络慢时连点两次会建出两个账号（虽然同名会被唯一键挡掉）

  try {
    await formRef.value.validate()
  } catch {
    return // 校验没过，el-form 已经在字段下面标红了
  }

  submitting.value = true

  /*
   * ★ 这两个值【先算进局部变量】，注册与自动登录两处共用。
   *   在两处各写一遍表达式的代价不是「多打几个字」：将来有人在其中一处
   *   加了个处理（比如把密码也 trim 一下）、另一处忘了，症状就是
   *   「注册成功但自动登录失败」—— 一个看起来像网络抖动、实则是自己造的假故障。
   *
   * ★ 密码两处都不 trim：后端也不 trim。这里 trim 了，注册进库的密码
   *   就和用户以为的那个不是同一个 —— 而他下次登录会被 1002 挡住。
   */
  const username = form.username.trim()
  const password = form.password

  try {
    await register({
      username,
      password,
      // ★ 空串要转成 undefined：后端 nickname 是选填，但它不认空串和 null 的
      //   区别 —— 传 "" 进去就是「昵称是个空字符串」，页面上会显示成一片空白。
      //   （对齐 `applyAuthor/index.vue` 里 intro 的同一处理。）
      nickname: form.nickname.trim() || undefined,
    })
  } catch {
    // 注册失败（1004 用户名已被注册 / 400 参数不合法）——拦截器已提示，停在原页重试。
    // ★ 不清空已填内容：用户只需要改一个字段，别让他重打一遍密码。
    submitting.value = false
    return
  }

  /*
   * 走到这里 = 库里确实已经有这个人了。下面这一步失败【不能】说成「注册失败」。
   */
  try {
    const user = await userStore.login({ username, password })
    ElMessage.success(`注册成功，欢迎你，${user.nickname || user.username}`)
    goAfterLogin(router, route)
  } catch {
    // ★★ 半成功：注册成功但自动登录没成（密码没错，通常是网络抖动，
    //    或者后端在这一瞬间不可用）。说法必须与事实一致 —— 见文件头那颗星。
    ElMessage.warning('注册成功，请用刚才的密码登录')
    router.replace(withRedirect('/login', route))
  } finally {
    // ★ 放在 finally 里：两条路径都要让按钮恢复可点。
    //   半成功那条虽然页面要跳走，但跳转是异步的，用户可能已经看到按钮在转圈了。
    submitting.value = false
  }
}

/** 去登录页。★ 带上 redirect，别让「详情页 → 注册 → 登录」这一圈把目标弄丢 */
const loginPath = withRedirect('/login', route)
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-head">
        <h2>📖 小说阅读</h2>
        <p class="sub">注册一个读者账号，登录后可在三个端之间免登录切换</p>
      </div>

      <!--
        ★ 用 el-form 的 rules 而不是手写判空 —— 对齐 `applyAuthor/index.vue` 的范式。
          判空只能告诉用户「没填」，rules 能告诉他「哪里不对、该怎么改」。
      -->
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @submit.prevent="handleRegister"
      >
        <el-form-item label="用户名" prop="username">
          <el-input
            v-model="form.username"
            placeholder="3~20 位字母、数字或下划线"
            autocomplete="username"
            @keyup.enter="handleRegister"
          />
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="6~32 位"
            show-password
            autocomplete="new-password"
            @keyup.enter="handleRegister"
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="再输入一次"
            show-password
            autocomplete="new-password"
            @keyup.enter="handleRegister"
          />
        </el-form-item>

        <el-form-item label="昵称（选填）" prop="nickname">
          <el-input
            v-model="form.nickname"
            maxlength="20"
            placeholder="不填就用用户名"
            @keyup.enter="handleRegister"
          />
        </el-form-item>

        <el-button type="primary" class="submit" :loading="submitting" @click="handleRegister">
          注册并登录
        </el-button>
      </el-form>

      <el-divider />

      <div class="footer-links">
        <span class="muted">已经有账号了？</span>
        <router-link :to="loginPath">去登录</router-link>
        <span class="sep">·</span>
        <router-link to="/">← 返回首页</router-link>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
/*
 * ★ 这一套类名和布局是照 `views/login/index.vue` 抄的 —— 两个页面
 *   本来就是同一个视觉家族（同样的全屏卡片、同样的标题区），
 *   分成两套写只会慢慢长歪。改动时两边一起看。
 */
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

.footer-links .muted {
  color: #909399;
}

.footer-links .sep {
  margin: 0 6px;
  color: #dcdfe6;
}
</style>
