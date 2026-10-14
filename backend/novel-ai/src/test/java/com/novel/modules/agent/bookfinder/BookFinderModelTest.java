package com.novel.modules.agent.bookfinder;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.novel.modules.agent.bookfinder.BookFinderTypes.Conversation;
import com.novel.modules.agent.bookfinder.model.BookFinderModel;
import com.novel.modules.ai.service.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookFinderModelTest {
    @Test void compatibleEndpointReceivesToolDefinitionsAndReturnsCallAndUsage() throws Exception {
        var json=new ObjectMapper();
        var config=mock(AiConfigService.class,withSettings().mockMaker("mock-maker-subclass"));
        var recorder=mock(AiUsageRecorder.class,withSettings().mockMaker("mock-maker-subclass"));
        when(config.decryptCurrentApiKey()).thenReturn("local-test-key");
        var seen=new AtomicReference<JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            seen.set(json.readTree(exchange.getRequestBody()));
            assertEquals("Bearer local-test-key",exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"searchBooks\",\"arguments\":\"{}\"}}]}}],\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":10,\"total_tokens\":40}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var model=new BookFinderModel(json,config,recorder);
            var messages=json.createArrayNode();messages.addObject().put("role","user").put("content","找书");
            var tools=json.readTree("[{\"type\":\"function\",\"function\":{\"name\":\"searchBooks\",\"parameters\":{\"type\":\"object\"}}}]");
            var cfg=new AiRuntimeConfig("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/","test-model",BigDecimal.ZERO,8192,20);
            var reply=model.call(99,cfg,messages,tools,System.currentTimeMillis()+5000,new Conversation(),"找书");
            assertEquals("searchBooks",reply.message().path("tool_calls").get(0).path("function").path("name").asText());
            assertEquals(40,reply.usage().totalTokens());
            assertEquals(tools,seen.get().path("tools"));assertEquals(2048,seen.get().path("max_tokens").asInt());
            verify(recorder).recordBookFinder(argThat(entry->entry.failure()==null&&entry.usage().totalTokens()==40));
            model.call(99,cfg,messages,null,System.currentTimeMillis()+5000,new Conversation(),"找书");
            assertEquals("json_object",seen.get().path("response_format").path("type").asText());
            assertFalse(seen.get().has("tools"));
        } finally { server.stop(0); }
    }
}
