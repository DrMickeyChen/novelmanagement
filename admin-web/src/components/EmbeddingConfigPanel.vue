<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getEmbeddingConfig, saveEmbeddingConfig } from '@/api/bookKnowledge'
const emit = defineEmits(['saved'])
const loading = ref(false), saving = ref(false), failed = ref(false), current = ref(null)
const form = reactive({ url: '', model: '', batchSize: 8 })
const newKey = ref(''), clearKey = ref(false)
function apply(data) {
  current.value = data
  form.url = data.url
  form.model = data.model
  form.batchSize = data.batchSize
  newKey.value = ''
  clearKey.value = false
}
async function load() {
  loading.value = true
  failed.value = false
  try { apply(await getEmbeddingConfig()) }
  catch { failed.value = true }
  finally { loading.value = false }
}
async function save() {
  if (!form.url.trim() || !form.model.trim()) return ElMessage.warning('请填写完整接口地址和模型名称')
  if (clearKey.value && newKey.value.trim()) return ElMessage.warning('更换密钥和清空密钥不能同时选择')
  if (clearKey.value) await ElMessageBox.confirm('清空后将停用向量接口，找书仍可使用正文关键词检索。', '清空向量 API Key')
  const payload = { url: form.url.trim(), model: form.model.trim(), batchSize: form.batchSize, clearApiKey: clearKey.value }
  if (newKey.value.trim()) payload.apiKey = newKey.value.trim()
  saving.value = true
  try {
    apply(await saveEmbeddingConfig(payload))
    ElMessage.success('向量模型配置已保存，立即生效')
    emit('saved')
  } finally { saving.value = false }
}
onMounted(load)
</script>
<template>
  <el-card v-loading="loading" shadow="never" class="embedding-config">
    <template #header><strong>向量模型配置（Embedding）</strong></template>
    <el-alert v-if="failed" title="向量配置获取失败，请检查服务是否已更新、数据库是否执行 embedding-config.sql，然后重新加载。" type="error" :closable="false" />
    <el-alert v-if="current?.apiKeyNotice" :title="current.apiKeyNotice" type="warning" :closable="false" />
    <p>用于检索语义相关的正文，与 DeepSeek 聊天配置独立。保存后无需重启；密钥加密存入数据库，只显示掩码。留空保留已保存密钥。</p>
    <el-form label-width="130px" :disabled="saving || failed">
      <el-form-item label="完整接口地址"><el-input v-model="form.url" placeholder="https://api.siliconflow.cn/v1/embeddings" maxlength="500" /></el-form-item>
      <el-form-item label="模型名称"><el-input v-model="form.model" placeholder="BAAI/bge-m3" maxlength="120" /></el-form-item>
      <el-form-item label="当前 API Key"><span>{{ current?.apiKeyMasked || (current?.apiKeyConfigured ? '已保存（无法解密）' : '未保存') }}</span></el-form-item>
      <el-form-item label="设置新 API Key"><el-input v-model="newKey" type="password" show-password autocomplete="new-password" :disabled="clearKey" maxlength="2000" placeholder="填写新密钥；留空保留原值" /></el-form-item>
      <el-form-item label="清空 API Key"><el-checkbox v-model="clearKey" :disabled="!!newKey.trim()">明确清空已保存的向量密钥</el-checkbox></el-form-item>
      <el-form-item label="每次请求片段数"><el-input-number v-model="form.batchSize" :min="1" :max="16" /><span class="batch-tip">默认 8；百炼 text-embedding-v4 最多 10，按厂商限制填写。</span></el-form-item>
      <el-form-item><el-button type="primary" :loading="saving" @click="save">保存向量配置</el-button></el-form-item>
    </el-form>
    <el-button :disabled="saving || loading" @click="load">重新加载配置</el-button>
    <p>修改模型或接口地址后，原有向量不会自动转换，需要重新构建对应作品。仅修改密钥无需重建。保存配置不调用模型；构建向量和语义查询会调用 API。</p>
  </el-card>
</template>
<style scoped>
.embedding-config { margin-bottom: 20px; }
p { color: #606266; line-height: 1.7; }
.batch-tip { margin-left: 12px; color: #909399; }
</style>
