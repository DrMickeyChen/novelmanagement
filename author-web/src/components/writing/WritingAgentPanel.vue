<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as api from '@/api/writing'
import * as resources from '@/api/story-library'
import * as extensions from '@/api/writing-extensions'
import WritingDraftVersions from './WritingDraftVersions.vue'
import WritingAssistantSettings from './WritingAssistantSettings.vue'
import WritingToolPicker from './WritingToolPicker.vue'
import { useRouter } from 'vue-router'

const props = defineProps({ node: Object, novelId: Number, prepare: Function, apply: Function })
const router = useRouter()
const resourcesOpen=ref(false), resourceLoading=ref(false), resourceError=ref(''), chapterOptions=ref([]), libraryOptions=ref([])
const chapterIds=ref([]), settingIds=ref([]), contextPreview=ref(null), indexBusy=ref(false), withVectors=ref(false), resourceNotice=ref('')
const styleIds=ref([]),styleOptions=ref([]),includeCumulative=ref(true),mcpIds=ref([]),allowExternalQuery=ref(false)
const mcpToolSelections=ref([])
const referenceSnapshot=computed(()=>task.value?.snapshot || contextPreview.value)
const resourceSelectionCount=computed(()=>chapterIds.value.length+settingIds.value.length)
const levelNames={BOOK:'全书剧情笔记',VOLUME:'当前卷剧情笔记',CHAPTER:'当前章节剧情笔记'}
async function loadResources(){resourceLoading.value=true;resourceError.value='';try{[chapterOptions.value,libraryOptions.value]=await Promise.all([resources.getWritingChapters(props.node.chapterId,props.node.id),resources.getLibrary(props.novelId)])}catch(e){resourceError.value=e.message||'资料读取失败'}finally{resourceLoading.value=false}}
async function previewResources(){await run(async()=>{await props.prepare(async()=>{contextPreview.value=await resources.previewContext(props.node.chapterId,{nodeId:props.node.id,chapterIds:chapterIds.value.map(Number),settingIds:settingIds.value,goal:goal.value})})})}
let indexTimer
const indexJobKey=()=>`writing-private-index-job:${props.node.chapterId}`
async function pollIndex(id){
  if(disposed)return
  try{const job=await resources.getIndexJob(id);if(disposed)return;if(job.state==='DONE'){const result=typeof job.result==='string'?JSON.parse(job.result):job.result;resourceNotice.value=`已构建 ${result.chunks} 个片段；${result.notice}`;indexBusy.value=false;return}if(job.state==='FAILED'){resourceNotice.value=job.error;indexBusy.value=false;return}resourceNotice.value=job.state==='QUEUED'?'构建已排队，可继续写作。':'正在构建私有检索资料，可继续写作。';indexTimer=setTimeout(()=>pollIndex(id),2000)}catch(e){indexBusy.value=false;resourceNotice.value=e.message||'进度读取失败，可刷新查询'}
}
async function buildIndex(){indexBusy.value=true;resourceNotice.value='';try{const ids=chapterIds.value.length?chapterIds.value:chapterOptions.value.slice(0,3).map(c=>c.id);const job=await resources.buildPrivateIndex(props.node.chapterId,{nodeId:props.node.id,chapterIds:ids.map(Number),vectors:withVectors.value});try{localStorage.setItem(indexJobKey(),job.id)}catch{}pollIndex(job.id)}catch(e){resourceNotice.value=e.message||'构建失败，可重试';indexBusy.value=false}}
async function clearIndex(){try{await ElMessageBox.confirm('仅移除本作品的私有检索资料，原文和故事资料库会保留。','清空检索资料',{confirmButtonText:'清空',cancelButtonText:'取消'})}catch{return}try{await resources.clearPrivateIndex(props.node.chapterId,props.node.id);resourceNotice.value='已清空私有检索资料';localStorage.removeItem(indexJobKey())}catch(e){resourceNotice.value=e.message||'清空失败，请重试'}}
function openLibrary(){resourcesOpen.value=false;router.push(`/novel/${props.novelId}/story-library`)}
function openExtensions(){resourcesOpen.value=false;router.push(`/novel/${props.novelId}/writing-lab`)}
async function loadExtensions(){const results=await Promise.allSettled([extensions.getSkills(props.novelId),extensions.getAssets(props.novelId)]);if(results[0].status==='fulfilled')availableSkills.value=results[0].value;if(results[1].status==='fulfilled')styleOptions.value=results[1].value.filter(r=>r.kind==='STYLE'&&r.state==='APPROVED');if(results.some(r=>r.status==='rejected'))resourceError.value='部分写作扩展读取失败，请在写作扩展页面刷新检查'}
function receiveBranch(result){receive(result);if(active.value)follow(result.id);refreshHistory()}
function chooseTools(selections, allow){mcpToolSelections.value=selections;mcpIds.value=selections.map(row=>row.connectionId);allowExternalQuery.value=allow}
defineEmits(['close'])
const settingsOpen = ref(false), planEditing = ref(false)
const assistantSettingsOpen = ref(false)
const historyOpen = ref(false), historyLoading = ref(false), historyError = ref(''), backgroundNotice = ref('')
const canCompose = computed(() => !task.value || task.value.status === 'READY')
const canSend = computed(() => enabled.value && !busy.value && !active.value && canCompose.value && (task.value?.status === 'READY' ? revision.value.trim() : goal.value.trim()))
const composerText = computed({ get: () => task.value?.status === 'READY' ? revision.value : goal.value, set: value => { if (task.value?.status === 'READY') revision.value = value; else goal.value = value } })
const composerHint = computed(() => active.value ? '助手正在处理，可以先在正文区继续写作' : task.value?.status === 'AWAITING_CONFIRM' ? '先确认情节计划，或点击“调整计划”' : task.value?.status === 'READY' ? '描述修改要求，例如保留开头，结尾增加悬念' : task.value ? '可查看已有结果；开始新的创作请点击“新会话”' : '这章想写什么？描述主要事件、人物变化或结尾安排')
const send = () => task.value?.status === 'READY' ? revise() : create()
const enabled = ref(false), busy = ref(false), task = ref(null), history = ref([])
const goal = ref(''), constraints = ref(''), style = ref(''), genre = ref(''), perspective = ref('第三人称')
const quotes = ref('“……”'), skillIds = ref([]), revision = ref(''), notice = ref('')
const skillMenuOpen = ref(false)
const availableSkills = ref([])
const selectedSkills = computed(() => availableSkills.value.filter(skill => skillIds.value.includes(skill.id)))
const planSummary = ref(''), planEvents = ref(''), planEnding = ref('')
const draftRef = ref(null)
const targetOriginal = computed(() => task.value?.input?.selection?.original || '')
let controller, retryTimer, disposed = false
const active = computed(() => ['QUEUED_PLAN','QUEUED_DRAFT','PLANNING','GENERATING','CHECKING','ADOPTING','UNDOING'].includes(task.value?.status))
const statusLabels = { QUEUED_PLAN:'等待规划', PLANNING:'读取前文并规划', AWAITING_CONFIRM:'请确认计划', QUEUED_DRAFT:'等待生成', GENERATING:'生成草稿', CHECKING:'核对前文', READY:'草稿待采纳', FAILED:'执行失败，可重试', CANCELLED:'已取消', INPUT_STALE:'资料已更新，请新建任务', ADOPTING:'正在确认采纳结果', ADOPTED:'已采纳', UNDOING:'正在撤销', UNDONE:'已撤销', EXPIRED:'任务已过期' }
const statusText = computed(() => statusLabels[task.value?.status] ?? '')
const draft = computed(() => task.value?.status === 'GENERATING' ? task.value.partialDraft : task.value?.draft || task.value?.partialDraft || '')
const profileKey = () => `writing-profile:${props.node?.novelId ?? props.node?.chapterId}`
let lastPlanHash = ''
function receive(value) {
  if (task.value?.id === value.id && value.throughSeq < task.value.throughSeq) return
  if (task.value?.id !== value.id) { lastPlanHash = ''; revision.value = ''; planEditing.value = false; skillIds.value = [...(value.input?.skillIds || [])];chapterIds.value=[...(value.input?.chapterIds||[])];settingIds.value=[...(value.input?.settingIds||[])] }
  if(task.value?.id!==value.id){styleIds.value=[...(value.input?.styleIds||[])];includeCumulative.value=value.input?.includeCumulative!==false}
  if(task.value?.id!==value.id || task.value?.toolsVersion!==value.toolsVersion){chooseTools(value.input?.mcpToolSelections || (value.input?.mcpSnapshots||[]).map(row=>({connectionId:row.id,tools:row.tools})),!!value.input?.allowExternalQuery)}
  task.value = value
  if (value.planHash && value.planHash !== lastPlanHash) {
    planSummary.value = value.plan.summary
    planEvents.value = value.plan.events.join('\n')
    planEnding.value = value.plan.ending
    lastPlanHash = value.planHash
    planEditing.value = false
  }
}
async function refreshHistory() {
  if (!enabled.value || !props.node?.chapterId) return
  historyLoading.value = true; historyError.value = ''
  try { history.value = await api.recentTasks(props.node.chapterId, props.node.id) }
  catch (e) { historyError.value = e.message || '历史记录暂时无法读取' }
  finally { historyLoading.value = false }
}
async function follow(id) {
  controller?.abort(); clearTimeout(retryTimer)
  controller = new AbortController()
  const ctl = controller
  const accept = value => { if (!disposed && !ctl.signal.aborted && task.value?.id === id) receive(value) }
  try { await api.subscribe(id, ctl.signal, accept) }
  catch (e) { if (!disposed && !ctl.signal.aborted && task.value?.id === id) notice.value = e.message }
  if (disposed || ctl.signal.aborted) return
  try { accept(await api.getTask(id)) } catch { return }
  if (disposed || ctl.signal.aborted || task.value?.id !== id) return
  if (active.value) retryTimer = setTimeout(() => follow(id), 1500)
}
async function run(action) {
  if (busy.value) return
  busy.value = true; notice.value = ''
  try { await action() } catch (e) { if (e !== 'cancel' && e !== 'close') notice.value = e.message || '操作失败，已保留正文和草稿' }
  finally { busy.value = false }
}
async function create() {
  await run(async () => {
    const profile = { genre: genre.value, perspective: perspective.value, style: style.value, dialogueQuotes: quotes.value }
    localStorage.setItem(profileKey(), JSON.stringify({ ...profile, skillIds: skillIds.value }))
    await props.prepare(async base => {
      const result = await api.newTask({ ...base, nodeId: String(props.node.id), goal: goal.value, constraints: constraints.value.split('\n').filter(Boolean), profile, skillIds: skillIds.value,chapterIds:chapterIds.value,settingIds:settingIds.value,styleIds:styleIds.value,includeCumulative:includeCumulative.value,mcpConnectionIds:mcpIds.value,mcpToolSelections:mcpToolSelections.value,allowExternalQuery:allowExternalQuery.value })
      receive(result); goal.value = ''; follow(result.id); await refreshHistory()
    })
  })
}
async function execute(operation, extra = {}) {
  const result = await api.command(task.value.id, operation, { expectedTaskVersion: task.value.version, ...extra }, crypto.randomUUID())
  receive(result)
  if (active.value) follow(result.id)
  return result
}
const confirm = () => run(async () => execute('confirm', { planHash: task.value.planHash, skillIds: [...skillIds.value], editedPlan: { summary: planSummary.value, events: planEvents.value.split('\n').filter(line => line.trim()), ending: planEnding.value, needsInput: false } }))
const revise = () => run(async () => { await execute('revise', { draftAttemptId: task.value.currentDraftAttemptId, instruction: revision.value, skillIds: [...skillIds.value] }); revision.value = '' })
const retry = () => run(async () => execute('retry', { failedAttemptId: task.value.activeAttemptId, skillIds: [...skillIds.value] }))
const cancel = () => run(async () => execute('cancel'))
async function adopt(all) {
  const el = draftRef.value?.textarea
  const start = all ? 0 : el?.selectionStart, end = all ? draft.value.length : el?.selectionEnd
  if (start == null || start === end) { ElMessage.warning('请在草稿框中选中要采纳的文字'); return }
  await run(async () => {
    const unchecked = task.value.check?.status !== 'OK'
    await ElMessageBox.confirm(`将把${all ? '整份草稿' : '所选草稿'}写入创建任务时的正文选区（空选区为插入）。正文有更新时会检查版本冲突。${unchecked ? '本次检查未完全通过，请人工核对。' : ''}`, '确认采纳', { type: 'warning', confirmButtonText: '确认采纳', cancelButtonText: '继续检查' })
    await props.prepare(async base => {
      const result = await execute('adopt', { expectedChapterVersion: base.expectedChapterVersion, draftAttemptId: task.value.currentDraftAttemptId, draftStart: start, draftEnd: end, acceptUnchecked: unchecked })
      if (result.status === 'ADOPTED') props.apply(result.applied)
      else notice.value = '采纳结果正在对账，请稍后刷新任务和章节，勿重复覆盖正文'
    })
  })
}
async function undo() {
  await run(async () => {
    await props.prepare(async base => {
      const result = await execute('undo', { expectedChapterVersion: base.expectedChapterVersion, adoptionId: task.value.applied.adoptionId })
      if (result.status === 'UNDONE') props.apply(result.applied)
      else notice.value = '撤销结果正在确认，请稍后刷新'
    })
  })
}
function stopFollowing() { controller?.abort(); clearTimeout(retryTimer) }
function newSession() {
  if (busy.value) return
  backgroundNotice.value = active.value ? '上一会话仍在后台处理，可从历史记录返回。' : ''
  stopFollowing(); task.value = null; contextPreview.value = null; lastPlanHash = ''; goal.value = ''; revision.value = ''; notice.value = ''
  planSummary.value = ''; planEvents.value = ''; planEnding.value = ''; planEditing.value = false
  historyOpen.value = false; skillMenuOpen.value = false; settingsOpen.value = false
}
async function reopen(id) {
  await run(async () => {
    const restored = await api.getTask(id)
    stopFollowing(); lastPlanHash = ''; goal.value = ''; revision.value = ''; backgroundNotice.value = ''
    receive(restored); historyOpen.value = false
    if(active.value) follow(id)
  })
}
function historyTime(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', hour12:false })
}
async function exportReport() {
  await run(async () => {
    const report = await api.getReport(task.value.id)
    const url = URL.createObjectURL(new Blob([JSON.stringify(report, null, 2)], { type: 'application/json' }))
    const link = document.createElement('a');link.href=url;link.download=`writing-${task.value.id}.json`;link.click();URL.revokeObjectURL(url)
  })
}
onMounted(async () => {
  try {
    const capabilities = await api.capabilities()
    enabled.value = capabilities.enabled
    availableSkills.value = capabilities.skills || []
    await loadExtensions()
    const saved = JSON.parse(localStorage.getItem(profileKey()) || 'null')
    if (saved) { genre.value=saved.genre||'';style.value=saved.style||'';perspective.value=saved.perspective||'第三人称';quotes.value=saved.dialogueQuotes||'“……”';skillIds.value=(saved.skillIds || []).filter(id => availableSkills.value.some(skill => skill.id===id)) }
    await refreshHistory()
    const indexJob=localStorage.getItem(indexJobKey())
    if(enabled.value && indexJob){indexBusy.value=true;pollIndex(indexJob)}
  } catch (e) { notice.value=e.message }
})
onBeforeUnmount(() => {disposed=true;controller?.abort();clearTimeout(retryTimer);clearTimeout(indexTimer)})
</script>

