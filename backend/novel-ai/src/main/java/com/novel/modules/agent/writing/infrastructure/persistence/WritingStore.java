package com.novel.modules.agent.writing.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.WritingText;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Consumer;

/** Database is the authority. State, outputs and events are committed together. */
@Repository @RequiredArgsConstructor
public class WritingStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private WritingDependencies dependencies;
    public static final Set<String> RUNNING=com.novel.modules.agent.writing.domain.WritingTaskRules.RUNNING;

    public ObjectNode load(String id,long uid) {
        var rows=jdbc.queryForList("SELECT *,lease_until>UTC_TIMESTAMP(3) AS lease_valid FROM writing_task WHERE id=? AND owner_user_id=?",id,uid);
        if(rows.isEmpty()) throw error(404,"任务不存在");
        return decode(rows.getFirst());
    }
    private ObjectNode decode(Map<String,Object> row) {
        try {
            var t=(ObjectNode)json.readTree((String)row.get("payload"));
            t.put("id",row.get("id").toString()); t.put("ownerUserId",row.get("owner_user_id").toString());
            t.put("status",row.get("status").toString());t.put("version",((Number)row.get("row_version")).longValue());
            t.put("throughSeq",((Number)row.get("last_event_seq")).longValue());
            t.put("leaseEpoch",((Number)row.get("lease_epoch")).longValue());
            t.put("leaseValid",Boolean.TRUE.equals(row.get("lease_valid")) || Objects.equals(row.get("lease_valid"),1L) || Objects.equals(row.get("lease_valid"),1));
            return t;
        } catch(Exception e) {throw new IllegalStateException("Invalid persisted writer task",e);}
    }
    private ObjectNode locked(String id,long uid) {
        var rows=jdbc.queryForList("SELECT *,lease_until>UTC_TIMESTAMP(3) AS lease_valid FROM writing_task WHERE id=? AND owner_user_id=? FOR UPDATE",id,uid);
        if(rows.isEmpty()) throw error(404,"任务不存在"); return decode(rows.getFirst());
    }
    public ObjectNode create(long uid,String chapter,String key,String hash,ObjectNode payload,Runnable quota) {
        return create(uid,chapter,key,hash,payload,quota,"QUEUED_PLAN");
    }
    public ObjectNode create(long uid,String chapter,String key,String hash,ObjectNode payload,Runnable quota,String initialStatus) {
        if(!Set.of("QUEUED_PLAN","QUEUED_DRAFT").contains(initialStatus))throw error(422,"创建状态无效");
        return tx.execute(s->{
            gate(0);gate(uid);
            var existing=command(uid,chapter,"CREATE",key,hash);
            if(existing!=null) return load(existing,uid);
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM writing_task WHERE owner_user_id=? AND status NOT IN('CANCELLED','INPUT_STALE','EXPIRED','ADOPTED','UNDONE') AND created_at>DATE_SUB(UTC_TIMESTAMP(),INTERVAL 30 DAY)",Integer.class,uid);
            if(count>=5) throw error(429,"最多保留 5 个活跃任务，请先取消旧任务");
            int queued=jdbc.queryForObject("SELECT COUNT(*) FROM writing_task WHERE status IN('QUEUED_PLAN','QUEUED_DRAFT')",Integer.class);
            if(queued>=20) throw error(429,"写作任务繁忙，请稍后重试");
            quota.run(); String id=UUID.randomUUID().toString();payload.put("id",id);payload.put("status",initialStatus);
            jdbc.update("INSERT INTO writing_task(id,owner_user_id,chapter_id,novel_id,status,payload,created_at,updated_at) VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    id,uid,chapter,payload.path("snapshot").path("novelId").asText(),initialStatus,payload.toString());
            remember(uid,chapter,"CREATE",key,hash,id);var t=locked(id,uid);save(t,"state",null);return t;
        });
    }
    private void gate(long uid) {
        // Acquire an exclusive lock directly. INSERT IGNORE followed by lock upgrade deadlocks.
        jdbc.update("INSERT INTO writing_gate(owner_user_id) VALUES(?) ON DUPLICATE KEY UPDATE owner_user_id=owner_user_id",uid);
    }
    public ObjectNode command(String id,long uid,Long expected,String op,String key,String hash,Consumer<ObjectNode> update) {
        return tx.execute(s->{
            if(Set.of("CONFIRM","REVISE","RETRY").contains(op)) gate(0);
            var t=locked(id,uid);
            String done=command(uid,id,op,key,hash);
            if(done!=null) return t;
            if(expected==null || t.path("version").asLong()!=expected) throw error(409,"任务状态已变化，请刷新");
            update.accept(t);
            if(Set.of("QUEUED_PLAN","QUEUED_DRAFT").contains(t.path("status").asText())) {
                int queued=jdbc.queryForObject("SELECT COUNT(*) FROM writing_task WHERE status IN('QUEUED_PLAN','QUEUED_DRAFT')",Integer.class);
                if(queued>=20) throw error(429,"任务队列已满，请稍后重试");
            }
            save(t,"state",null);remember(uid,id,op,key,hash,id);return t;
        });
    }
    private String command(long uid,String scope,String op,String key,String hash) {
        validateKey(key);
        var rows=jdbc.queryForList("SELECT request_hash,result_id FROM writing_command WHERE owner_user_id=? AND scope_id=? AND operation=? AND command_key=?",uid,scope,op,key);
        if(rows.isEmpty()) return null;
        if(!hash.equals(rows.getFirst().get("request_hash"))) throw error(409,"幂等键对应的请求不同");
        return (String)rows.getFirst().get("result_id");
    }
    private void remember(long uid,String scope,String op,String key,String hash,String id) {
        jdbc.update("INSERT INTO writing_command VALUES(?,?,?,?,?,?,UTC_TIMESTAMP(3))",uid,scope,op,key,hash,id);
    }
    public ObjectNode mutate(String id,long uid,Long epoch,String type,Consumer<ObjectNode> update) {
        return tx.execute(s->{
            var t=locked(id,uid);
            if(epoch!=null && (t.path("leaseEpoch").asLong()!=epoch || !t.path("leaseValid").asBoolean() || !RUNNING.contains(t.path("status").asText())))
                throw error(409,"执行已失效或被取消");
            update.accept(t);save(t,type,t.path("activeAttemptId").asText(null));return t;
        });
    }
    private void save(ObjectNode t,String type,String attempt) {
        if(dependencies!=null)dependencies.record(t);
        long version=t.path("version").asLong()+1,seq=t.path("throughSeq").asLong()+1;
        t.put("version",version);t.put("throughSeq",seq);
        jdbc.update("UPDATE writing_task SET status=?,row_version=?,payload=?,last_event_seq=?,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                t.path("status").asText(),version,t.toString(),seq,t.path("id").asText());
        var event=json.createObjectNode().put("taskId",t.path("id").asText()).put("seq",seq).put("taskVersion",version).put("type",type);
        if(attempt!=null) event.put("attemptId",attempt);
        // Events contain committed state, enabling replay without depending on process memory.
        event.set("payload",t.deepCopy());
        jdbc.update("INSERT INTO writing_event VALUES(?,?,?,?,?,UTC_TIMESTAMP(3))",t.path("id").asText(),seq,type,attempt,event.toString());
    }
    public ObjectNode claim(String worker) {
        return tx.execute(s->{
            gate(0);
            int active=jdbc.queryForObject("SELECT COUNT(*) FROM writing_task WHERE status IN('PLANNING','GENERATING','CHECKING') AND lease_until>UTC_TIMESTAMP(3)",Integer.class);
            if(active>=2) return null;
            var rows=jdbc.queryForList("SELECT t.*,t.lease_until>UTC_TIMESTAMP(3) AS lease_valid FROM writing_task t WHERE t.status IN('QUEUED_PLAN','QUEUED_DRAFT') AND NOT EXISTS(SELECT 1 FROM writing_task r WHERE r.owner_user_id=t.owner_user_id AND r.status IN('PLANNING','GENERATING','CHECKING') AND r.lease_until>UTC_TIMESTAMP(3)) ORDER BY t.created_at LIMIT 1 FOR UPDATE");
            if(rows.isEmpty()) return null;
            var t=decode(rows.getFirst());String status=t.path("status").asText().equals("QUEUED_PLAN")?"PLANNING":"GENERATING";
            long epoch=t.path("leaseEpoch").asLong()+1;
            jdbc.update("UPDATE writing_task SET lease_epoch=?,lease_owner=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 20 SECOND) WHERE id=?",epoch,worker,t.path("id").asText());
            t.put("leaseEpoch",epoch);t.put("status",status);t.put("leaseValid",true);save(t,"state",null);return t;
        });
    }
    public void heartbeat(String id,long epoch,String worker) {
        jdbc.update("UPDATE writing_task SET lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 20 SECOND) WHERE id=? AND lease_epoch=? AND lease_owner=? AND lease_until>UTC_TIMESTAMP(3) AND status IN('PLANNING','GENERATING','CHECKING')",id,epoch,worker);
    }
    public void attempt(String id,String task,String phase) {
        jdbc.update("INSERT INTO writing_attempt VALUES(?,?,?,'RUNNING','',NULL,UTC_TIMESTAMP(3),NULL)",id,task,phase);
    }
    public void output(String attempt,String text,String status,String usage) {
        jdbc.update("UPDATE writing_attempt SET output_text=?,status=?,usage_json=COALESCE(?,usage_json),finished_at=IF(?='RUNNING',NULL,UTC_TIMESTAMP(3)) WHERE id=? AND status='RUNNING'",text,status,usage,status,attempt);
    }
    public void recordUsage(String attempt,int input,int output,int total,long duration,boolean failed) {
        var rows=jdbc.queryForList("SELECT usage_json FROM writing_attempt WHERE id=? FOR UPDATE",attempt);
        if(rows.isEmpty())return;
        ObjectNode data=json.createObjectNode();
        if(rows.getFirst().get("usage_json")!=null)try{data=(ObjectNode)json.readTree(rows.getFirst().get("usage_json").toString());}catch(Exception e){throw new IllegalStateException(e);}
        data.put("calls",data.path("calls").asInt()+1);data.put("inputTokens",data.path("inputTokens").asInt()+input);
        data.put("outputTokens",data.path("outputTokens").asInt()+output);data.put("totalTokens",data.path("totalTokens").asInt()+total);
        data.put("durationMs",data.path("durationMs").asLong()+duration);data.put("usageUnknown",data.path("usageUnknown").asBoolean()||total==0);
        data.put("failures",data.path("failures").asInt()+(failed?1:0));
        jdbc.update("UPDATE writing_attempt SET usage_json=? WHERE id=?",data.toString(),attempt);
    }
    public List<ObjectNode> events(String id,long after) {
        return jdbc.query("SELECT payload FROM writing_event WHERE task_id=? AND seq>? ORDER BY seq LIMIT 100",(r,n)->{
            try{return (ObjectNode)json.readTree(r.getString(1));}catch(Exception e){throw new IllegalStateException(e);}
        },id,after);
    }
    public List<Map<String,Object>> recoverable() {
        return jdbc.queryForList("SELECT id,owner_user_id,status FROM writing_task WHERE (status IN('PLANNING','GENERATING','CHECKING') AND lease_until<UTC_TIMESTAMP(3)) OR status IN('ADOPTING','UNDOING') LIMIT 20");
    }
    public List<Map<String,Object>> recent(long uid,long chapter) {
        return jdbc.queryForList("SELECT id,status,LEFT(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.input.goal')),120) AS goal,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%sZ') AS createdAt,DATE_FORMAT(updated_at,'%Y-%m-%dT%H:%i:%sZ') AS updatedAt FROM writing_task WHERE owner_user_id=? AND chapter_id=? AND created_at>DATE_SUB(UTC_TIMESTAMP(),INTERVAL 30 DAY) ORDER BY created_at DESC,id DESC LIMIT 10",uid,chapter);
    }
    public ObjectNode export(String id,long uid) {
        var result=load(id,uid).deepCopy();
        result.set("events",json.valueToTree(events(id,0)));
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM writing_event WHERE task_id=?",Integer.class,id);
        result.put("retainedEventCount",count);result.put("eventsTruncated",count>100);
        result.set("attempts",json.valueToTree(jdbc.queryForList("SELECT id,phase,status,usage_json,created_at,finished_at FROM writing_attempt WHERE task_id=? ORDER BY created_at,id",id)));
        result.put("reportType","execution-trace");
        result.put("qualityEvaluated",false);
        return result;
    }
    public void expireAndClean() {
        jdbc.update("UPDATE writing_task SET status='EXPIRED',row_version=row_version+1 WHERE status IN('AWAITING_CONFIRM','READY','FAILED','QUEUED_PLAN','QUEUED_DRAFT') AND updated_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 7 DAY)");
        jdbc.update("UPDATE writing_task SET status='FAILED',row_version=row_version+1 WHERE status IN('QUEUED_PLAN','QUEUED_DRAFT') AND updated_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 60 SECOND)");
        jdbc.update("DELETE FROM writing_event WHERE created_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 7 DAY)");
        if(dependencies!=null)dependencies.expire();
        jdbc.update("DELETE a FROM writing_attempt a JOIN writing_task t ON a.task_id=t.id WHERE t.created_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 30 DAY) AND t.status NOT IN('ADOPTING','UNDOING')");
        jdbc.update("DELETE FROM writing_task WHERE created_at<DATE_SUB(UTC_TIMESTAMP(),INTERVAL 30 DAY) AND status NOT IN('ADOPTING','UNDOING')");
    }
    public static void validateKey(String key) {if(key==null || key.isBlank() || key.length()>100) throw error(422,"缺少有效操作键");}
    public static BusinessException error(int code,String text) {return new BusinessException(code,text,code);}
}
