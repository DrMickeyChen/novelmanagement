package com.novel.modules.agent.writing.infrastructure.job;

import com.novel.modules.agent.writing.context.WritingRetrieval;
import com.novel.modules.agent.writing.infrastructure.persistence.WritingStore;
import com.novel.modules.agent.writing.infrastructure.client.WritingContentClient;
import com.fasterxml.jackson.databind.*;
import com.novel.common.internal.InternalResults;
import com.novel.common.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Durable queue: restart marks interrupted jobs failed, never repeats billable embedding automatically. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="novel.ai.writing.enabled",havingValue="true")
public class WritingIndexJobs {
    private final JdbcTemplate jdbc;private final PlatformTransactionManager transactionManager;
    private final WritingRetrieval retrieval;private final WritingContentClient content;private final ObjectMapper json;
    private final AtomicBoolean running=new AtomicBoolean();
    private final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"writer-private-index");t.setDaemon(true);return t;});
    public Map<String,Object> submit(long chapter,JsonNode request) {
        long uid=UserContext.requireUserId(),node=request.path("nodeId").asLong();
        var allowed=InternalResults.unwrapBusiness(content.chapters(chapter,node),"private index submit ACL");
        var ids=request.path("chapterIds");if(!ids.isArray()||ids.isEmpty()||ids.size()>8)throw WritingStore.error(422,"一次选择 1～8 个更早章节");
        for(var id:ids)if(allowed.stream().noneMatch(c->c.get("id").toString().equals(id.asText())))throw WritingStore.error(404,"所选章节不可访问");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM writing_private_index_job WHERE owner_user_id=? AND state IN('QUEUED','RUNNING')",Integer.class,uid)>=2)throw WritingStore.error(429,"已有检索构建任务，请等待完成");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO writing_private_index_job(id,owner_user_id,chapter_id,node_id,request,state,created_at,updated_at) VALUES(?,?,?,?,?,'QUEUED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,uid,chapter,node,request.toString());return get(id);
    }
    public Map<String,Object> get(String id) {
        var rows=jdbc.queryForList("SELECT id,chapter_id,node_id,state,result,error FROM writing_private_index_job WHERE id=? AND owner_user_id=?",id,UserContext.requireUserId());
        if(rows.isEmpty())throw WritingStore.error(404,"构建任务不存在");var row=rows.getFirst();InternalResults.unwrapBusiness(content.access(((Number)row.get("chapter_id")).longValue(),((Number)row.get("node_id")).longValue()),"index job ACL");return row;
    }
    @Scheduled(fixedDelay=1000)
    public void work() {
        if(!running.compareAndSet(false,true))return;
        executor.execute(this::execute);
    }
    private void execute() {
        try {
            jdbc.update("UPDATE writing_private_index_job SET state='FAILED',error='构建中断或超时，请手动重试',updated_at=UTC_TIMESTAMP(3) WHERE state='RUNNING' AND updated_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 10 MINUTE)");
            var job=new TransactionTemplate(transactionManager).execute(tx->{var rows=jdbc.queryForList("SELECT * FROM writing_private_index_job WHERE state='QUEUED' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");if(rows.isEmpty())return null;var row=rows.getFirst();jdbc.update("UPDATE writing_private_index_job SET state='RUNNING',updated_at=UTC_TIMESTAMP(3) WHERE id=?",row.get("id"));return row;});
            if(job==null)return;
            var owner=new LoginUser();owner.setUserId(((Number)job.get("owner_user_id")).longValue());owner.setJti("writer-index:"+job.get("id"));owner.setRoles(List.of("AUTHOR"));owner.setUsername("writing-index-worker");UserContext.set(owner);
            try {
                var request=json.readTree(job.get("request").toString());var ids=new ArrayList<Long>();for(var id:request.path("chapterIds"))ids.add(id.asLong());
                var result=retrieval.build(((Number)job.get("chapter_id")).longValue(),((Number)job.get("node_id")).longValue(),ids,request.path("vectors").asBoolean());
                jdbc.update("UPDATE writing_private_index_job SET state='DONE',result=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND state='RUNNING'",json.writeValueAsString(result),job.get("id"));
            }catch(Exception e){jdbc.update("UPDATE writing_private_index_job SET state='FAILED',error='构建失败，可重新提交；已写入片段仍受权限和版本过滤',updated_at=UTC_TIMESTAMP(3) WHERE id=? AND state='RUNNING'",job.get("id"));}
            finally {UserContext.clear();}
        }catch(org.springframework.jdbc.BadSqlGrammarException ignored){/* Before migration, no jobs are executed. */}
        finally {running.set(false);}
    }
    @jakarta.annotation.PreDestroy public void close(){executor.shutdownNow();}
}
