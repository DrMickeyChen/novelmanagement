<script setup>
/**
 * 审核日志 —— 只读的审核流水。
 *
 * ============================================================
 * ★★ 这一页没有任何「写」的入口，而且以后也不该有
 * ============================================================
 * 后端 `AdminAuditLogController` 只有 GET —— 不是「还没做」，是「不该做」：
 * 流水是只追加的，能改的日志不能用来追责。
 * 所以这里**不要**加「删除某条记录」「清空日志」这类按钮，
 * 哪怕需求方提了也要顶回去。要清理历史请连数据库，那会留下数据库层的
 * 操作记录，比在应用里点一下「删除」可追查得多。
 *
 * ============================================================
 * ★★ `targetTitle` 为空 = 那个对象被删了，不是数据坏了
 * ============================================================
 * `AuditLogServiceImpl` 是按 targetId 去 join 出标题的，对象被逻辑删除后
 * join 不到，返回 null。后端类的注释里明确写了这一条：
 * **前端应显示成「作品 #5（已删除）」，而不是留白。**
 *
 * 留白会让人以为这条日志本身是坏的 —— 而它恰恰是最有价值的一类记录：
 * 「这本书被谁审过、然后被谁删了」。
 * 所以下面有个 `targetLabel()` 专门处理这个兜底，**不要**直接用
 * `{{ row.targetTitle }}`。
 *
 * ============================================================
 * ★★ 「只看此人的操作」用行内钻取，不做操作人下拉框
 * ============================================================
 * `operatorId` 是筛选参数，但要选一个人得先有**用户列表** —— 那是 S6 的事，
 * 现在没有。硬做一个数字输入框让管理员手填用户 id 是没用的
 * （没人记得住 id）。
 *
 * 所以改成：点某一行的操作人名字 → 筛出这个人的全部操作。
 * 这是**追责时真正的动作路径**（「这个人今天动了哪些东西」），
 * 而且不依赖任何还没做的页面。选中的操作人以一个可关闭的标签显示在筛选栏里，
 * 否则用户会忘记自己还挂着一个看不见的筛选条件，然后觉得日志"少了很多"。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getAuditLogPage } from '@/api/log'
import { usePagedList } from '@/composables/usePagedList'
import { formatDateTime } from '@/utils/format'

/* ==================== 码表 ==================== */
/*
 * ★ 这两张表**故意放在页面里**，没有提到 utils/status.js。
 *   理由：目前只有这一页用它们。放进 utils 的唯一好处是「将来别处也能用」，
 *   而代价是「改这一页要跳到另一个文件」。等真有第二个使用方（比如评论管理页
 *   要显示 COMMENT 类型的动作）再提上去，那时也才知道它该长什么样。
 *
 * ★★ 值必须和后端枚举**逐一对应**，改后端枚举时这里要跟着改：
 *   AuditTargetType: 1作品 2章节 3评论 4作者申请
 *   AuditAction:     1提交 2通过 3驳回 4下架 5重新上架 6退回草稿
 *   对不上的话，页面上那个动作会显示成空白或者原始数字，
 *   **而且不报错** —— 所以这里的 comment 是查错时的第一站。
 */
const TARGET_TYPES = [
  { label: '全部对象', value: null },
  { label: '作品', value: 1 },
  { label: '章节', value: 2 },
  { label: '评论', value: 3 },
  { label: '作者申请', value: 4 },
]

const ACTIONS = [
  { label: '全部动作', value: null },
  { label: '提交', value: 1 },
  { label: '通过', value: 2 },
  { label: '驳回', value: 3 },
  { label: '下架', value: 4 },
  { label: '重新上架', value: 5 },
  { label: '退回草稿', value: 6 },
]

/**
 * 动作 → 标签颜色。
 *
 * ★ 语义是「从管理员视角看这个动作是好消息还是坏消息」：
 *   通过 = 绿，驳回 = 红，下架/退回 = 灰（都是"撤下来"，不是错误）。
 *   ★ 提交(1) 用 `primary`（蓝）而不是绿：提交是**作者**做的，
 *     不是审核结果。全用绿色的话，一屏流水看下来分不清哪些是"审出来的"。
 */
const ACTION_TAG = {
  1: 'primary',
  2: 'success',
  3: 'danger',
  4: 'info',
  5: 'warning',
  6: 'info',
}

/** 类型 id → 中文量词，用来拼「作品 #5（已删除）」 */
const TYPE_NOUN = { 1: '作品', 2: '章节', 3: '评论', 4: '作者申请' }

/* ==================== 筛选 ==================== */

/**
 * ★ `null` = 不限。axios 默认的参数序列化器**跳过 null 和 undefined**，
 *   所以 null 就是「这个参数不传」，正好对上后端「不传 = 全部」的语义。
 *   不要用空字符串：那会发成 `?action=`，后端 `AuditAction.fromCode("")`
 *   拿到空串，行为不确定。
 */
