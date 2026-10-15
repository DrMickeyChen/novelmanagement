package com.novel.modules.agent.writing.infrastructure.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.internal.InternalResults;
import com.novel.common.security.*;
import com.novel.modules.agent.writing.application.*;
import com.novel.modules.agent.writing.infrastructure.client.*;
import com.novel.modules.agent.writing.domain.WritingTaskRules;
import com.novel.modules.ai.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Component @RequiredArgsConstructor @ConditionalOnProperty(name="novel.ai.writing.enabled",havingValue="true")
public class WritingExtensionsWorker {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final ObjectMapper json;
    private final WritingSynchronization sync;private final WritingDerivations derivations;private final WritingDeletion deletion;
    private final WritingAssetService assetService;
    private final WritingSyncClient content;private final WritingSearchClient search;private final AiConfigService config;private final AiQuotaService quota;
    private final com.novel.modules.agent.writing.infrastructure.model.WritingDerivedModel derivedModel;
    private final AtomicBoolean busy=new AtomicBoolean();
    private boolean preferStyle;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"writing-extensions");t.setDaemon(true);return t;});
    private final ScheduledExecutorService heartbeats=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"writing-extension-lease");t.setDaemon(true);return t;});
    public static void identity(long uid,boolean maintenance){var user=new LoginUser();user.setUserId(uid);user.setRoles(List.of(maintenance?"ADMIN":"AUTHOR"));user.setUsername("writing-extension-worker");user.setJti("writing-extension-worker");UserContext.set(user);}
    @Scheduled(fixedDelay=1500) public void poll(){if(busy.compareAndSet(false,true))executor.execute(()->{derivedModel.startBatch();try{preferStyle=!preferStyle;if(preferStyle){if(!style())source();}else if(!source())style();}catch(org.springframework.jdbc.BadSqlGrammarException ignored){}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("写作扩展队列本轮未完成，未重放付费调用；类型={}",e.getClass().getSimpleName());}finally{derivedModel.endBatch();UserContext.clear();busy.set(false);}});}
    private boolean source(){
        identity(Long.MAX_VALUE,true);var event=InternalResults.unwrapBusiness(content.claim(),"source queue claim");if(event==null)return false;
        long eventId=((Number)event.get("id")).longValue(),uid=((Number)event.get("owner_user_id")).longValue(),novel=((Number)event.get("novel_id")).longValue();String token=event.get("lease_token").toString(),kind=event.get("kind").toString();
        var valid=new AtomicBoolean(true);long started=System.nanoTime();
        var heartbeat=heartbeats.scheduleAtFixedRate(()->{identity(Long.MAX_VALUE,true);try{if(!Boolean.TRUE.equals(InternalResults.unwrapBusiness(content.heartbeat(eventId,Map.of("leaseToken",token)),"source heartbeat")))valid.set(false);}catch(Exception e){valid.set(false);}finally{UserContext.clear();}},5,5,TimeUnit.SECONDS);
        Runnable lease=()->{if(!valid.get()||Thread.currentThread().isInterrupted()||System.nanoTime()-started>TimeUnit.MINUTES.toNanos(10))throw WritingTaskRules.error(409,"同步租约或执行时间已失效");var previous=UserContext.get();identity(Long.MAX_VALUE,true);try{if(!Boolean.TRUE.equals(InternalResults.unwrapBusiness(content.heartbeat(eventId,Map.of("leaseToken",token)),"source fencing check"))){valid.set(false);throw WritingTaskRules.error(409,"同步租约已失效");}}finally{UserContext.set(previous);}};String outcome="DONE",error="";
        try{
            identity(uid,false);
            if(kind.equals("NOVEL_DELETE")||kind.equals("CHAPTER_DELETE")){
                String chapter=kind.equals("NOVEL_DELETE")?null:event.get("chapter_id").toString();lease.run();
                // Idempotent erasure runs even when automatic paid derivation is disabled.
                if(chapter==null)InternalResults.unwrapBusiness(search.delete(Long.toString(novel)),"deleted book index");else InternalResults.unwrapBusiness(search.deleteChapter(Long.toString(novel),chapter),"deleted chapter index");
                identity(Long.MAX_VALUE,true);var request=new HashMap<String,Object>();request.put("eventId",eventId);request.put("leaseToken",token);var purged=InternalResults.unwrapBusiness(content.purge(request),"source derivation erase");
                var settings=new ArrayList<String>();if(purged.get("storyEntryIds") instanceof List<?> ids)for(var id:ids)settings.add(id.toString());
                identity(uid,false);var tasks=deletion.purge(uid,novel,chapter,settings,eventId);identity(Long.MAX_VALUE,true);for(int startIndex=0;startIndex<tasks.size();startIndex+=100){request.put("taskIds",tasks.subList(startIndex,Math.min(startIndex+100,tasks.size())));InternalResults.unwrapBusiness(content.purge(request),"adoption snapshot erase");}
                jdbc.update("INSERT INTO writing_delete_ledger(id,owner_user_id,novel_id,source_kind,source_id,state,result,created_at,updated_at) VALUES(?,?,?,?,?,'DONE','{}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE state='DONE',updated_at=UTC_TIMESTAMP(3)","event:"+eventId,uid,novel,kind,chapter==null?Long.toString(novel):chapter);
            }else if(kind.equals("SETTING_DELETE")){String setting=event.get("source_id").toString();var tasks=deletion.purgeSetting(uid,novel,setting,eventId);var request=new HashMap<String,Object>();request.put("eventId",eventId);request.put("leaseToken",token);identity(Long.MAX_VALUE,true);for(int startIndex=0;startIndex<tasks.size();startIndex+=100){request.put("taskIds",tasks.subList(startIndex,Math.min(startIndex+100,tasks.size())));InternalResults.unwrapBusiness(content.purge(request),"setting adoption snapshot erase");}jdbc.update("UPDATE writing_delete_ledger SET state='DONE',updated_at=UTC_TIMESTAMP(3) WHERE id=?","event:"+eventId);}
            else if(kind.equals("ASSET_DELETE")){String asset=event.get("source_id").toString();var rows=jdbc.queryForList("SELECT version FROM writing_asset WHERE id=? AND owner_user_id=? AND novel_id=?",asset,uid,novel);if(!rows.isEmpty())assetService.delete(novel,asset,((Number)rows.getFirst().get("version")).longValue());}
            else sync.update(event,lease,(version,hash)->{var previous=UserContext.get();identity(Long.MAX_VALUE,true);try{if(!Boolean.TRUE.equals(InternalResults.unwrapBusiness(content.stamp(eventId,Map.of("leaseToken",token,"version",version,"hash",hash)),"source processing fingerprint")))throw WritingTaskRules.error(409,"同步来源已变化，请等待新事件");}finally{UserContext.set(previous);}});
        }catch(Exception e){outcome=kind.endsWith("DELETE")?"RETRY":"NEEDS_RETRY";error=e instanceof com.novel.common.exception.BusinessException?e.getMessage():"同步失败，已保留进度；请检查服务后重试";}
        finally{heartbeat.cancel(false);identity(Long.MAX_VALUE,true);try{InternalResults.unwrapBusiness(content.finish(eventId,Map.of("leaseToken",token,"state",outcome,"error",error)),"source finish");}finally{UserContext.clear();}}
        return true;
    }
    private boolean style(){
        jdbc.update("UPDATE writing_derivation_job SET state='FAILED',error='分析中断，请手动重新分析',lease_token=NULL,lease_until=NULL WHERE state='RUNNING' AND lease_until<UTC_TIMESTAMP(3)");
        var job=tx.execute(status->{var rows=jdbc.queryForList("SELECT * FROM writing_derivation_job WHERE state='QUEUED' ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED");if(rows.isEmpty())return null;var row=rows.getFirst();String token=UUID.randomUUID().toString();jdbc.update("UPDATE writing_derivation_job SET state='RUNNING',lease_token=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND) WHERE id=?",token,row.get("id"));row.put("lease_token",token);return row;});if(job==null)return false;
        long uid=((Number)job.get("owner_user_id")).longValue(),novel=((Number)job.get("novel_id")).longValue();String id=job.get("id").toString(),token=job.get("lease_token").toString();identity(uid,false);var valid=new AtomicBoolean(true);long start=System.nanoTime();
        var heartbeat=heartbeats.scheduleAtFixedRate(()->{try{if(jdbc.update("UPDATE writing_derivation_job SET lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND) WHERE id=? AND lease_token=? AND state='RUNNING' AND lease_until>UTC_TIMESTAMP(3)",id,token)!=1)valid.set(false);}catch(Exception e){valid.set(false);}},5,5,TimeUnit.SECONDS);
        Runnable lease=()->{if(!valid.get()||System.nanoTime()-start>TimeUnit.MINUTES.toNanos(10)||jdbc.queryForObject("SELECT COUNT(*) FROM writing_derivation_job WHERE id=? AND lease_token=? AND state='RUNNING'",Integer.class,id,token)!=1)throw WritingTaskRules.error(409,"分析已取消或中断");};
        try{var cfg=config.requireUsable();quota.acquire(uid,cfg.dailyLimit());var result=derivations.analyze(novel,job.get("asset_id").toString(),json.readTree(job.get("request").toString()),lease);lease.run();jdbc.update("UPDATE writing_derivation_job SET state='DONE',result=?,lease_token=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND lease_token=? AND state='RUNNING'",json.writeValueAsString(result),id,token);}
        catch(Exception e){jdbc.update("UPDATE writing_derivation_job SET state='FAILED',error=?,lease_token=NULL,lease_until=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND lease_token=?",e instanceof com.novel.common.exception.BusinessException?e.getMessage():"样文分析失败，请手动重新分析",id,token);}
        finally{try{jdbc.update("UPDATE writing_derivation_job SET usage_tokens=? WHERE id=?",derivedModel.batchUsage(),id);}finally{heartbeat.cancel(false);UserContext.clear();}}
        return true;
    }
    @jakarta.annotation.PreDestroy public void close(){executor.shutdownNow();heartbeats.shutdownNow();}
}
