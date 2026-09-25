<script setup>
/**
 * 违禁审核队列 —— E1/S9 的管理端页面（/ai-audit 一条菜单）。
 *
 * ============================================================
 * ★★★ 这一页只有两个动作，而它们的后果【完全不同】—— 页面必须说清楚
 * ============================================================
 *
 *   去处置 → 跳到既有的处置页（章节 → 审核中心，评论 → 评论管理）。
 *            那一刀落下时，内容的**状态真的变了**，会写一条 `audit_log`，
 *            并**顺带**把本页这一行标成「已确认违规」（后端
 *            `AuditLogServiceImpl#record` 里的联动，判断 3.2 的反转）。
 *
 *   排除   → **只**把这一行标成「已排除」，从待办里拿掉。
 *            **什么内容状态都不改，也不写 `audit_log`。**
 *
 * ★★ 所以「排除」**不等于「放过这条内容」**：那条评论/章节还在原来的待审队列里，
 *   管理员若认为它确实违规，**仍然**要去处置页驳回。
 *   这就是下面那个确认框为什么不写「确定排除吗」——
 *   那句话会让管理员以为点一下就完事了，而实际后果是
 *   **一条违规评论继续挂在待审队列里，而它上面的 AI 提示已经消失了**。
 *
 * ★★★ 这一页【没有】「确认违规」按钮，这是刻意的、不是没写完。
 *   那个按钮要改 `chapter.status` / `comment.status`，就必须过
 *   `ChapterServiceImpl#transitStatus` 那道唯一入口 ——
 *   在本页自己 UPDATE 会绕开它 → `audit_log` 缺一条、趋势图少一个点，
 *   **而且不报错**。要加它之前先回答：怎么在不绕开状态机的前提下写 `audit_log`？
 *   答不上来就不要加。
 *
 * ============================================================
 * ★★ 每行有四个「不出现 / 需要解读」的字段 —— 一个都不能当成空字符串
 * ============================================================
 *
 *   ① `targetTitle` 取不到 = 目标被逻辑删除，或评论已被删。
 *      **不要留白** —— 显示「章节 #306（已删除）」。留白和「加载失败」在视觉上分不出来。
 *   ② `novelId` 取不到 → **禁用「去处置」按钮**，不要跳一个 404 的 URL。
 *      （跳到一个「页面不存在」比按钮灰着更让人困惑：前者看起来像功能坏了。）
 *   ③ `contentUpdatedAt` 为 null **不等于**「内容没更新过」——
 *      它意味着**这是评论**（`novel_comment` 没有 `update_time` 列、正文端到端不可修改）。
 *      所以后端配了 `contentImmutable` 这个布尔量：
 *      少了它，「评论」和「章节但时间丢了」会渲染成同一个样子。
 *      **「不知道」和「不存在」不是一回事** —— 这是 M5 立下的同一条判据。
 *   ④ `aiState` 是 `aiComment` 那几个哨兵文本的**结构化说法**：
 *      `NOT_CALLED`（关键词层无命中，**一分钱没花**）/ `SKIPPED`（命中了但模型没复核）/
 *      `NO_REASON` / `RAN`。★ **页面只认这四个 token，不要自己去比字符串** ——
 *      比字符串的话，后端换一句哨兵文案，页面会静默地把「未调用」显示成「已复核」。
 *
 * ============================================================
 * ★ `hitWords` 是一个【字符串】，不是一个数组 —— 不要拆成 tag
 * ============================================================
 * 它的分隔符是全角「，」，而截断标记「（共N个词，此处列前M个）」里**含同一个字符**。
 * 按「，」拆开的话，那个标记会被拆成两个假的词条，标签上出现
 * 「（共 12 个词」和「此处列前 3 个）」两条 —— 看起来像词库里有这两个词。
 * 所以整串原样显示 + tooltip 展开。
 *
 * ============================================================
 * ★★ 「机器排除」和「已排除」是两个词，不能都显示成「已排除」
 * ============================================================
 * 两者 `status` 都是 2，唯一的区别是 `audit_user_id` 是不是 null：
 *   `audit_user_id IS NULL` → 机器排除的（关键词层无命中，**没有任何人做过判断**）
 *   非 null                 → 人排除的（管理员判定这是一次误杀）
 * ★ 把前者显示成「已排除」，就是在页面上**替一个不存在的人认领了一次动作**。
 *   所以这个字面量由后端 `AiAuditRowVO#statusDesc` 定，页面直接用 `statusDesc`。
 */
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getAiAuditPage, excludeAiAudit } from '@/api/ai'
import { usePagedList } from '@/composables/usePagedList'
import { formatDateTime } from '@/utils/format'
import { confirm } from '@/utils/confirm'

