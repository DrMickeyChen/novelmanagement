package com.novel.modules.agent.writing.infrastructure.persistence;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.util.*;

/** Same transaction as task snapshots; provides an indexed deletion graph, including retrieved evidence. */
@Component @RequiredArgsConstructor
public class WritingDependencies {
    private final JdbcTemplate jdbc;
    public void record(JsonNode task){
        String id=task.path("id").asText(),owner=task.path("ownerUserId").asText(),novel=task.path("snapshot").path("novelId").asText();
        var refs=new LinkedHashMap<String,String[]>();JsonNode snapshot=task.path("snapshot");add(refs,"CHAPTER",snapshot.path("chapterId"),snapshot.path("version"));
        for(var row:snapshot.path("sources"))add(refs,"CHAPTER",row.path("chapterId"),row.path("version"));for(var row:task.path("researchSources"))add(refs,"CHAPTER",row.path("chapterId"),row.path("version"));for(var row:snapshot.path("coverage"))add(refs,"CHAPTER",row.path("chapterId"),row.path("version"));
        for(var row:snapshot.path("settings"))add(refs,"SETTING",row.path("id"),row.path("version"));
        for(var row:task.path("extras").path("memory").path("sources"))add(refs,"CHAPTER",row.path("chapterId"),row.path("version"));
        for(var row:task.path("extras").path("styles"))add(refs,"ASSET",row.path("assetId"),row.path("version"));for(var row:task.path("input").path("skillSnapshots"))if(row.has("assetId"))add(refs,"ASSET",row.path("assetId"),row.path("version"));
        for(var row:task.path("input").path("mcpSnapshots"))add(refs,"MCP",row.path("id"),row.path("version"));
        jdbc.update("DELETE FROM writing_task_dependency WHERE task_id=? AND owner_user_id=?",id,owner);
        for(var ref:refs.values())jdbc.update("INSERT INTO writing_task_dependency(task_id,owner_user_id,novel_id,source_kind,source_id,source_version) VALUES(?,?,?,?,?,?)",id,owner,novel,ref[0],ref[1],ref[2]);
    }
    private void add(Map<String,String[]> refs,String kind,JsonNode id,JsonNode version){if(!id.asText().isBlank())refs.put(kind+":"+id.asText(),new String[]{kind,id.asText(),version.asText("0")});}
    public void expire(){for(String table:List.of("writing_draft_revision","writing_task_dependency"))jdbc.update("DELETE d FROM "+table+" d JOIN writing_task t ON t.id=d.task_id WHERE t.created_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 30 DAY) AND t.status NOT IN('ADOPTING','UNDOING')");}
}
