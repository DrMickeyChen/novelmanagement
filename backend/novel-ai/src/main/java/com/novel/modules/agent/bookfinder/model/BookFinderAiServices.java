package com.novel.modules.agent.bookfinder.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.novel.common.exception.BusinessException;
import com.novel.modules.agent.bookfinder.service.BookFinderAgent.Ticket;
import com.novel.modules.agent.bookfinder.conversation.ConversationChatMemoryStore;
import com.novel.modules.agent.infrastructure.model.LangChain4jModelFactory;
import com.novel.modules.ai.enums.AiFailure;
import com.novel.modules.ai.service.*;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.*;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** AI Services owns tool round trips; the business executor owns every tool's permissions. */
@Component @RequiredArgsConstructor
public class BookFinderAiServices {
    private final ObjectMapper json;
    private final LangChain4jModelFactory factory;
    private final AiUsageRecorder recorder;
    @FunctionalInterface public interface CheckedTool { ObjectNode execute(String name,String arguments) throws Exception; }
    public static final class Stats {
        public int calls,tokens,promptTokens,completionTokens;
        public String finishReason="unknown";
    }
    public class Invocation {
        private final BookFinderAssistant assistant,repairAssistant;
        private final String memoryKey;
        private Invocation(BookFinderAssistant assistant,BookFinderAssistant repairAssistant,String key) {
            this.assistant=assistant; this.repairAssistant=repairAssistant; this.memoryKey=key;
        }
        public String chat(String question) { return assistant.chat(memoryKey,question); }
        public String repair(String instruction) { return repairAssistant.chat(memoryKey,instruction); }
    }