const filter = ref({ targetType: null, action: null, operatorId: null, operatorName: '' })

const paged = usePagedList(
  ({ page, size }) =>
    getAuditLogPage({
      targetType: filter.value.targetType,
      action: filter.value.action,
      operatorId: filter.value.operatorId,
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
  filter.value = { targetType: null, action: null, operatorId: null, operatorName: '' }
  paged.reload()
}

/** 点某一行的操作人 → 筛出这个人的全部操作 */
function filterByOperator(row) {
  if (!row.operatorId) return
  filter.value.operatorId = row.operatorId
  // ★ 名字只是为了显示标签。后端不返回它，我们只能从当前这一行手里拿 ——
  //   所以如果操作人名字本身是空（用户被删了），标签上就退回显示 id
  filter.value.operatorName = row.operatorName || ''
  paged.reload()
}

function clearOperator() {
  filter.value.operatorId = null
  filter.value.operatorName = ''
  paged.reload()
}

/* ==================== 展示 ==================== */

/**
 * 日志行的「操作对象」列。
 *
 * ★★ 这个函数存在的全部理由就是那个 `（已删除）` 兜底，见文件头。
 *   直接写 `{{ row.targetTitle }}` 的话，被删掉的对象在页面上就是一片空白。
 */
function targetLabel(row) {
  if (row.targetTitle) return row.targetTitle
  const noun = TYPE_NOUN[row.targetType] || '对象'
  return `${noun} #${row.targetId}（已删除）`
}

/** 操作人。同样有兜底：用户被删掉之后 join 不出昵称 */
function operatorLabel(row) {
  if (row.operatorName) return row.operatorName
  return row.operatorId ? `用户 #${row.operatorId}` : '—'
}

function actionTagType(action) {
  return ACTION_TAG[action] || 'info'
}
</script>

<template>
  <div>
    <!-- ==================== 筛选栏 ==================== -->
    <el-card shadow="never" class="filter-card">
      <div class="filter">
        <el-select v-model="filter.targetType" style="width: 140px" @change="search">
          <el-option
            v-for="o in TARGET_TYPES"
            :key="String(o.value)"
            :label="o.label"
            :value="o.value"
          />
        </el-select>

        <el-select v-model="filter.action" style="width: 140px" @change="search">
          <el-option
            v-for="o in ACTIONS"
            :key="String(o.value)"
            :label="o.label"
            :value="o.value"
          />
        </el-select>

        <el-button @click="reset">重置</el-button>

        <!--
          ★ 操作人筛选做成一个**可见、可关闭**的标签。
            藏起来的话，用户点完「只看此人」再换别的条件，
            会忘记还有个 operatorId 挂在那里，然后觉得日志「少了很多」。
        -->
        <el-tag
          v-if="filter.operatorId"
          closable
          type="warning"
          effect="plain"
          @close="clearOperator"
        >
          只看：{{ filter.operatorName || `用户 #${filter.operatorId}` }} 的操作
        </el-tag>
      </div>

      <p class="hint">
        日志是只追加的流水，界面上不提供修改和删除。点击「操作人」可以筛出某个人做过的全部操作。
      </p>
    </el-card>

    <!-- ==================== 列表 ==================== -->
    <el-card shadow="never">
      <el-table v-loading="paged.loading.value" :data="paged.list.value" row-key="id" stripe>
        <el-table-column label="时间" width="150">
          <template #default="{ row }">
            <!-- ★ 精确到分。只到天的话「今天审了哪几本、按什么顺序」完全看不出来 -->
            {{ formatDateTime(row.createTime) || '—' }}
          </template>
        </el-table-column>

        <el-table-column label="对象" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.targetTypeDesc || '—' }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="名称" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- 见 targetLabel：被删除的对象显示「作品 #5（已删除）」而不是留白 -->
            <span :class="{ gone: !row.targetTitle }">{{ targetLabel(row) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="动作" width="110">
          <template #default="{ row }">
            <el-tag :type="actionTagType(row.action)" size="small">
              {{ row.actionDesc || row.action }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作人" width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <!-- 点一下 = 筛出这个人的全部操作 -->
            <el-button
              v-if="row.operatorId"
              link
              type="primary"
              @click="filterByOperator(row)"
            >
              {{ operatorLabel(row) }}
            </el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>

        <el-table-column label="备注" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <!--
              ★ 驳回理由是这里唯一有信息量的东西，但通过/提交没有备注是正常的，
                所以空的时候显示一个浅色的「—」而不是留白 —— 留白和「加载失败」
                在视觉上分不出来。
            -->
            <span v-if="row.remark">{{ row.remark }}</span>
            <span v-else class="none">—</span>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty
            v-if="paged.loaded.value"
            description="没有符合条件的日志"
            :image-size="80"
          />
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
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

/* 被删除的对象：用灰色 + 斜体，一眼能和正常标题分开 */
.gone {
  color: #a8abb2;
  font-style: italic;
}

.none {
  color: #c0c4cc;
}
</style>
