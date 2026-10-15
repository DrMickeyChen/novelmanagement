package com.novel.internal.controller;

import com.novel.common.result.Result;
import com.novel.common.security.UserContext;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.WritingText;
import com.novel.modules.content.service.NovelAccessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Durable source outbox. Global polling requires an authenticated maintenance identity. */
@RestController @RequiredArgsConstructor @RequestMapping("/internal/writing-sync")
public class InternalWritingSyncController {
    private final JdbcTemplate jdbc;private final NovelAccessGuard guard;
    private void maintenance(){UserContext.requireUserId();if(!UserContext.hasRole("ADMIN"))throw error(403,"维护接口需要受信任的管理身份");}
    @GetMapping("/owned/{novel}") public Result<Boolean> owned(@PathVariable long novel){guard.requireOwned(novel);return Result.success(true);}
    @PostMapping("/claim") @Transactional
    public Result<Map<String,Object>> claim(){
        maintenance();
        jdbc.update("UPDATE writing_source_event SET state=CASE WHEN kind LIKE '%DELETE' THEN 'RETRY' ELSE 'NEEDS_RETRY' END,error='执行中断，删除清理自动重试，模型派生需手动继续',lease_token=NULL,lease_until=NULL,next_attempt_at=UTC_TIMESTAMP(3) WHERE state='RUNNING' AND lease_until<UTC_TIMESTAMP(3)");
        jdbc.update("UPDATE writing_source_event e JOIN writing_source_event f ON f.owner_user_id=e.owner_user_id AND f.novel_id=e.novel_id AND f.chapter_id=e.chapter_id AND f.id<e.id AND f.state='NEEDS_RETRY' JOIN chapter c ON c.id=e.chapter_id AND c.novel_id=e.novel_id JOIN novel n ON n.id=e.novel_id AND n.author_id=e.owner_user_id LEFT JOIN chapter_content b ON b.chapter_id=c.id SET e.state='NEEDS_RETRY',e.error='同一正文前次处理失败，请手动继续，避免重复付费' WHERE e.state='NEW' AND e.kind='CHAPTER_UPDATE' AND f.processed_version=c.content_version AND f.processed_hash=SHA2(COALESCE(b.content,''),256)");
        var rows=jdbc.queryForList("SELECT e.* FROM writing_source_event e WHERE e.state IN('NEW','RETRY') AND e.next_attempt_at<=UTC_TIMESTAMP(3) AND NOT EXISTS(SELECT 1 FROM writing_source_event r WHERE r.owner_user_id=e.owner_user_id AND r.novel_id=e.novel_id AND r.state IN('NEW','RETRY','RUNNING') AND (r.id<e.id OR r.state='RUNNING')) ORDER BY e.id LIMIT 1 FOR UPDATE SKIP LOCKED");
        if(rows.isEmpty())return Result.success(null);
        var row=rows.getFirst();String lease=UUID.randomUUID().toString();
        jdbc.update("UPDATE writing_source_event SET state='RUNNING',lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),attempts=attempts+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",lease,row.get("id"));row.put("lease_token",lease);return Result.success(row);
    }
    @PostMapping("/events/{id}/heartbeat") public Result<Boolean> heartbeat(@PathVariable long id,@RequestBody Map<String,String> req){maintenance();return Result.success(jdbc.update("UPDATE writing_source_event SET lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND) WHERE id=? AND lease_token=? AND state='RUNNING' AND lease_until>UTC_TIMESTAMP(3)",id,req.get("leaseToken"))==1);}
    @PostMapping("/events/{id}/source") public Result<Boolean> stamp(@PathVariable long id,@RequestBody Map<String,Object> req){maintenance();long version=((Number)req.get("version")).longValue();String hash=req.get("hash").toString();if(!hash.matches("[a-f0-9]{64}"))throw error(422,"来源指纹无效");return Result.success(jdbc.update("UPDATE writing_source_event e JOIN chapter c ON c.id=e.chapter_id AND c.novel_id=e.novel_id JOIN novel n ON n.id=e.novel_id AND n.author_id=e.owner_user_id LEFT JOIN chapter_content b ON b.chapter_id=c.id SET e.processed_version=?,e.processed_hash=? WHERE e.id=? AND e.lease_token=? AND e.state='RUNNING' AND e.lease_until>UTC_TIMESTAMP(3) AND c.deleted=0 AND c.content_version=? AND SHA2(COALESCE(b.content,''),256)=?",version,hash,id,req.get("leaseToken"),version,hash)==1);}
    @PostMapping("/events/{id}/finish") public Result<Boolean> finish(@PathVariable long id,@RequestBody Map<String,String> req){
        maintenance();String state=req.get("state");if(!Set.of("DONE","RETRY","NEEDS_RETRY").contains(state))throw error(422,"事件状态无效");
        return Result.success(jdbc.update("UPDATE writing_source_event SET state=?,error=?,lease_token=NULL,lease_until=NULL,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),updated_at=UTC_TIMESTAMP(3) WHERE id=? AND lease_token=? AND state='RUNNING' AND lease_until>UTC_TIMESTAMP(3)",state,req.getOrDefault("error",""),id,req.get("leaseToken"))==1);
    }
    @GetMapping("/events/{novel}") public Result<?> events(@PathVariable long novel){guard.requireOwned(novel);return Result.success(jdbc.queryForList("SELECT id,chapter_id,kind,source_version,state,attempts,error,updated_at FROM writing_source_event WHERE owner_user_id=? AND novel_id=? ORDER BY id DESC LIMIT 100",UserContext.requireUserId(),novel));}
    @PostMapping("/events/{novel}/{id}/retry") public Result<?> retry(@PathVariable long novel,@PathVariable long id){guard.requireOwned(novel);if(jdbc.update("UPDATE writing_source_event SET state='RETRY',error=NULL,next_attempt_at=UTC_TIMESTAMP(3) WHERE id=? AND owner_user_id=? AND novel_id=? AND state='NEEDS_RETRY'",id,UserContext.requireUserId(),novel)!=1)throw error(409,"该事件不能重试");return Result.success();}
    @GetMapping("/source/{chapter}") @Transactional(readOnly=true)
    public Result<Map<String,Object>> source(@PathVariable long chapter){
        var owned=guard.requireOwnedChapter(chapter);
        var row=jdbc.queryForMap("SELECT c.id,c.novel_id,c.chapter_no,c.title,c.content_version,c.status,COALESCE(b.content,'') AS content FROM chapter c LEFT JOIN chapter_content b ON b.chapter_id=c.id WHERE c.id=? AND c.deleted=0",chapter);
        String text=Objects.toString(row.get("content"),"");if(text.length()>150000)throw error(422,"章节超过 15 万字，请拆分后同步");
        row.put("contentHash",WritingText.hash(text));row.put("eligible",Set.of(0,2,4).contains(((Number)row.get("status")).intValue()));return Result.success(row);
    }
    @GetMapping("/catalog/{novel}") public Result<?> catalog(@PathVariable long novel){guard.requireOwned(novel);return Result.success(jdbc.queryForList("SELECT c.id,c.chapter_no,c.title,c.content_version,c.status,SHA2(COALESCE(b.content,''),256) AS content_hash FROM chapter c LEFT JOIN chapter_content b ON b.chapter_id=c.id WHERE c.novel_id=? AND c.deleted=0 ORDER BY c.chapter_no",novel));}
    @PostMapping("/enqueue/{novel}") @Transactional
    public Result<?> enqueue(@PathVariable long novel){
        guard.requireOwned(novel);long uid=UserContext.requireUserId();
        int pending=jdbc.queryForObject("SELECT COUNT(*) FROM writing_source_event WHERE owner_user_id=? AND novel_id=? AND state IN('NEW','RETRY','RUNNING')",Integer.class,uid,novel);
        if(pending>0)throw error(409,"已有同步事件，请等待处理或重试失败事件");
        int count=jdbc.update("INSERT INTO writing_source_event(owner_user_id,novel_id,chapter_id,kind,source_version) SELECT ?,novel_id,id,'CHAPTER_REBUILD',content_version FROM chapter WHERE novel_id=? AND deleted=0 ORDER BY chapter_no",uid,novel);return Result.success(Map.of("queued",count));
    }
    /** Cleanup source-owned derived facts after a durable deletion event has been claimed. */
    @PostMapping("/purge") @Transactional public Result<?> purge(@RequestBody Map<String,Object> req){
        maintenance();long event=((Number)req.get("eventId")).longValue();var rows=jdbc.queryForList("SELECT * FROM writing_source_event WHERE id=? AND lease_token=? AND state='RUNNING' AND lease_until>UTC_TIMESTAMP(3)",event,req.get("leaseToken"));
        if(rows.isEmpty())throw error(409,"删除事件租约失效");var row=rows.getFirst();String kind=row.get("kind").toString();if(!Set.of("CHAPTER_DELETE","NOVEL_DELETE","SETTING_DELETE").contains(kind))throw error(422,"不是删除事件");
        String filter=kind.equals("CHAPTER_DELETE")?" AND source_chapter_id="+((Number)row.get("chapter_id")).longValue():"";
        var ids=kind.equals("SETTING_DELETE")?List.of(row.get("source_id").toString()):jdbc.queryForList("SELECT id FROM writing_story_entry WHERE owner_user_id=? AND novel_id=?"+filter,String.class,row.get("owner_user_id"),row.get("novel_id"));
        for(var id:ids)jdbc.update("DELETE FROM writing_story_revision WHERE entry_id=? AND owner_user_id=?",id,row.get("owner_user_id"));
        if(!kind.equals("SETTING_DELETE")){
            jdbc.update("DELETE FROM writing_story_entry WHERE owner_user_id=? AND novel_id=?"+filter,row.get("owner_user_id"),row.get("novel_id"));
            if(kind.equals("NOVEL_DELETE"))jdbc.update("DELETE FROM chapter_ai_adoption WHERE owner_user_id=? AND novel_id=?",row.get("owner_user_id"),row.get("novel_id"));
            else jdbc.update("DELETE FROM chapter_ai_adoption WHERE owner_user_id=? AND chapter_id=?",row.get("owner_user_id"),row.get("chapter_id"));
        }
        if(req.get("taskIds") instanceof List<?> tasks){if(tasks.size()>1000)throw error(422,"删除任务数量超限");for(var task:tasks){if(!task.toString().matches("[a-f0-9-]{36}"))throw error(422,"删除任务编号无效");jdbc.update("DELETE FROM chapter_ai_adoption WHERE owner_user_id=? AND novel_id=? AND task_id=?",row.get("owner_user_id"),row.get("novel_id"),task);}}
        return Result.success(Map.of("storyEntryIds",ids));
    }
    private BusinessException error(int code,String message){return new BusinessException(code,message,code);}
    @PostMapping("/replay-deletion") @Transactional public Result<?> replay(@RequestBody Map<String,Object> req){
        maintenance();long owner=((Number)req.get("owner_user_id")).longValue(),novel=((Number)req.get("novel_id")).longValue();String source=req.get("source_id").toString(),kind=req.get("source_kind").toString();if(kind.equals("ASSET"))kind="ASSET_DELETE";
        if(owner<=0||novel<0||!Set.of("CHAPTER_DELETE","NOVEL_DELETE","SETTING_DELETE","ASSET_DELETE").contains(kind))throw error(422,"删除账本范围无效");
        Long chapter=null;if(kind.equals("CHAPTER_DELETE")){if(!source.matches("[1-9][0-9]{0,18}"))throw error(422,"章节编号无效");chapter=Long.parseLong(source);jdbc.update("UPDATE chapter c JOIN novel n ON n.id=c.novel_id SET c.deleted=1 WHERE c.id=? AND c.novel_id=? AND n.author_id=?",chapter,novel,owner);}
        if(kind.equals("NOVEL_DELETE"))jdbc.update("UPDATE novel SET deleted=1 WHERE id=? AND author_id=?",novel,owner);
        if(kind.equals("SETTING_DELETE")){jdbc.update("DELETE FROM writing_story_revision WHERE entry_id=? AND owner_user_id=? AND novel_id=?",source,owner,novel);jdbc.update("DELETE FROM writing_story_entry WHERE id=? AND owner_user_id=? AND novel_id=?",source,owner,novel);}
        jdbc.update("INSERT INTO writing_source_event(owner_user_id,novel_id,chapter_id,source_id,kind) VALUES(?,?,?,?,?)",owner,novel,chapter,chapter==null&&!kind.equals("NOVEL_DELETE")?source:null,kind);return Result.success();
    }
}
