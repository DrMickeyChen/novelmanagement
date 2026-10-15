<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getLibrary, saveEntry, getRevisions, deleteEntry } from '@/api/story-library'
import { getChapters, getChapterDetail } from '@/api/chapter'
const route=useRoute(), router=useRouter(), novelId=computed(()=>Number(route.params.novelId))
const rows=ref([]), chapters=ref([]), loading=ref(false), failed=ref(false), search=ref(''), category=ref(''), stateFilter=ref('')
const kinds={CHARACTER:'人物',LOCATION:'地点',ITEM:'物品',WORLD:'世界观',RULE:'核心规则'}
const meanings={SETTING:'作者确认设定',FACT:'正文事实',PLAN:'创作计划'}
const states={APPROVED:'已确认',PENDING:'待确认',INACTIVE:'已停用'}
const visible=computed(()=>rows.value.filter(r=>(!category.value||r.kind===category.value)&&(!stateFilter.value||r.state===stateFilter.value)&&(!search.value||`${r.name} ${r.aliases} ${r.body}`.includes(search.value))))
const editOpen=ref(false), saving=ref(false), editId=ref(null), revisionsOpen=ref(false), revisions=ref([]), historyLoading=ref(false)
const sourceOpen=ref(false), sourceLoading=ref(false), sourceDetail=ref(null)
const form=reactive({kind:'CHARACTER',name:'',aliasText:'',body:'',meaning:'SETTING',state:'APPROVED',effectiveFrom:1,effectiveTo:null,core:false,sourceChapterId:null,sourceQuote:'',expectedVersion:0})
function aliases(value){try{return Array.isArray(value)?value:JSON.parse(value||'[]')}catch{return []}}
async function load(){loading.value=true;try{const result=await Promise.all([getLibrary(novelId.value),getChapters(novelId.value)]);rows.value=result[0];chapters.value=result[1];failed.value=false}catch{failed.value=true}finally{loading.value=false}}
watch(novelId,load,{immediate:true})
function edit(row){editId.value=row?.id||null;Object.assign(form,{kind:row?.kind||'CHARACTER',name:row?.name||'',aliasText:aliases(row?.aliases).join('、'),body:row?.body||'',meaning:row?.meaning||'SETTING',state:row?.state||'APPROVED',effectiveFrom:Number(row?.effective_from||1),effectiveTo:row?.effective_to==null?null:Number(row.effective_to),core:!!row?.core,sourceChapterId:row?.source_chapter_id==null?null:Number(row.source_chapter_id),sourceQuote:row?.source_quote||'',expectedVersion:Number(row?.version||0)});editOpen.value=true}
async function save(){saving.value=true;try{await saveEntry(novelId.value,editId.value,{...form,aliases:form.aliasText.split(/[、,，\n]/).map(s=>s.trim()).filter(Boolean)});editOpen.value=false;ElMessage.success('资料已保存');await load()}finally{saving.value=false}}
async function deactivate(row){await ElMessageBox.confirm('停用后不再自动加载，历史版本仍会保留。','停用资料',{confirmButtonText:'停用',cancelButtonText:'取消'});edit(row);form.state='INACTIVE';await save()}
async function erase(row){await ElMessageBox.confirm('永久删除条目及历史版本，后台会清理引用此资料的会话。','删除资料',{type:'warning'});await deleteEntry(novelId.value,row.id,row.version);await load()}
async function history(row){revisionsOpen.value=true;historyLoading.value=true;revisions.value=[];try{revisions.value=(await getRevisions(novelId.value,row.id)).map(r=>({...r,detail:typeof r.payload==='string'?JSON.parse(r.payload):r.payload}))}finally{historyLoading.value=false}}
function sourceLabel(id){const chapter=chapters.value.find(c=>String(c.id)===String(id));return chapter?`第 ${chapter.chapterNo} 章 · ${chapter.title}`:`章节 ${id}`}
async function viewSource(row){sourceOpen.value=true;sourceLoading.value=true;sourceDetail.value=null;try{sourceDetail.value=await getChapterDetail(row.source_chapter_id)}finally{sourceLoading.value=false}}
</script>

