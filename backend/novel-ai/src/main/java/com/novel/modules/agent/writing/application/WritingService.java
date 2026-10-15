package com.novel.modules.agent.writing.application;

import com.novel.modules.agent.writing.domain.WritingChecks;
import com.novel.modules.agent.writing.domain.WritingJson;
import com.novel.modules.agent.writing.infrastructure.model.WritingModel;
import com.novel.modules.agent.writing.infrastructure.persistence.WritingStore;
import com.novel.modules.agent.writing.infrastructure.client.WritingContentClient;
import com.novel.modules.agent.writing.context.WritingContext;
import com.novel.modules.agent.writing.domain.WritingLimits;
import com.novel.modules.agent.writing.skill.WritingSkillRegistry;
import static com.novel.modules.agent.writing.domain.WritingTaskRules.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.*;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.security.*;
import com.novel.modules.ai.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="novel.ai.writing.enabled",havingValue="true")
public class WritingService {
    private final WritingStore store;
    private final WritingContentClient content;
    private final WritingModel model;
    private final ObjectMapper json;
    private final AiConfigService config;
    private final AiQuotaService quota;
    private final WritingContext contexts;
    private final WritingBudgetFactory budgets;
    private final WritingSkillRegistry skills;
    private final WritingLimits limits;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.novel.modules.agent.writing.skill.WritingSkills personalSkills;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.novel.modules.agent.writing.context.WritingExtendedContext extendedContext;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private WritingDrafts drafts;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private WritingMcp mcp;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private WritingAssistantSettings assistantSettings;

