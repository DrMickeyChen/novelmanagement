<script setup>
/**
 * 章节管理 —— 某一本书的章节列表 + 状态流转 + 批量操作。
 *
 * ============================================================
 * ★★ 它和工作台的关系：两个入口，同一份数据
 * ============================================================
 * 写正文有两个入口，都是正当的，不能砍掉任何一个：
 *   · 工作台：作者有完整大纲时，顺着树一章一章往下写（主线）
 *   · 本页：作者在章节管理里直接加一章 —— 尤其是**还没有大纲的书**
 *     （`NovelServiceImpl.create` 不建根节点，新书进来就是空大纲），
 *     以及要批量提交/删除的时候
 *
 * ============================================================
 * ★★ 批量操作用【前端循环】，没有批量接口（方案决定 10）
 * ============================================================
 * 逐条发请求，然后**把失败的那几条汇总报出来**。
 * 加一个 `POST /batch-submit` 的诱惑在于少几次往返，但代价是
 * 「19 条成功、1 条失败」这件事在响应里没有天然的位置 ——
 * 要么整体事务回滚（那 19 条白提交了），要么返回一个部分成功的结构，
 * 而那个结构除了这个页面**没有任何调用方**，是一份只为一处存在的契约。
 *
 * ★ 循环必须是**顺序**的（`for...of await`），不能写成
 *   `Promise.all(ids.map(...))`：
 *    ① 删章节会触发 `syncNovelStat` 重算并 UPDATE 同一行 novel ——
 *       并发打过去全在等同一把行锁，总耗时不会更快
 *    ② 并发时失败结果是无序的，报出来的「第 3 条失败」
 *       和作者勾选的顺序对不上号，等于没报
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getChapters,
  submitChapter,
  withdrawChapter,
  deleteChapter,
} from '@/api/chapter'
import { getNovelDetail } from '@/api/novel'
import ChapterEditor from '@/components/ChapterEditor.vue'
import { formatDateTime } from '@/utils/format'
import { chapterStatusType, novelStatusType } from '@/utils/status'

const route = useRoute()
const router = useRouter()

const novelId = computed(() => Number(route.params.novelId))

const novel = ref(null)
const list = ref([])
const loading = ref(false)
const failed = ref(false)

/** 状态筛选。null = 全部。★ 传给后端，不在前端筛 —— 见 works/index.vue 的说明 */
const status = ref(null)

const STATUS_OPTIONS = [
  { label: '全部', value: null },
  { label: '草稿', value: 0 },
  { label: '待审核', value: 1 },
  { label: '已发布', value: 2 },
  { label: '已驳回', value: 4 },
]

/** 表格里勾选的章节对象 */
const selected = ref([])

/** 正在处理中的章节 id 集合，用来只禁用那一行 */
const busyIds = ref(new Set())

/** 批量操作进行中（此时整张表的操作按钮都该禁用） */
const batchRunning = ref(false)

/* ==================== 加载 ==================== */

async function loadNovel() {
  try {
    novel.value = await getNovelDetail(novelId.value)
  } catch {
    // 拦截器已提示（2001 不存在 / 2002 不是自己的书）。
    // ★ 章节列表那边会拿到同样的错误，所以这里不重复处理，静默即可 ——
    //   这个请求只是为了让页头显示书名。
    novel.value = null
  }
}

async function loadChapters() {
  loading.value = true
  failed.value = false
  try {
    list.value = (await getChapters(novelId.value, { status: status.value })) || []
  } catch {
    failed.value = true
    list.value = []
  } finally {
    loading.value = false
  }
}

/** ★ 筛选条件和「重新加载」成对出现，避免某处只改了 status 忘了重新拉 */
async function applyFilter(v) {
  status.value = v
  await loadChapters()
}

onMounted(() => {
  loadNovel()
  loadChapters()
})

/* ==================== 单条操作 ==================== */

function isBusy(id) {
  return busyIds.value.has(id)
}

/**
 * ★ 用一个 Set 而不是单个 `busyId`：批量操作之外，
 *   作者完全可能在第一条还在转的时候就去点第二条。
 *   单值的话第二条一点，第一条的按钮就重新可点了（而它还在飞），
 *   再点一次就是重复提交。
 *
 * ★ 必须换一个新 Set 再赋值 —— `busyIds.value.add(x)` 改的是同一个
 *   Set 对象，Vue 的 ref 只在 `.value` 被**替换**时才通知依赖，
 *   原地修改不会触发重渲染。
 */
function setBusy(id, on) {
  const next = new Set(busyIds.value)
  if (on) next.add(id)
  else next.delete(id)
  busyIds.value = next
}