const router = useRouter()

/* ==================== 码表 ==================== */
/*
 * ★ 值必须和后端**逐一对应**，改后端枚举时这里要跟着改：
 *   AuditTargetType:     1作品 2章节 3评论 4作者申请（★ AI 审核只用 2/3）
 *   AiAuditResult:       0待确认 1已确认违规 2已排除
 *   AiRiskLevel:         0无风险 1低 2中 3高
 * 对不上的症状是那一列显示成空白或原始数字，**而且不报错**。
 *
 * ★★ 「作品」故意**不在**类型下拉里：AI 审核不覆盖作品简介（已拍板），
 *   选它只会得到一个空列表 —— 那看起来像「AI 漏审了全部作品」。
 *   后端传 targetType=1 同样返回空，但那是接口层的宽容，不是产品语义。
 */
const TARGET_TYPES = [
  { label: '全部类型', value: null },
  { label: '章节', value: 2 },
  { label: '评论', value: 3 },
]

const RISK_LEVELS = [
  { label: '全部等级', value: null },
  { label: '无风险', value: 0 },
  { label: '低', value: 1 },
  { label: '中', value: 2 },
  { label: '高', value: 3 },
]

/**
 * ★★ 默认是「待确认」而不是「全部」，而且这个默认值是**页面**定的
 *   （后端三个筛选条件都可空 = 不筛，权威在调用方）。
 *   这一页叫「违禁审核」，它首先是一个**待办队列** ——
 *   默认显示全部的话，已处置的历史会把待办冲到后面几页去。
 */
const STATUSES = [
  { label: '待确认', value: 0 },
  { label: '已确认违规', value: 1 },
  { label: '已排除', value: 2 },
  { label: '全部状态', value: null },
]

/** 风险等级 → 标签颜色。★ 0 是「审过了、判无风险」，用 info 而不是 success：它不是审核结论 */
const RISK_TAG = { 0: 'info', 1: 'info', 2: 'warning', 3: 'danger' }

/** 类型 id → 中文量词，用来拼「章节 #306（已删除）」 */
const TYPE_NOUN = { 1: '作品', 2: '章节', 3: '评论' }

/* ==================== 筛选 ==================== */

/**
 * ★ `null` = 不限：axios 默认的参数序列化器**跳过 null 和 undefined**，
 *   正好对上后端「不传 = 全部」的语义。不要用空字符串
 *   （那会发成 `?status=`，后端拿到空串绑到 Integer 上会 400）。
 */
const filter = ref({ targetType: null, riskLevel: null, status: 0 })

const paged = usePagedList(
  ({ page, size }) =>
    getAiAuditPage({
      targetType: filter.value.targetType,
      riskLevel: filter.value.riskLevel,
      status: filter.value.status,
      page,
      size,
    }),
  { pageSize: 20 },
)

onMounted(() => {
  paged.load()
})

/** 换筛选条件。★ 走 reload（回第 1 页），见 usePagedList 的第 ③ 条 */
function search() {
  paged.reload()
}

function reset() {
  filter.value = { targetType: null, riskLevel: null, status: 0 }
  paged.reload()
}

/* ==================== 展示 ==================== */

/**
 * 「对象」列。★★ 这个函数存在的全部理由就是那个「（已删除）」兜底。
 * 直接写 `{{ row.targetTitle }}` 的话，被删掉的对象在页面上就是一片空白。
 */
function targetLabel(row) {
  if (row.targetTitle) return row.targetTitle
  const noun = TYPE_NOUN[row.targetType] || '对象'
  return `${noun} #${row.targetId}（已删除）`
}

/** 处置人。★ 待办行的 `auditUserId` 是 null（还没人处置），显示「—」而不是「用户 #null」 */
function disposerLabel(row) {
  if (row.auditUserName) return row.auditUserName
  return row.auditUserId ? `用户 #${row.auditUserId}` : '—'
}

