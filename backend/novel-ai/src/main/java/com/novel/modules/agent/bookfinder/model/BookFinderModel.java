package com.novel.modules.agent.bookfinder.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.novel.common.exception.BusinessException;
import com.novel.modules.ai.service.*;
import com.novel.modules.ai.enums.AiFailure;
import com.novel.modules.agent.bookfinder.BookFinderTypes.Conversation;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.concurrent.*;

/** OpenAI-compatible tool messages. Upstream raw bodies/keys are never logged. */
@Component @RequiredArgsConstructor
public class BookFinderModel {
    private final ObjectMapper json;
    private final AiConfigService config;
    private final AiUsageRecorder usage;
    @Value("${novel.ai.provider:deepseek}") private String provider;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public record Reply(ObjectNode message, AiChatUsage usage, String finishReason) {
        public Reply(ObjectNode message,AiChatUsage usage) { this(message,usage,"unknown"); }
    }
    public boolean mock() { return "mock".equals(provider); }

    public Reply call(long userId, AiRuntimeConfig cfg, ArrayNode messages, JsonNode tools,
                      long deadline, Conversation state, String input) throws Exception {
        if (mock()) return new Reply(mockReply(messages,state,input),AiChatUsage.ZERO);
        long start=System.currentTimeMillis();
        AiChatUsage tokens=AiChatUsage.ZERO;
        AiFailure failure=AiFailure.UNKNOWN;
        try {
            var body=json.createObjectNode();
            body.put("model",cfg.model()); body.set("messages",messages); body.put("stream",false);
            body.put("max_tokens",Math.min(2048,cfg.maxTokens()==null?2048:cfg.maxTokens()));
            if(cfg.temperature()!=null) body.put("temperature",cfg.temperature());
            if(tools!=null) { body.set("tools",tools); body.put("tool_choice","auto"); }
            else body.putObject("response_format").put("type","json_object");
            // Disable thinking for this bounded tool workflow on native DeepSeek models.
            if(cfg.model().startsWith("deepseek")) body.putObject("thinking").put("type","disabled");
            String base=cfg.baseUrl().replaceAll("/+$","");
            long remaining=deadline-System.currentTimeMillis();
            if(remaining<=0) throw new TimeoutException();
            var request=HttpRequest.newBuilder(URI.create(base+"/chat/completions"))
                    .timeout(Duration.ofMillis(Math.min(30000,remaining)))
                    .header("Content-Type","application/json")
                    .header("Authorization","Bearer "+config.decryptCurrentApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            var future=http.sendAsync(request,HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> response;
            try { response=future.get(Math.min(30000,remaining),TimeUnit.MILLISECONDS); }
            catch(InterruptedException | TimeoutException e) { future.cancel(true); throw e; }
            {
                if(response.statusCode()!=200) throw new BusinessException(502,"模型接口调用失败，请检查模型配置或稍后重试");
                byte[] bytes=response.body();
                if(bytes.length>2_000_000) throw new BusinessException(502,"模型响应过大");
                JsonNode root;
                try { root=json.readTree(bytes); }
                catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new BusinessException(502,"模型接口未返回有效 JSON，请检查接口兼容性或稍后重试"); }
                var u=root.path("usage");
                tokens=new AiChatUsage(u.path("prompt_tokens").asInt(),u.path("completion_tokens").asInt(),u.path("total_tokens").asInt());
                var message=root.path("choices").path(0).path("message");
                if(!message.isObject()) throw new BusinessException(502,"模型未返回有效消息");
                failure=null;
                return new Reply((ObjectNode)message,tokens,root.path("choices").path(0).path("finish_reason").asText("unknown"));
            }
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); failure=AiFailure.ABORTED; throw e; }
        catch(TimeoutException | HttpTimeoutException e) { failure=AiFailure.TIMEOUT; throw e; }
        finally {
            usage.recordBookFinder(new AiUsageRecorder.Entry(userId,null,null,null,cfg.model(),tokens,
                    System.currentTimeMillis()-start,failure));
        }
    }