<template>
  <section class="writing-agent">
    <header class="agent-head">
      <div class="agent-heading"><strong>写作助手</strong><div class="subtitle" :title="`${node?.title} · ${statusText || '新会话'}`">{{ node?.title }} · {{ statusText || '新会话' }}</div></div>
      <div class="header-actions">
        <el-button link :disabled="busy || !enabled" @click="assistantSettingsOpen = true">助手设置</el-button>
        <el-button link :disabled="busy || !enabled" @click="newSession">新会话</el-button>
        <el-popover v-model:visible="historyOpen" trigger="click" placement="bottom-end" :width="320" @show="refreshHistory">
          <template #reference><el-button link :disabled="busy || !enabled" :aria-expanded="historyOpen">历史</el-button></template>
          <div class="session-history">
            <div class="history-head"><strong>本章会话</strong><el-button link :loading="historyLoading" @click="refreshHistory">刷新</el-button></div>
            <p class="history-hint">最近 30 天的 10 条记录，点击查看或继续。</p>
            <div v-if="historyError" class="history-error">{{ historyError }}<el-button link @click="refreshHistory">重试</el-button></div>
            <div v-if="historyLoading && !history.length" class="history-empty">正在读取…</div>
            <div v-else-if="!history.length && !historyError" class="history-empty">本章还没有会话，从“新会话”开始创作。</div>
            <div class="history-list">
              <button v-for="item in history" :key="item.id" type="button" class="history-item" :class="{ selected: item.id === task?.id }" :disabled="busy" @click="reopen(item.id)">
                <span class="history-title">{{ item.goal || '本章创作' }}</span>
                <span class="history-meta"><span>{{ statusLabels[item.status] || item.status }}</span><time>{{ historyTime(item.createdAt) }}</time></span>
              </button>
            </div>
          </div>
        </el-popover>
        <el-button link aria-label="收起写作助手" title="收起助手" @click="$emit('close')">×</el-button>
      </div>
    </header>
    <div class="agent-feed">
      <el-alert v-if="!enabled" title="写作助手暂不可用，请检查服务配置" type="info" :closable="false" />
      <template v-else>
        <div v-if="!task" class="welcome">
          <strong>一起构思这一章</strong>
          <p>告诉我你想写的事件和人物变化，我会先整理计划，确认后再写草稿。</p>
          <p class="muted">参考本章、所在卷和全书剧情笔记，以及前三章正文。可在“资料”中额外选择更早章节和设定。</p>
          <p v-if="backgroundNotice" class="muted">{{ backgroundNotice }}</p>
        </div>
        <el-alert v-if="!node?.content?.trim() && !task" title="请填写并保存当前章节剧情笔记。" type="info" :closable="false" />
        <el-alert v-if="notice || task?.error" :title="notice || task.error" type="warning" :closable="false" />
        <details v-if="referenceSnapshot" class="card">
          <summary>本次参考资料</summary>
          <div v-for="note in referenceSnapshot.notes" :key="note.id"><b>{{ levelNames[note.level] || note.level }} · {{ note.title }}</b><p class="muted">{{ note.text ? '已读取；仅作为创作计划' : '剧情笔记为空，未提供计划' }}</p></div>
          <p v-if="!referenceSnapshot.notes?.some(n=>n.level==='VOLUME')" class="muted">未找到所属卷剧情笔记</p>
          <p v-if="!referenceSnapshot.notes?.some(n=>n.level==='BOOK')" class="muted">未找到全书剧情笔记</p>
          <p v-for="source in referenceSnapshot.coverage" :key="source.chapterId">第 {{ source.chapterNo }} 章 · {{ source.title }}：{{ source.readChars }}/{{ source.totalChars }} 字（{{ source.reason }}）</p>
          <div v-for="setting in referenceSnapshot.settings" :key="setting.id" class="reference-setting"><b>{{ setting.name }}</b> · {{ setting.reason }}<span class="muted"> · {{ setting.meaning==='PLAN'?'创作计划':setting.meaning==='FACT'?'正文事实':'作者设定' }}</span></div>
          <p v-if="!referenceSnapshot.settings?.length" class="muted">未激活故事资料条目</p>
          <p v-if="task?.retrievalMode" class="muted">更早正文检索：{{ task.retrievalMode }} · {{ task.retrievalNotice }}</p>
          <details v-for="hit in task?.researchSources || []" :key="hit.id"><summary>检索依据 · 第 {{ hit.chapterNo }} 章 {{ hit.title }} · 版本 {{ hit.version }}</summary><blockquote>{{ hit.text }}</blockquote></details>
        </details>
        <template v-if="task">
          <div class="user-message">{{ task.input?.goal || '已恢复本章任务' }}</div>
          <div v-if="active" class="progress" role="status">{{ statusText }}…</div>
          <el-tag v-if="task.mock" type="warning" size="small">演示模式</el-tag>
          <details v-if="task.recap" class="card"><summary>前文回顾</summary><pre>{{ task.recap.summary }}</pre></details>
          <details v-if="task.extras?.memory" class="card"><summary>累计回顾 · 截至第 {{ task.extras.memory.throughChapterNo }} 章</summary><p>模型回顾尚未核实，具体事实仍需正文证据。</p><pre>{{ task.extras.memory.recap.summary }}</pre><details><summary>实际来源版本</summary><pre>{{ task.extras.memory.sources }}</pre></details></details>
          <p v-if="task.extras?.memoryNotice" class="muted">{{ task.extras.memoryNotice }}</p>
          <div v-if="task.planHash" class="card">
            <h4>情节计划</h4>
            <template v-if="task.status === 'AWAITING_CONFIRM' && planEditing">
              <el-input v-model="planSummary" type="textarea" :rows="2" placeholder="本章安排" />
              <el-input v-model="planEvents" type="textarea" :rows="4" placeholder="主要事件，每行一个" />
              <el-input v-model="planEnding" type="textarea" :rows="2" placeholder="结尾安排" />
            </template>
            <template v-else>
              <p>{{ planSummary }}</p>
              <ol><li v-for="(event, i) in planEvents.split('\n').filter(Boolean)" :key="i">{{ event }}</li></ol>
              <p v-if="planEnding"><b>结尾：</b>{{ planEnding }}</p>
            </template>
            <div v-if="task.status === 'AWAITING_CONFIRM'" class="actions">
              <el-button type="primary" :loading="busy" @click="confirm">确认并生成</el-button>
              <el-button :disabled="busy" @click="planEditing = !planEditing">{{ planEditing ? '预览计划' : '调整计划' }}</el-button>
            </div>
          </div>
          <div v-if="draft" class="card">
            <h4>候选草稿</h4>
            <el-input ref="draftRef" :model-value="draft" type="textarea" :rows="12" readonly resize="none" aria-label="候选草稿，可选中文字采纳" />
            <details v-if="task.status === 'READY'" class="change-preview">
              <summary>预览修改范围</summary>
              <p>{{ targetOriginal ? '替换创建任务时选中的正文' : '插入创建任务时的光标位置' }}；采纳前检查正文版本。</p>
              <pre v-if="targetOriginal" class="original">{{ targetOriginal }}</pre>
              <p>新内容为上方草稿；采纳选区时只使用选中的草稿文字。</p>
            </details>
            <div v-if="task.status === 'READY'" class="actions">
              <el-button type="primary" :loading="busy" @click="adopt(true)">采纳草稿</el-button>
              <el-button :loading="busy" @click="adopt(false)">仅采纳选中部分</el-button>
              <WritingDraftVersions :task="task" :prepare="prepare" :apply="apply" @task="receiveBranch" />
            </div>
          </div>
          <details v-if="task.check" class="card"><summary>检查结果 · {{ task.check.status === 'OK' ? '检查通过' : '需要人工核对' }}</summary><p>{{ task.check.coverage }}</p><div v-for="(issue,i) in task.check.issues" :key="i"><b>{{ issue.assessment }}</b> {{ issue.message }}<blockquote v-if="issue.quote">{{ issue.sourceId }}：{{ issue.quote }}</blockquote></div></details>
          <el-button v-if="task.status === 'ADOPTED'" :loading="busy" @click="undo">撤销这次采纳</el-button>
          <el-button v-if="task.status === 'FAILED'" type="primary" :loading="busy" @click="retry">重试</el-button>
          <WritingDraftVersions v-if="task.status==='FAILED'&&task.draft" :task="task" :prepare="prepare" :apply="apply" @task="receiveBranch" />
        </template>
        <details class="run-details">
          <summary>运行详情</summary>
          <template v-if="task">
            <p>{{ task.modelCalls }} 次模型调用 · 用量 {{ task.usageUnknown ? '部分未知' : task.usedTokens }} · 预算预留 {{ task.reservedTokens }}</p>
            <p v-if="task.budget" class="muted">本任务最多 {{ task.budget.maxModelCalls }} 次模型调用、{{ task.budget.maxToolCalls }} 次工具调用；硬预算 {{ task.budget.hardTokens }} Token。向量及重排接口用量另计。</p>
            <p>阅读范围：<span v-for="source in task.snapshot.sources" :key="source.id">第 {{ source.chapterNo }} 章{{ source.truncated ? '（末尾片段）' : '' }}；</span><span v-if="!task.snapshot.sources.length">没有前文</span></p>
            <div class="actions"><el-button size="small" :loading="busy" @click="exportReport">导出记录</el-button><el-button v-if="!['ADOPTING','UNDOING','ADOPTED','UNDONE','CANCELLED'].includes(task.status)" size="small" :loading="busy" @click="cancel">取消任务</el-button></div>
          </template>
          <p v-else class="muted">发送创作目标后可查看本次运行记录。</p>
        </details>
      </template>
    </div>
    <footer v-if="enabled" class="composer">
      <el-button link :aria-expanded="settingsOpen" @click="settingsOpen = !settingsOpen">{{ settingsOpen ? '收起写作设置' : '写作设置' }}</el-button>
      <div v-show="settingsOpen" class="settings">
        <p class="muted">设置用于下一次新任务，保存在当前浏览器。</p>
        <el-input v-model="constraints" type="textarea" :rows="2" placeholder="约束，每行一条，例如不要揭露身份" />
        <el-input v-model="genre" placeholder="类型，例如修仙 / 悬疑" maxlength="100" />
        <div class="setting-row"><el-select v-model="perspective" aria-label="叙述视角"><el-option label="第三人称" value="第三人称" /><el-option label="第一人称" value="第一人称" /></el-select><el-select v-model="quotes" aria-label="对话格式"><el-option label="对话：“……”" value="“……”" /><el-option label="对话：「……」" value="「……」" /></el-select></div>
        <el-input v-model="style" type="textarea" :rows="2" placeholder="本次补充文风要求（长期偏好可在上方“助手设置”保存）" maxlength="600" />
      </div>
      <el-input v-model="composerText" type="textarea" :rows="3" maxlength="1200" resize="none" :disabled="active || busy || !canCompose" :placeholder="composerHint" aria-label="写作目标或草稿修改要求" />
      <div class="composer-foot">
        <div class="composer-tools"><el-button size="small" :disabled="busy" @click="resourcesOpen=true;loadResources();loadExtensions()">资料{{ resourceSelectionCount ? ` · ${resourceSelectionCount}` : '' }}</el-button>
        <el-popover v-model:visible="skillMenuOpen" trigger="click" placement="top-start" :width="290" @show="loadExtensions">
          <template #reference><el-button size="small" :disabled="busy || active" :aria-expanded="skillMenuOpen" aria-label="选择写作 Skill">Skill{{ skillIds.length ? ` · ${skillIds.length}` : '' }}</el-button></template>
          <div class="skill-picker">
            <strong>选择写作 Skill</strong><p>可多选，作用于下次生成或修改。</p>
            <el-checkbox-group v-model="skillIds" :disabled="busy || active" :max="2">
              <el-checkbox v-for="skill in availableSkills" :key="skill.id" :value="skill.id"><span>{{ skill.name }}<small>{{ skill.description }}</small></span></el-checkbox>
            </el-checkbox-group>
            <div class="skill-picker-foot"><el-button link :disabled="busy || active || !skillIds.length" @click="skillIds = []">清空选择</el-button><el-button size="small" type="primary" @click="skillMenuOpen = false">完成</el-button></div>
            <el-button link @click="openExtensions">编辑个人 Skill / 导入</el-button>
          </div>
        </el-popover>
        <WritingToolPicker :novel-id="novelId" :task="task" :selections="mcpToolSelections" :allow-external-query="allowExternalQuery" :disabled="busy" @change="chooseTools" @task="receive" @manage="openExtensions" />
        </div>
        <el-button v-if="active && !['ADOPTING','UNDOING'].includes(task?.status)" :loading="busy" @click="cancel">停止</el-button><el-button v-else type="primary" :loading="busy" :disabled="!canSend" @click="send">{{ task?.status === 'READY' ? '发送修改' : '发送' }}</el-button>
      </div>
      <div v-if="selectedSkills.length" class="selected-skills"><el-tag v-for="skill in selectedSkills" :key="skill.id" size="small" :closable="!active && !busy" @close="skillIds = skillIds.filter(id => id !== skill.id)">{{ skill.name }}</el-tag></div>
    </footer>
    <el-drawer v-model="resourcesOpen" title="本章参考资料" size="min(520px, 96vw)" append-to-body>
      <div v-loading="resourceLoading" class="resource-picker">
        <el-alert v-if="resourceError" :title="resourceError" type="error" :closable="false" /><el-button v-if="resourceError" @click="loadResources">重试</el-button>
        <p>自动读取本章、所属卷、全书剧情笔记和前三个可读章节。额外选择用于下一次新会话；当前任务资料已经冻结。</p>
        <el-checkbox v-model="includeCumulative">使用当前章之前的累计回顾（模型摘要，具体事实仍核验原文）</el-checkbox>
        <h4>文风配置</h4><el-select v-model="styleIds" multiple :multiple-limit="2" placeholder="选择已确认的样文分析"><el-option v-for="row in styleOptions" :key="row.id" :value="row.id" :label="row.name" /></el-select>
        <h4>外部参考</h4><p>在聊天框底部「工具」中随时开关具体工具，当前会话立即保存，无需重新开始。</p><el-button @click="openExtensions">管理样文、Skill、MCP 与自动同步</el-button>
        <h4>额外参考章节</h4><el-select v-model="chapterIds" multiple filterable :multiple-limit="8" placeholder="选择更早章节，最多 8 章"><el-option v-for="chapter in chapterOptions" :key="chapter.id" :value="chapter.id" :label="`第 ${chapter.chapterNo} 章 · ${chapter.title}`" /></el-select>
        <h4>手动引用故事资料</h4><el-select v-model="settingIds" multiple filterable :multiple-limit="20" placeholder="选择已确认的条目"><el-option v-for="entry in libraryOptions.filter(e=>e.state==='APPROVED')" :key="entry.id" :value="entry.id" :label="entry.name" /></el-select>
        <p class="muted">核心规则始终加载；人物名、别名和物品名会自动触发相关设定。尚未生效或来源过期的条目会在服务端被排除。</p><el-button @click="openLibrary">管理故事资料库 / 确认候选记忆</el-button><el-button :loading="busy" :disabled="!!task" @click="previewResources">预览本次参考资料</el-button>
        <h4>更早正文检索资料</h4><p>构建所选章节的私有索引，供助手按需检索；未选择时构建前三章。正文改动后需重新构建，旧版本不会参与召回。</p>
        <el-checkbox v-model="withVectors">同时构建向量（调用管理端配置的 API，可能产生费用）</el-checkbox><div class="actions"><el-button type="primary" :loading="indexBusy" :disabled="resourceLoading" @click="buildIndex">构建检索资料</el-button><el-button :disabled="indexBusy" @click="clearIndex">清空检索资料</el-button></div><p v-if="resourceNotice">{{ resourceNotice }}</p>
      </div>
    </el-drawer>
    <WritingAssistantSettings v-model="assistantSettingsOpen" :novel-id="novelId" :snapshot="task?.assistantInstructions" />
  </section>
