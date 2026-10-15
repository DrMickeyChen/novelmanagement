<script setup>
import { computed, ref } from 'vue'
import * as api from '@/api/writing-extensions'

const props = defineProps({ novelId: Number, task: Object, selections: Array, allowExternalQuery: Boolean, disabled: Boolean })
const emit = defineEmits(['change', 'task', 'manage'])
const open = ref(false), loading = ref(false), saving = ref(false), error = ref(''), connections = ref([])
const count = computed(() => (props.selections || []).reduce((sum, row) => sum + row.tools.length, 0))
const terminal = computed(() => ['CANCELLED','EXPIRED','ADOPTING','UNDOING','ADOPTED','UNDONE'].includes(props.task?.status))
const locked = computed(() => saving.value || loading.value || props.disabled || terminal.value)
const selected = (connectionId, name) => (props.selections || []).some(row => row.connectionId === connectionId && row.tools.includes(name))
const catalogue = row => api.parseJson(row.catalog) || []
const granted = (row, name) => !!row.enabled && (api.parseJson(row.grants) || []).includes(name)

async function load() {
  loading.value = true; error.value = ''
  try { connections.value = await api.getMcp(props.novelId) }
  catch (e) { error.value = e.message || '工具目录读取失败，请重试' }
  finally { loading.value = false }
}
async function save(selections, allowExternalQuery = props.allowExternalQuery) {
  if (locked.value) return
  if (selections.length && !allowExternalQuery) { error.value = '请先勾选允许向外部服务发送必要查询参数'; return }
  if (selections.length > 2 || selections.some(row => row.tools.length > 8)) { error.value = '最多启用两个连接，每个连接最多八个工具'; return }
  saving.value = true; error.value = ''
  const taskId = props.task?.id
  try {
    if (props.task) {
      const result = await api.updateTaskTools(taskId, { mcpToolSelections: selections, allowExternalQuery, expectedToolsVersion: props.task.toolsVersion || 0 })
      if (props.task?.id === taskId) emit('task', result)
    } else emit('change', selections, allowExternalQuery)
  } catch (e) { error.value = e.message || '工具选择未保存，原选择仍然有效' }
  finally { saving.value = false }
}
function toggle(row, name, value) {
  const choices = (props.selections || []).map(item => ({ connectionId: item.connectionId, tools: [...item.tools] }))
  let item = choices.find(item => item.connectionId === row.id)
  if (value) { if (!item) choices.push(item = { connectionId: row.id, tools: [] }); if (!item.tools.includes(name)) item.tools.push(name) }
  else if (item) item.tools = item.tools.filter(tool => tool !== name)
  save(choices.filter(item => item.tools.length))
}
async function reloadSession() {
  if (!props.task || saving.value) return
  saving.value = true; error.value = ''
  const taskId = props.task.id
  try { const result = await api.getToolTask(taskId); if (props.task?.id === taskId) emit('task', result) }
  catch (e) { error.value = e.message || '会话读取失败' }
  finally { saving.value = false }
}
</script>

<template>
  <el-popover v-model:visible="open" trigger="click" placement="top-start" :width="340" @show="load">
    <template #reference><el-button size="small" :disabled="disabled" :aria-expanded="open" aria-label="选择本会话外部工具">工具{{ count ? ` · ${count}` : '' }}</el-button></template>
    <div class="tool-picker">
      <div class="tools-heading"><strong>本会话工具</strong><el-button link :disabled="saving || loading" @click="load">刷新目录</el-button></div>
      <p>勾选即保存，无需新会话。规划和检查阶段按最新选择调用；外部服务可能另行计费。已发出的远程请求无法撤回。</p>
      <el-checkbox :model-value="allowExternalQuery" :disabled="locked" @change="value => save(value ? selections : [], value)">允许向外部服务发送必要查询参数</el-checkbox>
      <p v-if="terminal">该会话已结束或正在采纳，暂不能更改工具。</p>
      <div v-if="error" role="alert" class="tool-error">{{ error }}<el-button v-if="task" link :disabled="saving" @click="reloadSession">重新加载会话</el-button></div>
      <div v-if="loading" role="status">正在读取工具…</div>
      <div v-else class="tool-groups">
        <p v-if="!connections.length">还没有 MCP 连接，请先添加连接并授权工具。</p>
        <section v-for="row in connections" :key="row.id">
          <strong>{{ row.name }}</strong>
          <p v-if="!catalogue(row).length">尚未读取工具目录。</p>
          <div v-for="tool in catalogue(row)" :key="tool.name" class="tool-row">
            <el-checkbox :model-value="selected(row.id, tool.name)" :disabled="locked || (!granted(row, tool.name) && !selected(row.id, tool.name))" @change="value => toggle(row, tool.name, value)">{{ tool.title || tool.name }}</el-checkbox>
            <small>{{ tool.description }}</small><small v-if="!granted(row, tool.name)" class="tool-error">未授权或连接已变更，请到写作扩展授权；仍可取消选择。</small>
          </div>
        </section>
      </div>
      <div class="tools-heading"><el-button link :disabled="locked || !count" @click="save([])">全部关闭</el-button><span v-if="saving">正在保存…</span><el-button link @click="open = false; emit('manage')">管理连接与授权</el-button></div>
    </div>
  </el-popover>
</template>

<style scoped>
.tool-picker { font-size: 13px; line-height: 1.6; }
.tool-picker p { color: #909399; font-size: 12px; margin: 8px 0; }
.tools-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.tool-groups { max-height: 340px; overflow-y: auto; overscroll-behavior: contain; margin: 10px 0; }
.tool-groups section { padding: 10px 0; border-top: 1px solid #ebeef5; }
.tool-row { margin: 6px 0; }.tool-row small { display: block; color: #909399; font-size: 12px; overflow-wrap: anywhere; max-height: 58px; overflow-y: auto; }
.tool-picker :deep(.el-checkbox) { height: auto; white-space: normal; line-height: 1.6; }.tool-picker :deep(.el-checkbox__label) { white-space: normal; overflow-wrap: anywhere; }
.tool-error,.tool-row .tool-error { color: #e6a23c; font-size: 12px; }
</style>
