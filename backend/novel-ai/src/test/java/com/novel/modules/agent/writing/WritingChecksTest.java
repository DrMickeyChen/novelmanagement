package com.novel.modules.agent.writing;

import com.novel.modules.agent.writing.domain.WritingChecks;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.internal.WritingContent.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class WritingChecksTest {
    ObjectMapper json=new ObjectMapper();
    Snapshot snapshot=new Snapshot("2","1",2,"本章",0,"","3","不揭露身份","hash",List.of(new Source("chapter:1","1",1,"前章",4,20,27,true,"主角不会飞行。")));
    @Test void validQuoteAnchorsVersionAndOffsets() throws Exception {
        var check=json.readTree("{\"issues\":[{\"assessment\":\"CONFLICT\",\"sourceId\":\"chapter:1\",\"quote\":\"不会飞行\"}]}");
        var result=WritingChecks.validate(json,check,snapshot,json.createObjectNode(),"",false);
        assertEquals(4,result.path("issues").get(0).path("sourceVersion").asInt());assertEquals(22,result.path("issues").get(0).path("sourceStart").asInt());
    }
    @Test void fabricatedAndOtherSourceQuotesBecomeUnknown() throws Exception {
        var check=json.readTree("{\"issues\":[{\"assessment\":\"CONFLICT\",\"sourceId\":\"chapter:99\",\"quote\":\"不会飞行\"}]}");
        var result=WritingChecks.validate(json,check,snapshot,json.createObjectNode(),"",false);assertEquals("UNKNOWN",result.path("issues").get(0).path("assessment").asText());assertEquals("INVALID_CITATION",result.path("status").asText());
    }
    @Test void dialogueFormatHasDeterministicWarning() {
        var input=json.createObjectNode();input.putObject("profile").put("dialogueQuotes","「……」");var check=json.createObjectNode();check.putArray("issues");
        var result=WritingChecks.validate(json,check,snapshot,input,"“你好。”",false);assertEquals("FORMAT_ISSUES",result.path("status").asText());assertEquals(1,result.path("issues").size());
    }
    @Test void settingAndPlanConflictsRequireBothSourceAndDraftAnchors()throws Exception {
        var setting=new Setting("rule","RULE","境界限制","尚不能御剑","SETTING",2,1,null,true,"核心规则");
        var expanded=new Snapshot("2","1",2,"本章",0,"","3","不揭露身份","hash",snapshot.sources(),List.of(new Note("outline:book","BOOK","全书","第十章揭晓身份","bookhash")),List.of(setting),List.of(),"catalog");
        var input=json.createObjectNode().put("contextVersion",2);
        var check=json.readTree("{\"issues\":[{\"assessment\":\"CONFLICT\",\"sourceId\":\"setting:rule\",\"quote\":\"不能御剑\",\"draftQuote\":\"他御剑飞走\"}]}");
        var report=WritingChecks.validate(json,check,expanded,input,"他御剑飞走",false);assertEquals("CONFLICTS",report.path("status").asText());assertEquals("SETTING",report.path("issues").get(0).path("sourceType").asText());
        var wrong=WritingChecks.validate(json,check,expanded,input,"他步行离开",false);assertEquals("UNKNOWN",wrong.path("issues").get(0).path("assessment").asText());
        var future=json.readTree("{\"issues\":[{\"assessment\":\"CONFLICT\",\"sourceId\":\"outline:book\",\"quote\":\"第十章揭晓身份\",\"draftQuote\":\"揭晓身份\"}]}");
        assertEquals("PLAN",WritingChecks.validate(json,future,expanded,input,"提前揭晓身份",false).path("issues").get(0).path("sourceType").asText());
    }
}
