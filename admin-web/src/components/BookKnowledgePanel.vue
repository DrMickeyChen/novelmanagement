<script setup>
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getNovelPage } from '@/api/novel'
import { getKnowledgeStatus, getKnowledge, getKnowledgeTasks, buildKnowledge, deleteKnowledge, getFinderSkills, toggleFinderSkill } from '@/api/bookKnowledge'
import { previewKnowledge, getKnowledgeNotes, saveKnowledgeDraft, generateKnowledgeDraft, reviewKnowledgeDraft } from '@/api/bookKnowledge'
import { getFinderRuns, reindexKnowledge } from '@/api/bookKnowledge'
const status = ref(null), books = ref([]), knowledge = ref([]), tasks = ref([]), skills = ref([])
const props = defineProps({ mode: { type: String, default: 'knowledge' }, revision: { type: Number, default: 0 }, active: { type: Boolean, default: true } })
watch(() => [props.revision, props.active], () => { if (props.active) refresh() })
const selected = ref([]), selectedKnowledge = ref([]), loading = ref(false), submitting = ref(false), failed = ref(false)
const booksLoading = ref(false), removing = ref(false), stopBatch = ref(false), batchProgress = ref('')
const query = reactive({ keyword: '', page: 1, size: 20, status: 2 })
const knowledgeQuery = reactive({ keyword: '', page: 1, size: 20 })
const activeIds = computed(() => new Set(tasks.value.filter(t => ['QUEUED', 'RUNNING'].includes(t.status)).map(t => String(t.novelId))))
const builtIds = computed(() => new Set(knowledge.value.map(k => String(k.novelId))))
const pendingBooks = computed(() => books.value.filter(b => !builtIds.value.has(String(b.id)) && !activeIds.value.has(String(b.id))))
const total = computed(() => pendingBooks.value.length)
const bookPage = computed(() => pendingBooks.value.slice((query.page - 1) * query.size, query.page * query.size))
const filteredKnowledge = computed(() => {
  const keyword = knowledgeQuery.keyword.trim().toLowerCase()
  return knowledge.value.filter(k => !keyword || `${k.book.title} ${k.book.author || ''}`.toLowerCase().includes(keyword))
})
const knowledgePage = computed(() => filteredKnowledge.value.slice((knowledgeQuery.page - 1) * knowledgeQuery.size, knowledgeQuery.page * knowledgeQuery.size))
function setSelection(target, ids, checked) {
  if (target === 'books') target = selected
  if (target === 'knowledge') target = selectedKnowledge
  const result = new Set(target.value)
  ids.forEach(id => checked ? result.add(String(id)) : result.delete(String(id)))
  target.value = [...result]
}
const allBooksOnPage = computed(() => bookPage.value.length > 0 && bookPage.value.every(b => selected.value.includes(String(b.id))))
const someBooksOnPage = computed(() => bookPage.value.some(b => selected.value.includes(String(b.id))) && !allBooksOnPage.value)
const allKnowledgeOnPage = computed(() => knowledgePage.value.length > 0 && knowledgePage.value.every(k => selectedKnowledge.value.includes(String(k.novelId))))
const someKnowledgeOnPage = computed(() => knowledgePage.value.some(k => selectedKnowledge.value.includes(String(k.novelId))) && !allKnowledgeOnPage.value)
watch(pendingBooks, rows => {
  const allowed = new Set(rows.map(b => String(b.id)))
  selected.value = selected.value.filter(id => allowed.has(id))
  query.page = Math.min(query.page, Math.max(1, Math.ceil(rows.length / query.size)))
})
watch(filteredKnowledge, rows => {
  knowledgeQuery.page = Math.min(knowledgeQuery.page, Math.max(1, Math.ceil(rows.length / knowledgeQuery.size)))
})
watch(() => knowledgeQuery.keyword, () => { knowledgeQuery.page = 1 })
watch(knowledge, rows => {
  const allowed = new Set(rows.map(k => String(k.novelId)))
  selectedKnowledge.value = selectedKnowledge.value.filter(id => allowed.has(id))
})
const form = reactive({ chapters: 10, vectors: false })
const notes = ref([]), preview = ref(null), dialog = ref(false), savingNote = ref(false)
const runs = ref([])
const draft = reactive({ summary: '', chunkId: '', quote: '' })
let timer, refreshPromise, disposed = false, nextPollAt = 0
const refreshError = ref('')
function pageVisible() { return typeof document === 'undefined' || document.visibilityState !== 'hidden' }
async function loadBooks() {
  if (booksLoading.value) return
  booksLoading.value = true
  try {
    // Load metadata only, then exclude indexed/queued books BEFORE pagination.
    const keyword = query.keyword
    const first = await getNovelPage({ keyword, page: 1, size: 100, status: 2 })
    const rows = [...(first.records || [])]
    const pages = Math.ceil(first.total / 100)
    for (let page = 2; page <= pages; page++) {
      const next = await getNovelPage({ keyword, page, size: 100, status: 2 })
      rows.push(...(next.records || []))
    }
    books.value = [...new Map(rows.map(b => [String(b.id), b])).values()]
    query.page = 1
  } finally { booksLoading.value = false }
}
function refresh(background = false) {
  background = background === true
  if (disposed || props.active === false) return Promise.resolve()
  // Keep the lock until ALL requests settle, including siblings of a failed request.
  if (refreshPromise) return refreshPromise
  if (!background) loading.value = true
  const config = { silent: true }
  const requests = props.mode === 'operations'
    ? [[skills, getFinderSkills], [runs, getFinderRuns]]
    : [[knowledge, getKnowledge], [tasks, getKnowledgeTasks], [notes, getKnowledgeNotes],
        ...(props.mode === 'knowledge' ? [[status, getKnowledgeStatus]] : [])]
  refreshPromise = Promise.allSettled(requests.map(([, fetch]) => Promise.resolve().then(() => fetch(config))))
    .then(results => {
      if (disposed) return
      const errors = []
      results.forEach((result, i) => {
        if (result.status === 'fulfilled') requests[i][0].value = result.value
        else errors.push(result.reason)
      })
      failed.value = errors.length > 0
      const unauthorized = errors.some(error => error?.response?.status === 401)
      const timedOut = errors.some(error => error?.code === 'ECONNABORTED')
      refreshError.value = unauthorized ? '登录已过期，请重新登录后刷新资料'
        : timedOut ? '部分资料请求超时，已保留成功加载的数据，请稍后刷新重试'
          : '部分资料暂时获取失败，已保留成功加载的数据，请刷新重试'
      nextPollAt = Date.now() + (failed.value ? 30000 : 5000)
    }).finally(() => { loading.value = false; refreshPromise = null })
  return refreshPromise
}
function poll() {
  if (props.active === false || !pageVisible() || disposed || refreshPromise || submitting.value || removing.value || Date.now() < nextPollAt) return
  if (props.mode === 'operations' || tasks.value.some(t => ['RUNNING', 'QUEUED'].includes(t.status))) refresh(true)
}
async function submit() {
  if (!selected.value.length) return ElMessage.warning('请先选择已上架作品')
  const ids = [...selected.value], chapters = form.chapters, vectors = form.vectors
  if (vectors) await ElMessageBox.confirm(`将为 ${ids.length} 本作品构建向量，调用已配置的 Embedding 接口，可能产生费用。相同内容版本会复用已有索引。`, '构建向量知识库')
  submitting.value = true
  stopBatch.value = false
  batchProgress.value = '正在提交第一批'
  try {
    for (let start = 0; start < ids.length && !stopBatch.value; start += 50) {
      const batch = ids.slice(start, start + 50)
      const accepted = await buildKnowledge({ ids: batch, chapters, vectors })
      setSelection(selected, batch, false)
      batchProgress.value = `已提交 ${Math.min(start + 50, ids.length)} / ${ids.length} 本`
      await refresh()
      if (start + 50 < ids.length) {
        const taskIds = new Set(accepted.map(t => t.id))
        let waiting = true
        while (waiting && !stopBatch.value) {
          await new Promise(resolve => setTimeout(resolve, 3000))
          if (stopBatch.value) break
          const latest = await getKnowledgeTasks()
          tasks.value = latest
          const own = latest.filter(t => taskIds.has(t.id))
          if (own.length !== taskIds.size) {
            stopBatch.value = true
            ElMessage.warning('任务记录已变化，已暂停后续批次，请刷新后检查')
            break
          }
          waiting = own.some(t => ['QUEUED', 'RUNNING'].includes(t.status))
        }
      }
    }
    ElMessage.success(stopBatch.value ? '已停止后续批次，已提交任务继续执行' : '选中作品已分批提交构建')
  } finally { submitting.value = false; await refresh() }
}
async function migrateIndex() {
  if (!selectedKnowledge.value.length) return
  if (selectedKnowledge.value.length > 50) return ElMessage.warning('索引迁移每批最多50本，请分批选择')
  submitting.value = true
  try {
    const ids = [...selectedKnowledge.value]
    await reindexKnowledge(ids)
    ElMessage.success('ES 索引迁移已提交，复用已有向量，不调用模型 API')
    await refresh()
  } finally { submitting.value = false }
}
async function remove(row) {
  await ElMessageBox.confirm(`移除《${row.book.title}》的知识索引及关联摘要？小说正文会继续保留。`, '移除知识索引')
  removing.value = true
  try { await deleteKnowledge(row.novelId) }
  finally { removing.value = false; await refresh() }
}
async function removeSelected() {
  const ids = [...selectedKnowledge.value]
  if (!ids.length) return
  await ElMessageBox.confirm(`移除选中的 ${ids.length} 本作品知识索引及关联摘要？小说正文和封面会继续保留。`, '批量移除知识资料')
  removing.value = true
  let removed = 0
  try {
    for (const id of ids) {
      try { await deleteKnowledge(id); removed++; setSelection(selectedKnowledge, [id], false) }
      catch { /* Keep unsuccessful rows selected for retry. */ }
    }
    if (removed === ids.length) ElMessage.success(`已移除 ${removed} 本作品的知识资料`)
    else ElMessage.warning(`已移除 ${removed} 本，${ids.length - removed} 本移除失败，请重试`)
  } finally { removing.value = false; await refresh() }
}
async function toggle(skill) { await toggleFinderSkill(skill.id, !skill.enabled); await refresh() }
async function openNote(row) {
  preview.value = await previewKnowledge(row.novelId)
  const note = notes.value.find(n => n.novelId === row.novelId)
  Object.assign(draft, { summary: note?.summary || '', chunkId: note?.chunkId || preview.value.chunks[0]?.id || '', quote: note?.quote || '' })
  dialog.value = true
}
async function saveNote() {
  savingNote.value = true
  try { await saveKnowledgeDraft(preview.value.novelId, draft); ElMessage.success('已保存待审草稿'); await refresh() } finally { savingNote.value = false }
}
async function generate(row) {
  await ElMessageBox.confirm('将调用当前聊天模型生成开篇摘要草稿，可能产生费用。草稿必须审核后才参与推荐；同内容版本的生成结果会复用。', '生成待审摘要')
  await generateKnowledgeDraft(row.novelId); ElMessage.success('已提交摘要任务'); await refresh()
}
async function review(note, approved) {
  if (approved) await ElMessageBox.confirm('请确认摘要与逐字引文一致，且没有把少量开篇推断成全书结论。审核通过的摘要只作为辅助资料。', '审核摘要')
  await reviewKnowledgeDraft(note.novelId, approved); await refresh()
}
async function retry(task) {
  if (task.notice.includes('摘要')) return generate({ novelId: task.novelId })
  await buildKnowledge({ ids: [task.novelId], chapters: task.chapters, vectors: task.vectors }); await refresh()
}
onMounted(async () => {
  await Promise.allSettled([refresh(), props.mode === 'knowledge' && props.active !== false ? loadBooks() : Promise.resolve()])
  if (!disposed) timer = setInterval(poll, 5000)
})
onUnmounted(() => { disposed = true; clearInterval(timer); stopBatch.value = true })
const names = { QUEUED: '等待中', RUNNING: '构建中', DONE: '已完成', FAILED: '失败', STALE: '已失效', INTERRUPTED: '已中断' }
</script>
<template>
  <section v-loading="loading" class="knowledge-panel">
    <el-alert v-if="failed" :title="refreshError" type="error" :closable="false" />
    <el-button v-if="props.mode !== 'knowledge'" :loading="loading" @click="refresh">刷新资料</el-button>
    <el-alert v-if="props.mode !== 'operations'" title="正文知识库按需构建。默认前 10 个已发布章节，每本最多 10 万字符；少量开篇不能证明整本书风格。" type="info" :closable="false" />
    <p v-if="status && props.mode === 'knowledge'">Embedding：{{ status.embeddingConfigured ? status.embeddingModel : '未配置，使用关键词检索' }}；Rerank：{{ status.rerankConfigured ? status.rerankModel : '未配置' }}。向量方式：{{ status.vectorSearch }}</p>
    <template v-if="props.mode === 'knowledge'">
    <h3>构建知识库</h3>
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="搜索待构建作品" clearable :disabled="booksLoading || submitting" @keyup.enter="loadBooks" @clear="loadBooks" />
      <el-button :disabled="booksLoading || submitting" @click="loadBooks">搜索</el-button>
      <span>前</span><el-input-number v-model="form.chapters" :min="1" :max="50" /><span>章</span>
      <el-checkbox v-model="form.vectors" :disabled="!status?.embeddingConfigured">同时构建向量（调用 API）</el-checkbox>
      <el-button type="primary" :loading="submitting" :disabled="!selected.length || removing || failed" @click="submit">构建选中 {{ selected.length }} 本</el-button>
      <el-button :disabled="submitting || removing || booksLoading" @click="refresh(); loadBooks()">刷新列表</el-button>
    </div>
    <div class="toolbar">
      <el-button :disabled="submitting || booksLoading || failed" @click="selected = pendingBooks.map(b => String(b.id))">全选搜索结果（{{ total }} 本）</el-button>
      <el-button :disabled="submitting" @click="selected = []">清空选择</el-button>
      <span>跨页已选 {{ selected.length }} 本；已构建及正在构建的作品不在此列表。</span>
      <span v-if="submitting">{{ batchProgress }}，保持页面打开以继续提交。</span>
      <el-button v-if="submitting" @click="stopBatch = true">停止后续批次</el-button>
    </div>
    <el-table v-loading="booksLoading" :data="bookPage" row-key="id">
      <el-table-column width="55">
        <template #header><el-checkbox aria-label="选中当前页待构建作品" :model-value="allBooksOnPage" :indeterminate="someBooksOnPage" :disabled="submitting || failed" @change="setSelection('books', bookPage.map(b => b.id), $event)" /></template>
        <template #default="{ row }"><el-checkbox :aria-label="`选择${row.title}`" :model-value="selected.includes(String(row.id))" :disabled="submitting || failed" @change="setSelection('books', [row.id], $event)" /></template>
      </el-table-column>
      <el-table-column prop="title" label="作品" /><el-table-column prop="wordCount" label="字数" width="130" />
    </el-table>
    <el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total" layout="total, prev, pager, next" />
    </template>
    <template v-if="props.mode !== 'operations'">
    <h3>{{ props.mode === 'summaries' ? '可生成摘要的正文资料' : '已构建资料' }}（{{ knowledge.length }} 本）</h3>
    <div class="toolbar">
      <el-input v-model="knowledgeQuery.keyword" placeholder="搜索已构建作品或作者" clearable />
      <template v-if="props.mode === 'knowledge'">
        <el-button :disabled="removing || submitting || failed" @click="selectedKnowledge = filteredKnowledge.map(k => String(k.novelId))">全选搜索结果（{{ filteredKnowledge.length }} 本）</el-button>
        <el-button :disabled="removing" @click="selectedKnowledge = []">清空选择</el-button>
        <el-button type="danger" :loading="removing" :disabled="!selectedKnowledge.length || submitting || failed" @click="removeSelected">移除选中 {{ selectedKnowledge.length }} 本</el-button>
        <el-button :disabled="!selectedKnowledge.length || submitting || removing || failed" @click="migrateIndex">迁移 ES 检索索引（不调用模型）</el-button>
        <span>跨页已选 {{ selectedKnowledge.length }} 本</span>
      </template>
    </div>
    <el-table :data="knowledgePage" row-key="novelId">
      <el-table-column v-if="props.mode === 'knowledge'" width="55">
        <template #header><el-checkbox aria-label="选中当前页已构建资料" :model-value="allKnowledgeOnPage" :indeterminate="someKnowledgeOnPage" :disabled="removing || submitting" @change="setSelection('knowledge', knowledgePage.map(k => k.novelId), $event)" /></template>
        <template #default="{ row }"><el-checkbox :aria-label="`选择${row.book.title}资料`" :model-value="selectedKnowledge.includes(String(row.novelId))" :disabled="removing || submitting" @change="setSelection('knowledge', [row.novelId], $event)" /></template>
      </el-table-column>
      <el-table-column label="作品"><template #default="{ row }">{{ row.book.title }}</template></el-table-column>
      <el-table-column label="正文覆盖" width="180"><template #default="{ row }">前 {{ row.coveredChapters }} / {{ row.totalChapters }} 章{{ row.truncated ? '，有截断' : '' }}</template></el-table-column>
      <el-table-column prop="characterCount" label="字符数" width="120" />
      <el-table-column label="检索资料" width="130"><template #default="{ row }">{{ row.embeddingModel ? '正文 + 向量' : '正文关键词' }}</template></el-table-column>
      <el-table-column label="操作" width="250"><template #default="{ row }"><el-button link @click="openNote(row)">{{ props.mode === 'summaries' ? '查看资料 / 写摘要' : '查看正文资料' }}</el-button><el-button v-if="props.mode === 'summaries'" link :disabled="removing || submitting" @click="generate(row)">生成草稿</el-button><el-button v-else link type="danger" :disabled="removing || submitting" @click="remove(row)">移除</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="knowledgeQuery.page" :page-size="knowledgeQuery.size" :total="filteredKnowledge.length" layout="total, prev, pager, next" />
    </template>
    <template v-if="props.mode === 'summaries'">
    <h3>风格摘要审核</h3>
    <p>摘要属于知识库辅助资料，由聊天模型生成。草稿不参与推荐；审核后仍须与当前正文版本一致，生成只读取最多 8 个开篇片段。</p>
    <el-table :data="notes" max-height="300">
      <el-table-column prop="novelId" label="作品 ID" width="150" />
      <el-table-column prop="summary" label="摘要" min-width="220" /><el-table-column prop="quote" label="逐字出处" min-width="180" />
      <el-table-column label="状态" width="90"><template #default="{ row }">{{ ({ PENDING: '待审核', REVIEWED: '已审核', REJECTED: '已驳回' })[row.state] }}</template></el-table-column>
      <el-table-column label="操作" width="130"><template #default="{ row }"><el-button v-if="row.state === 'PENDING'" link type="success" @click="review(row, true)">通过</el-button><el-button v-if="row.state === 'PENDING'" link type="danger" @click="review(row, false)">驳回</el-button></template></el-table-column>
    </el-table>
    </template>
    <template v-if="props.mode !== 'operations'">
    <h3>处理任务（最近 100 条，保留 7 天）</h3>
    <el-table :data="tasks" max-height="320">
      <el-table-column prop="novelId" label="作品 ID" width="160" />
      <el-table-column label="状态" width="95"><template #default="{ row }">{{ names[row.status] || row.status }}</template></el-table-column>
      <el-table-column prop="chunks" label="已处理片段" width="110" />
      <el-table-column prop="notice" label="说明" />
      <el-table-column label="操作" width="100"><template #default="{ row }"><el-button v-if="['FAILED', 'STALE', 'INTERRUPTED'].includes(row.status)" link @click="retry(row)">重新构建</el-button></template></el-table-column>
    </el-table>
    </template>
    <template v-if="props.mode === 'operations'">
    <h3>找书任务策略（Skill）</h3>
    <p>策略只影响任务说明，工具白名单、权限和硬条件仍由 Java 校验。当前版本通过代码维护。</p>
    <el-collapse><el-collapse-item v-for="skill in skills" :key="skill.id" :title="`${skill.name} · v${skill.version} · ${skill.enabled ? '启用' : '停用'}`">
      <pre>{{ skill.instructions }}</pre><el-button @click="toggle(skill)">{{ skill.enabled ? '停用策略' : '启用策略' }}</el-button>
    </el-collapse-item></el-collapse>
    <h3>找书运行记录</h3>
    <el-table :data="runs" max-height="320">
      <el-table-column prop="request" label="用户 / 请求 ID" min-width="250" show-overflow-tooltip />
      <el-table-column label="执行框架" width="130"><template #default="{ row }">{{ row.record.engine === 'langchain4j' ? 'LangChain4j' : row.record.engine === 'native' ? '原有实现' : '未记录' }}</template></el-table-column>
      <el-table-column label="结果" width="90"><template #default="{ row }">{{ row.record.status === 'done' ? '完成' : '失败' }}</template></el-table-column>
      <el-table-column label="耗时" width="100"><template #default="{ row }">{{ row.record.usage?.durationMs != null ? `${row.record.usage.durationMs} ms` : '—' }}</template></el-table-column>
      <el-table-column label="模型 / 工具次数" width="150"><template #default="{ row }">{{ row.record.usage?.modelCalls ?? row.record.modelCalls }} / {{ row.record.usage?.toolCalls ?? row.record.toolCalls }}</template></el-table-column>
      <el-table-column label="报告 Token" width="120"><template #default="{ row }">{{ row.record.usage?.totalTokens ?? row.record.tokens }}</template></el-table-column>
      <el-table-column label="工具轨迹" min-width="220"><template #default="{ row }"><el-popover trigger="click" width="500"><pre>{{ JSON.stringify(row.record.tools, null, 2) }}</pre><template #reference><el-button link>查看调用与检索模式</el-button></template></el-popover></template></el-table-column>
    </el-table>
    </template>
    <el-dialog v-model="dialog" :title="props.mode === 'summaries' ? '开篇资料与待审摘要' : '正文资料预览'" width="800px">
      <template v-if="preview">
        <p>《{{ preview.book.title }}》：覆盖前 {{ preview.coveredChapters }} / {{ preview.totalChapters }} 章。正文摘录仅在管理员端展示。</p>
        <el-select v-model="draft.chunkId" placeholder="选择出处片段" style="width: 100%"><el-option v-for="chunk in preview.chunks" :key="chunk.id" :value="chunk.id" :label="`第 ${chunk.chapterNumber} 章 · 字符 ${chunk.start}～${chunk.end}`" /></el-select>
        <pre class="source-preview">{{ preview.chunks.find(c => c.id === draft.chunkId)?.text }}</pre>
        <el-input v-if="props.mode === 'summaries'" v-model="draft.summary" type="textarea" :rows="4" maxlength="400" show-word-limit placeholder="仅概括可证实的开篇题材或氛围" />
        <el-input v-if="props.mode === 'summaries'" v-model="draft.quote" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="从选中片段复制 1～200 字逐字出处" style="margin-top: 12px" />
      </template>
      <template #footer><el-button @click="dialog = false">关闭</el-button><el-button v-if="props.mode === 'summaries'" type="primary" :loading="savingNote" @click="saveNote">保存待审草稿</el-button></template>
    </el-dialog>
  </section>
</template>
<style scoped>
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 15px 0; }
.toolbar .el-input { width: 240px; } .knowledge-panel p { color: #606266; line-height: 1.7; }
h3 { margin-top: 24px; } pre { white-space: pre-wrap; font-family: inherit; line-height: 1.8; } .el-pagination { margin-top: 14px; }
.source-preview { max-height: 260px; overflow: auto; padding: 12px; background: #f5f7fa; }
</style>
