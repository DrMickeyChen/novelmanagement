package com.novel.modules.agent.writing.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.internal.*;
import com.novel.common.security.UserContext;
import com.novel.modules.agent.writing.infrastructure.client.WritingSyncClient;
import com.novel.modules.agent.writing.domain.WritingTaskRules;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Author-owned samples, confirmed expression profiles and personal Skills. */
@Service @RequiredArgsConstructor
public class WritingAssetService {
    private final JdbcTemplate jdbc;private final ObjectMapper json;private final WritingSyncClient content;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private WritingDeletion deletion;
    public void access(long novel){if(novel==0){UserContext.requireUserId();if(!UserContext.hasRole("AUTHOR")&&!UserContext.hasRole("ADMIN"))throw WritingTaskRules.error(403,"个人 Skill 需要作者身份");return;}if(!Boolean.TRUE.equals(InternalResults.unwrapBusiness(content.owned(novel),"writer asset ownership")))throw WritingTaskRules.error(404,"作品不可访问");}
    public List<Map<String,Object>> list(long novel){access(novel);return jdbc.queryForList("SELECT id,novel_id,kind,name,state,version,content_hash,metadata,body,created_at,updated_at FROM writing_asset WHERE owner_user_id=? AND (novel_id=? OR novel_id=0 AND kind='SKILL') AND deleted=0 ORDER BY updated_at DESC,id",UserContext.requireUserId(),novel);}
    public Map<String,Object> get(long novel,String id){access(novel);var rows=jdbc.queryForList("SELECT * FROM writing_asset WHERE id=? AND owner_user_id=? AND (novel_id=? OR novel_id=0 AND kind='SKILL') AND deleted=0",id,UserContext.requireUserId(),novel);if(rows.isEmpty())throw WritingTaskRules.error(404,"资料不存在");return rows.getFirst();}
    @Transactional public Map<String,Object> save(long novel,String id,ObjectNode req){
        access(novel);long uid=UserContext.requireUserId();Map<String,Object> old=id==null?null:get(novel,id);
        if(old==null&&jdbc.queryForObject("SELECT COUNT(*) FROM writing_asset WHERE owner_user_id=? AND novel_id=?",Integer.class,uid,novel)>=300)throw WritingTaskRules.error(422,"此资料区最多300个条目，请整理后再添加");
        if(old!=null&&((Number)old.get("version")).longValue()!=req.path("expectedVersion").asLong(-1))throw WritingTaskRules.error(409,"资料已变化，请刷新");
        String kind=old==null?req.path("kind").asText():old.get("kind").toString(),name=req.path("name").asText().trim(),body=req.path("body").asText(),state=req.path("state").asText("PENDING");
        if(!Set.of("SAMPLE","STYLE","SKILL").contains(kind)||!Set.of("PENDING","APPROVED","INACTIVE").contains(state)||name.isBlank()||name.length()>100||body.isBlank()||body.length()>(kind.equals("SAMPLE")?60000:6000))throw WritingTaskRules.error(422,"资料分类、名称、正文或状态无效");
        if(novel==0&&!kind.equals("SKILL"))throw WritingTaskRules.error(422,"个人资料区仅允许 Skill，样文绑定作品");
        ObjectNode meta=req.path("metadata").isObject()?(ObjectNode)req.get("metadata").deepCopy():json.createObjectNode();
        if(meta.toString().length()>131072)throw WritingTaskRules.error(422,"参考资料超出上限");
        if(kind.equals("STYLE")){
            if(old==null)throw WritingTaskRules.error(422,"请先从样文生成文风草稿");
            meta.put("sourceId",metadata(old).path("sourceId").asText());meta.put("sourceVersion",metadata(old).path("sourceVersion").asLong());meta.put("sourceHash",metadata(old).path("sourceHash").asText());
            var source=get(novel,meta.path("sourceId").asText());
            if(((Number)source.get("version")).longValue()!=meta.path("sourceVersion").asLong()||!source.get("content_hash").equals(meta.path("sourceHash").asText()))throw WritingTaskRules.error(409,"样文已更新，请重新分析");
            validateStyle(meta.path("preferences"));body=expressionRules(meta.path("preferences"));
        }
        if(old!=null&&!kind.equals("SAMPLE")){var previous=metadata(old);if(!meta.has("import"))meta.set("import",previous.path("import").deepCopy());}
        if(id==null)id=UUID.randomUUID().toString();long version=old==null?1:((Number)old.get("version")).longValue()+1;String hash=WritingText.hash(body+meta);
        if(old==null)jdbc.update("INSERT INTO writing_asset(id,owner_user_id,novel_id,kind,name,body,metadata,state,version,content_hash,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,uid,novel,kind,name,body,meta.toString(),state,version,hash);
        else if(jdbc.update("UPDATE writing_asset SET name=?,body=?,metadata=?,state=?,version=?,content_hash=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND owner_user_id=? AND version=?",name,body,meta.toString(),state,version,hash,id,uid,version-1)!=1)throw WritingTaskRules.error(409,"资料并发修改，请刷新");
        if(kind.equals("SAMPLE"))jdbc.update("UPDATE writing_asset SET state='INACTIVE',updated_at=UTC_TIMESTAMP(3) WHERE owner_user_id=? AND novel_id=? AND kind='STYLE' AND JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.sourceId'))=? AND deleted=0",uid,novel,id);
        var row=get(novel,id);revision(row);return row;
    }
    public JsonNode metadata(Map<String,Object> row){try{return json.readTree(row.get("metadata").toString());}catch(Exception e){throw new IllegalStateException("资产元信息无效",e);}}
    void revision(Map<String,Object> row){try{jdbc.update("INSERT INTO writing_asset_revision(asset_id,version,owner_user_id,novel_id,payload,created_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(3))",row.get("id"),row.get("version"),row.get("owner_user_id"),row.get("novel_id"),json.writeValueAsString(row));}catch(Exception e){throw new IllegalStateException("资产修订保存失败",e);}}
    public List<Map<String,Object>> history(long novel,String id){get(novel,id);return jdbc.queryForList("SELECT version,payload,created_at FROM writing_asset_revision WHERE asset_id=? AND owner_user_id=? ORDER BY version DESC LIMIT 50",id,UserContext.requireUserId());}
    public List<Map<String,Object>> approved(long novel,String kind){return list(novel).stream().filter(r->r.get("kind").equals(kind)&&r.get("state").equals("APPROVED")).toList();}
    @Transactional public void delete(long novel,String id,long expectedVersion){
        access(novel);jdbc.queryForList("SELECT id FROM writing_asset WHERE id=? AND owner_user_id=? FOR UPDATE",id,UserContext.requireUserId());
        var row=get(novel,id);long uid=UserContext.requireUserId();
        if(((Number)row.get("version")).longValue()!=expectedVersion)throw WritingTaskRules.error(409,"资料已变化，请刷新");
        var derived=jdbc.queryForList("SELECT id FROM writing_asset WHERE owner_user_id=? AND kind='STYLE' AND JSON_UNQUOTE(JSON_EXTRACT(metadata,'$.sourceId'))=?",uid,id);
        var ids=new ArrayList<String>();ids.add(id);for(var item:derived)ids.add(item.get("id").toString());
        for(String asset:ids){
            if(deletion!=null)deletion.purgeAsset(uid,asset);
            // Erase historical bodies too; only deletion metadata survives.
            jdbc.update("DELETE FROM writing_asset_revision WHERE asset_id=? AND owner_user_id=?",asset,uid);
            jdbc.update("DELETE FROM writing_derivation_job WHERE asset_id=? AND owner_user_id=?",asset,uid);
            jdbc.update("DELETE FROM writing_asset WHERE id=? AND owner_user_id=?",asset,uid);
        }
        jdbc.update("INSERT IGNORE INTO writing_delete_ledger(id,owner_user_id,novel_id,source_kind,source_id,state,result,created_at,updated_at) VALUES(?,?,?,'ASSET',?,'DONE','{}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))","asset:"+id,uid,row.get("novel_id"),id);
    }
    public static void validateStyle(JsonNode prefs){
        var allowed=Map.of("sentenceLength",Set.of("SHORT","MIXED","LONG"),"narrativeDistance",Set.of("CLOSE","NEUTRAL","DISTANT"),"dialogueRatio",Set.of("LOW","MEDIUM","HIGH"),"pacing",Set.of("SLOW","MEDIUM","FAST"),"emotion",Set.of("SHOW","TELL","BALANCED"),"imagery",Set.of("REALISTIC","LYRICAL","MINIMAL"));
        if(!prefs.isObject()||prefs.size()!=allowed.size())throw WritingTaskRules.error(422,"文风偏好结构无效");
        allowed.forEach((key,values)->{if(!values.contains(prefs.path(key).asText()))throw WritingTaskRules.error(422,"文风偏好无效："+key);});
    }
    public static String expressionRules(JsonNode prefs){validateStyle(prefs);return "表达参考，仅约束句式和叙述，不引入样文剧情：句长="+prefs.path("sentenceLength").asText()+"；叙述距离="+prefs.path("narrativeDistance").asText()+"；对话比例="+prefs.path("dialogueRatio").asText()+"；节奏="+prefs.path("pacing").asText()+"；情绪表达="+prefs.path("emotion").asText()+"；意象="+prefs.path("imagery").asText()+"。";}
    /** Called only after validated model analysis; drafts require explicit author approval. */
    @Transactional public Map<String,Object> styleDraft(long novel,String sampleId,long sourceVersion,String sourceHash,JsonNode analysis){
        access(novel);jdbc.queryForList("SELECT id FROM writing_asset WHERE id=? AND owner_user_id=? FOR UPDATE",sampleId,UserContext.requireUserId());
        var source=get(novel,sampleId);if(!source.get("kind").equals("SAMPLE")||((Number)source.get("version")).longValue()!=sourceVersion||!source.get("content_hash").equals(sourceHash))throw WritingTaskRules.error(409,"样文已变化，分析结果未保存");
        validateStyle(analysis.path("preferences"));String raw=source.get("body").toString();
        if(!analysis.path("observations").isArray()||analysis.path("observations").size()>12)throw WritingTaskRules.error(502,"文风分析格式无效");
        for(var item:analysis.path("observations")){String quote=item.path("quote").asText();if(quote.isBlank()||quote.length()>200||!raw.contains(quote))throw WritingTaskRules.error(502,"样文分析引用不能定位");}
        var meta=(ObjectNode)analysis.deepCopy();meta.put("sourceId",sampleId).put("sourceVersion",sourceVersion).put("sourceHash",sourceHash);
        String id=UUID.randomUUID().toString(),body=expressionRules(meta.path("preferences"));
        String name="文风分析 · "+source.get("name");if(name.length()>100)name=name.substring(0,100);
        jdbc.update("INSERT INTO writing_asset(id,owner_user_id,novel_id,kind,name,body,metadata,state,version,content_hash,created_at,updated_at) VALUES(?,?,?,'STYLE',?,?,?,'PENDING',1,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,UserContext.requireUserId(),novel,name,body,meta.toString(),WritingText.hash(body+meta));var row=get(novel,id);revision(row);return row;
    }
}
