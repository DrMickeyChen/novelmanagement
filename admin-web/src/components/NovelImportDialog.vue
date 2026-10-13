<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { previewTxt, confirmTxt, getTxtTask, discardTxt } from '@/api/novelImport'

const props = defineProps({ modelValue: Boolean, categories: { type: Array, default: () => [] } })
const emit = defineEmits(['update:modelValue', 'imported'])
const visible = computed({ get: () => props.modelValue, set: v => emit('update:modelValue', v) })
const file = ref(null), encoding = ref('AUTO'), mode = ref('AUTO'), busy = ref(false), task = ref(null)
const form = ref({ title: '', originalAuthor: '', categoryId: null, intro: '', tags: '', cover: '', isFinish: 1 })
const processing = computed(() => ['QUEUED', 'IMPORTING'].includes(task.value?.status))
const progress = computed(() => task.value?.totalChapters ? Math.floor(task.value.importedChapters / task.value.totalChapters * 100) : 0)
const chapterPage = ref(1)
const chapterRows = computed(() => task.value?.preview?.chapters?.slice((chapterPage.value - 1) * 20, chapterPage.value * 20) || [])
const statusText = { PREVIEW: '解析完成，请确认书籍信息与目录', QUEUED: '等待导入', IMPORTING: '正在导入，完成后统一上架', COMPLETED: '导入成功，作品已上架', FAILED: '导入失败，作品未公开', CANCELLED: '已取消', EXPIRED: '预览已过期，请重新解析' }
const tagOptions = ['悬疑', '灵异', '热血', '成长', '冒险', '日常', '慢热', '轻松', '生存']
const selectedTags = computed({ get: () => form.value.tags.split(',').map(t => t.trim()).filter(Boolean), set: v => { form.value.tags = v.join(',') } })
let timer = null
const storageKey = 'novel-admin-txt-import-task'
function stopPolling() { if (timer) clearTimeout(timer); timer = null }
async function poll() {
  stopPolling()
  if (!processing.value) return
  try {
    task.value = await getTxtTask(task.value.taskId)
    if (task.value.status === 'COMPLETED') {
      localStorage.removeItem(storageKey)
      ElMessage.success(`导入成功：${task.value.totalChapters} 章`)
      emit('imported')
    } else if (task.value.status === 'FAILED') localStorage.removeItem(storageKey)
    else timer = setTimeout(poll, 1500)
  } catch { /* retain task id for manual reconnect */ }
}
async function reconnect() {
  const id = task.value?.taskId || localStorage.getItem(storageKey)
  if (!id) return
  try { task.value = await getTxtTask(id); await poll() } catch { localStorage.removeItem(storageKey) }
}
watch(() => props.modelValue, v => { if (v) reconnect(); else stopPolling() })
watch([encoding, mode], async () => {
  if (task.value?.status === 'PREVIEW') {
    await discardTxt(task.value.taskId).catch(() => {})
    task.value = null
  }
})
function pick(event) {
  file.value = event.target.files?.[0] || null
  if (task.value?.status === 'PREVIEW') discardTxt(task.value.taskId).catch(() => {})
  task.value = null
}
async function parse() {
  if (!file.value) return ElMessage.warning('请选择 TXT 文件')
  if (file.value.size > 50 * 1024 * 1024) return ElMessage.warning('文件不能超过 50MB')
  busy.value = true
  try {
    if (task.value?.status === 'PREVIEW') await discardTxt(task.value.taskId)
    task.value = await previewTxt(file.value, encoding.value, mode.value)
    const p = task.value.preview
    const category = props.categories.find(c => c.status === 1 && c.name === p.category)
    form.value = { title: p.title || '', originalAuthor: p.originalAuthor || '', categoryId: category?.id ?? null,
      intro: p.intro || '', tags: (p.tags || '').replace(/[，、；;]/g, ','), cover: '', isFinish: p.isFinish ?? 1 }
    chapterPage.value = 1
  } finally { busy.value = false }
}
async function submit() {
  if (!form.value.title.trim() || !form.value.originalAuthor.trim() || !form.value.categoryId) return ElMessage.warning('请填写书名、原作者，并选择分类')
  if (form.value.title.length > 100 || form.value.originalAuthor.length > 100 || form.value.intro.length > 1000 || form.value.tags.length > 200) return ElMessage.warning('书名/作者最多100字，简介最多1000字，标签最多200字，请修改后导入')
  busy.value = true
  try {
    task.value = await confirmTxt(task.value.taskId, form.value)
    localStorage.setItem(storageKey, task.value.taskId)
    await poll()
  } finally { busy.value = false }
}
onBeforeUnmount(stopPolling)
</script>