    public ObjectNode create(ObjectNode req,String key) {
        req=req.deepCopy();
        req.retain("chapterId","nodeId","expectedChapterVersion","selection","goal","constraints","profile","skillIds","chapterIds","settingIds","styleIds","includeCumulative","mcpConnectionIds","mcpToolSelections","allowExternalQuery");
        WritingStore.validateKey(key);
        long uid=UserContext.requireUserId();
        long chapter=positive(req,"chapterId"),node=positive(req,"nodeId");
        req.put("contextVersion",2);
        var snapshot=contexts.fetch(chapter,node,req);
        if(req.path("expectedChapterVersion").asLong(-1)!=snapshot.version()) throw WritingStore.error(409,"章节已变化，请刷新后创建");
        String goal=req.path("goal").asText("");
        if(goal.isBlank() || goal.length()>1200) throw WritingStore.error(422,"请填写 1～1200 字的本章目标");
        if(snapshot.content().length()>100000) throw WritingStore.error(422,"当前章节过长，请拆分后创作");
        if(snapshot.outline().length()>4000) throw WritingStore.error(422,"本章大纲最多 4000 字，请精简");
        var selection=req.path("selection");int start=selection.path("start").asInt(-1),end=selection.path("end").asInt(-1);
        WritingText.range(snapshot.content(),start,end);
        if(end-start>8000 || !snapshot.content().substring(start,end).equals(selection.path("original").asText())) throw WritingStore.error(422,"选区原文不一致或过长");
        if(req.path("constraints").isArray() && (req.path("constraints").size()>10 || anyLong(req.path("constraints"),200))) throw WritingStore.error(422,"约束最多 10 条，每条 200 字");
        if(req.path("profile").toString().length()>2000) throw WritingStore.error(422,"创作配置过长");
        req.remove(List.of("skillSnapshots","mcpSnapshots"));
        String requestHash=WritingText.hash(req.toString());
        if(mcp!=null)mcp.freeze(req,Long.parseLong(snapshot.novelId()));
        if(personalSkills!=null)personalSkills.freeze(req,req.has("skillIds")?req.get("skillIds"):json.createArrayNode(),Long.parseLong(snapshot.novelId()));
        else skills.freeze(req,req.has("skillIds")?req.get("skillIds"):json.createArrayNode());
        var t=json.createObjectNode();t.set("input",req.deepCopy());t.set("snapshot",json.valueToTree(snapshot));
        if(assistantSettings!=null)t.set("assistantInstructions",assistantSettings.snapshot(Long.parseLong(snapshot.novelId())));
        if(extendedContext!=null)t.set("extras",extendedContext.freeze(snapshot,req));
        t.put("sourceHash",contexts.fingerprint(snapshot));t.put("admin",UserContext.hasRole("ADMIN"));
        t.put("mock",model.mock());t.put("reservedTokens",0);t.put("usedTokens",0);t.put("modelCalls",0);t.put("toolCalls",0);
        t.putObject("budget").put("maxInputTokens",limits.maxInputTokens()).put("hardTokens",limits.hardTokenBudget()).put("softTokens",limits.softTokenBudget()).put("maxModelCalls",limits.maxModelCalls()).put("maxToolCalls",12);
        return store.create(uid,Long.toString(chapter),key,requestHash,t,()->{
            if(!model.mock()) {var cfg=config.requireUsable();if(!UserContext.hasRole("ADMIN")) quota.acquire(uid,cfg.dailyLimit());}
        });
    }
    public ObjectNode get(String id) {
        long uid=UserContext.requireUserId();var t=store.load(id,uid);contexts.authorize(t);return t;
    }
    public ObjectNode updateTools(String id,ObjectNode request){
        get(id);if(mcp==null)throw WritingStore.error(503,"外部工具服务尚未启用");
        if(!request.path("expectedToolsVersion").isIntegralNumber()||request.path("expectedToolsVersion").asLong()<0||!request.path("mcpToolSelections").isArray())throw WritingStore.error(422,"请提供工具选择和当前工具版本");
        return store.mutate(id,UserContext.requireUserId(),null,"tools_changed",state->{
            if(Set.of("CANCELLED","EXPIRED","ADOPTING","UNDOING","ADOPTED","UNDONE").contains(state.path("status").asText()))throw WritingStore.error(409,"当前会话已结束或正在采纳，不能切换工具");
            if(state.path("toolsVersion").asLong()!=request.path("expectedToolsVersion").asLong())throw WritingStore.error(409,"工具选择已在其他窗口变化，请重新加载");
            var input=(ObjectNode)state.get("input");input.set("mcpToolSelections",request.get("mcpToolSelections").deepCopy());input.put("allowExternalQuery",request.path("allowExternalQuery").asBoolean());
            mcp.freeze(input,Long.parseLong(contexts.asSnapshot(state).novelId()));state.put("toolsVersion",state.path("toolsVersion").asLong()+1);
        });
    }
    public List<Map<String,Object>> recent(long chapter,long node) {contexts.access(chapter,node);return store.recent(UserContext.requireUserId(),chapter);}
    public ObjectNode command(String id,String op,ObjectNode req,String key) {
        get(id);long uid=UserContext.requireUserId();
        Long expected=req.has("expectedTaskVersion")?req.path("expectedTaskVersion").asLong():null;
        var t=store.command(id,uid,expected,op,key,WritingText.hash(req.toString()),state->{
            String status=state.path("status").asText();
            switch(op) {
                case "SELECT_DRAFT" -> {if(!Set.of("READY","FAILED").contains(status))throw WritingStore.error(409,"当前状态不能切换草稿");contexts.ensureSources(state);if(drafts==null)throw WritingStore.error(503,"草稿版本未启用");drafts.select(state,req.path("revisionId").asText());state.put("status","READY");}
                case "CONFIRM" -> {
                    require(status,"AWAITING_CONFIRM");contexts.ensureSources(state);
                    if(!state.path("planHash").asText().equals(req.path("planHash").asText())) throw WritingStore.error(409,"计划已变化");
                    if(req.has("editedPlan")) state.set("plan",validatePlan(req.get("editedPlan")));
                    updateSkills(state, req);
                    state.put("planHash",WritingText.hash(state.path("plan").toString()));state.put("status","QUEUED_DRAFT");
                }
                case "REVISE" -> {
                    require(status,"READY");contexts.ensureSources(state);
                    if(!state.path("currentDraftAttemptId").asText().equals(req.path("draftAttemptId").asText())) throw WritingStore.error(409,"草稿已更新");
                    String instruction=req.path("instruction").asText();
                    if(instruction.isBlank() || instruction.length()>1200) throw WritingStore.error(422,"修改要求最多 1200 字");
                    updateSkills(state, req);
                    state.put("revisionInstruction",instruction);state.put("status","QUEUED_DRAFT");
                }
                case "RETRY" -> {
                    require(status,"FAILED");contexts.ensureSources(state);
                    if(!state.path("activeAttemptId").asText().equals(req.path("failedAttemptId").asText())) throw WritingStore.error(409,"失败尝试已更新");
                    if(state.path("modelCalls").asInt()>=limits.maxModelCalls() || state.path("reservedTokens").asInt()>=limits.hardTokenBudget()) throw WritingStore.error(429,"本任务预算已用完，请新建更小的任务");
                    updateSkills(state, req);
                    state.put("status",state.has("planHash")?"QUEUED_DRAFT":"QUEUED_PLAN");
                }
                case "CANCEL" -> {
                    if(Set.of("ADOPTING","UNDOING","ADOPTED","UNDONE").contains(status)) throw WritingStore.error(409,"采纳事务处理中或已完成，不能取消");
                    state.put("status","CANCELLED");
                }
                case "ADOPT" -> {
                    state.remove("pendingKind");
                    require(status,"READY");contexts.ensureSources(state);
                    if(!state.path("currentDraftAttemptId").asText().equals(req.path("draftAttemptId").asText())) throw WritingStore.error(409,"草稿版本已变化");
                    if(!state.path("check").path("status").asText().equals("OK") && !req.path("acceptUnchecked").asBoolean()) throw WritingStore.error(422,"检查未完成，请明确确认后采纳");
                    String draft=state.path("draft").asText();int from=req.path("draftStart").asInt(-1),to=req.path("draftEnd").asInt(-1);WritingText.range(draft,from,to);
                    if(from==to) throw WritingStore.error(422,"请选择要采纳的草稿内容");
                    var snap=contexts.asSnapshot(state);var selection=state.path("input").path("selection");
                    if(req.path("expectedChapterVersion").asLong(-1)!=snap.version()) throw WritingStore.error(409,"正文已有新编辑，不能自动覆盖");
                    var patch=new Patch(id,state.path("currentDraftAttemptId").asText(),snap.version(),selection.path("start").asInt(),selection.path("end").asInt(),selection.path("original").asText(),draft.substring(from,to),key);
                    state.set("pending",json.valueToTree(patch));state.put("status","ADOPTING");
                }
                case "ADOPT_MERGED" -> {
                    require(status,"READY");contexts.ensureMergeSources(state);
                    if(!state.path("currentDraftAttemptId").asText().equals(req.path("draftAttemptId").asText()))throw WritingStore.error(409,"草稿已变化");
                    if(!state.path("check").path("status").asText().equals("OK")&&!req.path("acceptUnchecked").asBoolean())throw WritingStore.error(422,"请确认未核实的检查结果");
                    var patch=mergePatch(state,req,key);var preview=InternalResults.unwrapBusiness(content.mergePreview(Long.parseLong(contexts.asSnapshot(state).chapterId()),patch),"merge preview");
                    if(preview.currentVersion()!=req.path("expectedChapterVersion").asLong(-1))throw WritingStore.error(409,"正文又有新编辑，请重新预览");
                    state.set("pending",json.valueToTree(patch));state.put("pendingKind","MERGE").put("status","ADOPTING");
                }
                case "UNDO" -> {
                    require(status,"ADOPTED");
                    var applied=state.path("applied");
                    if(!applied.path("adoptionId").asText().equals(req.path("adoptionId").asText())) throw WritingStore.error(409,"采纳记录不一致");
                    state.set("pending",json.valueToTree(new Undo(id,req.path("adoptionId").asText(),req.path("expectedChapterVersion").asLong(-1),key)));state.put("status","UNDOING");
                }
                default -> throw WritingStore.error(422,"不支持的操作");
            }
            state.put("error","");
        });
        if(Set.of("ADOPTING","UNDOING").contains(t.path("status").asText())) return reconcile(t);
        return t;
    }

