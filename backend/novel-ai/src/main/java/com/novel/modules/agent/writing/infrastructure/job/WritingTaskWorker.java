package com.novel.modules.agent.writing.infrastructure.job;

import com.novel.modules.agent.writing.application.WritingService;
import com.novel.modules.agent.writing.infrastructure.persistence.WritingStore;
import com.novel.modules.agent.writing.context.WritingContext;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.security.*;
import java.util.*;
import java.util.concurrent.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
@Component @RequiredArgsConstructor @Slf4j
@ConditionalOnProperty(name="novel.ai.writing.enabled",havingValue="true")
public class WritingTaskWorker {
    private final WritingStore store;private final WritingService service;private final WritingContext context;
    private final String worker=UUID.randomUUID().toString();
    private final ExecutorService pool=Executors.newFixedThreadPool(2,r->{var t=new Thread(r,"writing-agent");t.setDaemon(true);return t;});
    private final Map<String,Long> active=new ConcurrentHashMap<>();
    @Scheduled(fixedDelay=1000)
    public void dispatch() {
        if(active.size()>=2) return;
        try {
            var task=store.claim(worker);if(task==null)return;
            String id=task.path("id").asText();long epoch=task.path("leaseEpoch").asLong();active.put(id,epoch);
            pool.submit(()->{try{login(task);service.execute(task);}finally{active.remove(id,epoch);UserContext.clear();}});
        } catch(Exception e) {log.warn("写作任务调度暂不可用 | type={}",e.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelay=5000)
    public void heartbeatAndRecover() {
        try {
            active.forEach((id,epoch)->store.heartbeat(id,epoch,worker));
            for(var row:store.recoverable()) {
                long uid=((Number)row.get("owner_user_id")).longValue();var task=store.load(row.get("id").toString(),uid);
                if(Set.of("ADOPTING","UNDOING").contains(task.path("status").asText())) {
                    login(task);try{context.authorize(task);service.reconcile(task);}finally{UserContext.clear();}
                } else store.mutate(task.path("id").asText(),uid,null,"error",t->{
                    if(!WritingStore.RUNNING.contains(t.path("status").asText()) || t.path("leaseValid").asBoolean()) return;
                    t.put("status","FAILED");t.put("error","执行进程中断，保留结果，请手动重试");
                    store.output(t.path("activeAttemptId").asText(),t.path("partialDraft").asText(),"ABANDONED",null);
                });
            }
        } catch(Exception e) {log.warn("写作恢复检查暂不可用 | type={}",e.getClass().getSimpleName());}
    }
    @Scheduled(fixedDelay=60000) public void clean() {try{store.expireAndClean();}catch(Exception e){log.warn("写作保留清理暂不可用 | type={}",e.getClass().getSimpleName());}}
    public static void login(ObjectNode t) {
        var u=new LoginUser();u.setUserId(Long.parseLong(t.path("ownerUserId").asText()));
        // Internal delegated job identity, not a JWT. The trusted service token and resource ACL
        // remain mandatory; IdentityHeaders also requires a nonempty correlation/session ID.
        u.setJti("writing-task:"+t.path("id").asText());
        u.setUsername("writing-worker");
        u.setRoles(t.path("admin").asBoolean()?List.of("AUTHOR","ADMIN"):List.of("AUTHOR"));UserContext.set(u);
    }
    @jakarta.annotation.PreDestroy public void close(){pool.shutdownNow();}
}
