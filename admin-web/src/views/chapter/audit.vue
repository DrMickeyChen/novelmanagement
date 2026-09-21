<script setup>
/**
 * 某一本书的章节审核 —— **P5 验收主线的那一步**。
 *
 * 大纲 §1286：「管理员能在界面上**审完一本书的全部章节**」。
 * 这句话要落地，缺的不是「审核」这个动作（`views/audit/index.vue` 里也有），
 * 而是**这条路径**：进到某本书里 → 看到它全部待审章节 → 逐章审完 → 审作品。
 * 只有一个扁平的全局待审列表时，审核员要在几十本书之间反复找同一本 ——
 * 那正是这句话要防的。
 *
 * ============================================================
 * ★★ 单章审核和批量审核走**同一段代码**
 * ============================================================
 * `auditMany()` 接受一个数组，单章审核传 `[chapter]`。
 * 分成两份实现的话，迟早出现「批量是顺序的、单条是并发的」这种漂移，
 * 而这种漂移只在几十章一起提交时才会暴露（行锁竞争），
 * 本地一条一条测永远测不出来。
 *
 * ============================================================
 * ★★ 批量必须【顺序】执行，绝不 Promise.all
 * ============================================================
 * 和 `author-web/src/views/chapters/index.vue` 里 `runBatch` 的注释同一个理由，
 * 这里更严重：审核**通过**一章会触发后端的 `syncNovelStat`，
 * 在同一个事务里 UPDATE 同一行 `novel`（重算章节数/总字数）。
 * 并发打过去全都在等同一把行锁，总耗时不会更快，
 * 而失败结果是无序的 —— 「第 3 条失败」和审核员勾选的顺序对不上号，等于没报。
 *
 * ============================================================
 * ★★ 批量驳回用 prompt()，单章审核用 AuditDialog —— 这是有意的分工
 * ============================================================
 * `utils/confirm.js` 里 `prompt()` 的注释写着「审核弹窗不要用它」，
 * 说的正是**单章**那种场景：审核员要一边读正文一边写理由，
 * 一个飘在正文上面的输入框会把正文挡住。
 *
 * 批量的取舍**正好相反**：审核员是看着这张表做的「一次全清」的决定，
 * 再弹一个把同样几行标题列一遍的大预览框只是重复劳动 ——
 * 这里真正缺的只是一个理由输入框。所以两处用不同的东西是对的，
 * 不是不一致。
 *
 * ============================================================
 * ★★ 审完之后回第几页
 * ============================================================
 * 见 `refreshAfterAudit()` —— 「待审」筛选下被审的章节会**立刻离开列表**，
 * 停在原页码会看到一片空白 + 「共 40 条」。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getChapterPage, getChapterDetail, auditChapter } from '@/api/chapter'
import { getNovelDetail, getNovelCatalog, auditNovel } from '@/api/novel'
import { usePagedList } from '@/composables/usePagedList'
import { formatWordCount, formatDateTime } from '@/utils/format'
import { chapterStatusType, novelStatusType } from '@/utils/status'
import { confirm, prompt } from '@/utils/confirm'
import AuditDialog from '@/components/AuditDialog.vue'
import NovelPreview from '@/components/NovelPreview.vue'
import ChapterPreview from '@/components/ChapterPreview.vue'

const route = useRoute()
const router = useRouter()

const novelId = computed(() => Number(route.params.novelId))

/* ==================== 作品信息（页头 + 审作品按钮） ==================== */

const novel = ref(null)
/** 目录。★ P6 起详情不再带它了，是**另一个请求**（见 api/novel.js） */
const novelChapters = ref([])

async function loadNovel() {
  try {
    // ★ 详情和目录并发。两者都失败的话，报错由拦截器提示，这里只管状态
    const [d, chapters] = await Promise.all([
      getNovelDetail(novelId.value),
      getNovelCatalog(novelId.value),
    ])
    novel.value = d
    novelChapters.value = chapters ?? []
  } catch {
    // 拦截器已提示（2001 不存在）。
    // ★ 不重新抛：这个请求只是为了让页头有书名 + 决定「审核作品」按钮可不可点。
    //   章节列表那个请求拿到的错误更准确，够了。
    novel.value = null
    novelChapters.value = []
  }
}

/**
 * 「审核作品」按钮能不能点。
 *
 * ★★ 和后端 `AdminNovelController.audit` 的校验**必须一致**。
 *   作品的状态机允许「已下架(3) → 已上架(2)」，那是「重新上架」走
 *   `PUT /status` 接口 —— 所以**光靠状态机挡不住**「对一本已下架的书点审核通过」，
 *   后端为此专门加了一句「当前是不是待审核」的判断。
 *   前端不照着做的话，就会给出一个「点了必然报 2003」的按钮。
 */