    private ObjectNode mockReply(ArrayNode messages, Conversation state, String input) throws Exception {
        var last=messages.get(messages.size()-1);
        if(!"tool".equals(last.path("role").asText())) {
            if(input.contains("第二本") && state.getLastBooks().size()<2)
                return finalMessage("当前没有第二本推荐，请先告诉我想看什么类型。",json.createArrayNode(),true);
            var args=json.valueToTree(state.getConditions());
            var query=(ObjectNode)args; query.remove("excludeIds"); query.remove("limit");
            if(input.contains("开篇")||input.contains("调查")||input.contains("成长")||input.contains("节奏")||input.contains("氛围"))
                query.put("preference",input.substring(0,Math.min(200,input.length())));
            for(String name:new String[]{"玄幻","科幻","历史","都市","悬疑","武侠","言情"})
                if(input.contains(name)) query.put("category",name);
            if(input.contains("完结")) query.put("completed",true);
            if(input.contains("连载")) query.put("completed",false);
            if(input.contains("不限分类")) query.putNull("category");
            if(input.contains("不限完结") || input.contains("不限状态")) query.putNull("completed");
            if(input.contains("不限字数")) { query.putNull("minWords"); query.putNull("maxWords"); }
            if(input.contains("第二本") && state.getLastBooks().size()>1) {
                query.put("category",state.getLastBooks().get(1).category()); query.putNull("keyword");
            }
            var matcher=java.util.regex.Pattern.compile("(至少|不少于|超过|最多|不超过)?\\s*(\\d+)\\s*万字").matcher(input);
            if(matcher.find()) query.put("最多".equals(matcher.group(1))||"不超过".equals(matcher.group(1))?"maxWords":"minWords",
                    Math.min(Integer.MAX_VALUE,Long.parseLong(matcher.group(2))*10000));
            if(input.contains("一百万字")) query.put("minWords",1000000);
            return tool("searchBooks",query);
        }
        var result=json.readTree(last.path("content").asText());
        if("searchBooks".equals(result.path("tool").asText())) {
            var ids=json.createArrayNode();
            for(var b:result.path("books")) { if(ids.size()==5) break; ids.add(b.path("id").asText()); }
            if(ids.isEmpty()) return finalMessage("没有找到符合当前条件的新作品。可以告诉我是否放宽分类或字数条件。",json.createArrayNode(),true);
            var args=json.createObjectNode(); args.set("ids",ids); return tool("getBookDetails",args);
        }
        if("getBookDetails".equals(result.path("tool").asText()) && state.getPreference()!=null && !state.getPreference().isBlank()) {
            var args=json.createObjectNode().put("query",state.getPreference()); var ids=args.putArray("ids");
            for(var b:result.path("books")) ids.add(b.path("id").asText());
            if(!ids.isEmpty()) return tool("searchBookEvidence",args);
        }
        JsonNode hits=json.createArrayNode();
        if("searchBookEvidence".equals(result.path("tool").asText())) {
            hits=result.path("evidence").path("hits");
            for(int i=messages.size()-2;i>=0;i--) {
                if(!"tool".equals(messages.get(i).path("role").asText())) continue;
                var previous=json.readTree(messages.get(i).path("content").asText());
                if("getBookDetails".equals(previous.path("tool").asText())) { result=previous; break; }
            }
        }
        var recommendations=json.createArrayNode();
        for(var b:result.path("books")) {
            var r=recommendations.addObject(); r.put("id",b.path("id").asText());
            String intro=b.path("intro").asText(); r.put("quote",intro.substring(0,Math.min(80,intro.length())));
            var refs=r.putArray("evidenceIds");
            for(var hit:hits) if(hit.path("novelId").asText().equals(b.path("id").asText()) && refs.size()<2) refs.add(hit.path("chunkId").asText());
        }
        return finalMessage("已按当前条件筛选站内作品。",recommendations,false);
    }
    private ObjectNode tool(String name, JsonNode args) {
        var reply=json.createObjectNode(); reply.put("role","assistant"); reply.putNull("content");
        var call=reply.putArray("tool_calls").addObject(); call.put("id","call_"+java.util.UUID.randomUUID()); call.put("type","function");
        var fn=call.putObject("function"); fn.put("name",name); fn.put("arguments",args.toString()); return reply;
    }
    private ObjectNode finalMessage(String answer, ArrayNode books, boolean clarify) {
        var output=json.createObjectNode(); output.put("answer",answer); output.put("needsClarification",clarify); output.set("recommendations",books);
        var reply=json.createObjectNode(); reply.put("role","assistant"); reply.put("content",output.toString()); return reply;
    }
}