/**
 * 「内容最后更新」列。
 *
 * ★★ 两个分支的**区别本身**就是信息（见文件头第 ③ 条）：
 *   `contentImmutable` → 这是评论，正文不可修改，没有「最后更新时间」这个量可言
 *   时间取不到（章节但 join 不到）→ 显示「—」，和上面那个词**不能混**
 */
function contentTime(row) {
  if (row.contentImmutable) return '评论不可修改'
  return formatDateTime(row.contentUpdatedAt) || '—'
}

/** 只有待确认的行才有处置入口 —— 已经处置过的行再点「排除」只会拿到 5010 */
function isPending(row) {
  return row.status === 0
}

/** ★ 取不到 novelId 时按钮必须灰着：跳到一个 404 比按钮不可点更让人困惑 */
function canJump(row) {
  return !!row.novelId
}

/**
 * 「去处置」—— 跳到**既有的**处置页。
 *
 * ★★ 本页不提供「审」的功能，只提供「跳过去」。理由是那条路上有状态机
 *   （见文件头）。所以这里只做两件事：找到正确的落地页、把参数带上。
 * ★ 两个目标页都**不支持** query 预选（点了以后要自己找到那一条）——
 *   这是已知的粗糙处，不是 bug：给它们加预选要改那两个页面的拉取逻辑，
 *   而 E1 的边界是「不碰既有处置页」。
 */
function goDispose(row) {
  if (!canJump(row)) return
  if (row.targetType === 2) {
    // 章节：进那本书的章节审核页（与审核中心里「审章节」同一个落地页）
    router.push(`/audit/novel/${row.novelId}`)
  } else {
    // 评论：评论管理页（★ 那一页既有章评也有书评，进来以后按正文找）
    router.push('/comment')
  }
}

/**
 * 「排除」—— 判定这是一次误杀。
 *
 * ★★ 确认框的正文必须说清**它不改内容状态**（见文件头）。写成
 *   「确定排除这条 AI 判定吗」的话，管理员会以为点一下就完事了，
 *   而真相是那条内容还在待审队列里 —— 只是 AI 的提示没了。
 */
async function doExclude(row) {
  const ok = await confirm(
    `这只会把这条 AI 判定标记为「已排除」，从待办里拿掉。\n\n` +
      `它【不会】改变内容的任何状态：${targetLabel(row)} 仍在原来的待审队列里。\n` +
      `如果你认为它确实违规，请点「去处置」驳回。`,
    '排除这条 AI 判定',
    { confirmText: '确定排除', type: 'warning' },
  )
  if (!ok) return

  try {
    await excludeAiAudit(row.targetType, row.targetId)
    ElMessage.success('已排除')
    // 默认视图是「待确认」，排除后这一行会离开列表 —— 重查一次让它消失
    paged.reload()
  } catch {
    /*
     * ★ 失败提示由 request.js 的拦截器统一弹过了，这里不重复弹。
     *   ★★ 最常见的一种是 `5010`（该审核已被处置）—— 那是 HTTP 200 + 业务码，
     *   拦截器会把它当业务失败弹出来。**重查一次**：页面上的数据旧了，
     *   刷新之后就能看到那一行现在是什么状态（很可能已被别人处置）。
     */
    paged.reload()
  }
}
</script>

