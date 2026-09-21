<script setup>
/**
 * 评论管理 —— 列表 + 筛选 + 审核（通过 / 隐藏）。
 *
 * ============================================================
 * ★★ 这一页是「审核」，不是「删除」
 * ============================================================
 * 管理端对评论只有两个动作：**通过**（→ 已显示）和**隐藏**（→ 已隐藏）。
 * 没有删除，后端也没有删评论的接口 —— 理由见 api/comment.js 的文件头。
 * 别因为「列表页通常有个删除按钮」就去补一个。
 *
 * ============================================================
 * ★★ 审核按钮在两种行上是禁用的，各有各的原因
 * ============================================================
 * ① **已删除的行**（`row.deleted === true`）
 *    后端审核走 `selectById`，`@TableLogic` 会让它把这条当成「不存在」，
 *    返回 `4001`。不禁用的话，管理员会对一条已经不存在的内容反复点隐藏，
 *    每次都拿到「评论不存在」，却看不出为什么 —— 因为列表里它明明在。
 *    （列表能看见它是刻意的：见后端 `NovelCommentMapper#selectAdminPage`。
 *      「读者看不见了」和「无从查证」是两件事。）
 *
 * ② **状态已经是目标状态的行**
 *    已显示的不能再「通过」，已隐藏的不能再「隐藏」，后端返回 `4005`。
 *    这一页和作品/章节审核页**不一样**：那两页只查 `status = 1`，
 *    按钮恒可用；这一页要能改判（隐藏错了要能放回来），
 *    所以三种状态的行都在列表里，按钮就得按状态分。
 *
 * ============================================================
 * ★★ 状态列和「已删除」是两个维度，不是四种状态
 * ============================================================
 * 「已隐藏」是管理员的处置，「已删除」是评论人自己删的（`deleted` 字段）。
 * **一条评论可以既是「已显示」又是「已删除」**。
 * 所以它们是**两列**（状态标签 + 一个「已删除」标记），
 * 而不是往 `utils/status.js` 那张表里塞第四个取值 ——
 * 塞进去就再也表达不了「已显示但已被作者删除」这种情况了。
 *
 * ============================================================
 * ★★ 中文文案全部来自后端，这一页【不写】0→'待审核' 的映射表
 * ============================================================
 * 显示用 `row.statusDesc`，只有**颜色**用 `utils/status.js` 的
 * `commentStatusType(row.status)`。这是全项目的规矩
 * （见 utils/status.js 的文件头）。
 * 判断一律用数字 `row.status` —— 文案是会改的，
 * 拿文案当条件的话，改一次文案就会静默地让某个分支永不命中。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getCommentPage, auditComment } from '@/api/comment'
import { usePagedList } from '@/composables/usePagedList'
import { formatDateTime } from '@/utils/format'
import { commentStatusType } from '@/utils/status'
import AuditDialog from '@/components/AuditDialog.vue'

/* ==================== 状态常量 ==================== */

/**
 * ★ 用常量而不是裸数字 —— 这一页里 `status` 出现在筛选器、标签、按钮文案、
 *   禁用判断、提交参数五处，裸写 `1` 迟早会有一处写成 `2` 而没人发现
 *   （它不报错，只是「通过」变成了「隐藏」）。
 */
const STATUS_PENDING = 0
const STATUS_VISIBLE = 1
const STATUS_HIDDEN = 2

/* ==================== 列表 ==================== */

/**
 * 筛选条件。★ `null` = 不限（axios 跳过 null/undefined 参数，
 * 正好对上后端「不传 = 全部」）。不要用空字符串。
 */
const filter = ref({ status: null, novelId: null, keyword: '' })

const paged = usePagedList(
  ({ page, size }) =>
    getCommentPage({
      status: filter.value.status,
      novelId: filter.value.novelId,
      keyword: filter.value.keyword.trim() || null,
      page,
      size,
    }),
  { pageSize: 10 },
)

onMounted(() => {
  paged.load()
})

/** 换筛选条件。★ 走 reload（回第 1 页），见 usePagedList 的第 ③ 条 */
function search() {
  paged.reload()
}

function reset() {
  filter.value = { status: null, novelId: null, keyword: '' }
  paged.reload()
}

/* ==================== 审核 ==================== */

const auditVisible = ref(false)
const auditLoading = ref(false)
/** 当前被审的评论。整行都留着 —— 按钮文案和禁用状态都要看它的 status/deleted */
const auditTarget = ref(null)

/**
 * 打开审核弹窗。
 *
 * ★ 评论**不需要再调一次详情接口**（和作品/章节审核页不同）：
 *   列表里返回的 `CommentVO` 已经带了正文全文（最多 500 字），
 *   那就是审核员要看的全部内容。作品/章节不行 —— 它们的正文在
 *   另一张表里，列表 VO 不返回。
 */
function openAudit(row) {
  auditTarget.value = row
  auditVisible.value = true
}

/** 这一行能不能审。不能的理由见文件头 */
function auditable(row) {
  return !row.deleted
}

