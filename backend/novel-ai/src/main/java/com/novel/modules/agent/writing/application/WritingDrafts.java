package com.novel.modules.agent.writing.application;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.security.UserContext;
import com.novel.modules.agent.writing.domain.WritingTaskRules;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service @RequiredArgsConstructor
public class WritingDrafts {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    /** Called in the same transaction as READY and its event. Immutable parent links form the revision DAG. */
    public void capture(ObjectNode task){
        String id=UUID.randomUUID().toString(),taskId=task.path("id").asText(),group=task.path("branchGroup").asText(taskId),parent=task.path("currentRevisionId").asText(null);
        jdbc.update("INSERT INTO writing_draft_revision(id,owner_user_id,novel_id,task_id,branch_group,parent_id,name,body,check_report,source_hash,draft_attempt_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",id,UserContext.requireUserId(),task.path("snapshot").path("novelId").asText(),taskId,group,parent,task.path("branchName").asText("主稿"),task.path("draft").asText(),task.path("check").toString(),task.path("sourceHash").asText(),task.path("currentDraftAttemptId").asText());
        task.put("branchGroup",group).put("currentRevisionId",id);
    }
    public List<Map<String,Object>> list(ObjectNode task){return jdbc.queryForList("SELECT id,task_id,parent_id,name,body,check_report,source_hash,draft_attempt_id,created_at FROM writing_draft_revision WHERE owner_user_id=? AND novel_id=? AND branch_group=? ORDER BY created_at DESC LIMIT 100",UserContext.requireUserId(),task.path("snapshot").path("novelId").asText(),task.path("branchGroup").asText(task.path("id").asText()));}
    public void select(ObjectNode task,String id){var row=list(task).stream().filter(r->r.get("id").equals(id)).findFirst().orElseThrow(()->WritingTaskRules.error(404,"草稿版本不可访问"));
        if(!row.get("source_hash").equals(task.path("sourceHash").asText()))throw WritingTaskRules.error(409,"草稿基准不同，不能直接切换");
        try{task.put("draft",row.get("body").toString()).put("partialDraft",row.get("body").toString()).put("currentDraftAttemptId",row.get("draft_attempt_id").toString()).put("currentRevisionId",id);task.set("check",json.readTree(row.get("check_report").toString()));}catch(Exception e){throw new IllegalStateException("草稿检查数据无效",e);}
    }
}