const canAuditNovel = computed(() => novel.value?.status === 1)

/* ==================== 章节列表 ==================== */

/**
 * 状态筛选。★ **默认「待审核」**，不是「全部」。
 *
 * 这一页存在的理由是「把这本书的待审章节清掉」，
 * 默认「全部」的话，一本 200 章、已发布 198 章的书，
 * 打开先看到 200 行、要自己从里面挑出那 2 行 —— 而默认「待审」时
 * 列表天然就是一份待办清单，「全部」是查历史时才切过去看的。
 */
const AUDIT_STATUS = 1
const status = ref(AUDIT_STATUS)

const STATUS_OPTIONS = [
  { label: '待审核', value: 1 },
  { label: '全部', value: null },
  { label: '草稿', value: 0 },
  { label: '已发布', value: 2 },
  { label: '已驳回', value: 4 },
]

const paged = usePagedList(
  ({ page, size }) =>
    getChapterPage({ novelId: novelId.value, status: status.value, page, size }),
  { pageSize: 20 },
)

onMounted(() => {
  loadNovel()
  paged.load()
})

/** 换筛选条件 —— ★ 必须走 reload（回第 1 页），见 usePagedList 的说明 */
function onStatusChange() {
  paged.reload()
}

/* ==================== 行内忙碌状态 ==================== */

/** 正在处理中的章节 id 集合，用来只禁用那一行 */
const busyIds = ref(new Set())
/** 批量进行中（此时整张表的操作按钮都该禁用） */
const batchRunning = ref(false)

/**
 * ★ 换一个**新 Set** 再赋值。
 *   `busyIds.value.add(x)` 改的是同一个 Set 对象，
 *   Vue 的 ref 只在 `.value` 被**替换**时才通知依赖 —— 原地改不触发重渲染，
 *   表现就是「点了通过，按钮不转圈，但其实已经在飞了」。
 */
function setBusyAll(chapters, on) {
  const next = new Set(busyIds.value)
  for (const c of chapters) {
    if (on) next.add(c.id)
    else next.delete(c.id)
  }
  busyIds.value = next
}

/* ==================== 勾选 ==================== */

const selected = ref([])

/**
 * 选中项里**真正能审的**那部分。
 *
 * ★★ 只挑能审的发出去，但**绝不静默过滤**。
 *   筛选切到「全部」时，审核员完全可能顺手勾上一条已发布的章节。
 *   全打一遍的提示会是「2 章已通过，8 章失败」—— 看起来像出了大问题；
 *   过滤掉之后提示是干净的，但**必须把跳过几条说出来**，
 *   否则审核员以为 10 章都审了。
 *   （这条规矩照抄 author-web 的 `batchSubmit`。）
 */
function pickAuditable() {
  const auditable = selected.value.filter((c) => c.status === AUDIT_STATUS)
  const skipped = selected.value.length - auditable.length
  return { auditable, skipped }
}

/* ==================== 审核的核心：一段代码，两个入口 ==================== */

/**
 * 审核一批章节。
 *
 * ★ 顺序 `for...of await`，理由见文件头。逐条 catch ——
 *   一条失败不影响后面的，这正是选前端循环而不是批量接口的理由：
 *   部分失败的语义在这里是显式的、能报给用户看的。
 *
 * @param {Array} chapters 要审的章节（至少一个）
 * @param {{pass: boolean, remark?: string}} payload
 */
async function auditMany(chapters, payload) {
  const label = payload.pass ? '通过' : '驳回'
  const failures = []
  let ok = 0

  batchRunning.value = true
  /*
   * ★ 全部标忙：批量进行中，行内的「审核」按钮也不该能点 ——
   *   否则审核员能在「批量正在审第 3 条」的时候手动再点一次第 5 条，
   *   而第 5 条就在这批里，等于同一条审了两次。
   *   （后端第二次会因状态不对报错，但那条假失败会混进汇总里，很难看。）
   */
  setBusyAll(chapters, true)
  try {
    for (const c of chapters) {
      try {
        await auditChapter(c.id, payload)
        ok += 1
      } catch (e) {
        failures.push({
          title: `第 ${c.chapterNo} 章 ${c.title}`,
          msg: e?.message || '未知错误',
        })
      }
    }
  } finally {
    setBusyAll(chapters, false)
    batchRunning.value = false
  }

  reportBatch(label, ok, failures)

  /*
   * ★ 审完要重新拉作品详情：通过一章会在后端同一个事务里重算
   *   这本书的章节数和总字数，页头那几个数字已经旧了。
   */
  await Promise.all([refreshAfterAudit(), loadNovel()])
}