async function runOne(chapter, fn, okMsg) {
  setBusy(chapter.id, true)
  try {
    await fn(chapter.id)
    ElMessage.success(okMsg)
    await loadChapters()
    return true
  } catch {
    // 拦截器已提示
    return false
  } finally {
    setBusy(chapter.id, false)
  }
}

const handleSubmit = (c) => runOne(c, submitChapter, `「${c.title}」已提交审核`)
const handleWithdraw = (c) => runOne(c, withdrawChapter, `「${c.title}」已撤回，可以继续修改`)

async function handleDelete(chapter) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${chapter.title}」吗？正文会一并逻辑删除，作品的章节数和字数会重算。`,
      '删除章节',
      // ★ 命令式 API 拿不到 el-config-provider 的语言包，按钮文案必须显式传
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' },
    )
  } catch {
    return // 用户取消
  }
  await runOne(chapter, deleteChapter, '已删除')
}

/* ==================== 批量操作 ==================== */

/**
 * 顺序执行一批操作，汇总失败项。
 *
 * @param {Array} chapters 要处理的章节
 * @param {(id:number)=>Promise} fn
 * @param {string} label 用于提示语，例如「提交」
 * @returns {Promise<{ok:number, failures:Array<{title:string,msg:string}>}>}
 */
async function runBatch(chapters, fn, label) {
  const failures = []
  let ok = 0

  batchRunning.value = true
  // ★ 全部标忙：批量进行中，行内的操作按钮也不该能点 ——
  //   否则作者能在「批量删除正在删第 3 条」的时候手动再删一次第 5 条，
  //   两个请求撞在一起，失败的那条报的还是「章节不存在」，莫名其妙。
  setBusyAll(chapters, true)
  try {
    for (const c of chapters) {
      try {
        await fn(c.id)
        ok += 1
      } catch (e) {
        // ★ 逐条 catch，一条失败不影响后面的 —— 这正是选前端循环的理由：
        //   部分失败的语义在这里是显式的、可展示的
        failures.push({ title: c.title, msg: e?.message || '未知错误' })
      }
    }
  } finally {
    setBusyAll(chapters, false)
    batchRunning.value = false
  }

  return { ok, failures }
}

function setBusyAll(chapters, on) {
  const next = new Set(busyIds.value)
  for (const c of chapters) {
    if (on) next.add(c.id)
    else next.delete(c.id)
  }
  busyIds.value = next
}

/** 报告批量结果。★ 失败时必须把「哪几条、为什么」说出来 */
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
 * 批量提交审核。
 *
 * ★★ 只挑「能提交的」发出去，不要把全部勾选项都打一遍。
 *    待审核(1) 和已发布(2) 提交上去必然失败（状态机不允许），
 *    结果是作者勾了 10 章、其中 8 章本来就是已发布的，
 *    然后收到一句「2 章已提交，8 章失败」—— 看起来像出了大问题。
 *    过滤掉之后，提示才会是干净的「2 章已提交审核」。
 *
 * ★ 但**不能静默过滤**：过滤掉了哪几章要说出来，
 *   否则作者以为 10 章都提交了。
 */
async function batchSubmit() {
  const submittable = selected.value.filter((c) => c.status === 0 || c.status === 4)
  const skipped = selected.value.length - submittable.length

  if (!submittable.length) {
    ElMessage.warning('选中的章节里没有可提交的（只有草稿和已驳回能提交审核）')
    return
  }

  const { ok, failures } = await runBatch(submittable, submitChapter, '提交')
  reportBatch('提交审核', ok, failures)
  if (skipped > 0) {
    ElMessage.info(`另外 ${skipped} 章是待审核或已发布，已跳过`)
  }
  await loadChapters()
}

async function batchDelete() {
  if (!selected.value.length) return
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selected.value.length} 章吗？此操作不可撤销。` +
        `已发布的章节也会被删除，读者将无法再读到。`,
      '批量删除章节',
      { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  const { ok, failures } = await runBatch(selected.value, deleteChapter, '删除')
  reportBatch('删除', ok, failures)
  await loadChapters()
}

/* ==================== 编辑弹窗 ==================== */

const editorVisible = ref(false)
/** null = 新建 */
const editingId = ref(null)
const editorRef = ref(null)
const savingEditor = ref(false)

function openCreate() {
  editingId.value = null
  editorVisible.value = true
}

function openEdit(chapter) {
  editingId.value = chapter.id
  editorVisible.value = true
}