<template>
  <div class="story-library" v-loading="loading">
    <header><div><h2>故事资料库</h2><p>由你维护人物、地点、物品和世界观。AI 提取的资料先进入待确认区。</p></div><div><el-button @click="router.push(`/novel/${novelId}/work`)">返回写作</el-button><el-button type="primary" @click="edit()">新增资料</el-button></div></header>
    <el-alert v-if="failed" type="error" title="资料读取失败，请刷新重试" :closable="false" /><el-button v-if="failed" @click="load">重试</el-button>
    <div class="filters"><el-input v-model="search" placeholder="搜索名称、别名或内容" clearable /><el-select v-model="category" placeholder="全部分类" clearable><el-option v-for="(name,id) in kinds" :key="id" :value="id" :label="name" /></el-select><el-select v-model="stateFilter" placeholder="全部状态" clearable><el-option v-for="(name,id) in states" :key="id" :value="id" :label="name" /></el-select><el-button @click="load">刷新</el-button></div>
    <p class="hint">生效章节控制故事时间；版本号记录编辑历史。核心规则始终参考，合计最多 2400 字。</p>
    <el-empty v-if="!visible.length && !failed && !loading" description="暂无符合条件的资料" />
    <div class="entries">
      <article v-for="row in visible" :key="row.id"><div class="entry-head"><h3>{{ row.name }}</h3><el-tag size="small">{{ kinds[row.kind] }}</el-tag><el-tag size="small" :type="row.state==='PENDING'?'warning':row.state==='INACTIVE'?'info':'success'">{{ states[row.state] }}</el-tag><el-tag v-if="row.core" size="small">始终参考</el-tag></div>
        <el-alert v-if="Number(row.source_stale)" type="warning" title="来源正文已修改或删除，此条目不会参与生成，请重新核对来源" :closable="false" />
        <p v-if="aliases(row.aliases).length" class="hint">别名：{{ aliases(row.aliases).join('、') }}</p><p class="entry-body">{{ row.body }}</p>
        <p class="hint">{{ meanings[row.meaning] }} · 第 {{ row.effective_from }} 章起{{ row.effective_to ? `，至第 ${row.effective_to} 章` : '' }} · 版本 {{ row.version }}</p>
        <details v-if="row.source_chapter_id"><summary>{{ sourceLabel(row.source_chapter_id) }} · 来源版本 {{ row.source_version }}</summary><blockquote>{{ row.source_quote }}</blockquote></details>
        <div class="actions"><el-button link type="primary" @click="edit(row)">{{ row.state==='PENDING'?'审核并编辑':'编辑' }}</el-button><el-button v-if="row.source_chapter_id" link @click="viewSource(row)">查看来源正文</el-button><el-button link @click="history(row)">修改历史</el-button><el-button v-if="row.state!=='INACTIVE'" link type="danger" @click="deactivate(row)">停用</el-button><el-button link type="danger" @click="erase(row)">永久删除</el-button></div>
      </article>
    </div>
    <el-dialog v-model="editOpen" :title="editId?'编辑资料':'新增资料'" width="min(680px, 95vw)">
      <el-form label-position="top"><div class="form-row"><el-form-item label="分类"><el-select v-model="form.kind"><el-option v-for="(name,id) in kinds" :key="id" :value="id" :label="name" /></el-select></el-form-item><el-form-item label="名称"><el-input v-model="form.name" maxlength="100" /></el-form-item></div>
        <el-form-item label="别名（用顿号或逗号分隔）"><el-input v-model="form.aliasText" /></el-form-item><el-form-item label="设定内容"><el-input v-model="form.body" type="textarea" :rows="6" maxlength="4000" show-word-limit /></el-form-item>
        <div class="form-row"><el-form-item label="资料性质"><el-select v-model="form.meaning"><el-option v-for="(name,id) in meanings" :key="id" :value="id" :label="name" /></el-select></el-form-item><el-form-item label="确认状态"><el-select v-model="form.state"><el-option v-for="(name,id) in states" :key="id" :value="id" :label="name" /></el-select></el-form-item></div>
        <div class="form-row"><el-form-item label="从第几章生效"><el-input-number v-model="form.effectiveFrom" :min="1" /></el-form-item><el-form-item label="截至第几章（留空不限）"><el-input-number v-model="form.effectiveTo" :min="form.effectiveFrom" /></el-form-item></div>
        <el-checkbox v-model="form.core">始终参考（少量全书核心规则）</el-checkbox>
        <el-form-item label="正文来源（正文事实必填）"><el-select v-model="form.sourceChapterId" clearable filterable><el-option v-for="chapter in chapters" :key="chapter.id" :value="Number(chapter.id)" :label="`第 ${chapter.chapterNo} 章 · ${chapter.title}`" /></el-select></el-form-item><el-form-item v-if="form.sourceChapterId" label="逐字原文引用（修改后需重新确认）"><el-input v-model="form.sourceQuote" type="textarea" :rows="3" maxlength="2000" /></el-form-item>
      </el-form><template #footer><el-button @click="editOpen=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!form.name.trim()||!form.body.trim()" @click="save">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="revisionsOpen" title="资料修改历史" width="min(720px, 95vw)"><div v-loading="historyLoading"><article v-for="r in revisions" :key="r.version" class="revision"><strong>版本 {{ r.version }} · {{ r.detail.name }}</strong><p class="entry-body">{{ r.detail.body }}</p><p class="hint">{{ states[r.detail.state] }} · 生效章节 {{ r.detail.effective_from }}</p></article></div></el-dialog>
    <el-dialog v-model="sourceOpen" title="当前来源正文" width="min(780px, 95vw)"><div v-loading="sourceLoading"><h3 v-if="sourceDetail">{{ sourceDetail.title }}</h3><p class="hint">这是当前正文版本，请与条目中的原文引用核对。</p><p class="entry-body">{{ sourceDetail?.content }}</p></div></el-dialog>
  </div>
</template>

<style scoped>
.story-library { max-width:1400px;margin:auto }.story-library header { display:flex;justify-content:space-between;align-items:center;gap:16px }h2 { margin:0 0 8px }header p,.hint { font-size:13px;color:#909399;line-height:1.7 }.filters { display:flex;gap:12px;margin:24px 0 12px }.filters .el-input { max-width:420px }.filters .el-select { width:160px }.entries { display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:16px }.entries article,.revision { padding:18px;background:#fff;border:1px solid #e4e7ed;border-radius:8px }.entry-head { display:flex;gap:8px;align-items:center;flex-wrap:wrap }h3 { margin:0 }.entry-body { white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.8;font-size:14px }.actions { margin-top:12px }.form-row { display:flex;gap:16px }.form-row > * { flex:1;min-width:0 }.revision { margin-bottom:12px }blockquote { white-space:pre-wrap;color:#606266 }summary { cursor:pointer;font-size:13px }@media(max-width:900px){header,.filters{flex-wrap:wrap}}
</style>