/** 汇总报告。★ 失败时必须把「哪几条、为什么」说出来 */
function reportBatch(label, ok, failures) {
  if (!failures.length) {
    ElMessage.success(`${ok} 章已${label}`)
    return
  }
  const detail = failures
    .slice(0, 5)
    .map((f) => `「${f.title}」${f.msg}`)
    .join('；')
  const more = failures.length > 5 ? ` 等 ${failures.length} 条` : ''
  ElMessage.warning(`${ok} 章已${label}，${failures.length} 章失败：${detail}${more}`)
}

/**
 * ★★ 审完之后回第几页 —— 取决于被审的章节**还在不在当前列表里**。
 *
 * 「待审核」筛选下，审过的章会立刻离开这个列表。停在原页码的话：
 * 一页 20 条全清掉之后那一页就空了，而分页组件还写着「共 40 条」——
 * 看起来像翻页翻过头了，实际上只是列表往前挪了 20 位。
 * 所以这种情况下回第 1 页（剩下的待审就在那儿）。
 *
 * 「全部」筛选下，那些章还在列表里、只是状态变了 ——
 * 这时候跳回第 1 页反而把审核员从他正在看的位置上踢走。
 */
function refreshAfterAudit() {
  return status.value === AUDIT_STATUS ? paged.reload() : paged.load()
}

/* ==================== 单章审核（AuditDialog） ==================== */

const auditVisible = ref(false)
const auditLoading = ref(false)
/** `{ chapter, detail }` —— detail 里才有正文，那才是审核的依据 */
const auditTarget = ref(null)

/**
 * ★★ 点开时**才**调详情接口。
 *   列表接口刻意不返回正文（一页 20 条正文就是几百 KB），
 *   而审核员一次只看一章 —— 点开哪章调哪章。
 */
async function openAudit(row) {
  try {
    const detail = await getChapterDetail(row.id)
    auditTarget.value = { chapter: row, detail }
    auditVisible.value = true
  } catch {
    // 拦截器已提示（3001 不存在）
  }
}

async function submitSingle({ pass, remark }) {
  const target = auditTarget.value
  if (!target) return
  auditLoading.value = true
  try {
    await auditMany([target.chapter], { pass, remark })
    auditVisible.value = false
  } finally {
    auditLoading.value = false
  }
}

/* ==================== 作品审核（页面终点） ==================== */

const novelAuditVisible = ref(false)
const novelAuditLoading = ref(false)

async function submitNovel({ pass, remark }) {
  novelAuditLoading.value = true
  try {
    await auditNovel(novelId.value, { pass, remark })
    ElMessage.success(pass ? '作品已通过并上架' : '作品已驳回')
    novelAuditVisible.value = false
    await loadNovel()
  } catch {
    // 拦截器已提示（2003 状态不对 / 网络错误）。
    // ★ 弹窗保持打开 —— 关掉的话刚写的驳回理由就没了。
  } finally {
    novelAuditLoading.value = false
  }
}

/* ==================== 批量 ==================== */

async function batchPass() {
  const { auditable, skipped } = pickAuditable()
  if (!auditable.length) {
    ElMessage.warning('选中的章节里没有待审核的（只有待审核的章节能审）')
    return
  }

  const ok = await confirm(
    `选中的 ${auditable.length} 章都会被通过并立刻发布，读者马上能读到。`,
    '批量通过章节',
    { type: 'warning', confirmText: '确定通过' },
  )
  if (!ok) return

  await auditMany(auditable, { pass: true })
  if (skipped > 0) {
    ElMessage.info(`另外 ${skipped} 章不是待审核状态，已跳过`)
  }
}

async function batchReject() {
  const { auditable, skipped } = pickAuditable()
  if (!auditable.length) {
    ElMessage.warning('选中的章节里没有待审核的（只有待审核的章节能审）')
    return
  }

  /*
   * ★★ 理由走 `prompt()` 而不是 AuditDialog，理由见文件头。
   *   `inputValidator` 让「没写理由」时输入框**保持打开**、红字贴在下面，
   *   而不是关掉框我们再弹一次（那一闪很迷惑，而且刚打的字没了）。
   *   ★ 这个必填校验和 `AuditDialog.handleReject()` 里的那条是**同一条规矩**，
   *     只是换了场景 —— 两处都写，是因为后端刻意没把 remark 做成硬校验
   *     （见 AuditDTO 的注释），前端不拦就等于没有。
   */
  const reason = await prompt(
    `选中的 ${auditable.length} 章都会被驳回，它们会收到同一条理由。`,
    '批量驳回章节',
    {
      confirmText: '确定驳回',
      inputPlaceholder: '写明驳回原因（必填）',
      inputValidator: (v) => (v && v.trim() ? true : '驳回必须写明理由，作者要据此修改'),
    },
  )
  if (reason === null) return // 用户取消

  await auditMany(auditable, { pass: false, remark: reason.trim() })
  if (skipped > 0) {
    ElMessage.info(`另外 ${skipped} 章不是待审核状态，已跳过`)
  }
}
</script>