    public List<ObjectNode> events(String id,long after) {get(id);return store.events(id,after);}
    public ObjectNode export(String id) {get(id);return store.export(id,UserContext.requireUserId());}
    public List<Map<String,Object>> revisions(String id){var task=get(id);if(drafts==null)throw WritingStore.error(503,"版本功能未启用");return drafts.list(task);}
    public ObjectNode branch(String id,ObjectNode req,String key){
        var original=get(id);require(original.path("status").asText(),"READY");contexts.ensureSources(original);
        if(drafts!=null&&!original.has("currentRevisionId"))original=store.mutate(id,UserContext.requireUserId(),null,"revision_archived",s->{require(s.path("status").asText(),"READY");contexts.ensureSources(s);drafts.capture(s);});
        String instruction=req.path("instruction").asText(),name=req.path("name").asText("分支稿");if(instruction.isBlank()||instruction.length()>1200||name.isBlank()||name.length()>100)throw WritingStore.error(422,"分支名称最多100字、修改要求1～1200字");
        var payload=original.deepCopy();payload.remove(List.of("id","version","throughSeq","leaseEpoch","leaseValid","activeAttemptId","error","failedPhase","pending","applied","formatRepairs"));payload.put("branchName",name).put("branchGroup",original.path("branchGroup").asText(id)).put("revisionInstruction",instruction).put("usedTokens",0).put("reservedTokens",0).put("modelCalls",0).put("toolCalls",0);
        return store.create(UserContext.requireUserId(),contexts.asSnapshot(original).chapterId(),key,WritingText.hash(id+req),payload,()->{if(!model.mock()){var cfg=config.requireUsable();if(!UserContext.hasRole("ADMIN"))quota.acquire(UserContext.requireUserId(),cfg.dailyLimit());}},"QUEUED_DRAFT");
    }
    public MergeView mergePreview(String id,ObjectNode req){var task=get(id);require(task.path("status").asText(),"READY");contexts.ensureMergeSources(task);return InternalResults.unwrapBusiness(content.mergePreview(Long.parseLong(contexts.asSnapshot(task).chapterId()),mergePatch(task,req,"preview")),"author merge preview");}
    private MergePatch mergePatch(ObjectNode task,JsonNode req,String key){
        var snapshot=contexts.asSnapshot(task);var selection=task.path("input").path("selection");String draft=task.path("draft").asText();int start=req.path("draftStart").asInt(-1),end=req.path("draftEnd").asInt(-1);WritingText.range(draft,start,end);if(start==end)throw WritingStore.error(422,"请选择草稿内容");
        return new MergePatch(task.path("id").asText(),task.path("currentDraftAttemptId").asText(),req.path("expectedChapterVersion").asLong(-1),snapshot.version(),snapshot.content(),selection.path("start").asInt(),selection.path("end").asInt(),selection.path("original").asText(),draft.substring(start,end),req.path("resolved").asText(""),req.path("approveResolved").asBoolean(),key);
    }