/** 不能审的原因，给 tooltip 用 */
function auditDisabledReason(row) {
  return row.deleted
    ? '这条评论已被评论人自己删除，后端会返回「评论不存在」—— 读者已经看不到了，不需要再处置'
    : ''
}

/** 「通过」按钮是否禁用：已经是已显示，或者这一行根本审不了 */
function passDisabled() {
  const row = auditTarget.value
  return !row || !auditable(row) || row.status === STATUS_VISIBLE
}

/** 「隐藏」按钮是否禁用：已经是已隐藏，或者这一行根本审不了 */
function rejectDisabled() {
  const row = auditTarget.value
  return !row || !auditable(row) || row.status === STATUS_HIDDEN
}

/** 通过按钮的文案。改判时要说明「这是改判，不是首次通过」 */
function passText() {
  const row = auditTarget.value
  return row?.status === STATUS_HIDDEN ? '改判为已显示' : '通过并显示'
}

/** 隐藏按钮的文案 */
function rejectText() {
  const row = auditTarget.value
  return row?.status === STATUS_VISIBLE ? '隐藏这条评论' : '隐藏'
}

/** 通过之后会发生什么。★ 审核员据此判断，不能含糊 */
function passHint() {
  const row = auditTarget.value
  if (!row) return ''
  return row.status === STATUS_HIDDEN
    ? '改判后这条评论会重新出现在读者端的评论区'
    : '通过后这条评论会立刻出现在读者端的评论区（匿名可见）'
}

/**
 * 提交审核结果。
 *
 * ★ 弹窗只负责「通过还是隐藏 + 理由」，**接口调用在这里** ——
 *   只有这一层知道当前行的状态，也才知道该发 1 还是 2。
 *
 * ★★ 弹窗给的 `pass` 是布尔，而接口要的是目标状态码 ——
 *   这个转换必须显式写出来（`pass ? VISIBLE : HIDDEN`）。
 *   别把布尔直接当数字发（`true` 序列化成 `1` 看着正好对，
 *   但那是巧合，`false` → `0` 就变成「改回待审核」了，后端会拒）。
 */
async function submitAudit({ pass, remark }) {
  const row = auditTarget.value
  if (!row) return

  auditLoading.value = true
  try {
    await auditComment(row.id, pass ? STATUS_VISIBLE : STATUS_HIDDEN, remark)
    ElMessage.success(pass ? '已通过，读者端立刻可见' : '已隐藏，读者端立刻不可见')
    auditVisible.value = false
    await paged.load()
  } catch {
    /*
     * 拦截器已提示（4001 已删除 / 4005 状态被别人改过）。
     * ★ 弹窗**保持打开** —— 关掉的话刚写的备注就没了，
     *   而管理员多半会想「那我重试一次」。
     */
  } finally {
    auditLoading.value = false
  }
}
</script>

