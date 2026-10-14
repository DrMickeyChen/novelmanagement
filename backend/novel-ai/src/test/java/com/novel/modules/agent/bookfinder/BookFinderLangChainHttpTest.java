package com.novel.modules.agent.bookfinder;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import com.novel.common.internal.*;
import com.novel.common.result.Result;
import com.novel.common.exception.BusinessException;
import com.novel.modules.agent.bookfinder.BookFinderTypes.*;
import com.novel.modules.agent.bookfinder.client.*;
import com.novel.modules.agent.bookfinder.conversation.BookFinderStore;
import com.novel.modules.agent.bookfinder.model.*;
import com.novel.modules.agent.bookfinder.service.BookFinderAgent;
import com.novel.modules.agent.infrastructure.model.LangChain4jModelFactory;
import com.novel.modules.ai.service.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real OpenAiChatModel + AI Services against a local HTTP fixture, zero paid calls. */
class BookFinderLangChainHttpTest {
    private final ObjectMapper json=new ObjectMapper();
    private final AiConfigService config=mock(AiConfigService.class,withSettings().mockMaker("mock-maker-subclass"));
    private final AiUsageRecorder recorder=mock(AiUsageRecorder.class,withSettings().mockMaker("mock-maker-subclass"));
    private final BookFinderSearchClient search=mock(BookFinderSearchClient.class,withSettings().mockMaker("mock-maker-subclass"));
    private final BookFinderContentClient content=mock(BookFinderContentClient.class,withSettings().mockMaker("mock-maker-subclass"));
    private final BookFinderStore store=mock(BookFinderStore.class,withSettings().mockMaker("mock-maker-subclass"));
    private final BookFinderModel legacy=mock(BookFinderModel.class,withSettings().mockMaker("mock-maker-subclass"));
    private final Conversation state=new Conversation();
    private final AtomicReference<Throwable> fixtureError=new AtomicReference<>();
    private final AtomicInteger requests=new AtomicInteger();
    private final AtomicBoolean fail=new AtomicBoolean();
    private final AtomicBoolean stall=new AtomicBoolean();
    private final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    private final AtomicReference<String> expectedKey=new AtomicReference<>("local-fixture-key");
    private final List<BookFinderBook> books=new ArrayList<>();
    private HttpServer server;
    private BookFinderAgent agent;
    private AiRuntimeConfig settings;
    @BeforeEach void setup() throws Exception {
        state.setId(UUID.randomUUID().toString());
        for(int i=1;i<=15;i++) books.add(new BookFinderBook(Integer.toString(i),"测试书"+i,"作者","","悬疑","推理","调查线索。",true,300000));
        when(config.decryptCurrentApiKey()).thenAnswer(i->expectedKey.get());
        when(content.categories()).thenReturn(Result.success(List.of("悬疑")));
        when(search.search(any())).thenReturn(Result.success(books));
        when(content.details(any())).thenAnswer(i->{
            List<String> ids=i.getArgument(0);
            var data=new ArrayList<>(books.stream().filter(b->ids.contains(b.id())).toList()); Collections.reverse(data);
            return Result.success(data);
        });
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.incrementAndGet();
            try {
                assertEquals("Bearer "+expectedKey.get(),exchange.getRequestHeaders().getFirst("Authorization"));
                var request=json.readTree(exchange.getRequestBody());
                assertEquals(2048,request.path("max_tokens").asInt());
                assertEquals("disabled",request.path("thinking").path("type").asText());
                if(stall.get()) { entered.countDown(); release.await(5,TimeUnit.SECONDS); }
                if(fail.get()) { exchange.sendResponseHeaders(500,-1); return; }
                var tools=request.path("tools");
                if(!tools.isMissingNode()) {
                    assertEquals(3,tools.size());
                    assertEquals(json.readTree("[\"string\",\"null\"]"),tools.get(0).path("function").path("parameters").path("properties").path("keyword").path("type"));
                }
                var messages=request.path("messages"); var last=messages.get(messages.size()-1);
                ObjectNode message=json.createObjectNode().put("role","assistant");
                boolean searched=false;
                for(var m:messages) if("tool".equals(m.path("role").asText()) && "searchBooks".equals(json.readTree(m.path("content").asText()).path("tool").asText())) searched=true;
                if("user".equals(last.path("role").asText()) && last.path("content").asText().contains("类似")) {
                    String rules=messages.get(0).path("content").asText();
                    var context=json.readTree(rules.substring(rules.indexOf("{\"currentConditions\"")));
                    assertEquals("9",context.path("referencedBook").path("id").asText());
                    var referenceArgs=json.createObjectNode(); referenceArgs.putArray("ids").add("9");
                    tool(message,"getBookDetails",referenceArgs);
                } else if("user".equals(last.path("role").asText()) || !searched) tool(message,"searchBooks",json.createObjectNode());
                else {
                    var result=json.readTree(last.path("content").asText());
                    if("searchBooks".equals(result.path("tool").asText())) {
                        var args=json.createObjectNode(); var ids=args.putArray("ids");
                        for(var b:result.path("books")) { if(ids.size()==5) break; ids.add(b.path("id").asText()); }
                        tool(message,"getBookDetails",args);
                    } else {
                        var value=json.createObjectNode().put("answer","已找到作品").put("needsClarification",false);
                        var recommendations=value.putArray("recommendations");
                        // Return in candidate order despite reversed details.
                        var selected=new ArrayList<String>(); result.path("books").forEach(b->selected.add(b.path("id").asText()));
                        selected.sort(Comparator.comparingInt(Integer::parseInt));
                        for(var id:selected) recommendations.addObject().put("id",id).put("quote","");
                        message.put("content",value.toString());
                    }
                }
                var response=json.createObjectNode(); response.putArray("choices").addObject().put("finish_reason",message.has("tool_calls")?"tool_calls":"stop").set("message",message);
                response.putObject("usage").put("prompt_tokens",30).put("completion_tokens",10).put("total_tokens",40);
                byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
            } catch(Throwable e) { fixtureError.set(e); exchange.sendResponseHeaders(500,-1); }
            finally { exchange.close(); }
        });
        server.start();
        settings=new AiRuntimeConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","deepseek-chat",BigDecimal.ZERO,2048,20);
        agent=new BookFinderAgent(json,search,content,legacy,store,config);
        agent.framework(new BookFinderAiServices(json,new LangChain4jModelFactory(config),recorder));
        ReflectionTestUtils.setField(agent,"engine","langchain4j");
    }
    private void tool(ObjectNode message,String name,JsonNode args) {
        message.putNull("content"); var call=message.putArray("tool_calls").addObject().put("id","call_"+UUID.randomUUID()).put("type","function");
        call.putObject("function").put("name",name).put("arguments",args.toString());
    }
    private Answer chat(String message) throws Exception {
        return agent.run(new BookFinderAgent.Ticket(99,new ChatRequest(message,UUID.randomUUID().toString(),state.getId()),state,settings,"fp",null,System.currentTimeMillis()),p->{});
    }
    @AfterEach void close() { release.countDown(); if(server!=null) server.stop(0); }
    @Test void sdkAndAiServicesExecuteRefreshAndOrdinalWithLiveKeyUpdates() throws Exception {
        var first=chat("推荐几本小说");
        assertEquals(List.of("1","2","3","4","5"),first.recommendations().stream().map(r->r.book().id()).toList());
        expectedKey.set("rotated-fixture-key");
        var second=chat("换一批");
        assertEquals(List.of("6","7","8","9","10"),second.recommendations().stream().map(r->r.book().id()).toList());
        var third=chat("类似第4本的还有吗");
        assertEquals(List.of("11","12","13","14","15"),third.recommendations().stream().map(r->r.book().id()).toList());
        assertEquals(3,state.getHistory().size()); assertNull(fixtureError.get());
        assertEquals(120,first.usage().totalTokens());
        verify(legacy,never()).call(anyLong(),any(),any(),any(),anyLong(),any(),anyString());
        verify(recorder,times(requests.get())).recordBookFinder(any());
        assertEquals(10,requests.get());
    }
    @Test void sdkDoesNotRetryProviderFailuresOrInvokeBusinessTools() throws Exception {
        fail.set(true);
        assertThrows(BusinessException.class,()->chat("推荐几本小说"));
        assertEquals(1,requests.get()); verifyNoInteractions(search);
        verify(store,never()).complete(anyLong(),anyString(),anyString(),any(),any());
        verify(recorder).recordBookFinder(argThat(e->e.failure()!=null));
    }
    @Test void businessToolFailurePreservesErrorCodeAndNeverCommits() throws Exception {
        when(search.search(any())).thenThrow(new BusinessException(503,"搜索服务暂时不可用"));
        var error=assertThrows(BusinessException.class,()->chat("推荐几本小说"));
        assertEquals(503,error.getCode()); assertEquals(1,requests.get());
        verify(store,never()).complete(anyLong(),anyString(),anyString(),any(),any());
    }
    @Test void stalledProviderRespectsRemainingTaskDeadline() throws Exception {
        stall.set(true);
        var ticket=new BookFinderAgent.Ticket(99,new ChatRequest("推荐几本小说",UUID.randomUUID().toString(),state.getId()),state,settings,"fp",null,System.currentTimeMillis()-58000);
        var error=assertThrows(BusinessException.class,()->agent.run(ticket,p->{}));
        assertEquals(504,error.getCode());
        assertTrue(requests.get()<=1); verifyNoInteractions(search);
        verify(store,never()).complete(anyLong(),anyString(),anyString(),any(),any());
    }
    @Test void interruptCancelsStalledSdkCallWithoutCommittingConversation() throws Exception {
        stall.set(true);
        var executor=Executors.newSingleThreadExecutor();
        var finished=new CountDownLatch(1);
        try {
            var task=executor.submit(()-> { try { chat("推荐几本小说"); } catch(Exception ignored) {} finally { finished.countDown(); } });
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            task.cancel(true);
            assertTrue(finished.await(2,TimeUnit.SECONDS),"SDK call must stop promptly when the SSE worker is interrupted");
            verify(store,never()).complete(anyLong(),anyString(),anyString(),any(),any());
            verifyNoInteractions(search);
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}