    private void updateSkills(ObjectNode state, JsonNode req) {
        if(!req.has("skillIds")) return;
        if(personalSkills!=null)personalSkills.freeze((ObjectNode)state.get("input"),req.get("skillIds"),Long.parseLong(contexts.asSnapshot(state).novelId()));
        else {skills.validate(req.get("skillIds"));skills.freeze((ObjectNode)state.get("input"),req.get("skillIds"));}
    }

    public void execute(ObjectNode initial) {
        String id=initial.path("id").asText();long uid=UserContext.requireUserId(),epoch=initial.path("leaseEpoch").asLong();
        long started=System.nanoTime();
        try {
            var cfg=model.mock()?null:config.requireUsable();
            var t=store.load(id,uid);contexts.ensureSources(t);
            if(t.path("status").asText().equals("PLANNING")) {
                if(!t.has("recap")) {
                    JsonNode recap;
                    if(contexts.asSnapshot(t).sources().isEmpty()) {
                        var empty=json.createObjectNode().put("summary","本次没有可用前文，按本章大纲和作者目标规划；不推测此前剧情。");
                        empty.putArray("sourceIds");empty.put("generatedBy","system");recap=empty;
                    } else recap=structured(id,uid,epoch,"RECAP","根据来源片段回顾已发生剧情。只输出 JSON，例如：{\"summary\":\"已发生的剧情\",\"sourceIds\":[\"chapter:章节ID\"]}。明确截断范围。\n"+json.valueToTree(contexts.asSnapshot(t).sources()),cfg,started);
                    store.mutate(id,uid,epoch,"state",s->s.set("recap",recap));
                }
                t=store.load(id,uid);
                var plan=structured(id,uid,epoch,"PLAN",contexts.compile(t)+"\n提出一个本章计划，只输出 JSON：{\"summary\":\"本章安排\",\"events\":[\"主要事件\"],\"ending\":\"结尾安排\",\"needsInput\":false}。必要时调用工具。缺信息则 needsInput 为 true，在 summary 说明需补充的信息。",cfg,started);
                var valid=validatePlan(plan);
                store.mutate(id,uid,epoch,"plan_ready",s->{s.set("plan",valid);s.put("planHash",WritingText.hash(valid.toString()));s.put("status","AWAITING_CONFIRM");});return;
            }
            String attempt=UUID.randomUUID().toString();
            store.mutate(id,uid,epoch,"state",s->{store.attempt(attempt,id,"DRAFT");s.put("activeAttemptId",attempt);s.put("partialDraft","");});
            t=store.load(id,uid);var buffer=new StringBuilder();var sent=new StringBuilder();long[] last={System.nanoTime()};
            var budget=budgets.create(id,uid,epoch,cfg,started);
            String prompt=contexts.compile(t)+"\n已确认计划："+t.path("plan")+"\n生成正文，不输出解释。";
            if(t.has("revisionInstruction"))prompt+="\n当前草稿："+t.path("draft").asText()+"\n修改要求："+t.path("revisionInstruction").asText();
            model.draft(prompt,cfg,budget,delta->{
                buffer.append(delta);
                if(buffer.length()>20000) throw WritingStore.error(422,"草稿超出长度上限");
                if(buffer.length()-sent.length()>=512 || System.nanoTime()-last[0]>=500_000_000) {
                    persistPartial(id,uid,epoch,attempt,buffer.toString());sent.setLength(buffer.length());last[0]=System.nanoTime();
                }
            });
            if(buffer.toString().isBlank())throw WritingStore.error(502,"模型返回空草稿");
            final String draft=buffer.toString();
            store.mutate(id,uid,epoch,"state",s->{store.output(attempt,draft,"SUCCEEDED",null);s.put("draft",draft);s.put("partialDraft",draft);s.put("currentDraftAttemptId",attempt);s.put("status","CHECKING");s.remove("revisionInstruction");});
            t=store.load(id,uid);JsonNode check;
            try {
                check=structured(id,uid,epoch,"CHECK",contexts.compile(t)+"\n草稿："+draft+"\n核对人物能力、知情、时间、地点、大纲与格式。使用工具核实前文；只输出 JSON：{\"status\":\"OK\",\"issues\":[{\"message\":\"问题说明\",\"assessment\":\"CONFLICT\",\"sourceId\":\"chapter:章节ID\",\"quote\":\"原文引用\"}],\"coverage\":\"检查范围\"}。无问题时 issues 为 []。只有原文明确支持才报告 CONFLICT，资料不足用 UNKNOWN。",cfg,started);
                t=store.load(id,uid);check=WritingChecks.validate(json,check,contexts.evidenceSnapshot(t),t.path("input"),draft,model.mock());
            } catch(Exception e) {
                budget.check();check=json.createObjectNode().put("status","UNAVAILABLE").put("coverage","检查未完成，不能保证符合设定");
            }
            final var report=check;contexts.ensureSources(store.load(id,uid));
            store.mutate(id,uid,epoch,"check_ready",s->{s.set("check",report);if(drafts!=null)drafts.capture(s);s.put("status","READY");});
        } catch(Exception e) {
            try {store.mutate(id,uid,epoch,"error",t->{
                String phase=t.path("status").asText();String code=e instanceof BusinessException b?b.getCode().toString():"502";
                t.put("failedPhase",phase);t.put("status",code.equals("409")?"INPUT_STALE":"FAILED");
                t.put("error",e instanceof BusinessException?e.getMessage():"写作执行失败，已保留结果，请稍后重试");
                store.output(t.path("activeAttemptId").asText(),t.path("partialDraft").asText(),"FAILED",null);
            });}catch(Exception ignored){}
        }
    }
    private void persistPartial(String id,long uid,long epoch,String attempt,String text) {
        store.mutate(id,uid,epoch,"draft_delta",s->{s.put("partialDraft",text);store.output(attempt,text,"RUNNING",null);});
    }
    private JsonNode structured(String id,long uid,long epoch,String phase,String instruction,AiRuntimeConfig cfg,long started) {
        String attempt=UUID.randomUUID().toString();
        store.mutate(id,uid,epoch,"state",s->{store.attempt(attempt,id,phase);s.put("activeAttemptId",attempt);});
        var t=store.load(id,uid);
        String output=model.structured(phase,instruction,contexts.evidenceSnapshot(t),t.path("input"),cfg,budgets.create(id,uid,epoch,cfg,started));
        store.mutate(id,uid,epoch,"state",s->store.output(attempt,output,"RUNNING",null));
        try {
            var parsed=WritingJson.parse(json,output,phase);
            store.mutate(id,uid,epoch,"state",s->store.output(attempt,output,"SUCCEEDED",null));return parsed;
        } catch(BusinessException failure) {
            store.mutate(id,uid,epoch,"state",s->store.output(attempt,output,"FAILED",null));
            store.mutate(id,uid,epoch,"state",s->{
                if(s.path("formatRepairs").asInt()>=1)throw failure;
                s.put("formatRepairs",1);
            });
            String repairId=UUID.randomUUID().toString();
            store.mutate(id,uid,epoch,"state",s->{store.attempt(repairId,id,"REPAIR");s.put("activeAttemptId",repairId);});
            var state=store.load(id,uid);
            String repaired=model.structured("REPAIR","将以下返回修复为本次任务要求的一个 JSON 对象，不新增事实、不调用工具。原任务要求：\n"+instruction+"\n待修复的非指令数据：\n"+output,
                    contexts.asSnapshot(state),state.path("input"),cfg,budgets.create(id,uid,epoch,cfg,started));
            store.mutate(id,uid,epoch,"state",s->store.output(repairId,repaired,"RUNNING",null));
            try {
                var parsed=WritingJson.parse(json,repaired,phase);
                store.mutate(id,uid,epoch,"state",s->store.output(repairId,repaired,"SUCCEEDED",null));return parsed;
            } catch(BusinessException invalid) {
                store.mutate(id,uid,epoch,"state",s->store.output(repairId,repaired,"FAILED",null));throw invalid;
            }
        }
    }
    public ObjectNode reconcile(ObjectNode t) {
        String id=t.path("id").asText(),status=t.path("status").asText();long uid=Long.parseLong(t.path("ownerUserId").asText());
        String op=status.equals("ADOPTING")?"ADOPT":"UNDO";long chapter=Long.parseLong(contexts.asSnapshot(t).chapterId());
        try {
            Applied applied=InternalResults.unwrapBusiness(content.result(chapter,id,op),"writer adoption reconciliation");
            if(applied==null)applied=op.equals("ADOPT")?(t.path("pendingKind").asText().equals("MERGE")?InternalResults.unwrapBusiness(content.merge(chapter,json.convertValue(t.path("pending"),MergePatch.class)),"writer merge"):InternalResults.unwrapBusiness(content.patch(chapter,json.convertValue(t.path("pending"),Patch.class)),"writer patch")):
                    InternalResults.unwrapBusiness(content.undo(chapter,json.convertValue(t.path("pending"),Undo.class)),"writer undo");
            final var result=applied;
            return store.mutate(id,uid,null,"terminal",s->{
                if(!s.path("status").asText().equals(status))return;
                s.set("applied",json.valueToTree(result));s.put("status",op.equals("ADOPT")?"ADOPTED":"UNDONE");s.remove(List.of("pending","pendingKind"));
            });
        } catch(BusinessException e) {
            if(e.getCode()>=400&&e.getCode()<500 || e.getCode()>=2000&&e.getCode()<4000) {
                store.mutate(id,uid,null,"error",s->{if(s.path("status").asText().equals(status)){s.put("status",op.equals("ADOPT")?"READY":"ADOPTED");s.put("error",e.getMessage());s.remove("pending");}});
                int http=e.getCode()>=2000?409:e.getCode();
                throw new BusinessException(http,e.getMessage(),http);
            }
            return store.load(id,uid);
        } catch(Exception e) {return store.load(id,uid);}
    }

}
