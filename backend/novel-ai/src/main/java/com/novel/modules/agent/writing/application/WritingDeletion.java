package com.novel.modules.agent.writing.application;

import com.fasterxml.jackson.databind.*;
import com.novel.common.internal.WritingText;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Removes plaintext throughout the AI-owned derivation graph, including historical events. */
@Service @RequiredArgsConstructor
public class WritingDeletion {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    @Transactional public List<String> purge(long owner,long novel,String chapter,List<String> settingIds,long eventId){
        var removed=new ArrayList<String>();
        var previous=jdbc.queryForList("SELECT result FROM writing_delete_ledger WHERE id=? AND owner_user_id=? AND novel_id=?","event:"+eventId,owner,novel);if(!previous.isEmpty())for(var id:parse(previous.getFirst().get("result")).path("taskIds"))removed.add(id.asText());
        for(var row:jdbc.queryForList("SELECT id,payload FROM writing_task WHERE owner_user_id=? AND novel_id=? FOR UPDATE",owner,novel)){
            JsonNode payload=parse(row.get("payload"));
            if(chapter==null||references(payload,"chapterId",chapter)||settingIds.stream().anyMatch(id->references(payload,"id",id)||references(payload,"sourceId","setting:"+id))){String id=row.get("id").toString();eraseTask(id,owner);removed.add(id);}
        }
        if(chapter==null){
            jdbc.update("DELETE r FROM writing_asset_revision r JOIN writing_asset a ON a.id=r.asset_id WHERE a.owner_user_id=? AND a.novel_id=?",owner,novel);
            for(String table:List.of("writing_asset","writing_derivation_job","writing_chapter_recap","writing_index_receipt","writing_cumulative_recap","writing_mcp_audit","writing_mcp_connection","writing_draft_revision","writing_task_dependency","writing_sync_config","writing_assistant_settings"))jdbc.update("DELETE FROM "+table+" WHERE owner_user_id=? AND novel_id=?",owner,novel);
        }else{
            var rows=jdbc.queryForList("SELECT chapter_no FROM writing_chapter_recap WHERE owner_user_id=? AND novel_id=? AND chapter_id=?",owner,novel,chapter);
            // Cumulative rows contain old source text and are erased, not merely hidden.
            jdbc.update("DELETE FROM writing_cumulative_recap WHERE owner_user_id=? AND novel_id=?",owner,novel);
            jdbc.update("DELETE FROM writing_chapter_recap WHERE owner_user_id=? AND novel_id=? AND chapter_id=?",owner,novel,chapter);
            jdbc.update("DELETE FROM writing_index_receipt WHERE owner_user_id=? AND novel_id=? AND chapter_id=?",owner,novel,chapter);
            jdbc.update("DELETE FROM writing_private_index_job WHERE owner_user_id=? AND (chapter_id=? OR JSON_CONTAINS(JSON_EXTRACT(request,'$.chapterIds'),JSON_ARRAY(CAST(? AS UNSIGNED))))",owner,chapter,chapter);
        }
        var result=json.createObjectNode();result.set("taskIds",json.valueToTree(new LinkedHashSet<>(removed)));
        jdbc.update("INSERT INTO writing_delete_ledger(id,owner_user_id,novel_id,source_kind,source_id,state,result,created_at,updated_at) VALUES(?,?,?,?,?,'AI_ERASED',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE result=VALUES(result),updated_at=UTC_TIMESTAMP(3)","event:"+eventId,owner,novel,chapter==null?"NOVEL_DELETE":"CHAPTER_DELETE",chapter==null?Long.toString(novel):chapter,result.toString());return removed;
    }
    @Transactional public void purgeAsset(long owner,String asset){
        for(var row:jdbc.queryForList("SELECT id,payload FROM writing_task WHERE owner_user_id=? FOR UPDATE",owner))if(references(parse(row.get("payload")),"assetId",asset)||references(parse(row.get("payload")),"id",asset))eraseTask(row.get("id").toString(),owner);
    }
    @Transactional public List<String> purgeSetting(long owner,long novel,String setting,long eventId){var removed=new ArrayList<String>();var previous=jdbc.queryForList("SELECT result FROM writing_delete_ledger WHERE id=? AND owner_user_id=? AND novel_id=?","event:"+eventId,owner,novel);if(!previous.isEmpty())for(var id:parse(previous.getFirst().get("result")).path("taskIds"))removed.add(id.asText());for(var row:jdbc.queryForList("SELECT id,payload FROM writing_task WHERE owner_user_id=? AND novel_id=? FOR UPDATE",owner,novel)){var payload=parse(row.get("payload"));if(references(payload,"id",setting)||references(payload,"sourceId","setting:"+setting)){String id=row.get("id").toString();eraseTask(id,owner);removed.add(id);}}var result=json.createObjectNode();result.set("taskIds",json.valueToTree(new LinkedHashSet<>(removed)));jdbc.update("INSERT INTO writing_delete_ledger(id,owner_user_id,novel_id,source_kind,source_id,state,result,created_at,updated_at) VALUES(?,?,?,'SETTING_DELETE',?,'AI_ERASED',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE result=VALUES(result)","event:"+eventId,owner,novel,setting,result.toString());return removed;}
    private void eraseTask(String id,long owner){
        for(String table:List.of("writing_attempt","writing_event"))jdbc.update("DELETE FROM "+table+" WHERE task_id=?",id);
        jdbc.update("DELETE FROM writing_draft_revision WHERE task_id=? AND owner_user_id=?",id,owner);
        jdbc.update("DELETE FROM writing_task_dependency WHERE task_id=? AND owner_user_id=?",id,owner);
        jdbc.update("DELETE FROM writing_mcp_audit WHERE task_id=? AND owner_user_id=?",id,owner);
        jdbc.update("DELETE FROM writing_command WHERE owner_user_id=? AND (scope_id=? OR result_id=?)",owner,id,id);
        jdbc.update("DELETE FROM writing_task WHERE id=? AND owner_user_id=?",id,owner);
    }
    private JsonNode parse(Object value){try{return json.readTree(value.toString());}catch(Exception e){throw new IllegalStateException("派生来源格式损坏",e);}}
    private boolean references(JsonNode value,String key,String id){if(value.isObject()){if(value.path(key).asText().equals(id))return true;var it=value.elements();while(it.hasNext())if(references(it.next(),key,id))return true;}else if(value.isArray())for(var item:value)if(references(item,key,id))return true;return false;}
}