<template>
  <div>
    <!-- ==================== 页头 ==================== -->
    <el-card shadow="never" class="head-card">
      <div class="head">
        <div class="head-left">
          <el-button link type="primary" @click="router.push('/audit')">
            ← 返回审核中心
          </el-button>
          <span class="novel-title">{{ novel?.title || '加载中…' }}</span>
          <el-tag v-if="novel" :type="novelStatusType(novel.status)" size="small">
            {{ novel.statusDesc }}
          </el-tag>
          <span v-if="novel" class="novel-meta">
            共 {{ novelChapters.length }} 章 · {{ formatWordCount(novel.wordCount) }}
          </span>
        </div>

        <div class="head-right">
          <!--
            ★ 这个按钮是这条路径的**终点**：章节审完了，作品本身还没审。
              放在这里是因为那一刻的下一步就是它 —— 让审核员退回去找，
              他多半会忘掉。
          -->
          <el-button
            type="primary"
            :disabled="!canAuditNovel"
            @click="novel && (novelAuditVisible = true)"
          >
            审核作品
          </el-button>
          <span v-if="novel && !canAuditNovel" class="head-hint">
            作品当前是「{{ novel.statusDesc }}」，不是待审核
          </span>
        </div>
      </div>
    </el-card>

    <!-- ==================== 章节列表 ==================== -->
    <el-card shadow="never" class="table-card">
      <div class="toolbar">
        <div class="toolbar-left">
          <span class="filter-label">状态</span>
          <el-select v-model="status" style="width: 130px" @change="onStatusChange">
            <el-option
              v-for="o in STATUS_OPTIONS"
              :key="String(o.value)"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </div>

        <div class="toolbar-right">
          <el-button
            type="success"
            :disabled="!selected.length || batchRunning"
            @click="batchPass"
          >
            批量通过
          </el-button>
          <el-button
            type="danger"
            :disabled="!selected.length || batchRunning"
            @click="batchReject"
          >
            批量驳回
          </el-button>
        </div>
      </div>

      <el-table
        v-loading="paged.loading.value"
        :data="paged.list.value"
        row-key="id"
        stripe
        @selection-change="selected = $event"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column prop="chapterNo" label="章号" width="76" />
        <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
        <el-table-column label="字数" width="110">
          <template #default="{ row }">{{ formatWordCount(row.wordCount) }}</template>
        </el-table-column>
        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createTime) || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="chapterStatusType(row.status)" size="small">
              {{ row.statusDesc }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <!--
              ★ 只有待审核的行才有按钮。已发布的章节再点审核必然被状态机挡下，
                 给一个「点了必然报错」的按钮，用户会觉得是系统坏了。
            -->
            <el-button
              v-if="row.status === 1"
              link
              type="primary"
              :disabled="batchRunning || busyIds.has(row.id)"
              @click="openAudit(row)"
            >
              审核
            </el-button>
            <span v-else class="no-action">—</span>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty
            v-if="paged.loaded.value"
            :description="
              status === 1 ? '这本书没有待审的章节' : '没有符合条件的章节'
            "
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
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="paged.changePage"
        @size-change="paged.changeSize"
      />
    </el-card>

    <!-- ==================== 单章审核弹窗 ==================== -->
    <AuditDialog
      v-model="auditVisible"
      title="审核章节"
      :target-name="
        auditTarget
          ? `第 ${auditTarget.chapter.chapterNo} 章 ${auditTarget.chapter.title}`
          : ''
      "
      pass-text="通过并发布"
      pass-hint="通过后章节立刻发布，读者马上能在目录里读到"
      :loading="auditLoading"
      @submit="submitSingle"
    >
      <ChapterPreview v-if="auditTarget" :chapter="auditTarget.detail" />
    </AuditDialog>

    <!-- ==================== 作品审核弹窗 ==================== -->
    <AuditDialog
      v-model="novelAuditVisible"
      title="审核作品"
      :target-name="novel?.title || ''"
      pass-text="通过并上架"
      pass-hint="通过后作品立刻上架，读者马上能搜到"
      :loading="novelAuditLoading"
      @submit="submitNovel"
    >
      <NovelPreview :novel="novel" :chapters="novelChapters" />
    </AuditDialog>
  </div>
</template>

<style scoped>
.head-card {
  margin-bottom: 12px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.novel-title {
  font-size: 17px;
  font-weight: 600;
}

.novel-meta {
  font-size: 13px;
  color: #909399;
}

.head-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.head-hint {
  font-size: 12px;
  color: #909399;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-label {
  font-size: 13px;
  color: #606266;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.no-action {
  color: #c0c4cc;
}

/* ★ 预览区那几十行样式搬去了 NovelPreview.vue / ChapterPreview.vue —— 那里是三处共用的 */
</style>