/**
 * ★★ 拦截关闭动作。
 *
 *   el-dialog 的 × / ESC / 点遮罩**都不会**走我们 footer 里的按钮，
 *   它们直接改 `v-model`。所以「有未保存的修改」这个判断必须挂在
 *   `:before-close` 上 —— 挂在自己的「关闭」按钮上只挡住了三条路径里的一条。
 */
function beforeClose(done) {
  if (!editorRef.value?.isDirty) {
    done()
    return
  }
  ElMessageBox.confirm('有未保存的修改，关闭后会丢失。', '放弃修改？', {
    type: 'warning',
    confirmButtonText: '放弃修改',
    cancelButtonText: '继续编辑',
  })
    .then(() => done())
    .catch(() => {
      /* 用户选择继续编辑 —— 什么都不做，弹窗保持打开 */
    })
}

/**
 * ★ 保存成功后要把 `editingId` 补上（新建时它是 null）。
 *
 *   不补的话，弹窗标题会一直停在第 2 步之前的状态 ——
 *   点「保存并提交审核」之后弹窗关掉了看不出来，
 *   但作者点「保存」而不关弹窗时，他会看到标题还写着「新建章节」，
 *   于是怀疑「到底建出来没有」，然后再点一次保存 ——
 *   这一次不会再建一章（编辑器里的 currentId 已经不是 null 了），
 *   但那次多余的往返本来就不该发生。
 */
async function handleEditorSave() {
  savingEditor.value = true
  try {
    const ok = await editorRef.value.save()
    if (ok) {
      if (editingId.value === null) editingId.value = editorRef.value.currentId
      await loadChapters()
      await loadNovel()
    }
  } finally {
    savingEditor.value = false
  }
}

/**
 * 保存并提交审核 —— 一步到位。
 *
 * ★ 存在的理由：作者的常见动作是「写完 → 提交」两步连做。
 *   没有这个按钮他就得「保存 → 关掉弹窗 → 在列表里找到刚存的那一行 → 提交」，
 *   而刚存的那一行在筛选了「草稿」时还会跳位置。
 *
 * ★ 保存失败时**不能继续提交** —— 否则提交上去的是上一版内容，
 *   而作者以为提交的是刚写的那一版。
 */
async function handleEditorSaveAndSubmit() {
  savingEditor.value = true
  try {
    const ok = await editorRef.value.save()
    if (!ok) return
    const id = editorRef.value.currentId
    if (id === null) return

    try {
      await submitChapter(id)
      ElMessage.success('已保存并提交审核')
    } catch {
      // ★ 最常见的失败是 3005「正文为空」。此时**章节已经建出来了**，
      //   只是没提交 —— 必须把这个事实说出来，否则作者以为整件事都失败了，
      //   会在列表里找不到它而重复新建。
      ElMessage.warning('内容已保存，但提交审核没有成功（原因见上）')
    }
    editorVisible.value = false
    await loadChapters()
    await loadNovel()
  } finally {
    savingEditor.value = false
  }
}

/* ==================== 其它 ==================== */

const draftCount = computed(() => list.value.filter((c) => c.status === 0).length)

function openWorkbench() {
  router.push(`/novel/${novelId.value}/work`)
}
</script>