<template>
  <el-dialog v-model="visible" title="导入 TXT 小说" width="900px" top="4vh" :close-on-click-modal="false">
    <el-alert title="导入完成后直接上架，来源为外部导入。先检查书籍资料、章节目录及正文预览。" type="info" :closable="false" />
    <div class="upload-row">
      <input type="file" accept=".txt,text/plain" :disabled="busy || processing" @change="pick" />
      <el-select v-model="encoding" :disabled="busy || processing" style="width:150px">
        <el-option label="自动识别编码" value="AUTO" />
        <el-option v-for="e in ['UTF-8','GB18030','GBK','UTF-16LE','UTF-16BE']" :key="e" :label="e" :value="e" />
      </el-select>
      <el-select v-model="mode" :disabled="busy || processing" style="width:190px">
        <el-option label="自动：第N章 / Chapter" value="AUTO" />
        <el-option label="中文：第N章 / 回 / 节" value="CHINESE" />
        <el-option label="数字：1.标题 / 一、标题" value="NUMBERED" />
        <el-option label="英文：Chapter 1" value="ENGLISH" />
        <el-option label="无章节：整篇正文" value="SINGLE" />
      </el-select>
      <el-button :loading="busy" :disabled="processing" @click="parse">解析预览</el-button>
    </div>
    <div v-if="task">
      <p>{{ statusText[task.status] }} · 编码 {{ task.encoding }} · {{ task.totalChapters }} 章 · {{ Number(task.preview.wordCount).toLocaleString() }} 字</p>
      <template v-if="task.status === 'PREVIEW'">
        <el-alert v-for="warning in task.preview.warnings" :key="warning" :title="warning" type="warning" :closable="false" class="warning" />
        <el-form label-width="90px" class="metadata">
          <el-form-item label="书名" required><el-input v-model="form.title" /></el-form-item>
          <el-form-item label="原作者" required><el-input v-model="form.originalAuthor" /></el-form-item>
          <el-form-item label="分类" required>
            <el-select v-model="form.categoryId" placeholder="请选择分类">
              <el-option v-for="c in categories.filter(c => c.status === 1)" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
            <span v-if="task.preview.category" class="hint">TXT 分类：{{ task.preview.category }}</span>
          </el-form-item>
          <el-form-item label="标签"><el-select v-model="selectedTags" multiple filterable allow-create default-first-option placeholder="可选已有标签或输入自定义标签" style="width:100%"><el-option v-for="tag in tagOptions" :key="tag" :label="tag" :value="tag" /></el-select></el-form-item>
          <el-form-item label="简介"><el-input v-model="form.intro" type="textarea" :rows="4" /></el-form-item>
          <el-form-item label="封面地址"><el-input v-model="form.cover" placeholder="可留空，使用默认封面" /></el-form-item>
          <el-form-item label="完结情况"><el-radio-group v-model="form.isFinish"><el-radio :value="1">已完结</el-radio><el-radio :value="0">连载中</el-radio></el-radio-group><span v-if="task.preview.isFinish == null" class="hint">未识别，请确认</span></el-form-item>
        </el-form>
        <el-table :data="chapterRows" height="260" border>
          <el-table-column prop="number" label="顺序" width="75" />
          <el-table-column prop="title" label="章节标题" />
          <el-table-column prop="wordCount" label="字数" width="100" />
        </el-table>
        <el-pagination v-model:current-page="chapterPage" :page-size="20" :total="task.totalChapters" layout="prev,pager,next,total" class="pagination" />
        <p>首章正文预览</p><pre class="sample">{{ task.preview.sample }}</pre>
      </template>
      <template v-else>
        <el-progress :percentage="progress" :status="task.status === 'FAILED' ? 'exception' : task.status === 'COMPLETED' ? 'success' : undefined" />
        <p>已写入 {{ task.importedChapters }} / {{ task.totalChapters }} 章</p>
        <el-alert v-if="task.errorMessage" :title="task.errorMessage" type="error" :closable="false" />
        <p v-if="task.status === 'COMPLETED'">作品 ID：{{ task.novelId }}。读者已可阅读，搜索结果等待索引同步。</p>
        <el-button v-if="processing" @click="reconnect">刷新进度</el-button>
      </template>
    </div>
    <template #footer>
      <el-button @click="visible = false">{{ processing ? '关闭（后台继续导入）' : '关闭' }}</el-button>
      <el-button v-if="task?.status === 'PREVIEW'" type="primary" :loading="busy" :disabled="!task.totalChapters" @click="submit">确认导入并上架</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.upload-row { display:flex; gap:12px; flex-wrap:wrap; align-items:center; margin:18px 0; }
.upload-row input { max-width:240px; }
.metadata { margin-top:18px; }
.warning { margin:6px 0; }
.sample { white-space:pre-wrap; max-height:220px; overflow:auto; padding:12px; background:#f5f7fa; line-height:1.8; }
.pagination { margin-top:12px; }
.hint { color:#909399; margin-left:12px; }
</style>
