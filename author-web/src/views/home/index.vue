<script setup>
/**
 * 创作中心首页 —— P0 验收页。
 *
 * 重点看两件事：
 *   ① 这里显示的登录态是【从共享 localStorage 读来的】——
 *      在阅读端登录后跳过来，不需要再登一次
 *   ② 三个 /api/ping* 请求能通，说明 5174 的代理也配好了
 */
import { ref, onMounted } from 'vue'
import { ping, pingDb, pingRedis } from '@/api/ping'
import { useUserStore } from '@/stores/user'
import { TOKEN_KEY, USER_KEY, READER_WEB_URL } from '@/utils/auth'

const userStore = useUserStore()

const checks = ref([
  { key: 'app', label: '后端应用', url: 'GET /api/ping', state: 'idle', data: null },
  { key: 'db', label: 'MySQL', url: 'GET /api/ping/db', state: 'idle', data: null },
  { key: 'redis', label: 'Redis', url: 'GET /api/ping/redis', state: 'idle', data: null },
])

const running = ref(false)

async function runAllChecks() {
  running.value = true
  checks.value.forEach((c) => {
    c.state = 'loading'
    c.data = null
  })

  await Promise.allSettled(
    [
      ['app', ping],
      ['db', pingDb],
      ['redis', pingRedis],
    ].map(async ([key, fn]) => {
      const item = checks.value.find((c) => c.key === key)
      try {
        item.data = await fn()
        item.state = 'ok'
      } catch (e) {
        item.data = e.message
        item.state = 'fail'
      }
    }),
  )

  running.value = false
}

onMounted(runAllChecks)

function gotoApp(url) {
  window.location.href = url
}

const stateType = { ok: 'success', fail: 'danger', loading: 'warning', idle: 'info' }
const stateText = { ok: '正常', fail: '失败', loading: '检测中', idle: '未检测' }
</script>

<template>
  <div>
    <!-- 未登录时给一条明确的指引，而不是让用户对着空白页发呆 -->
    <el-alert
      v-if="!userStore.isLogin"
      type="warning"
      show-icon
      :closable="false"
      class="block"
      title="当前未登录"
    >
      请先到
      <el-link type="primary" @click="gotoApp(READER_WEB_URL + 'login')">阅读端登录</el-link>
      —— 登录态存在共用的 localStorage 里，登录后跳回来就是已登录。
    </el-alert>

    <!-- ============ ① 三端登录态共享 ============ -->
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>登录态（读自共享 localStorage）</span>
          <el-tag :type="userStore.isLogin ? 'success' : 'danger'" size="small">
            {{ userStore.isLogin ? '已登录' : '未登录' }}
          </el-tag>
        </div>
      </template>

      <p class="tip">
        下面这些值不是本应用存的 —— 是阅读端写进
        <el-tag size="small" type="warning">{{ TOKEN_KEY }}</el-tag> /
        <el-tag size="small" type="warning">{{ USER_KEY }}</el-tag>
        之后，本应用直接读出来的。三个端是三个独立应用，唯一的连接点就是这两个 key。
      </p>

      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="token">
          <code v-if="userStore.token">{{ userStore.token }}</code>
          <span v-else class="muted">（空）</span>
        </el-descriptions-item>
        <el-descriptions-item label="角色">
          <el-tag v-if="userStore.role" size="small">{{ userStore.role }}</el-tag>
          <span v-else class="muted">（无）</span>
        </el-descriptions-item>
        <el-descriptions-item label="昵称">
          {{ userStore.nickname || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="是作者">
          <el-tag :type="userStore.isAuthor ? 'success' : 'info'" size="small">
            {{ userStore.isAuthor ? '是' : '否' }}
          </el-tag>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- ============ ② 代理连通性 ============ -->
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>连通性检测（经网关 /api → :8080）</span>
          <el-button type="primary" size="small" :loading="running" @click="runAllChecks">
            重新检测
          </el-button>
        </div>
      </template>

      <el-row :gutter="16">
        <el-col v-for="c in checks" :key="c.key" :xs="24" :sm="8">
          <div class="check-item" :class="c.state">
            <div class="check-head">
              <span class="check-label">{{ c.label }}</span>
              <el-tag :type="stateType[c.state]" size="small">{{ stateText[c.state] }}</el-tag>
            </div>
            <div class="check-desc">{{ c.url }}</div>
            <div v-if="c.key === 'db' && c.state === 'ok'" class="check-detail">
              查到 {{ c.data.userCount }} 个用户
            </div>
            <div v-if="c.state === 'fail'" class="check-detail fail-detail">{{ c.data }}</div>
          </div>
        </el-col>
      </el-row>
    </el-card>
  </div>
</template>

<style scoped>
.block {
  margin-bottom: 16px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.tip {
  margin: 0 0 14px;
  font-size: 13px;
  color: #606266;
  line-height: 1.7;
}

.muted {
  color: #c0c4cc;
}

.check-item {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 12px 14px;
  margin-bottom: 12px;
  min-height: 88px;
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
</style>