<template>
  <div>
    <div class="page-head">
      <div class="head-left">
        <h2 class="page-title">{{ novel?.title || '章节管理' }}</h2>
        <el-tag v-if="novel" :type="novelStatusType(novel.status)" size="small">
          {{ novel.statusDesc }}
        </el-tag>
        <span class="sub">{{ list.length }} 章</span>
      </div>
      <div class="head-right">
        <el-button @click="openWorkbench">工作台</el-button>
        <el-button @click="router.push('/works')">返回列表</el-button>
        <el-button type="primary" @click="openCreate">新建章节</el-button>
      </div>
    </div>

    <el-card shadow="never">
      <div class="filters">
        <el-radio-group :model-value="status" size="small" @update:model-value="applyFilter">
          <el-radio-button v-for="o in STATUS_OPTIONS" :key="String(o.value)" :value="o.value">
            {{ o.label }}
          </el-radio-button>
        </el-radio-group>

        <!--
          ★ 批量按钮【常驻但按需禁用】，不做 v-if 显隐。
            显隐的话，作者勾选的那一刻按钮突然出现，工具栏会跳一下；
            而且「这个功能存在吗」这个问题在没有勾选时无法回答。
        -->
        <div class="batch">
          <span v-if="selected.length" class="selected-tip">
            已选 {{ selected.length }} 章
          </span>
          <el-button
            size="small"
            type="success"
            :disabled="!selected.length || batchRunning"
            :loading="batchRunning"
            @click="batchSubmit"
          >
            批量提交审核
          </el-button>
          <el-button
            size="small"
            type="danger"
            :disabled="!selected.length || batchRunning"
            :loading="batchRunning"
            @click="batchDelete"
          >
            批量删除
          </el-button>
        </div>
      </div>

      <div v-loading="loading">
        <div v-if="failed" class="tip">
          章节加载失败，<el-link type="primary" @click="loadChapters">重试</el-link>
        </div>

        <el-empty
          v-else-if="!loading && !list.length"
          :image-size="70"
          :description="status === null ? '这本书还没有章节' : '这个状态下没有章节'"
        >
          <el-button type="primary" @click="openCreate">新建章节</el-button>
        </el-empty>

        <el-table
          v-else
          :data="list"
          row-key="id"
          size="small"
          @selection-change="selected = $event"
        >
          <el-table-column type="selection" width="42" />

          <el-table-column prop="chapterNo" label="序号" width="70" align="center" />

          <el-table-column label="标题" min-width="220">
            <template #default="{ row }">
              <!-- ★ 标题可点直接进编辑：这是最高频的动作，
                   让作者去最右边找「编辑」按钮是没必要的一步 -->
              <el-link type="primary" @click="openEdit(row)">{{ row.title }}</el-link>
            </template>
          </el-table-column>

          <el-table-column label="字数" width="90" align="right">
            <template #default="{ row }">
              <span class="num">{{ row.wordCount ?? 0 }}</span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="chapterStatusType(row.status)" size="small">
                {{ row.statusDesc }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="发布时间" width="150">
            <template #default="{ row }">
              <!-- ★ 未发布时显示创建时间并标注，而不是留一个空格 ——
                   空单元格让人以为是数据丢了 -->
              <span v-if="row.publishTime">{{ formatDateTime(row.publishTime) }}</span>
              <span v-else class="muted">创建于 {{ formatDateTime(row.createTime) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="200" align="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>

              <el-button
                v-if="row.status === 0 || row.status === 4"
                link
                type="success"
                size="small"
                :loading="isBusy(row.id) && !batchRunning"
                :disabled="batchRunning"
                @click="handleSubmit(row)"
              >
                提交审核
              </el-button>
              <el-button
                v-else-if="row.status === 1"
                link
                type="warning"
                size="small"
                :loading="isBusy(row.id) && !batchRunning"
                :disabled="batchRunning"
                @click="handleWithdraw(row)"
              >
                撤回提交
              </el-button>

              <el-button
                link
                type="danger"
                size="small"
                :loading="isBusy(row.id) && !batchRunning"
                :disabled="batchRunning"
                @click="handleDelete(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!--
        ★ 这里【没有分页】。章节列表接口返回的是完整数组（不分页）——
          一本书几百章对浏览器毫无压力，而目录天然需要「一眼看全」
          （作者要按序号核对有没有缺章、有没有重复）。
          真到几千章的时候要的是搜索和虚拟滚动，不是分页。
      -->
      <div v-if="list.length" class="foot-tip">
        共 {{ list.length }} 章<span v-if="draftCount">，其中 {{ draftCount }} 章还是草稿</span>
      </div>
    </el-card>

    <!-- ==================== 编辑弹窗 ==================== -->
    <el-dialog
      v-model="editorVisible"
      :title="editingId === null ? '新建章节' : '编辑章节'"
      width="780px"
      :close-on-click-modal="false"
      :before-close="beforeClose"
    >
      <ChapterEditor ref="editorRef" :novel-id="novelId" :chapter-id="editingId" />

      <template #footer>
        <el-button @click="editorVisible = false">关闭</el-button>
        <el-button :loading="savingEditor" @click="handleEditorSave">保存</el-button>
        <el-button type="success" :loading="savingEditor" @click="handleEditorSaveAndSubmit">
          保存并提交审核
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  gap: 12px;
  flex-wrap: wrap;
}

.head-left,
.head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-title {
  margin: 0;
  font-size: 20px;
}

.sub {
  font-size: 13px;
  color: #909399;
}

.filters {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.batch {
  display: flex;
  align-items: center;
  gap: 8px;
}

.selected-tip {
  font-size: 13px;
  color: #409eff;
}

.tip {
  font-size: 13px;
  color: #909399;
  padding: 12px 4px;
}

/* ★ 数字列右对齐 + 等宽数字：字数是不等宽的，
   不用 tabular-nums 的话每行的小数点对不齐，看起来是歪的 */
.num {
  font-variant-numeric: tabular-nums;
}

.muted {
  color: #c0c4cc;
  font-size: 12px;
}

.foot-tip {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
}
</style>