    public Invocation open(Ticket ticket,String rules,JsonNode definitions,CheckedTool executor,
                           Stats stats,int tokenBudget,Consumer<String> progress,BookFinderModel legacy) {
        String memoryKey=ticket.userId()+":"+ticket.state().getId();
        var store=new ConversationChatMemoryStore(memoryKey,ticket.state(),rules);
        var memory=MessageWindowChatMemory.builder().id(memoryKey).maxMessages(48).chatMemoryStore(store).build();
        var repairing=new boolean[]{false};
        ChatModel guarded=new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                check(ticket,stats,repairing[0]);
                progress.accept(stats.calls==0?"正在理解找书需求":"正在整理查询结果");
                stats.calls++;
                boolean forceFinal=repairing[0] || stats.calls>=5;
                var actual=forceFinal?ChatRequest.builder().messages(request.messages()).responseFormat(ResponseFormat.JSON).build():request;
                long started=System.currentTimeMillis();
                AiChatUsage usage=AiChatUsage.ZERO;
                AiFailure failure=AiFailure.UNKNOWN;
                try {
                    ChatResponse response;
                    if(legacy.mock()) response=mockResponse(ticket,actual,definitions,forceFinal,legacy);
                    else response=factory.create(ticket.config(),ticket.startedAt()+60000-System.currentTimeMillis()).chat(actual);
                    var u=response.tokenUsage();
                    if(u!=null) usage=new AiChatUsage(value(u.inputTokenCount()),value(u.outputTokenCount()),value(u.totalTokenCount()));
                    stats.tokens+=usage.totalTokens(); stats.promptTokens+=usage.promptTokens(); stats.completionTokens+=usage.completionTokens();
                    stats.finishReason=response.finishReason()==null?"unknown":response.finishReason().name();
                    failure=null;
                    if(stats.tokens>Math.max(1000,tokenBudget)) throw new BusinessException(429,"本次找书已达到任务 Token 预算，请重试或由管理员调整预算");
                    checkDeadline(ticket);
                    if(response.aiMessage().hasToolExecutionRequests()) {
                        if(forceFinal) throw new BusinessException(422,"找书执行次数达到上限");
                        for(var tool:response.aiMessage().toolExecutionRequests())
                            if(tool.id()==null || tool.id().isBlank() || tool.id().length()>200) throw new BusinessException(502,"模型工具调用格式不合法");
                    }
                    return response;
                } catch(BusinessException | CancellationException e) { throw e; }
                catch(Exception e) {
                    if(Thread.currentThread().isInterrupted() || hasCause(e,InterruptedException.class)) {
                        Thread.currentThread().interrupt(); failure=AiFailure.ABORTED; throw new CancellationException("找书已停止");
                    }
                    if(hasCause(e,java.net.http.HttpTimeoutException.class)) {
                        failure=AiFailure.TIMEOUT; throw new BusinessException(504,"模型响应超时，请稍后重试");
                    }
                    throw new BusinessException(502,"模型接口调用失败，请检查配置或稍后重试");
                } finally {
                    if(!legacy.mock()) recorder.recordBookFinder(new AiUsageRecorder.Entry(ticket.userId(),null,null,null,
                            ticket.config().model(),usage,System.currentTimeMillis()-started,failure));
                }
            }
        };
        var tools=new LinkedHashMap<ToolSpecification,ToolExecutor>();
        for(var definition:definitions) {
            var function=definition.path("function"); var schema=function.path("parameters");
            var parameters=JsonObjectSchema.builder().additionalProperties(false);
            schema.path("properties").fields().forEachRemaining(p->parameters.addProperty(p.getKey(),JsonRawSchema.from(p.getValue().toString())));
            var required=new ArrayList<String>(); schema.path("required").forEach(p->required.add(p.asText())); parameters.required(required);
            var spec=ToolSpecification.builder().name(function.path("name").asText()).description(function.path("description").asText()).parameters(parameters.build()).build();
            tools.put(spec,(request,id)->execute(executor,request));
        }
        var assistant=AiServices.builder(BookFinderAssistant.class).chatModel(guarded)
                .systemMessageProvider(id->rules).chatMemoryProvider(id->memory)
                .tools(tools).maxToolCallingRoundTrips(5).toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocation())
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.failInvocation())
                .hallucinatedToolNameStrategy(request->ToolExecutionResultMessage.from(request,execute(executor,request))).build();
        var repair=AiServices.builder(BookFinderAssistant.class).chatModel(guarded)
                .systemMessageProvider(id->rules).chatMemoryProvider(id->memory).build();
        return new Invocation(assistant,repair,memoryKey) {
            @Override public String repair(String instruction) { repairing[0]=true; return super.repair(instruction); }
        };
    }
    private String execute(CheckedTool executor,ToolExecutionRequest request) {
        try { return executor.execute(request.name(),request.arguments()).toString(); }
        catch(RuntimeException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException("找书已停止"); }
        catch(Exception e) { throw new BusinessException(502,"找书工具执行失败，请稍后重试"); }
    }
    private static int value(Integer value) { return value==null?0:value; }
    private static boolean hasCause(Throwable error,Class<?> type) {
        for(int i=0;error!=null && i<16;i++,error=error.getCause()) if(type.isInstance(error)) return true;
        return false;
    }
    private static void checkDeadline(Ticket ticket) {
        if(Thread.currentThread().isInterrupted()) throw new CancellationException("找书已停止");
        if(System.currentTimeMillis()>=ticket.startedAt()+60000) throw new BusinessException(504,"找书请求超时，请缩小条件");
    }
    private static void check(Ticket ticket,Stats stats,boolean repair) {
        checkDeadline(ticket);
        if(stats.calls>=(repair?6:5)) throw new BusinessException(422,"找书执行次数达到上限");
    }
    /** Existing local demo stays offline, but is driven by the real AI Services loop. */
    private ChatResponse mockResponse(Ticket ticket,ChatRequest request,JsonNode definitions,boolean forceFinal,BookFinderModel legacy) throws Exception {
        var messages=json.createArrayNode();
        for(var message:request.messages()) {
            var node=messages.addObject();
            if(message instanceof SystemMessage system) node.put("role","system").put("content",system.text());
            else if(message instanceof dev.langchain4j.data.message.UserMessage user) node.put("role","user").put("content",user.singleText());
            else if(message instanceof ToolExecutionResultMessage tool) node.put("role","tool").put("tool_call_id",tool.id()).put("content",tool.text());
            else if(message instanceof AiMessage ai) {
                node.put("role","assistant").put("content",ai.text());
                if(ai.hasToolExecutionRequests()) for(var tool:ai.toolExecutionRequests()) {
                    var call=node.withArray("tool_calls").addObject().put("id",tool.id()).put("type","function");
                    call.putObject("function").put("name",tool.name()).put("arguments",tool.arguments());
                }
            }
        }
        var reply=legacy.call(ticket.userId(),ticket.config(),messages,forceFinal?null:definitions,ticket.startedAt()+60000,ticket.state(),ticket.request().message());
        var calls=new ArrayList<ToolExecutionRequest>();
        for(var call:reply.message().path("tool_calls")) calls.add(ToolExecutionRequest.builder().id(call.path("id").asText()).name(call.path("function").path("name").asText()).arguments(call.path("function").path("arguments").asText()).build());
        var ai=calls.isEmpty()?AiMessage.from(reply.message().path("content").asText()):AiMessage.from(reply.message().path("content").asText(null),calls);
        return ChatResponse.builder().aiMessage(ai).tokenUsage(new TokenUsage(reply.usage().promptTokens(),reply.usage().completionTokens(),reply.usage().totalTokens()))
                .finishReason(calls.isEmpty()?FinishReason.STOP:FinishReason.TOOL_EXECUTION).build();
    }
}
