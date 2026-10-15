package com.novel.modules.agent.writing.domain;


import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.novel.common.internal.WritingContent.Snapshot;

/** Validates citation location, not whether a model's interpretation is true. */
public final class WritingChecks {
    private WritingChecks() {}
    public static ObjectNode validate(ObjectMapper json,JsonNode check,Snapshot snap,JsonNode input,String draft,boolean mock) {
        if(!check.isObject() || !check.path("issues").isArray() || check.path("issues").size()>20)
            throw WritingTaskRules.error(502,"检查结果格式错误");
        var copy=(ObjectNode)check.deepCopy();boolean invalid=false;
        for(var issue:copy.path("issues")) {
            if(!(issue instanceof ObjectNode item)) throw WritingTaskRules.error(502,"检查项必须是对象");
            String assessment=item.path("assessment").asText();
            if(!assessment.equals("CONFLICT")) {item.put("assessment","UNKNOWN");continue;}
            String source=item.path("sourceId").asText(),quote=item.path("quote").asText();
            if(input.path("contextVersion").asInt()>=2 && (item.path("draftQuote").asText().isBlank() || !draft.contains(item.path("draftQuote").asText()))) {
                item.put("assessment","UNKNOWN");item.put("unverifiedDraftAnchor",true);invalid=true;continue;
            }
            var found=snap.sources().stream().filter(s->s.id().equals(source)&&!quote.isBlank()&&s.text().contains(quote)).findFirst();
            var note=snap.notes().stream().filter(n->n.id().equals(source)&&!quote.isBlank()&&n.text().contains(quote)).findFirst();
            var setting=snap.settings().stream().filter(s->("setting:"+s.id()).equals(source)&&!quote.isBlank()&&s.text().contains(quote)).findFirst();
            boolean outline=source.equals("outline:"+snap.nodeId())&&!quote.isBlank()&&snap.outline().contains(quote);
            if(found.isPresent()) {
                var s=found.get();int offset=s.text().indexOf(quote);
                item.put("sourceVersion",s.version());item.put("sourceStart",s.start()+offset);item.put("sourceEnd",s.start()+offset+quote.length());
                item.put("sourceType","FACT");
            } else if(note.isPresent()){item.put("sourceHash",note.get().hash());item.put("sourceType","PLAN");}
            else if(setting.isPresent()){item.put("sourceVersion",setting.get().version());item.put("sourceType",setting.get().meaning());}
            else if(outline) {item.put("sourceHash",snap.outlineHash());item.put("sourceStart",snap.outline().indexOf(quote));item.put("sourceType","PLAN");}
            else {item.put("assessment","UNKNOWN");item.put("invalidCitation",true);invalid=true;}
        }
        var issues=(ArrayNode)copy.path("issues");boolean format=false;
        String quotes=input.path("profile").path("dialogueQuotes").asText();
        if(quotes.equals("「……」") && (draft.contains("“")||draft.contains("”"))
                || quotes.equals("“……”") && (draft.contains("「")||draft.contains("」"))) {
            issues.addObject().put("assessment","FORMAT").put("message","对话符号与作者配置不一致，请检查后修改");format=true;
        }
        for(var forbidden:input.path("profile").path("forbiddenExpressions")) {
            if(forbidden.isTextual()&&!forbidden.asText().isBlank()&&draft.contains(forbidden.asText())) {
                issues.addObject().put("assessment","FORMAT").put("message","包含禁用表达："+forbidden.asText());format=true;
            }
        }
        boolean conflicts=false,unknown=false;
        for(var issue:issues){conflicts|=issue.path("assessment").asText().equals("CONFLICT");unknown|=issue.path("assessment").asText().equals("UNKNOWN");}
        copy.put("status",mock?"DEMO":invalid?"INVALID_CITATION":conflicts?"CONFLICTS":format?"FORMAT_ISSUES":unknown?"NEEDS_REVIEW":"OK");
        copy.put("coverage","仅核验本次实际加载的正文、分层剧情笔记、有效设定及检索证据；引文定位通过不等于事实解释正确。计划不能视为已发生事件。");
        return copy;
    }
}
