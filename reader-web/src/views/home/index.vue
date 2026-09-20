<script setup>
/**
 * 首页 —— P0 阶段的验收页。
 *
 * 它同时证明三件事：
 *   ① 前端能跑（页面出来了）
 *   ② 代理能通（三个 /api/ping* 请求从 5173 转发到了 8080）
 *   ③ 登录态能共享（在登录页登录后写入 localStorage，跳到另外两个端就是已登录）
 *
 * ★ P0 时第三项是靠一个「模拟登录」按钮往 localStorage 里写假 token 来演示的，
 *   P1 接了真实认证后那个按钮已经删掉 —— 现在要验证第三项，
 *   请走 /login 用真账号登录（种子管理员 admin / admin123）。
 *
 * 这个页面本身是 P0 的验收页，之后会被真正的首页替换掉。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ping, pingDb, pingRedis } from '@/api/ping'
import { useUserStore } from '@/stores/user'
import { TOKEN_KEY, USER_KEY, AUTHOR_WEB_URL, ADMIN_WEB_URL } from '@/utils/auth'

const router = useRouter()
const userStore = useUserStore()

/** 三个检测项的结果。null = 还没测 / 正在测 */
const checks = ref([
  { key: 'app', label: '后端应用', desc: 'GET /api/ping', state: 'idle', data: null },
  { key: 'db', label: 'MySQL', desc: 'GET /api/ping/db', state: 'idle', data: null },
  { key: 'redis', label: 'Redis', desc: 'GET /api/ping/redis', state: 'idle', data: null },
])

const running = ref(false)

/** 把后端返回的数据塞进对应的检测项 */
function setResult(key, state, data) {
  const item = checks.value.find((c) => c.key === key)
  if (item) {
    item.state = state
    item.data = data
  }
}

async function runAllChecks() {
  running.value = true

  // 三个请求互相独立，用 allSettled 而不是 all ——
  // 这样即使 MySQL 挂了，Redis 的结果照样能显示出来，方便判断到底哪一环出问题
  const tasks = [
    ['app', ping],
    ['db', pingDb],
    ['redis', pingRedis],
  ]

  checks.value.forEach((c) => {
    c.state = 'loading'
    c.data = null
  })

  await Promise.allSettled(
    tasks.map(async ([key, fn]) => {
      try {
        setResult(key, 'ok', await fn())
      } catch (e) {
        setResult(key, 'fail', e.message)
      }
    }),
  )

  running.value = false
}

onMounted(runAllChecks)

/**
 * token 的展示形式：只截前 16 位。
 *
 * ★ P0 时这里存的是 'mock-token-for-p0-verification' 这种短短一截，
 *   整个塞进页面没问题；P1 换成了真的 JWT（200+ 个字符），
 *   整条渲染出来会把布局撑爆。反正这个卡片只是用来肉眼确认
 *   「登录态确实写进去了」，看开头几位就够了，完整值在
 *   DevTools → Application → Local Storage 里。
 */
const tokenPreview = computed(() => {
  const t = userStore.token || ''
  return t.length > 16 ? `${t.slice(0, 16)}…` : t
})

const stateType = { ok: 'success', fail: 'danger', loading: 'warning', idle: 'info' }
const stateText = { ok: '正常', fail: '失败', loading: '检测中', idle: '未检测' }

/**
 * 跨应用跳转。
 *
 * ★ 必须包成一个函数，不能直接在模板里写 window.location.href ——
 *   Vue 的模板表达式【不暴露 window 这个全局对象】，模板里的标识符
 *   会被编译成 _ctx.window，运行时是 undefined，直接报错。
 *   模板里能用的全局只有 Math / Date / JSON 那一小撮白名单。
 */
function gotoApp(url) {
  window.location.href = url
}

/** 去登录页。登录成功后登录页会自己跳回来（它认 ?redirect= 参数） */
function gotoLogin() {
  router.push('/login')
}
</script>

