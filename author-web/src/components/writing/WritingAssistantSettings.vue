<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getAssistantSettings, saveAssistantSettings } from '@/api/writing-extensions'

const props = defineProps({ modelValue: Boolean, novelId: Number, snapshot: Object })
const emit = defineEmits(['update:modelValue'])
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const tab = ref('book'), loading = ref(false), saving = ref(false), error = ref(''), loaded = ref(false)
const data = ref(null)
const form = computed(() => data.value?.[tab.value])
const scopeNames = { DEFAULT: '平台默认', PERSONAL: '个人默认', BOOK: '当前作品' }

async function load() {
  loading.value = true; error.value = ''; loaded.value = false
  try {
    data.value = await getAssistantSettings(props.novelId)
    if (!data.value.book.enabled) data.value.book.instructions = data.value.effective.instructions
    loaded.value = true
  }
  catch (e) { error.value = e.message || '助手设置读取失败，请重试' }
  finally { loading.value = false }
}
watch(() => props.modelValue, value => { if (value) { tab.value = 'book'; load() } })
function reset() {
  form.value.instructions = tab.value === 'book' && data.value.personal.enabled ? data.value.personal.instructions : data.value.defaultInstructions
  form.value.enabled = false
}
async function save() {
  const selectedTab = tab.value, value = form.value
  if (value.enabled && !value.instructions.trim()) { error.value = '请填写自定义指令'; return }
  saving.value = true; error.value = ''
  try {
    const saved = await saveAssistantSettings(selectedTab === 'personal' ? 0 : props.novelId, {
      enabled: value.enabled, instructions: value.instructions, expectedVersion: value.version,
    })
    // Preserve any unsaved edits in the other tab; do not reload the whole form.
    data.value[selectedTab] = saved[selectedTab]
    if (selectedTab === 'book' && !saved.book.enabled) data.value.book.instructions = saved.effective.instructions
    if (selectedTab === 'personal' && !data.value.book.enabled) data.value.book.instructions = saved.effective.instructions
    ElMessage.success('已保存，下次新会话生效')
  } catch (e) { error.value = e.message || '保存失败，已保留输入内容' }
  finally { saving.value = false }
}
</script>

<template>
  <el-dialog v-model="visible" title="助手设置" width="min(760px, 94vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving">
    <p class="settings-help">设置助手的角色、描写偏好和协作方式，保存到账号。个人默认适用于所有作品；当前作品可单独覆盖。保存后仅对新会话生效。</p>
    <el-alert v-if="snapshot" :title="`当前会话使用：${scopeNames[snapshot.scope] || '历史指令'}，本次设置不会改变该会话。`" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="settings-error" />
    <div v-if="loading" role="status">正在读取助手设置…</div>
    <el-button v-if="!loading && !loaded" @click="load">重新加载</el-button>
    <template v-if="loaded">
      <el-tabs v-model="tab" :before-leave="() => !saving">
        <el-tab-pane label="个人默认" name="personal" />
        <el-tab-pane label="当前作品" name="book" />
      </el-tabs>
      <el-checkbox v-model="form.enabled" :disabled="saving">{{ tab === 'book' ? '为当前作品单独设置指令' : '使用个人自定义指令' }}</el-checkbox>
      <p v-if="tab === 'book' && !form.enabled" class="settings-help">当前跟随{{ data.personal.enabled ? '个人默认' : '平台默认' }}。勾选后使用下方内容。</p>
      <el-input v-model="form.instructions" type="textarea" :rows="12" maxlength="6000" show-word-limit :disabled="saving" aria-label="助手自定义指令" placeholder="例如：你擅长克制的情绪描写，通过行动和对话表现人物；修改时优先保留原句。" @input="form.enabled = true" />
      <p class="settings-help">可填写人物描写、节奏、用词和修改方式。剧情设定请放到剧情笔记或故事资料库；工具权限及各阶段必要格式由平台控制。</p>
    </template>
    <template #footer>
      <el-button :disabled="!loaded || saving || loading" @click="reset">{{ tab === 'book' ? '恢复跟随默认' : '恢复平台默认' }}</el-button>
      <el-button :disabled="saving" @click="visible = false">关闭</el-button>
      <el-button v-if="loaded" :disabled="saving || loading" @click="load">重新加载</el-button>
      <el-button type="primary" :loading="saving" :disabled="!loaded || loading" @click="save">保存{{ tab === 'book' ? '当前作品' : '个人默认' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.settings-help { color: #606266; font-size: 13px; line-height: 1.7; margin: 10px 0; }
.settings-error { margin: 12px 0; }
</style>
