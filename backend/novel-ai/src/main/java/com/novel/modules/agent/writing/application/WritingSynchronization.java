package com.novel.modules.agent.writing.application;

import com.fasterxml.jackson.databind.*;
import com.novel.common.internal.*;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.security.UserContext;
import com.novel.modules.agent.writing.infrastructure.client.*;
import com.novel.modules.agent.writing.infrastructure.model.WritingDerivedModel;
import com.novel.modules.agent.bookfinder.knowledge.RetrievalModels;
import com.novel.modules.agent.writing.domain.WritingTaskRules;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class WritingSynchronization {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final WritingAssetService assets;
    private final WritingSyncClient content;private final WritingSearchClient search;private final RetrievalModels vectors;
    private final WritingDerivations derivations;private final WritingDerivedModel model;private final WritingDeletion deletion;
    private final com.novel.modules.ai.service.AiQuotaService quota;private final com.novel.modules.ai.service.AiConfigService modelConfig;
    public Map<String,Object> config(long novel){assets.access(novel);var rows=jdbc.queryForList("SELECT * FROM writing_sync_config WHERE owner_user_id=? AND novel_id=?",UserContext.requireUserId(),novel);return rows.isEmpty()?Map.of("enabled",false,"vectors",false,"summaries",false,"version",0):rows.getFirst();}
    public Map<String,Object> configure(long novel,JsonNode req){
        assets.access(novel);boolean enabled=req.path("enabled").asBoolean(),embedding=req.path("vectors").asBoolean(),summaries=req.path("summaries").asBoolean();
        if(enabled&&(embedding||summaries)&&!req.path("acceptCost").asBoolean())throw WritingTaskRules.error(422,"自动向量化或摘要会调用付费 API，请确认费用");
        if(enabled&&embedding&&!vectors.embeddingEnabled())throw WritingTaskRules.error(422,"尚未配置向量模型");
        long uid=UserContext.requireUserId(),version=req.path("expectedVersion").asLong(-1);
        if(version==0){if(jdbc.update("INSERT IGNORE INTO writing_sync_config(owner_user_id,novel_id,enabled,vectors,summaries,version,updated_at) VALUES(?,?,?,?,?,1,UTC_TIMESTAMP(3))",uid,novel,enabled,embedding,summaries)!=1)throw WritingTaskRules.error(409,"配置已变化");}
        else if(jdbc.update("UPDATE writing_sync_config SET enabled=?,vectors=?,summaries=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE owner_user_id=? AND novel_id=? AND version=?",enabled,embedding,summaries,uid,novel,version)!=1)throw WritingTaskRules.error(409,"配置已变化");return config(novel);
    }
    public List<Map<String,Object>> recaps(long novel){assets.access(novel);return jdbc.queryForList("SELECT through_chapter_no,manifest_hash,source_keys,summary,state,updated_at FROM writing_cumulative_recap WHERE owner_user_id=? AND novel_id=? ORDER BY through_chapter_no DESC LIMIT 100",UserContext.requireUserId(),novel);}
    public void update(Map<String,Object> event,Runnable lease)throws Exception{
        update(event,lease,(version,hash)->{});
    }
    public void update(Map<String,Object> event,Runnable lease,java.util.function.BiConsumer<Long,String> fingerprint)throws Exception{
        long uid=UserContext.requireUserId(),novel=((Number)event.get("novel_id")).longValue(),chapter=((Number)event.get("chapter_id")).longValue();
        var row=InternalResults.unwrapBusiness(content.source(chapter),"automatic index ownership");int chapterNo=((Number)row.get("chapter_no")).intValue();String hash=row.get("contentHash").toString();long version=((Number)row.get("content_version")).longValue();
        fingerprint.accept(version,hash);
        var settings=config(novel);
        var previous=jdbc.queryForList("SELECT * FROM writing_chapter_recap WHERE owner_user_id=? AND chapter_id=?",uid,chapter);
        boolean same=Boolean.TRUE.equals(row.get("eligible"))&&!previous.isEmpty()&&((Number)previous.getFirst().get("chapter_no")).intValue()==chapterNo&&previous.getFirst().get("source_hash").equals(hash)&&((Number)previous.getFirst().get("source_version")).longValue()==version&&(!truth(settings.get("summaries"))||previous.getFirst().get("model_identity").equals(model.identity()));
        int invalidateFrom=previous.isEmpty()?chapterNo:Math.min(chapterNo,((Number)previous.getFirst().get("chapter_no")).intValue());
        if(!same){jdbc.update("DELETE FROM writing_cumulative_recap WHERE owner_user_id=? AND novel_id=? AND through_chapter_no>=?",uid,novel,invalidateFrom);jdbc.update("DELETE FROM writing_chapter_recap WHERE owner_user_id=? AND chapter_id=?",uid,chapter);}
        if(!truth(settings.get("enabled"))||!Boolean.TRUE.equals(row.get("eligible"))||row.get("content").toString().isBlank()){lease.run();jdbc.update("DELETE FROM writing_index_receipt WHERE owner_user_id=? AND chapter_id=?",uid,chapter);InternalResults.unwrapBusiness(search.deleteChapter(Long.toString(novel),Long.toString(chapter)),"remove disabled source index");return;}
        String body=row.get("content").toString();boolean embedding=truth(settings.get("vectors"));String identity=embedding?vectors.embeddingIdentity():"";int offset=0;
        List<Source> chunks=new ArrayList<>();for(String text:WritingDerivations.parts(body,1200)){chunks.add(new Source("chapter:"+chapter+":"+offset,Long.toString(chapter),chapterNo,row.get("title").toString(),version,offset,offset+text.length(),body.length()>text.length(),text));offset+=text.length();}
        Runnable fresh=()->{lease.run();var latest=InternalResults.unwrapBusiness(content.source(chapter),"automatic source freshness");if(!hash.equals(latest.get("contentHash"))||version!=((Number)latest.get("content_version")).longValue()||chapterNo!=((Number)latest.get("chapter_no")).intValue()||!row.get("title").equals(latest.get("title"))||!Boolean.TRUE.equals(latest.get("eligible")))throw WritingTaskRules.error(409,"同步来源已变化");var current=config(novel);if(!current.get("version").equals(settings.get("version"))||!truth(current.get("enabled")))throw WritingTaskRules.error(409,"同步配置已变化");};
        var receipts=jdbc.queryForList("SELECT * FROM writing_index_receipt WHERE owner_user_id=? AND chapter_id=?",uid,chapter);
        boolean indexed=!"CHAPTER_REBUILD".equals(event.get("kind"))&&!receipts.isEmpty()&&hash.equals(receipts.getFirst().get("source_hash"))&&version==((Number)receipts.getFirst().get("source_version")).longValue()&&chapterNo==((Number)receipts.getFirst().get("chapter_no")).intValue()&&row.get("title").equals(receipts.getFirst().get("title"))&&identity.equals(receipts.getFirst().get("model_identity"));
        if(!indexed){
            fresh.run();jdbc.update("DELETE FROM writing_index_receipt WHERE owner_user_id=? AND chapter_id=?",uid,chapter);InternalResults.unwrapBusiness(search.deleteChapter(Long.toString(novel),Long.toString(chapter)),"remove obsolete private chunks");
            for(int start=0;start<chunks.size();start+=8){var batch=chunks.subList(start,Math.min(start+8,chunks.size()));fresh.run();var values=embedding?vectors.embed(batch.stream().map(Source::text).toList()):List.<List<Double>>of();fresh.run();if(embedding&&!identity.equals(vectors.embeddingIdentity()))throw WritingTaskRules.error(409,"向量模型配置已变化");var docs=new ArrayList<PrivateChunk>();for(int i=0;i<batch.size();i++)docs.add(new PrivateChunk(Long.toString(novel),batch.get(i),embedding?values.get(i):List.of(),identity));InternalResults.unwrapBusiness(search.put(docs),"automatic private index write");}
            fresh.run();jdbc.update("INSERT INTO writing_index_receipt(owner_user_id,novel_id,chapter_id,source_version,source_hash,chapter_no,title,model_identity,chunks,updated_at) VALUES(?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE source_version=VALUES(source_version),source_hash=VALUES(source_hash),chapter_no=VALUES(chapter_no),title=VALUES(title),model_identity=VALUES(model_identity),chunks=VALUES(chunks),updated_at=UTC_TIMESTAMP(3)",uid,novel,chapter,version,hash,chapterNo,row.get("title"),identity,chunks.size());
        }
        if(truth(settings.get("summaries"))){
            if(!same){var cfg=modelConfig.requireUsable();quota.acquire(uid,cfg.dailyLimit());}
            if(!same){var recap=derivations.chapter(novel,row,fresh);fresh.run();jdbc.update("INSERT INTO writing_chapter_recap(owner_user_id,novel_id,chapter_id,chapter_no,source_version,source_hash,model_identity,summary,state,read_chars,total_chars,updated_at) VALUES(?,?,?,?,?,?,?,?,'READY',?,?,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE chapter_no=VALUES(chapter_no),source_version=VALUES(source_version),source_hash=VALUES(source_hash),model_identity=VALUES(model_identity),summary=VALUES(summary),state='READY',read_chars=VALUES(read_chars),total_chars=VALUES(total_chars),updated_at=UTC_TIMESTAMP(3)",uid,novel,chapter,chapterNo,version,hash,model.identity(),recap.toString(),body.length(),body.length());}
            fold(novel,lease,!same);
        }
    }
    public void fold(long novel,Runnable lease){
        fold(novel,lease,false);
    }
    private void fold(long novel,Runnable lease,boolean quotaAcquired){
        long uid=UserContext.requireUserId();var catalog=InternalResults.unwrapBusiness(content.catalog(novel),"cumulative source ACL");var keys=json.createArrayNode();String prior="";int calls=0;
        for(var source:catalog){if(!Set.of(0,2,4).contains(((Number)source.get("status")).intValue()))continue;
            var recaps=jdbc.queryForList("SELECT * FROM writing_chapter_recap WHERE owner_user_id=? AND chapter_id=? AND state='READY'",uid,source.get("id"));if(recaps.isEmpty())break;var recap=recaps.getFirst();
            if(!Objects.equals(recap.get("source_hash"),source.get("content_hash"))||((Number)recap.get("source_version")).longValue()!=((Number)source.get("content_version")).longValue())break;
            keys.addObject().put("chapterId",source.get("id").toString()).put("chapterNo",((Number)source.get("chapter_no")).intValue()).put("version",((Number)source.get("content_version")).longValue()).put("hash",source.get("content_hash").toString());String manifest=WritingText.hash(keys.toString());int number=((Number)source.get("chapter_no")).intValue();
            var existing=jdbc.queryForList("SELECT * FROM writing_cumulative_recap WHERE owner_user_id=? AND novel_id=? AND through_chapter_no=? AND manifest_hash=? AND state='READY'",uid,novel,number,manifest);
            if(!existing.isEmpty()){prior=existing.getFirst().get("summary").toString();continue;}
            if(++calls>20)throw WritingTaskRules.error(429,"本轮累计摘要达到 20 次调用上限，已保存进度，请手动继续");
            if(!quotaAcquired){var cfg=modelConfig.requireUsable();quota.acquire(uid,cfg.dailyLimit());quotaAcquired=true;}
            var frozen=keys.deepCopy();Runnable fresh=()->{lease.run();var current=InternalResults.unwrapBusiness(content.catalog(novel),"cumulative freshness");for(var key:frozen){var matched=current.stream().filter(s->s.get("id").toString().equals(key.path("chapterId").asText())).findFirst().orElseThrow(()->WritingTaskRules.error(409,"摘要来源已删除"));if(!matched.get("content_hash").equals(key.path("hash").asText())||((Number)matched.get("content_version")).longValue()!=key.path("version").asLong()||!Set.of(0,2,4).contains(((Number)matched.get("status")).intValue()))throw WritingTaskRules.error(409,"摘要来源已变化");}};
            JsonNode chapterRecap;try{chapterRecap=json.readTree(recap.get("summary").toString());}catch(Exception e){throw new IllegalStateException("章节回顾格式错误",e);}
            var result=model.derive(novel,"压缩此前累计回顾与新增章节回顾。返回 summary(最多3000字)、unresolved(最多10条短字符串，每条最多100字)。不新增事实，不把不确定事项写成已确认，不调用工具。",prior+"\n新增章节回顾："+chapterRecap.path("parts"),fresh);
            if(result.path("summary").asText().isBlank()||result.path("summary").asText().length()>3500||!result.path("unresolved").isArray()||result.path("unresolved").size()>10)throw WritingTaskRules.error(502,"累计摘要格式无效");fresh.run();prior=result.toString();
            jdbc.update("INSERT INTO writing_cumulative_recap(owner_user_id,novel_id,through_chapter_no,manifest_hash,source_keys,summary,model_identity,state,updated_at) VALUES(?,?,?,?,?,?,?,'READY',UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE manifest_hash=VALUES(manifest_hash),source_keys=VALUES(source_keys),summary=VALUES(summary),model_identity=VALUES(model_identity),state='READY',updated_at=UTC_TIMESTAMP(3)",uid,novel,number,manifest,keys.toString(),prior,model.identity());
        }
    }
    public static boolean truth(Object value){return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()==1;}
}
