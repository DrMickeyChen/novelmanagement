<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/utils/request'
const root='/admin/ai/book-finder/evaluations'
const reports=ref([]), current=ref(null), busy=ref(false), failed=ref(false)
const label=ref('找书助手基准评测'), inputPrice=ref(null), outputPrice=ref(null), pricingNote=ref('')
const suite=ref(JSON.stringify([
  {id:'completed',prompt:'找已完结的小说，至少二十万字',expected:{completed:true,minWords:200000},allowEmpty:false},
  {id:'range',prompt:'找二十万到一百万字的小说',expected:{minWords:200000,maxWords:1000000},allowEmpty:false},
  {id:'first',group:'refresh',prompt:'推荐几本小说',allowEmpty:false},
  {id:'next',group:'refresh',prompt:'换一批',allowEmpty:false},
  {id:'fourth-reference',group:'refresh',prompt:'类似第4本的还有吗',allowEmpty:false},
  {id:'style',prompt:'有没有虐的同时还有修仙的小说',allowEmpty:true},
  {id:'greeting',prompt:'你好',allowEmpty:true,clarify:true}
],null,2))
const verdict=ref('INSUFFICIENT'), note=ref(''), selected=ref(null)
let timer
async function load() { try { reports.value=await request.get(root); failed.value=false } catch { failed.value=true } }
async function open(id) { current.value=await request.get(`${root}/${id}`) }
async function start() {
  let cases
  try { cases=JSON.parse(suite.value); if(!Array.isArray(cases)) throw Error() } catch { ElMessage.error('测试题必须是 JSON 数组'); return }
  try { await ElMessageBox.confirm(`将执行 ${cases.length} 道测试题；真实模型模式会调用聊天、向量和重排 API 并产生费用。`, '执行评测', {type:'warning'}) } catch { return }
  busy.value=true
  try { current.value=await request.post(root,{label:label.value,cases,inputPricePerMillion:inputPrice.value,outputPricePerMillion:outputPrice.value,pricingNote:pricingNote.value}); await load() } finally { busy.value=false }
}
async function cancel() { await request.post(`${root}/${current.value.id}/cancel`); ElMessage.info('当前题目结束后停止后续测试') }
function exportReport() {
  const url=URL.createObjectURL(new Blob([JSON.stringify(current.value,null,2)],{type:'application/json;charset=utf-8'}))
  const a=document.createElement('a'); a.href=url; a.download=`book-finder-eval-${current.value.id}.json`; a.click(); setTimeout(()=>URL.revokeObjectURL(url),1000)
}
async function review() {
  current.value=await request.put(`${root}/${current.value.id}/review/${selected.value.case.id}`,{verdict:verdict.value,note:note.value}); selected.value=null; await load()
}
onMounted(()=>{load(); timer=setInterval(async()=>{if(current.value && ['RUNNING','QUEUED'].includes(current.value.status)) { await open(current.value.id); await load() }},4000)})
onUnmounted(()=>clearInterval(timer))
</script>
<template>
  <el-alert title="自动检查硬条件与重复推荐；软偏好须人工审核。报告持久保存，费用为聊天模型估算，不包含向量和重排费用。" type="info" :closable="false" />
  <el-form label-width="120px" style="margin-top:20px">
    <el-form-item label="评测名称"><el-input v-model="label" maxlength="100" /></el-form-item>
    <el-form-item label="测试题 JSON"><el-input v-model="suite" type="textarea" :rows="10" /></el-form-item>
    <p>同一 group 按顺序续聊；不同 group 开新会话。expected 是预期硬条件；allowEmpty 控制是否允许空推荐。预期分类须填写平台真实名称。</p>
    <el-form-item label="输入单价 / 百万"><el-input-number v-model="inputPrice" :min="0" :precision="4" /></el-form-item>
    <el-form-item label="输出单价 / 百万"><el-input-number v-model="outputPrice" :min="0" :precision="4" /></el-form-item>
    <el-form-item label="价格来源与币种"><el-input v-model="pricingNote" maxlength="300" placeholder="填写币种、价格来源和生效日期；不填写价格则不估算费用" /></el-form-item>
    <el-button type="primary" :loading="busy" @click="start">执行评测</el-button><el-button @click="load">刷新记录</el-button>
  </el-form>
  <el-alert v-if="failed" title="评测记录加载失败，请重试" type="error" />
  <el-table :data="reports" style="margin-top:20px">
    <el-table-column prop="label" label="名称" /><el-table-column prop="startedAt" label="时间" /><el-table-column prop="status" label="状态" />
    <el-table-column label="执行框架" width="150"><template #default="{row}">{{ row.engine === 'langchain4j' ? 'LangChain4j' : row.engine === 'native' ? '原有实现' : '旧报告未记录' }}</template></el-table-column>
    <el-table-column label="模式"><template #default="{row}">{{ row.mock ? 'Mock，不用于证明真实推荐效果' : '真实模型' }}</template></el-table-column>
    <el-table-column label="操作"><template #default="{row}"><el-button link @click="open(row.id)">查看报告</el-button></template></el-table-column>
  </el-table>
  <section v-if="current">
    <h3>{{ current.label }} · {{ current.status }}</h3>
    <p>执行框架：{{ current.engine === 'langchain4j' ? 'LangChain4j AI Services' : current.engine === 'native' ? '原有实现' : '旧报告未记录' }} {{ current.engineVersion || '' }}</p>
    <p>执行 {{ current.metrics?.executed || 0 }} / {{ current.suite?.cases?.length }} 题；自动通过 {{ current.metrics?.automaticPassed || 0 }} 题；人工审核 {{ current.metrics?.reviewed || 0 }} 题。</p>
    <p>平均耗时 {{ Math.round(current.metrics?.averageMs || 0) }} ms；P95 {{ current.metrics?.p95Ms ?? '—' }} ms；已返回 Token {{ current.metrics?.reportedTotalTokens ?? '—' }}；聊天估算费用 {{ current.metrics?.estimatedChatCost ?? '未设置价格' }}。</p>
    <p>硬条件符合率 {{ current.metrics?.hardConditionRate == null ? '暂无推荐可统计' : (current.metrics.hardConditionRate*100).toFixed(1)+'%' }}；人工证据支持率 {{ current.metrics?.humanSupportRate == null ? '尚未审核' : (current.metrics.humanSupportRate*100).toFixed(1)+'%' }}。</p>
    <el-button @click="exportReport">导出完整 JSON 证明</el-button><el-button v-if="['RUNNING','QUEUED'].includes(current.status)" @click="cancel">停止后续题目</el-button>
    <el-table :data="current.rows">
      <el-table-column prop="case.prompt" label="问题" /><el-table-column prop="status" label="执行" width="100" />
      <el-table-column label="自动检查" width="110"><template #default="{row}">{{ row.automaticPass ? '通过' : '未通过' }}</template></el-table-column>
      <el-table-column prop="review" label="人工审核" width="150" />
      <el-table-column label="结果与证据"><template #default="{row}"><el-button link @click="selected=row;note=row.reviewNote || '';verdict=row.review === 'PENDING' ? 'INSUFFICIENT' : row.review">查看 / 审核</el-button></template></el-table-column>
    </el-table>
  </section>
  <el-dialog :model-value="!!selected" title="逐题审核：证据是否支持推荐判断" width="80%" @close="selected=null">
    <template v-if="selected">
      <pre style="max-height:420px;overflow:auto;white-space:pre-wrap">{{ JSON.stringify(selected,null,2) }}</pre>
      <el-select v-model="verdict"><el-option label="证据支持推荐判断" value="SUPPORTED" /><el-option label="证据不支持判断" value="NOT_SUPPORTED" /><el-option label="资料不足，无法判定" value="INSUFFICIENT" /></el-select>
      <el-input v-model="note" type="textarea" maxlength="1000" placeholder="写明支持或反驳的证据；没有风格判断的题目可不审核" style="margin-top:12px" />
    </template>
    <template #footer><el-button :disabled="!note.trim() || ['RUNNING','QUEUED'].includes(current?.status)" @click="review">保存审核记录</el-button></template>
  </el-dialog>
</template>
