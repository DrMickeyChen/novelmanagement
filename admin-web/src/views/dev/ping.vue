<script setup>
/**
 * 管理端连通性自检页 —— **原 P0 的工作台页，P5 挪到这里**。
 *
 * ------------------------------------------------------------
 * ★ 为什么不删掉
 * ------------------------------------------------------------
 * 它是 P0 的验收页（三端共享 localStorage、后端/MySQL/Redis 三连通），
 * 也是排障时第一个要看的东西：出问题时打开这一页，
 * 「后端挂了」和「只是这个接口写错了」立刻能分开。
 * 删掉它等于把唯一的「环境体检」入口丢了。
 *
 * ★ 为什么从 `/` 挪走：P5 的 `/` 是**数据大盘**（管理员进来要看的第一件事）。
 *   自检页是给开发者和排障用的，不是给管理员用的 ——
 *   它挂在根路径上会让真正的首页变成一个「开发工具」。
 *   （author-web 在 P4 也是这么处理的。）
 *
 * ★ 它没有从左侧菜单进入的入口，全靠手敲 URL：`/admin/dev/ping`。
 *   这是有意的 —— 管理端的菜单要留给真实业务。
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
      —— 三个端共用同一份 localStorage，登录后跳回来即可。
    </el-alert>

    <!-- ============ ① 服务状态 ============ -->
    <el-card shadow="never" class="block">
      <template #header>
        <div class="card-header">
          <span>服务状态（经网关 /api → :8080）</span>
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
              用户表 {{ c.data.userCount }} 条记录
            </div>
            <div v-if="c.key === 'redis' && c.state === 'ok'" class="check-detail">
              序列化 {{ c.data.serializationOk ? '正常' : '异常' }}
            </div>
            <div v-if="c.state === 'fail'" class="check-detail fail-detail">{{ c.data }}</div>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <el-row :gutter="16">
      <!-- ============ ② 数据库实际内容 ============ -->
      <el-col :xs="24" :md="14">
        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>数据库里的实际数据</span>
              <el-tag size="small" type="info" effect="plain">novel 库</el-tag>
            </div>
          </template>

          <p class="tip">
            数据来自 <code>GET /api/ping/db</code>，读的是 <code>sys_user</code> 表。
            能查出来说明 <code>docs/sql/seed.sql</code> 执行成功、后端也连上了虚拟机上的 MySQL。
          </p>

          <el-descriptions v-if="checks[1].state === 'ok'" :column="1" border size="small">
            <el-descriptions-item label="用户总数">
              {{ checks[1].data.userCount }}
            </el-descriptions-item>
            <el-descriptions-item label="admin.id">{{ checks[1].data.admin?.id }}</el-descriptions-item>
            <el-descriptions-item label="admin.username">
              {{ checks[1].data.admin?.username }}
            </el-descriptions-item>
            <el-descriptions-item label="admin.nickname">
              {{ checks[1].data.admin?.nickname }}
            </el-descriptions-item>
            <el-descriptions-item label="admin.createTime">
              {{ checks[1].data.admin?.createTime }}
            </el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="未取到数据" :image-size="60" />
        </el-card>
      </el-col>

      <!-- ============ ③ 登录态 ============ -->
      <el-col :xs="24" :md="10">
        <el-card shadow="never" class="block">
          <template #header>
            <div class="card-header">
              <span>登录态（共享 localStorage）</span>
              <el-tag :type="userStore.isLogin ? 'success' : 'danger'" size="small">
                {{ userStore.isLogin ? '已登录' : '未登录' }}
              </el-tag>
            </div>
          </template>

          <p class="tip">
            读自阅读端写入的
            <el-tag size="small" type="warning">{{ TOKEN_KEY }}</el-tag> /
            <el-tag size="small" type="warning">{{ USER_KEY }}</el-tag>
            。
          </p>

          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="token">
              <code v-if="userStore.token">{{ userStore.token }}</code>
              <span v-else class="muted">（空）</span>
            </el-descriptions-item>
            <el-descriptions-item label="昵称">{{ userStore.nickname || '—' }}</el-descriptions-item>
            <el-descriptions-item label="角色">
              <el-tag v-if="userStore.role" size="small">{{ userStore.role }}</el-tag>
              <span v-else class="muted">（无）</span>
            </el-descriptions-item>
            <el-descriptions-item label="是管理员">
              <el-tag :type="userStore.isAdmin ? 'success' : 'info'" size="small">
                {{ userStore.isAdmin ? '是' : '否' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
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
