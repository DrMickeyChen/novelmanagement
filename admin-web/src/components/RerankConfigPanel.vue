<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getRerankConfig, saveRerankConfig } from '@/api/bookKnowledge'
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
  try { apply(await getRerankConfig()) }
  catch { failed.value = true }
  finally { loading.value = false }
}
async function save() {
  if (!form.url.trim() || !form.model.trim()) return ElMessage.warning('请填写完整接口地址和模型名称')
  if (clearKey.value && newKey.value.trim()) return ElMessage.warning('更换密钥和清空密钥不能同时选择')
  if (clearKey.value) await ElMessageBox.confirm('清空后将跳过模型重排，保留原有关键词和向量排序。', '清空重排 API Key')
  const payload = { url: form.url.trim(), model: form.model.trim(), batchSize: form.batchSize, clearApiKey: clearKey.value }
  if (newKey.value.trim()) payload.apiKey = newKey.value.trim()
  saving.value = true
  try {
    apply(await saveRerankConfig(payload))
    ElMessage.success('重排模型配置已保存，立即生效')
    emit('saved')
  } finally { saving.value = false }
}
onMounted(load)
</script>
<template>
  <el-card v-loading="loading" shadow="never" class="embedding-config">
    <template #header><strong>重排模型配置（Rerank）</strong></template>
    <el-alert v-if="failed" title="重排配置获取失败，请检查服务是否已更新、数据库是否执行 rerank-config.sql，然后重新加载。" type="error" :closable="false" />
    <el-alert v-if="current?.apiKeyNotice" :title="current.apiKeyNotice" type="warning" :closable="false" />
    <p>重排在读者查询时对候选正文片段排序，不在构建知识库时调用。配置独立保存，密钥加密存入数据库；保存本身不调用模型。</p>
    <el-form label-width="130px" :disabled="saving || failed">
      <el-form-item label="完整接口地址"><el-input v-model="form.url" placeholder="https://api.siliconflow.cn/v1/rerank" maxlength="500" /></el-form-item>
      <el-form-item label="模型名称"><el-input v-model="form.model" placeholder="BAAI/bge-reranker-v2-m3" maxlength="120" /></el-form-item>
      <el-form-item label="当前 API Key"><span>{{ current?.apiKeyMasked || (current?.apiKeyConfigured ? '已保存（无法解密）' : '未保存') }}</span></el-form-item>
      <el-form-item label="设置新 API Key"><el-input v-model="newKey" type="password" show-password autocomplete="new-password" :disabled="clearKey" maxlength="2000" placeholder="填写新密钥；留空保留原值" /></el-form-item>
      <el-form-item label="清空 API Key"><el-checkbox v-model="clearKey" :disabled="!!newKey.trim()">明确清空已保存的重排密钥</el-checkbox></el-form-item>
      <el-form-item><el-button type="primary" :loading="saving" @click="save">保存重排配置</el-button></el-form-item>
    </el-form>
    <el-button :disabled="saving || loading" @click="load">重新加载配置</el-button>
    <p>修改重排配置无需重建知识库。查询时有正文候选才调用，失败时保留原排序。运行记录的检索模式包含 _RERANK 表示重排成功。</p>
  </el-card>
</template>
<style scoped>
.embedding-config { margin-bottom: 20px; }
p { color: #606266; line-height: 1.7; }
.batch-tip { margin-left: 12px; color: #909399; }
</style>
