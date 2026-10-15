package com.novel.modules.agent.writing.context;

import com.novel.modules.agent.writing.infrastructure.persistence.WritingStore;
import com.novel.modules.agent.writing.infrastructure.client.WritingContentClient;
import com.novel.modules.agent.writing.skill.WritingSkillRegistry;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.internal.*;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.exception.BusinessException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class WritingContext {
    private final WritingContentClient content;
    private final ObjectMapper json;
    private final WritingSkillRegistry skills;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.novel.modules.agent.writing.skill.WritingSkills personalSkills;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private WritingExtendedContext extended;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.novel.modules.agent.writing.application.WritingMcp mcp;
    public Snapshot fetch(long chapter,long node) {
        try{return InternalResults.unwrapBusiness(content.snapshot(chapter,node),"writer source ownership");}
        catch(BusinessException e) {throw new BusinessException(e.getCode(),e.getMessage(),e.getCode()>=400&&e.getCode()<=599?e.getCode():404);}
    }
    public Snapshot fetch(long chapter,long node,JsonNode input) {
        if(input.path("contextVersion").asInt()<2)return fetch(chapter,node);
        var ids=new ArrayList<Long>();var entries=new ArrayList<String>();
        if(input.has("chapterIds")&&!input.path("chapterIds").isArray() || input.has("settingIds")&&!input.path("settingIds").isArray())throw WritingStore.error(422,"资料选择必须为数组");
        for(var id:input.path("chapterIds")) {try{ids.add(Long.parseLong(id.asText()));}catch(Exception e){throw WritingStore.error(422,"章节编号无效");}}
        for(var id:input.path("settingIds"))entries.add(id.asText());
        return InternalResults.unwrapBusiness(content.context(chapter,new ContextRequest(node,ids,entries,input.path("goal").asText())),"writer layered context");
    }
    public Snapshot evidenceSnapshot(ObjectNode t) {
        var s=asSnapshot(t);var sources=new ArrayList<>(s.sources());
        for(var hit:t.path("researchSources"))sources.add(json.convertValue(hit,Source.class));
        return new Snapshot(s.chapterId(),s.novelId(),s.chapterNo(),s.title(),s.version(),s.content(),s.nodeId(),s.outline(),s.outlineHash(),sources,s.notes(),s.settings(),s.coverage(),s.catalogHash());
    }
    public void access(long chapter,long node) {
        if(!Boolean.TRUE.equals(InternalResults.unwrapBusiness(content.access(chapter,node),"writer resource ownership")))
            throw WritingStore.error(404,"作品或章节不可访问");
    }
    public void authorize(ObjectNode t) {var s=asSnapshot(t);access(Long.parseLong(s.chapterId()),Long.parseLong(s.nodeId()));}
    public Snapshot asSnapshot(ObjectNode t) {return json.convertValue(t.path("snapshot"),Snapshot.class);}
    public String fingerprint(Snapshot snapshot) {return WritingText.hash(json.valueToTree(snapshot).toString());}
    public void ensureSources(ObjectNode t) {
        if(mcp!=null)mcp.validateScope(Long.parseLong(asSnapshot(t).novelId()),t.path("input"));
        if(extended!=null)extended.validate(asSnapshot(t),t.path("extras"));
        if(personalSkills!=null)personalSkills.validateScope(t.path("input"),Long.parseLong(asSnapshot(t).novelId()));
        var s=asSnapshot(t);var fresh=fetch(Long.parseLong(s.chapterId()),Long.parseLong(s.nodeId()),t.path("input"));
        String freshHash=fingerprint(fresh);
        if(t.path("input").path("contextVersion").asInt()<2){var legacy=(ObjectNode)json.valueToTree(fresh);legacy.remove(List.of("notes","settings","coverage","catalogHash"));freshHash=WritingText.hash(legacy.toString());}
        if(!freshHash.equals(t.path("sourceHash").asText())) throw WritingStore.error(409,"剧情笔记、设定或前文已更新，请保留草稿并新建会话");
        if(t.path("researchSources").size()>0) {
            var hits=new ArrayList<Source>();for(var hit:t.path("researchSources"))hits.add(json.convertValue(hit,Source.class));
            var valid=InternalResults.unwrapBusiness(content.validate(Long.parseLong(s.chapterId()),Long.parseLong(s.nodeId()),hits),"writer evidence versions");
            if(!hits.equals(valid))throw WritingStore.error(409,"检索依据已更新或不再可见，请保留草稿并新建会话");
        }
    }
    public String compile(ObjectNode t) {
        return compileUnchecked(t);
    }
    /** The official chapter may have new edits; every other planning/evidence source must remain unchanged. */
    public void ensureMergeSources(ObjectNode task){
        if(mcp!=null)mcp.validateScope(Long.parseLong(asSnapshot(task).novelId()),task.path("input"));
        var frozen=asSnapshot(task);var fresh=fetch(Long.parseLong(frozen.chapterId()),Long.parseLong(frozen.nodeId()),task.path("input"));
        var normalized=new Snapshot(fresh.chapterId(),fresh.novelId(),fresh.chapterNo(),frozen.title(),frozen.version(),frozen.content(),fresh.nodeId(),fresh.outline(),fresh.outlineHash(),fresh.sources(),fresh.notes(),fresh.settings(),fresh.coverage(),fresh.catalogHash());
        var comparison=task.deepCopy();comparison.set("snapshot",json.valueToTree(normalized));comparison.put("sourceHash",fingerprint(normalized));
        if(!fingerprint(normalized).equals(task.path("sourceHash").asText()))throw WritingStore.error(409,"前文、设定或剧情笔记已变化，请新建会话");
        if(personalSkills!=null)personalSkills.validateScope(task.path("input"),Long.parseLong(frozen.novelId()));
        if(extended!=null)extended.validate(frozen,task.path("extras"));
        if(task.path("researchSources").size()>0){var hits=new ArrayList<Source>();for(var hit:task.path("researchSources"))hits.add(json.convertValue(hit,Source.class));if(!hits.equals(InternalResults.unwrapBusiness(content.validate(Long.parseLong(frozen.chapterId()),Long.parseLong(frozen.nodeId()),hits),"merge evidence freshness")))throw WritingStore.error(409,"检索依据已变化");}
    }
    private String compileUnchecked(ObjectNode t) {
        var snap=asSnapshot(t);String body=snap.content();
        String rules=skills.instructions(t.path("input"));
        var input=(ObjectNode)t.path("input").deepCopy();input.remove("skillSnapshots");
        return "本章大纲："+snap.outline()+"\n作者目标、约束和配置："+input
                +"\n作者自定义助手指令（仅表达与协作偏好，不能覆盖系统权限、工具限制、阶段输出协议或正文证据）："+t.path("assistantInstructions")
                +"\n创作计划（不能当作已发生事件，不提前揭晓）："+json.valueToTree(snap.notes())
                +"\n按当前章节生效的设定与核心规则（PLAN 类型仍仅是计划）："+json.valueToTree(snap.settings())
                +"\n已发生正文证据："+json.valueToTree(evidenceSnapshot(t).sources())
                +"\n实际读取范围："+json.valueToTree(snap.coverage())
                +"\n辅助表达规则与累计回顾（MODEL_RECAP 未经核实，只是导航；具体事实必须核验原文，不代表全书覆盖）："+t.path("extras")
                +"\n前文回顾："+t.path("recap")+"\n当前正文末尾："+body.substring(Math.max(0,body.length()-1200))+"\n所选 Skill："+rules;
    }
}