</template>

<style scoped>
.writing-agent { height:100%;display:flex;flex-direction:column;min-height:0;font-size:13px;color:#303133 }
.agent-head { flex-shrink:0;display:flex;align-items:center;justify-content:space-between;gap:8px;padding:16px;border-bottom:1px solid #e4e7ed;background:#fff }
.agent-head strong { font-size:16px }.subtitle { margin-top:5px;font-size:12px;color:#909399 }
.agent-heading { min-width:0;flex:1 }.subtitle { white-space:nowrap;overflow:hidden;text-overflow:ellipsis }
.header-actions { display:flex;align-items:center;gap:8px;flex-shrink:0 }.header-actions :deep(.el-button) { margin:0;font-size:12px }
.history-head { display:flex;justify-content:space-between;align-items:center }.history-hint { color:#909399;font-size:12px;margin:8px 0 }
.history-list { max-height:360px;overflow-y:auto;overscroll-behavior-y:contain }
.history-item { display:block;width:100%;text-align:left;padding:12px 10px;margin:4px 0;border:1px solid transparent;border-radius:6px;background:#f7f8fa;cursor:pointer;font:inherit }
.history-item:hover,.history-item.selected { background:#ecf5ff;border-color:#b3d8ff }.history-item:focus-visible { outline:2px solid #409eff }.history-item:disabled { cursor:wait;opacity:.6 }
.history-title { display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#303133;font-size:13px }
.history-meta { display:flex;justify-content:space-between;gap:6px;margin-top:7px;color:#909399;font-size:11px }
.history-empty,.history-error { padding:16px 4px;font-size:13px;color:#909399 }.history-error { color:#e6a23c }
.agent-feed { flex:1;min-height:0;overflow-y:auto;overscroll-behavior-y:contain;padding:16px }
.welcome { padding:16px 4px }.writing-agent p { line-height:1.7;margin:8px 0 }.muted { color:#909399;font-size:12px }
.user-message { margin:12px 0 16px 24px;padding:12px;background:#eaf2ff;border-radius:12px 12px 2px 12px;white-space:pre-wrap;overflow-wrap:anywhere }
.card { padding:12px;margin:12px 0;border:1px solid #e4e7ed;border-radius:8px;background:#fff }
h4 { margin:0 0 10px;font-size:14px }.card ol { padding-left:22px;line-height:1.8 }
.progress { color:#409eff;padding:8px 0 }.actions { display:flex;flex-wrap:wrap;gap:8px;margin-top:12px }.actions :deep(.el-button) { margin:0 }
summary { cursor:pointer;line-height:1.7;color:#606266 }.run-details { margin-top:18px;font-size:12px }.change-preview { margin-top:12px;font-size:12px }
pre { white-space:pre-wrap;overflow-wrap:anywhere;font-family:inherit;line-height:1.7 }.original { padding:8px;background:#fff3f0;border-radius:4px;max-height:180px;overflow:auto } blockquote { margin:8px;color:#606266 }
.composer { flex-shrink:0;padding:12px;border-top:1px solid #e4e7ed;background:#fff;max-height:55%;overflow-y:auto;overscroll-behavior-y:contain }
.composer-foot { display:flex;align-items:center;justify-content:space-between;gap:8px;margin-top:8px }.composer-foot span { color:#909399;font-size:11px }
.settings { padding:8px 0 }.setting-row { display:flex;gap:8px }.setting-row > * { flex:1;min-width:0 }
.selected-skills { display:flex;flex-wrap:wrap;gap:6px;margin-top:8px }
.composer-tools { display:flex;align-items:center;gap:6px }.composer-tools :deep(.el-button) { margin:0 }
.resource-picker { font-size:13px;line-height:1.8 }.resource-picker h4 { margin-top:24px }.resource-picker :deep(.el-select) { width:100% }.resource-picker :deep(.el-checkbox) { white-space:normal;height:auto;line-height:1.6 }
.reference-setting { margin:8px 0;font-size:12px;line-height:1.6 }
.skill-picker p { color:#909399;font-size:12px;margin:8px 0 12px }
.skill-picker :deep(.el-checkbox) { display:flex;height:auto;margin:12px 0;align-items:flex-start;white-space:normal }
.skill-picker small { display:block;font-size:12px;color:#909399;margin-top:4px;line-height:1.5 }
.skill-picker-foot { display:flex;justify-content:space-between;align-items:center;border-top:1px solid #ebeef5;padding-top:10px }
.writing-agent :deep(.el-input),.writing-agent :deep(.el-textarea),.writing-agent :deep(.el-select),.writing-agent :deep(.el-alert) { margin:5px 0;width:100% }
</style>