<template>
  <div>
    <!-- ==================== 筛选栏 ==================== -->
    <el-card shadow="never" class="filter-card">
      <div class="filter">
        <el-select v-model="filter.status" style="width: 140px" @change="search">
          <el-option v-for="o in STATUSES" :key="String(o.value)" :label="o.label" :value="o.value" />
        </el-select>

        <el-select v-model="filter.targetType" style="width: 130px" @change="search">
          <el-option
            v-for="o in TARGET_TYPES"
            :key="String(o.value)"
            :label="o.label"
            :value="o.value"
          />
        </el-select>

        <el-select v-model="filter.riskLevel" style="width: 130px" @change="search">
          <el-option
            v-for="o in RISK_LEVELS"
            :key="String(o.value)"
            :label="o.label"
            :value="o.value"
          />
        </el-select>

        <el-button @click="reset">重置</el-button>
      </div>

      <p class="hint">
        这里是<b>待办队列</b>，按风险等级从高到低排。本页不做审核动作：「去处置」跳到既有的审核页
        （那一刀才真正改内容的状态）；「排除」只表示这条 AI 判定是误杀，<b>内容本身不会被放过</b>。
      </p>
      <p class="hint">
        ★ AI 只审<b>章节正文</b>和<b>评论</b>，不审作品简介。没有出现在这里的章节/评论，
        要么是还没被审（消息积压），要么是关键词层无命中 —— 后者的判定结果在「全部状态」里可见。
      </p>
    </el-card>

    <!-- ==================== 列表 ==================== -->
    <el-card shadow="never">
      <el-table v-loading="paged.loading.value" :data="paged.list.value" row-key="id" stripe>
        <el-table-column label="风险" width="90">
          <template #default="{ row }">
            <el-tag :type="RISK_TAG[row.riskLevel] || 'info'" size="small">
              {{ row.riskLevelDesc || '—' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="对象" width="90">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.targetTypeDesc || '—' }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="标题 / 正文" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- 见 targetLabel：被删除的目标显示「章节 #306（已删除）」而不是留白 -->
            <span :class="{ gone: !row.targetTitle }">{{ targetLabel(row) }}</span>
            <div v-if="row.novelTitle" class="sub">《{{ row.novelTitle }}》</div>
          </template>
        </el-table-column>

        <el-table-column label="命中词" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <!--
              ★★ 整串原样显示，**不要**按「，」拆成 tag：
                 截断标记里含同一个全角逗号，拆开会出现两个假的词条。见文件头。
            -->
            <span v-if="row.hitWords">{{ row.hitWords }}</span>
            <span v-else class="none">未命中</span>
          </template>
        </el-table-column>

        <el-table-column label="AI 复核" width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- ★ 只认后端给的四个 token，不比字符串 -->
            <div>{{ row.aiStateDesc || '—' }}</div>
            <div v-if="row.aiComment" class="sub">{{ row.aiComment }}</div>
          </template>
        </el-table-column>

        <el-table-column label="判定 / 内容更新" width="160">
          <template #default="{ row }">
            <!--
              ★★ 这两行是「AI 判的是不是当前这一版」的答案（判断 3.2 用它替代 content_hash）。
                 判定时间取自 ai_audit_result.create_time，被人工处置【不会】刷掉它。
            -->
            <div>{{ formatDateTime(row.createTime) || '—' }}</div>
            <div class="sub">{{ contentTime(row) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <!-- ★★ 「机器排除」与「已排除」的用词由后端定，页面不自己拼 -->
            <el-tag :type="row.status === 1 ? 'danger' : 'info'" size="small">
              {{ row.statusDesc || '—' }}
            </el-tag>
            <div v-if="row.auditTime" class="sub">{{ disposerLabel(row) }}</div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :disabled="!isPending(row) || !canJump(row)"
              :title="canJump(row) ? '' : '这本书/这一章已取不到，无法跳转'"
              @click="goDispose(row)"
            >
              去处置
            </el-button>
            <el-button
              link
              type="warning"
              :disabled="!isPending(row)"
              @click="doExclude(row)"
            >
              排除
            </el-button>
          </template>
        </el-table-column>

        <template #empty>
          <!--
            ★★ M5 的判据：失败态与空态必须分开。
               只说「暂无数据」的话，停掉 content 之后这一页会显示
               「没有需要处理的 AI 判定」—— 一句彻头彻尾的谎话。
          -->
          <el-empty v-if="paged.isEmpty.value" description="没有符合条件的 AI 判定" :image-size="80" />
          <el-empty v-else-if="paged.failed.value" description="暂时加载失败" :image-size="80">
            <el-button @click="paged.reload()">重试</el-button>
          </el-empty>
        </template>
      </el-table>

      <el-pagination
        v-if="paged.total.value > 0"
        class="pager"
        :current-page="paged.page.value"
        :page-size="paged.size.value"
        :total="paged.total.value"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="paged.changePage"
        @size-change="paged.changeSize"
      />
    </el-card>
  </div>
</template>

<style scoped>
.filter-card {
  margin-bottom: 12px;
}

.filter {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

/* 被删除 / 取不到的对象：灰色 + 斜体，一眼能和正常标题分开 */
.gone {
  color: #a8abb2;
  font-style: italic;
}

/* 次要行（书名、AI 理由、处置人） */
.sub {
  margin-top: 2px;
  font-size: 12px;
  color: #909399;
}

.none {
  color: #c0c4cc;
}
</style>
