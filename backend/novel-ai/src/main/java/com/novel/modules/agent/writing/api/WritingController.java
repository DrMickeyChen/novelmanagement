package com.novel.modules.agent.writing.api;

import com.novel.modules.agent.writing.application.WritingService;
import com.novel.modules.agent.writing.infrastructure.persistence.WritingStore;
import com.novel.modules.agent.writing.skill.WritingSkillRegistry;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.result.Result;
import com.novel.common.security.UserContext;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/ai/writing")
public class WritingController {
    private final ObjectProvider<WritingService> services;
    private final WritingSkillRegistry skills;
    private final ScheduledExecutorService streams=Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"writing-sse");t.setDaemon(true);return t;});
    private WritingService service(){var s=services.getIfAvailable();if(s==null)throw WritingStore.error(503,"写作 Agent 尚未启用，请执行迁移并启用配置");return s;}
    @GetMapping("/capabilities") public Result<?> capabilities(){return Result.success(Map.of("enabled",services.getIfAvailable()!=null,"skills",skills.list()));}
    @PostMapping("/tasks") public Result<ObjectNode> create(@RequestBody ObjectNode req,@RequestHeader("Idempotency-Key") String key){return Result.success(service().create(req,key));}
    @GetMapping("/tasks") public Result<?> recent(@RequestParam long chapterId,@RequestParam long nodeId){return Result.success(service().recent(chapterId,nodeId));}
    @GetMapping("/tasks/{id}") public Result<ObjectNode> get(@PathVariable String id){return Result.success(service().get(id));}
    @GetMapping("/tasks/{id}/report") public Result<ObjectNode> report(@PathVariable String id){return Result.success(service().export(id));}
    @GetMapping("/tasks/{id}/revisions") public Result<?> revisions(@PathVariable String id){return Result.success(service().revisions(id));}
    @PutMapping("/tasks/{id}/tools") public Result<?> tools(@PathVariable String id,@RequestBody ObjectNode request){return Result.success(service().updateTools(id,request));}
    @PostMapping("/tasks/{id}/branches") public Result<?> branch(@PathVariable String id,@RequestBody ObjectNode req,@RequestHeader("Idempotency-Key") String key){return Result.success(service().branch(id,req,key));}
    @PostMapping("/tasks/{id}/merge-preview") public Result<?> merge(@PathVariable String id,@RequestBody ObjectNode req){return Result.success(service().mergePreview(id,req));}
    @PostMapping("/tasks/{id}/{operation}") public Result<ObjectNode> command(@PathVariable String id,@PathVariable String operation,@RequestBody ObjectNode req,@RequestHeader("Idempotency-Key") String key){return Result.success(service().command(id,operation.toUpperCase(Locale.ROOT),req,key));}
    @GetMapping(value="/tasks/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id,@RequestParam(defaultValue="0") long afterSeq) {
        var s=service();var initial=s.get(id);var login=UserContext.get();var emitter=new SseEmitter(180000L);
        var cursor=new AtomicLong(Math.max(0,afterSeq));var closed=new AtomicBoolean();
        var ref=new AtomicReference<ScheduledFuture<?>>();long[] heartbeat={0};
        Runnable cleanup=()->{closed.set(true);var f=ref.get();if(f!=null)f.cancel(false);};
        emitter.onCompletion(cleanup);emitter.onTimeout(cleanup);emitter.onError(e->cleanup.run());
        try {emitter.send(SseEmitter.event().name("snapshot").data(initial));cursor.set(initial.path("throughSeq").asLong());}
        catch(Exception e){cleanup.run();return emitter;}
        ref.set(streams.scheduleWithFixedDelay(()->{
            if(closed.get())return;
            UserContext.set(login);
            try {
                for(var event:s.events(id,cursor.get())) {
                    emitter.send(SseEmitter.event().id(event.path("seq").asText()).name(event.path("type").asText()).data(event));cursor.set(event.path("seq").asLong());
                }
                var task=s.get(id);
                if(cursor.get()<task.path("throughSeq").asLong()) {emitter.send(SseEmitter.event().name("snapshot").data(task));cursor.set(task.path("throughSeq").asLong());}
                if(!Set.of("QUEUED_PLAN","QUEUED_DRAFT","PLANNING","GENERATING","CHECKING","ADOPTING","UNDOING").contains(task.path("status").asText())) {
                    emitter.send(SseEmitter.event().name("terminal").data(task));emitter.complete();cleanup.run();
                } else if(System.currentTimeMillis()-heartbeat[0]>15000){emitter.send(SseEmitter.event().comment("heartbeat"));heartbeat[0]=System.currentTimeMillis();}
            } catch(Exception e){emitter.completeWithError(e);cleanup.run();}
            finally{UserContext.clear();}
        },0,1,TimeUnit.SECONDS));
        if(closed.get())cleanup.run();return emitter;
    }
    @PreDestroy public void close(){streams.shutdownNow();}
}
