package com.novel.modules.agent.bookfinder.controller;

import com.novel.common.exception.BusinessException;
import com.novel.common.result.Result;
import com.novel.common.security.UserContext;
import com.novel.modules.agent.bookfinder.BookFinderTypes.*;
import com.novel.modules.agent.bookfinder.conversation.BookFinderStore;
import com.novel.modules.agent.bookfinder.service.BookFinderAgent;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RestController @RequestMapping("/api/ai/book-finder") @RequiredArgsConstructor @Slf4j
public class BookFinderController {
    private final BookFinderAgent agent;
    private final BookFinderStore store;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,4,60,TimeUnit.SECONDS,
            new SynchronousQueue<>(),r->{var t=new Thread(r,"book-finder-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"book-finder-timeout");t.setDaemon(true);return t;});

    @PostMapping(value="/chat",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        long uid=UserContext.requireUserId();
        var login=UserContext.get();
        var trace=org.slf4j.MDC.getCopyOfContextMap();
        var ticket=agent.prepare(uid,request);
        var emitter=new SseEmitter(65000L);
        var taskRef=new AtomicReference<Thread>();
        var closed=new AtomicBoolean();
        Runnable cancel=()-> { if(closed.compareAndSet(false,true)) { var thread=taskRef.get(); if(thread!=null) thread.interrupt(); } };
        emitter.onCompletion(cancel); emitter.onTimeout(cancel); emitter.onError(e->cancel.run());
        try {
            workers.execute(()-> {
                taskRef.set(Thread.currentThread());
                UserContext.set(login);
                if(trace!=null) org.slf4j.MDC.setContextMap(trace);
                ScheduledFuture<?> timeout=null;
                try {
                    if(closed.get()) { agent.rejected(ticket); return; }
                    timeout=timer.schedule(()-> {
                        if(closed.compareAndSet(false,true)) {
                            var thread=taskRef.get(); if(thread!=null) thread.interrupt();
                            emitter.completeWithError(new TimeoutException("找书请求超时"));
                        }
                    },60,TimeUnit.SECONDS);
                    send(emitter,"session",Map.of("conversationId",ticket.replay()!=null?ticket.replay().conversationId():ticket.state().getId(),"requestId",request.requestId()));
                    var answer=agent.run(ticket,message->{
                        if(closed.get()) throw new CancellationException();
                        try { send(emitter,"progress",Map.of("message",message)); } catch(IOException e) { throw new CancellationException(); }
                    });
                    if(!closed.get()) {
                        // Only validated text is sent; progress is live, model completions are non-streaming.
                        send(emitter,"text",Map.of("text",answer.answer()));
                        send(emitter,"result",answer);
                        send(emitter,"done",Map.of("requestId",request.requestId()));
                        closed.set(true); emitter.complete();
                    }
                } catch(Exception e) {
                    log.warn("找书流式请求失败 | user={} request={} type={}",uid,request.requestId(),e.getClass().getSimpleName());
                    if(!closed.get()) {
                        String message=e instanceof BusinessException?e.getMessage():e instanceof TimeoutException?"模型响应超时，请稍后重试":"找书暂时失败，请稍后重试";
                        try { send(emitter,"error",Map.of("message",message)); } catch(IOException ignored) {}
                        closed.set(true); emitter.complete();
                    }
                } finally {
                    if(timeout!=null) timeout.cancel(false);
                    taskRef.set(null);
                    Thread.interrupted();
                    try { agent.release(ticket); } catch(Exception e) { log.warn("找书会话锁释放失败 | user={}",uid); }
                    UserContext.clear();
                    org.slf4j.MDC.clear();
                }
            });
        } catch(RejectedExecutionException e) {
            try { agent.rejected(ticket); } finally { agent.release(ticket); }
            throw new BusinessException(503,"找书助手繁忙，请稍后重试");
        }
        return emitter;
    }
    @GetMapping("/conversations/{id}")
    public Result<Conversation> conversation(@PathVariable String id) { return Result.success(agent.view(UserContext.requireUserId(),id)); }
    @DeleteMapping("/conversations/{id}")
    public Result<Void> delete(@PathVariable String id) {
        long uid=UserContext.requireUserId(); String token=UUID.randomUUID().toString();
        store.lock(uid,token);
        try { store.delete(uid,id); } finally { store.unlock(uid,token); }
        return Result.success();
    }
    @GetMapping("/quota")
    public Result<Map<String,Object>> quota() {
        long uid=UserContext.requireUserId();
        boolean unlimited=UserContext.hasRole("ADMIN");
        var data=unlimited?new HashMap<String,Object>():new HashMap<String,Object>(store.quota(uid,agent.dailyLimit()));
        data.put("unlimited",unlimited);
        data.put("mock",agent.mock()); return Result.success(data);
    }
    private static void send(SseEmitter emitter,String event,Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data,MediaType.APPLICATION_JSON));
    }
    @GetMapping("/examples")
    public Result<List<String>> examples() {
        UserContext.requireUserId();
        return Result.success(agent.categories().stream().limit(3).map(c->"推荐几本分类为「"+c+"」的小说").toList());
    }
    @PreDestroy public void close() { workers.shutdownNow();timer.shutdownNow(); }
}
