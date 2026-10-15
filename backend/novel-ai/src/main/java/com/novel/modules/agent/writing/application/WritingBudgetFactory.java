package com.novel.modules.agent.writing.application;

import com.novel.modules.agent.writing.infrastructure.model.WritingModel;
import com.novel.modules.agent.writing.infrastructure.persistence.WritingStore;
import com.novel.modules.agent.writing.context.WritingContext;
import com.novel.modules.agent.writing.domain.WritingLimits;

import com.fasterxml.jackson.databind.*;
import com.novel.common.internal.WritingContent.*;
import com.novel.modules.ai.service.*;
import com.novel.modules.ai.enums.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component @RequiredArgsConstructor
public class WritingBudgetFactory {
    private final WritingStore store;private final WritingContext context;private final WritingModel model;
    private final ObjectMapper json;private final AiUsageRecorder usage;private final WritingLimits limits;
    public WritingModel.Budget create(String id,long uid,long epoch,AiRuntimeConfig cfg,long started){return new Budget(id,uid,epoch,cfg,started);}
    private class Budget implements WritingModel.Budget {
        private final String id;private final long uid,epoch,started;private final AiRuntimeConfig cfg;
        private int reservation;private long callStarted;
        Budget(String id,long uid,long epoch,AiRuntimeConfig cfg,long started){this.id=id;this.uid=uid;this.epoch=epoch;this.cfg=cfg;this.started=started;}
        @Override public void check() {
            if(Thread.currentThread().isInterrupted() || System.nanoTime()-started>TimeUnit.SECONDS.toNanos(180)) throw WritingStore.error(504,"执行超时，已保留结果");
            var t=store.load(id,uid);
            if(t.path("leaseEpoch").asLong()!=epoch || !t.path("leaseValid").asBoolean() || !WritingStore.RUNNING.contains(t.path("status").asText())) throw WritingStore.error(409,"任务已取消或执行租约失效");
        }
        @Override public void before(String input,int output) {
            check();context.ensureSources(store.load(id,uid));
            int estimate=(input.getBytes(StandardCharsets.UTF_8).length+1)/2+256;
            if(estimate>limits.maxInputTokens())throw WritingStore.error(422,"本次上下文超出预算，请减少手动资料或精简剧情笔记");
            reservation=estimate+output;callStarted=System.nanoTime();
            store.mutate(id,uid,epoch,"state",t->{
                int held=t.path("reservedTokens").asInt();
                if(t.path("modelCalls").asInt()>=limits.maxModelCalls() || held+reservation>limits.hardTokenBudget())throw WritingStore.error(429,"本任务预算已用完");
                t.put("modelCalls",t.path("modelCalls").asInt()+1);t.put("reservedTokens",held+reservation);
            });
        }
        @Override public void tool() {check();store.mutate(id,uid,epoch,"state",t->{if(t.path("toolCalls").asInt()>=12 || t.path("reservedTokens").asInt()>=limits.softTokenBudget())throw WritingStore.error(429,"工具次数或软预算已达上限");t.put("toolCalls",t.path("toolCalls").asInt()+1);});}
        @Override public void external(){check();context.ensureSources(store.load(id,uid));}
        @Override public JsonNode externalPermissions(JsonNode fallback){check();return store.load(id,uid).path("input").deepCopy();}
        @Override public void evidence(List<Source> sources,String mode,String notice) {
            store.mutate(id,uid,epoch,"research",t->{
                var all=new LinkedHashMap<String,Source>();for(var hit:t.path("researchSources")){var s=json.convertValue(hit,Source.class);all.put(s.id(),s);}for(var s:sources)all.put(s.id(),s);
                if(all.size()>12)throw WritingStore.error(422,"检索证据超过 12 条，请缩小查询范围");
                t.set("researchSources",json.valueToTree(all.values()));t.put("retrievalMode",mode);t.put("retrievalNotice",notice);
            });
        }
        @Override public void after(ChatResponse response,Throwable failure) {
            var u=response==null?null:response.tokenUsage();
            int in=u==null||u.inputTokenCount()==null?0:u.inputTokenCount(),out=u==null||u.outputTokenCount()==null?0:u.outputTokenCount();
            int total=u==null||u.totalTokenCount()==null?0:u.totalTokenCount();
            try {store.mutate(id,uid,epoch,"state",t->{
                t.put("usedTokens",t.path("usedTokens").asInt()+total);
                if(total>0)t.put("reservedTokens",Math.max(0,t.path("reservedTokens").asInt()-reservation+total));
                t.put("usageUnknown",t.path("usageUnknown").asBoolean()||total==0&&!model.mock());
                store.recordUsage(t.path("activeAttemptId").asText(),in,out,total,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-callStarted),failure!=null);
            });}catch(Exception ignored){}
            if(!model.mock())usage.record(new AiUsageRecorder.Entry(uid,AiScene.WRITE,Long.parseLong(store.load(id,uid).path("snapshot").path("novelId").asText()),Long.parseLong(store.load(id,uid).path("snapshot").path("chapterId").asText()),cfg.model(),new AiChatUsage(in,out,total),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-callStarted),failure==null?null:AiFailure.UNKNOWN));
        }
    }
}