<template>
  <div>
    <!-- ==================== 筛选栏 ==================== -->
    <el-card shadow="never" class="filter-card">
      <div class="filter">
        <el-select v-model="filter.status" style="width: 130px" @change="search">
          <el-option label="全部状态" :value="null" />
          <!-- ★ 文案写死在这里是**对的**：这是筛选条件（管理员给自己选的），
               不是某条数据的状态。它和后端 statusDesc 是两回事 ——
               后端哪天把「待审核」改成「待审」，这个下拉框不会跟着变，
               但那不影响正确性：管理员选的是「状态 = 0」，看到的是自己写的标签。 -->
          <el-option label="待审核" :value="STATUS_PENDING" />
          <el-option label="已显示" :value="STATUS_VISIBLE" />
          <el-option label="已隐藏" :value="STATUS_HIDDEN" />
        </el-select>

        <!--
          ★ 作品 id 用**数字输入框**而不是下拉：作品有成百上千本，
            下拉框要先把它们全查出来（而且还会分页），得不偿失。
            日常用法是「从大盘/作品列表拿到 id，回到这里查」。
        -->
        <el-input-number
          v-model="filter.novelId"
          style="width: 160px"
          :min="1"
          :controls="false"
          placeholder="作品 id"
          @keyup.enter="search"
        />

        <el-input
          v-model="filter.keyword"
          style="width: 200px"
          placeholder="评论内容关键字"
          clearable
          @keyup.enter="search"
          @clear="search"
        />

        <el-button type="primary" @click="search">搜索</el-button>
        <el-button @click="reset">重置</el-button>
      </div>

      <p class="hint">
        管理端对评论只有两个动作：通过（已显示）和隐藏（已隐藏），没有删除 ——
        隐藏会留下「谁在什么时候因为什么处置了它」的记录，删除不会。
        通过后评论立刻出现在读者端，隐藏后立刻消失。
      </p>
    </el-card>

    <!-- ==================== 列表 ==================== -->
    <el-card shadow="never">
      <el-table v-loading="paged.loading.value" :data="paged.list.value" row-key="id" stripe>
        <el-table-column prop="id" label="ID" width="80" />

        <el-table-column label="作品" width="100">
          <template #default="{ row }">
            <span class="novel-id">#{{ row.novelId }}</span>
          </template>
        </el-table-column>

        <el-table-column label="评论内容" min-width="300">
          <template #default="{ row }">
            <!--
              ★★ 「已删除」必须显示，而且要显眼。
                这条列表里**混着已经删掉的评论**（后端刻意不滤 deleted）——
                不标出来的话它们长得和正常评论一模一样，
                管理员会对一条不存在的内容点审核，然后收到「评论不存在」。
            -->
            <el-tag v-if="row.deleted" size="small" type="info" effect="dark" class="del-tag">
              已删除
            </el-tag>
            <span :class="{ 'del-text': row.deleted }">{{ row.content }}</span>
          </template>
        </el-table-column>

        <el-table-column label="评分" width="80">
          <template #default="{ row }">
            <!-- 没打分为 null，JSON 里 key 会整个消失 —— 所以用 ?? 而不是 === null -->
            <span v-if="row.score">{{ row.score }} 分</span>
            <span v-else class="none">—</span>
          </template>
        </el-table-column>

        <el-table-column label="点赞" width="70">
          <template #default="{ row }">{{ row.likeCount ?? 0 }}</template>
        </el-table-column>

        <el-table-column label="评论人" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.nickname">{{ row.nickname }}</span>
            <!--
              ★ 昵称会为空（评论人已注销）。判定必须用「假值」而不是 === null：
                后端配了 default-property-inclusion: non_null，
                为空时这个 key **整个不存在**，`row.nickname === null` 永远不成立。
            -->
            <span v-else class="none">已注销</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <!-- ★ 文案来自后端 statusDesc，这里只给颜色。见文件头 -->
            <el-tag :type="commentStatusType(row.status)" size="small">
              {{ row.statusDesc }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="发表时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createTime) || '—' }}</template>
        </el-table-column>

        <el-table-column label="审核备注" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.auditRemark">{{ row.auditRemark }}</span>
            <span v-else class="none">—</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <!--
              ★ 已删除的行禁用审核，并且要说明原因。
                两个细节（和用户管理页「不能封禁自己」那处一样）：
                ① 禁用的按钮不触发鼠标事件，所以必须包一层 <span> ——
                   直接给 el-button 套 el-tooltip 是弹不出来的
                ② 光禁用不说话，管理员会以为「按钮坏了」
            -->
            <el-tooltip
              :disabled="auditable(row)"
              :content="auditDisabledReason(row)"
              placement="top"
            >
              <span>
                <el-button link type="primary" :disabled="!auditable(row)" @click="openAudit(row)">
                  审核
                </el-button>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty
            v-if="paged.loaded.value"
            description="没有符合条件的评论"
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

    <!-- ==================== 审核弹窗 ==================== -->
    <!--
      ★ 复用 AuditDialog（作品 / 章节 / 评论 / 作者申请四处共用），
        差异全部走 prop：评论这边动作叫「隐藏」、理由不必填、按钮按状态禁用。
        ★★ 理由不必填不是前端放宽，是**后端就是这么定的** ——
          评论人看不到审核意见（P5 没有反馈通道），强制填写只会得到
          一堆「违规」这种凑数的内容，而它看起来像一条有效记录。
    -->
    <AuditDialog
      v-model="auditVisible"
      title="审核评论"
      :target-name="auditTarget ? `评论 #${auditTarget.id}` : ''"
      :pass-text="passText()"
      :reject-text="rejectText()"
      :pass-hint="passHint()"
      :pass-disabled="passDisabled()"
      :reject-disabled="rejectDisabled()"
      :remark-required="false"
      remark-tip="★ 可选 —— 评论人看不到这条备注（读者端没有反馈通道）。它只是你自己的记录，写清楚「为什么隐藏」方便日后回溯"
      :loading="auditLoading"
      @submit="submitAudit"
    >
      <div v-if="auditTarget" class="preview-body">
        <p class="preview-meta">
          {{ auditTarget.nickname || '已注销用户' }}
          ·
          <span v-if="auditTarget.score">{{ auditTarget.score }} 分</span>
          <span v-else class="none">未评分</span>
          ·
          {{ formatDateTime(auditTarget.createTime) }}
          ·
          点赞 {{ auditTarget.likeCount ?? 0 }}
        </p>
        <p class="preview-content">{{ auditTarget.content }}</p>
      </div>
    </AuditDialog>
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

.novel-id {
  color: #606266;
  font-family: Consolas, Monaco, monospace;
}

/*
 * ★ 已删除的评论用**删除线 + 浅色**，不只是加一个标签：
 *   这一列混着两种行，标签在长文本后面很容易被忽略。
 */
.del-tag {
  margin-right: 6px;
}

.del-text {
  color: #a8abb2;
  text-decoration: line-through;
}

.none {
  color: #c0c4cc;
}

.preview-meta {
  margin: 0 0 10px;
  font-size: 12px;
  color: #909399;
}

.preview-content {
  margin: 0;
  line-height: 1.9;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