<template>
  <div class="home">
    <el-alert type="info" :closable="false" show-icon class="intro">
      <template #title>P0 阶段验收页</template>
      下面三张卡片验证「前端 → Vite 代理 → 后端 → 中间件」整条链路。后端没启动时它们会全部报错 ——
      这正是这个页面存在的意义。
    </el-alert>

    <!-- ============ ① 连通性检测 ============ -->
    <el-card shadow="never" class="section">
      <template #header>
        <div class="card-header">
          <span>连通性检测</span>
          <el-button type="primary" :loading="running" @click="runAllChecks">重新检测</el-button>
        </div>
      </template>

      <el-row :gutter="16">
        <el-col v-for="c in checks" :key="c.key" :xs="24" :sm="8">
          <div class="check-item" :class="c.state">
            <div class="check-head">
              <span class="check-label">{{ c.label }}</span>
              <el-tag :type="stateType[c.state]" size="small">
                {{ stateText[c.state] }}
              </el-tag>
            </div>
            <div class="check-desc">{{ c.desc }}</div>

            <div v-if="c.key === 'db' && c.state === 'ok'" class="check-detail">
              查到 {{ c.data.userCount }} 个用户
              <template v-if="c.data.admin">
                ，admin 的昵称是「{{ c.data.admin.nickname }}」
              </template>
            </div>
            <div v-if="c.key === 'redis' && c.state === 'ok'" class="check-detail">
              序列化往返 {{ c.data.serializationOk ? '正常' : '异常' }}
            </div>
            <div v-if="c.state === 'fail'" class="check-detail fail-detail">
              {{ c.data }}
            </div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- ============ ② 三端登录态共享 ============ -->
    <el-card shadow="never" class="section">
      <template #header>
        <span>三端登录态共享（项目大纲 §7.5）</span>
      </template>

      <p class="tip">
        三个前端是三个独立应用，唯一的连接点是 localStorage。只要读写
        <el-tag size="small" type="warning">{{ TOKEN_KEY }}</el-tag>
        和
        <el-tag size="small" type="warning">{{ USER_KEY }}</el-tag>
        这两个 key，在阅读端登录一次，另外两端就是免登录状态。
      </p>

      <el-descriptions :column="1" border size="small" class="desc">
        <el-descriptions-item label="当前登录态">
          <el-tag v-if="userStore.isLogin" type="success" size="small">已登录</el-tag>
          <el-tag v-else type="info" size="small">未登录</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="token">
          <code v-if="userStore.token">{{ tokenPreview }}</code>
          <span v-else class="muted">（空）</span>
        </el-descriptions-item>
        <el-descriptions-item label="角色">
          <span v-if="userStore.role">{{ userStore.role }}</span>
          <span v-else class="muted">（无）</span>
        </el-descriptions-item>
      </el-descriptions>

      <div class="actions">
        <el-button v-if="!userStore.isLogin" type="primary" @click="gotoLogin">去登录</el-button>
        <el-button v-else @click="userStore.logout()">退出登录</el-button>
        <el-button link type="primary" @click="gotoApp(AUTHOR_WEB_URL)">
          跳到创作中心 →
        </el-button>
        <el-button link type="primary" @click="gotoApp(ADMIN_WEB_URL)">
          跳到管理端 →
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.home {
  padding: 20px 0;
}

.intro {
  margin-bottom: 16px;
}

.section {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.check-item {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 12px 14px;
  margin-bottom: 12px;
  min-height: 96px;
}

.check-item.ok {
  border-color: #b3e19d;
  background: #f0f9eb;
}

.check-item.fail {
  border-color: #fab6b6;
  background: #fef0f0;
}

.check-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.check-label {
  font-weight: 600;
}

.check-desc {
  font-size: 12px;
  color: #909399;
}

.check-detail {
  margin-top: 8px;
  font-size: 12px;
  color: #606266;
  word-break: break-all;
}

.fail-detail {
  color: #f56c6c;
}

.tip {
  margin: 0 0 14px;
  font-size: 13px;
  color: #606266;
  line-height: 1.7;
}

.desc {
  margin-bottom: 14px;
}

.muted {
  color: #c0c4cc;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
</style>
