package com.novel.modules.agent.writing.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.novel.common.internal.*;
import com.novel.common.security.UserContext;
import com.novel.modules.agent.writing.infrastructure.client.WritingSyncClient;
import com.novel.modules.agent.writing.infrastructure.model.WritingDerivedModel;
import com.novel.modules.agent.writing.domain.WritingTaskRules;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class WritingDerivations {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final WritingAssetService assets;
    private final WritingSyncClient content;private final WritingDerivedModel model;
    public Map<String,Object> submit(long novel,String asset,boolean acceptCost){
        var sample=assets.get(novel,asset);if(!sample.get("kind").equals("SAMPLE"))throw WritingTaskRules.error(422,"请先保存样文");
        if(!acceptCost)throw WritingTaskRules.error(422,"请确认模型费用后分析");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM writing_derivation_job WHERE owner_user_id=? AND state IN('QUEUED','RUNNING')",Integer.class,UserContext.requireUserId())>=2)throw WritingTaskRules.error(429,"已有分析任务，请等待");
        String id=UUID.randomUUID().toString();var request=json.createObjectNode().put("version",((Number)sample.get("version")).longValue()).put("hash",sample.get("content_hash").toString());
        jdbc.update("INSERT INTO writing_derivation_job(id,owner_user_id,novel_id,asset_id,kind,request,state,created_at,updated_at) VALUES(?,?,?,?,'STYLE',?,'QUEUED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,UserContext.requireUserId(),novel,asset,request.toString());return job(novel,id);
    }
    public Map<String,Object> job(long novel,String id){assets.access(novel);var rows=jdbc.queryForList("SELECT id,kind,asset_id,state,result,error,usage_tokens,updated_at FROM writing_derivation_job WHERE id=? AND owner_user_id=? AND novel_id=?",id,UserContext.requireUserId(),novel);if(rows.isEmpty())throw WritingTaskRules.error(404,"分析任务不存在");return rows.getFirst();}
    public List<Map<String,Object>> jobs(long novel){assets.access(novel);return jdbc.queryForList("SELECT id,kind,asset_id,state,result,error,usage_tokens,updated_at FROM writing_derivation_job WHERE owner_user_id=? AND novel_id=? ORDER BY created_at DESC LIMIT 20",UserContext.requireUserId(),novel);}
    public Map<String,Object> analyze(long novel,String asset,JsonNode request,Runnable lease){
        var sample=assets.get(novel,asset);String body=sample.get("body").toString();long version=request.path("version").asLong();String hash=request.path("hash").asText();
        Runnable fresh=()->{lease.run();var latest=assets.get(novel,asset);if(((Number)latest.get("version")).longValue()!=version||!hash.equals(latest.get("content_hash")))throw WritingTaskRules.error(409,"样文已变化");};
        var analyses=new ArrayList<JsonNode>();var observations=json.createArrayNode();
        for(String piece:parts(body,18000)){
            var analysis=model.derive(novel,"分析表达特征，不总结剧情。返回 preferences 对象，必须恰含 sentenceLength(SHORT/MIXED/LONG)、narrativeDistance(CLOSE/NEUTRAL/DISTANT)、dialogueRatio(LOW/MEDIUM/HIGH)、pacing(SLOW/MEDIUM/FAST)、emotion(SHOW/TELL/BALANCED)、imagery(REALISTIC/LYRICAL/MINIMAL)。observations 数组最多3条，每条含 quote(不超过200字的逐字样文引用)。",piece,fresh);
            WritingAssetService.validateStyle(analysis.path("preferences"));if(!analysis.path("observations").isArray())throw WritingTaskRules.error(502,"分析引用格式无效");
            for(var item:analysis.path("observations")){String quote=item.path("quote").asText();if(quote.isBlank()||quote.length()>200||!piece.contains(quote))throw WritingTaskRules.error(502,"分析引用不匹配样文");if(observations.size()<12)observations.add(json.createObjectNode().put("quote",quote));}analyses.add(analysis);
        }
        ObjectNode result=json.createObjectNode();ObjectNode preferences=result.putObject("preferences");
        analyses.getFirst().path("preferences").fieldNames().forEachRemaining(key->{var votes=new TreeMap<String,Integer>();for(var part:analyses)votes.merge(part.path("preferences").path(key).asText(),1,Integer::sum);preferences.put(key,votes.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey());});
        result.set("observations",observations);result.put("readChars",body.length()).put("totalChars",body.length()).put("model",model.identity());fresh.run();return assets.styleDraft(novel,asset,version,hash,result);
    }
    public static List<String> parts(String body,int size){var result=new ArrayList<String>();for(int start=0;start<body.length();){int end=Math.min(body.length(),start+size);if(end<body.length()&&Character.isHighSurrogate(body.charAt(end-1)))end--;result.add(body.substring(start,end));start=end;}return result.isEmpty()?List.of(""):result;}
    /** Only exact quoted claims survive; summary prose is explicitly an unverified model recap. */
    public JsonNode chapter(long novel,Map<String,Object> source,Runnable lease){
        String body=source.get("content").toString(),hash=source.get("contentHash").toString();long id=((Number)source.get("id")).longValue(),version=((Number)source.get("content_version")).longValue();
        var summaries=json.createArrayNode();var facts=json.createArrayNode();int start=0;
        Runnable fresh=()->{lease.run();var row=InternalResults.unwrapBusiness(content.source(id),"recap freshness");if(!hash.equals(row.get("contentHash"))||version!=((Number)row.get("content_version")).longValue()||!Boolean.TRUE.equals(row.get("eligible")))throw WritingTaskRules.error(409,"摘要来源已变化");};
        for(String piece:parts(body,18000)){
            var part=model.derive(novel,"回顾已经发生的故事，返回 summary(最多1000字)、facts(最多8条，含 statement 和 quote，quote 必须是200字以内逐字原文)。不得将建议写成事实。",piece,fresh);
            if(part.path("summary").asText().length()>1000||!part.path("facts").isArray()||part.path("facts").size()>8)throw WritingTaskRules.error(502,"章节摘要格式无效");summaries.add(part.path("summary"));
            for(var item:part.path("facts")){String quote=item.path("quote").asText(),statement=item.path("statement").asText();if(quote.isBlank()||quote.length()>200||!piece.contains(quote)||statement.isBlank()||statement.length()>500)throw WritingTaskRules.error(502,"摘要引用无法定位");facts.addObject().put("statement",statement).put("quote",quote).put("sourceId","chapter:"+id+":"+start).put("sourceVersion",version);}
            start+=piece.length();
        }
        fresh.run();var result=json.createObjectNode().put("kind","MODEL_RECAP").put("verified",false).put("sourceHash",hash).put("readChars",body.length()).put("totalChars",body.length());result.set("parts",summaries);result.set("facts",facts);return result;
    }
}
