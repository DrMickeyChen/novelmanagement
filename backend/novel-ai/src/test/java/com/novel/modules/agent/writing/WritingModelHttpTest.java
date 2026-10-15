package com.novel.modules.agent.writing;

import com.novel.modules.agent.writing.context.WritingRetrieval;
import com.novel.modules.agent.writing.infrastructure.model.WritingModel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.internal.WritingContent.*;
import com.novel.modules.agent.infrastructure.model.LangChain4jModelFactory;
import com.novel.modules.ai.service.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WritingModelHttpTest {
    HttpServer server;ObjectMapper json=new ObjectMapper();WritingModel model;AiRuntimeConfig cfg;
    AtomicInteger calls=new AtomicInteger();String mode="plan";
    List<com.fasterxml.jackson.databind.JsonNode> requests=new java.util.concurrent.CopyOnWriteArrayList<>();
    Snapshot snapshot=new Snapshot("1","1",1,"标题",0,"正文","1","大纲","hash",List.of());
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            try {
                calls.incrementAndGet();assertEquals("Bearer fixture-only",exchange.getRequestHeaders().getFirst("Authorization"));
                var req=json.readTree(exchange.getRequestBody());
                requests.add(req);
                if(!req.path("stream").asBoolean())assertEquals("json_object",req.path("response_format").path("type").asText());
                if(mode.equals("fail")){exchange.sendResponseHeaders(500,-1);return;}
                if(req.path("stream").asBoolean()) {
                    assertEquals(4096,req.path("max_tokens").asInt());
                    exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
                    String wire="data: {\"id\":\"draft\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"少年停住脚步。\"}}]}\n\n"
                        +"data: {\"id\":\"draft\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        +"data: {\"id\":\"draft\",\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
                        +"data: [DONE]\n\n";
                    exchange.getResponseBody().write(wire.getBytes(StandardCharsets.UTF_8));return;
                }
                var message=json.createObjectNode().put("role","assistant");
                var messages=req.path("messages");var last=messages.get(messages.size()-1);
                if(mode.equals("live")&&calls.get()==1){
                    message.putArray("tool_calls").addObject().put("id","live-1").put("type","function").putObject("function").put("name","getChapterOutline").put("arguments","{}");
                } else if(mode.equals("tool") && !last.path("role").asText().equals("tool")) {
                    assertEquals(5,req.path("tools").size());
                    message.putArray("tool_calls").addObject().put("id","tool-1").put("type","function").putObject("function").put("name","getChapterOutline").put("arguments","{}");
                } else if(Set.of("research","memory").contains(mode) && !last.path("role").asText().equals("tool")) {
                    assertEquals(mode.equals("research")?8:9,req.path("tools").size());
                    var names=new HashSet<String>();for(var tool:req.path("tools"))names.add(tool.path("function").path("name").asText());
                    assertEquals(mode.equals("memory"),names.contains("proposeStoryMemory"));
                    var function=message.putArray("tool_calls").addObject().put("id","extra-1").put("type","function").putObject("function");
                    function.put("name",mode.equals("research")?"searchEarlierChapters":"proposeStoryMemory");
                    function.put("arguments",mode.equals("research")?"{\"query\":\"玉佩\",\"limit\":2}":"{\"name\":\"玉佩\",\"kind\":\"ITEM\",\"body\":\"不能飞行\",\"sourceId\":\"chapter:7:0\",\"quote\":\"玉佩不能飞行\"}");
                } else {
                    if(mode.equals("tool"))assertEquals("大纲",last.path("content").asText());
                    if(mode.equals("research"))assertTrue(last.path("content").asText().contains("BM25"));
                    if(mode.equals("memory"))assertTrue(last.path("content").asText().contains("PENDING"));
                    message.put("content","{\"summary\":\"计划\",\"events\":[\"事件\"],\"ending\":\"悬念\",\"needsInput\":false}");
                }
                var response=json.createObjectNode().put("id","plan").put("model","fixture");
                response.putArray("choices").addObject().put("index",0).put("finish_reason",message.has("tool_calls")?"tool_calls":"stop").set("message",message);
                response.putObject("usage").put("prompt_tokens",10).put("completion_tokens",5).put("total_tokens",15);
                byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}
        });server.start();
        var config=mock(AiConfigService.class);when(config.decryptCurrentApiKey()).thenReturn("fixture-only");
        model=new WritingModel(new LangChain4jModelFactory(config),json);ReflectionTestUtils.setField(model,"provider","deepseek");
        cfg=new AiRuntimeConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","deepseek-chat",new BigDecimal("0.7"),2048,20);
    }
    @AfterEach void cleanup(){server.stop(0);}
    static class Budget implements WritingModel.Budget {
        int calls,tools,total,evidenceCount;boolean cancel;
        public void before(String input,int max){calls++;check();}
        public void after(ChatResponse r,Throwable error){if(r!=null&&r.tokenUsage()!=null)total+=r.tokenUsage().totalTokenCount();}
        public void tool(){tools++;check();}
        public void evidence(List<Source> sources,String mode,String notice){evidenceCount+=sources.size();}
        public void check(){if(cancel)throw new java.util.concurrent.CancellationException();}
    }
    @Test void realAiServicesExecutesReadOnlyJavaTool(){mode="tool";var budget=new Budget();String plan=model.structured("PLAN","规划",snapshot,json.createObjectNode(),cfg,budget);assertTrue(plan.contains("悬念"));assertEquals(2,calls.get());assertEquals(1,budget.tools);assertEquals(30,budget.total);}
    @Test void enablingExternalToolsDuringAiServicesLoopUpdatesNextModelRequest(){dynamicTools(false);}
    @Test void disablingExternalToolsDuringAiServicesLoopRemovesThemFromNextModelRequest(){dynamicTools(true);}
    void dynamicTools(boolean initiallyEnabled){
        mode="live";ReflectionTestUtils.setField(model,"mcp",mock(com.novel.modules.agent.writing.application.WritingMcp.class));
        var on=json.createObjectNode();var item=on.putArray("mcpSnapshots").addObject().put("id","conn");item.putArray("tools").add("search");item.putArray("catalog").addObject().put("name","search").put("description","已授权搜索");
        var off=json.createObjectNode();off.putArray("mcpSnapshots");
        var budget=new Budget(){@Override public com.fasterxml.jackson.databind.JsonNode externalPermissions(com.fasterxml.jackson.databind.JsonNode fallback){return (WritingModelHttpTest.this.calls.get()==0)==initiallyEnabled?on:off;}};
        assertTrue(model.structured("PLAN","规划",snapshot,off,cfg,budget).contains("悬念"));assertEquals(2,requests.size());
        for(int i=0;i<2;i++){
            boolean expected=i==0?initiallyEnabled:!initiallyEnabled,found=false;for(var tool:requests.get(i).path("tools"))found|=tool.path("function").path("name").asText().equals("callExternalReference");
            assertEquals(expected,found);var messages=requests.get(i).path("messages");String last=messages.get(messages.size()-1).path("content").asText();assertTrue(last.contains("当前外部工具选择"));assertEquals(expected,last.contains("已授权搜索"));
        }
    }
    @Test void planningInvokesPrivateResearchAndPersistsEvidenceThroughBudget(){
        mode="research";var retrieval=mock(WritingRetrieval.class);ReflectionTestUtils.setField(model,"retrieval",retrieval);
        var source=new Source("chapter:7:0","7",1,"前文",0,0,7,true,"玉佩不能飞行。");
        when(retrieval.search(snapshot,"玉佩",2)).thenReturn(new WritingRetrieval.Evidence(List.of(source),"BM25",""));
        var budget=new Budget();model.structured("PLAN","规划",snapshot,json.createObjectNode(),cfg,budget);
        verify(retrieval).search(snapshot,"玉佩",2);assertEquals(1,budget.evidenceCount);assertEquals(1,budget.tools);assertEquals(2,calls.get());
    }
    @Test void onlyCheckingExposesPendingMemoryProposalTool(){
        mode="memory";var retrieval=mock(WritingRetrieval.class);ReflectionTestUtils.setField(model,"retrieval",retrieval);
        when(retrieval.propose(any(),eq("玉佩"),eq("ITEM"),eq("不能飞行"),eq("chapter:7:0"),eq("玉佩不能飞行"))).thenReturn(Map.of("state","PENDING"));
        var budget=new Budget();model.structured("CHECK","检查",snapshot,json.createObjectNode(),cfg,budget);
        verify(retrieval).propose(any(),eq("玉佩"),eq("ITEM"),eq("不能飞行"),eq("chapter:7:0"),eq("玉佩不能飞行"));assertEquals(1,budget.tools);assertEquals(2,calls.get());
    }
    @Test void realStreamingSdkDeliversTextAndUsage(){var budget=new Budget();var output=new StringBuilder();model.draft("写正文",cfg,budget,output::append);assertEquals("少年停住脚步。",output.toString());assertEquals(1,calls.get());assertEquals(15,budget.total);}
    @Test void providerFailureHasNoHiddenRetry(){mode="fail";var budget=new Budget();assertThrows(RuntimeException.class,()->model.structured("RECAP","回顾",snapshot,json.createObjectNode(),cfg,budget));assertEquals(1,calls.get());}
    @Test void cancellationRejectsFurtherOutput(){var budget=new Budget();var output=new StringBuilder();assertThrows(RuntimeException.class,()->model.draft("写正文",cfg,budget,t->{output.append(t);budget.cancel=true;}));assertEquals(1,calls.get());}
}
