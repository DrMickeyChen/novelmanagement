<script setup>
import { ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import * as ext from '@/api/writing-extensions'
import { command } from '@/api/writing'
const props=defineProps({task:Object,prepare:Function,apply:Function})
const emit=defineEmits(['task'])
const open=ref(false), rows=ref([]), error=ref(''), busy=ref(false), left=ref(null), right=ref(null), merge=ref(null), resolved=ref(''), approved=ref(false)
async function run(fn){if(busy.value)return;busy.value=true;error.value='';try{await fn()}catch(e){if(e!=='cancel'&&e!=='close')error.value=e.message||'操作失败'}finally{busy.value=false}}
async function show(){open.value=true;await run(async()=>{rows.value=await ext.revisions(props.task.id);left.value=rows.value[0]||null;right.value=rows.value[1]||null})}
async function select(row){await run(async()=>{emit('task',await command(props.task.id,'select_draft',{expectedTaskVersion:props.task.version,revisionId:row.id},crypto.randomUUID()));open.value=false})}
async function branch(){await run(async()=>{const name=await ElMessageBox.prompt('为新分支命名；原稿会保留。','创建草稿分支',{inputValidator:v=>!!v?.trim()&&v.length<=100});const change=await ElMessageBox.prompt('描述这个分支的改写方向。','分支要求',{inputType:'textarea',inputValidator:v=>!!v?.trim()&&v.length<=1200});emit('task',await ext.branch(props.task.id,{name:name.value,instruction:change.value}));open.value=false})}
async function preview(){await run(async()=>{await props.prepare(async base=>{merge.value=await ext.mergePreview(props.task.id,{expectedChapterVersion:base.expectedChapterVersion,draftStart:0,draftEnd:props.task.draft.length});resolved.value=merge.value.preview.clean?merge.value.preview.merged:merge.value.preview.current;approved.value=false})})}
async function adopt(){await run(async()=>{if(!merge.value.preview.clean&&!approved.value)throw new Error('请处理冲突并明确确认合并后的正文');await ElMessageBox.confirm('把预览结果写入正文；如果预览后正文再次改变，操作会拒绝。检查未完全通过时仍需你人工核对。','确认合并采纳');await props.prepare(async base=>{if(Number(base.expectedChapterVersion)!==Number(merge.value.currentVersion))throw new Error('正文已更新，请重新预览');const result=await command(props.task.id,'adopt_merged',{expectedTaskVersion:props.task.version,expectedChapterVersion:merge.value.currentVersion,draftAttemptId:props.task.currentDraftAttemptId,draftStart:0,draftEnd:props.task.draft.length,approveResolved:!merge.value.preview.clean,resolved:resolved.value,acceptUnchecked:true},crypto.randomUUID());emit('task',result);if(result.status==='ADOPTED'){props.apply(result.applied);open.value=false}else error.value='采纳正在确认，请稍后刷新任务'})})}
</script>
<template>
  <el-button size="small" @click="show">版本、分支与合并</el-button>
  <el-dialog v-model="open" title="草稿版本" width="min(1100px,96vw)" append-to-body>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="actions"><el-button :loading="busy" @click="branch">创建改写分支</el-button><el-button :loading="busy" @click="preview">与最新正文合并预览</el-button></div>
    <div class="compare"><div v-for="(side,index) in [left,right]" :key="index"><el-select :model-value="side?.id" placeholder="选择版本" @update:model-value="id=>index===0?left=rows.find(r=>r.id===id):right=rows.find(r=>r.id===id)"><el-option v-for="row in rows" :key="row.id" :value="row.id" :label="`${row.name} · ${row.created_at}`" /></el-select><p v-if="side">父版本：{{ side.parent_id || '初稿' }}</p><pre>{{ side?.body }}</pre><el-button v-if="side" :disabled="busy" @click="select(side)">使用此版本继续修改</el-button></div></div>
    <section v-if="merge"><h3>{{ merge.preview.clean?'独立修改可以自动合并':'有冲突，需要人工处理' }}</h3><p v-for="issue in merge.preview.conflicts" :key="String(issue)">{{ issue }}</p><div class="compare three"><details open><summary>生成时的正文</summary><pre>{{ merge.preview.base }}</pre></details><details open><summary>作者最新正文</summary><pre>{{ merge.preview.current }}</pre></details><details open><summary>候选修改</summary><pre>{{ merge.preview.candidate }}</pre></details></div><h4>合并结果</h4><el-input v-model="resolved" type="textarea" :rows="12" :readonly="merge.preview.clean" maxlength="100000" /><el-checkbox v-if="!merge.preview.clean" v-model="approved">我已解决冲突，确认以上完整正文</el-checkbox><div class="actions"><el-button type="primary" :loading="busy" :disabled="!merge.preview.clean&&!approved" @click="adopt">合并并采纳</el-button></div></section>
  </el-dialog>
</template>
<style scoped>.actions{display:flex;gap:12px;margin:12px 0}.compare{display:grid;grid-template-columns:1fr 1fr;gap:16px;margin:16px 0}.compare>div,.compare>details{min-width:0;border:1px solid #e4e7ed;padding:12px;border-radius:6px}.three{grid-template-columns:repeat(3,1fr)}pre{max-height:300px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.7;font-family:inherit}@media(max-width:700px){.compare,.three{grid-template-columns:1fr}}</style>
