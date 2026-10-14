package com.novel.modules.agent.bookfinder.knowledge;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalModelsTest {
    private HttpServer server; private RetrievalModels models;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); models=new RetrievalModels(new ObjectMapper());
        for(var field:List.of("embeddingUrl","embeddingModel","embeddingKey","rerankUrl","rerankModel","rerankKey")) ReflectionTestUtils.setField(models,field,"");
    }
    @AfterEach void close() { server.stop(0); }
    private void endpoint(String path,String payload) {
        server.createContext(path,x->{ byte[] raw=payload.getBytes(StandardCharsets.UTF_8); x.getRequestBody().readAllBytes(); x.sendResponseHeaders(200,raw.length); x.getResponseBody().write(raw); x.close(); });
        server.start();
    }
    private void configure(String kind,String path) {
        ReflectionTestUtils.setField(models,kind+"Url","http://127.0.0.1:"+server.getAddress().getPort()+path);
        ReflectionTestUtils.setField(models,kind+"Model","test-model"); ReflectionTestUtils.setField(models,kind+"Key","test-key-never-show");
    }
    @Test void embeddingReordersByIndexAndDoesNotExposeKey() throws Exception {
        endpoint("/embeddings","{\"data\":[{\"index\":1,\"embedding\":[0,1]},{\"index\":0,\"embedding\":[1,0]}]}"); configure("embedding","/embeddings");
        assertEquals(List.of(List.of(1.0,0.0),List.of(0.0,1.0)),models.embed(List.of("a","b")));
        assertFalse(new ObjectMapper().writeValueAsString(models.status()).contains("test-key"));
    }
    @Test void duplicateEmbeddingIndexesAreRejected() {
        endpoint("/embeddings","{\"data\":[{\"index\":0,\"embedding\":[1,0]},{\"index\":0,\"embedding\":[0,1]}]}"); configure("embedding","/embeddings");
        assertThrows(Exception.class,()->models.embed(List.of("a","b")));
    }
    @Test void rerankValidatesIndexesAndActuallyOrdersByScores() throws Exception {
        endpoint("/rerank","{\"results\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.9}]}"); configure("rerank","/rerank");
        assertEquals(List.of(1,0),models.rerank("q",List.of("a","b")));
    }
    @Test void missingProviderCannotSilentlyPretendToEmbed() {
        assertFalse(models.embeddingEnabled()); assertFalse(models.rerankEnabled());
        assertThrows(Exception.class,()->models.embed(List.of("a")));
    }
    @Test void savedRerankConfigIsUsedImmediatelyAndExplicitClearDisablesIt() throws Exception {
        server.createContext("/saved-rerank",x->{
            var request=new ObjectMapper().readTree(x.getRequestBody());
            assertEquals("saved-reranker",request.path("model").asText());
            assertEquals("Bearer isolated-rerank-key",x.getRequestHeaders().getFirst("Authorization"));
            byte[] raw="{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,raw.length); x.getResponseBody().write(raw); x.close();
        }); server.start();
        var cfg=org.mockito.Mockito.mock(RerankConfigService.class,org.mockito.Mockito.withSettings().mockMaker("mock-maker-subclass"));
        String url="http://127.0.0.1:"+server.getAddress().getPort()+"/saved-rerank";
        org.mockito.Mockito.when(cfg.runtime()).thenReturn(new RerankConfigService.Runtime(url,"saved-reranker","isolated-rerank-key",8));
        ReflectionTestUtils.setField(models,"rerankConfiguration",cfg);
        assertTrue(models.rerankEnabled()); assertEquals(List.of(1,0),models.rerank("growth",List.of("a","b")));
        org.mockito.Mockito.when(cfg.runtime()).thenReturn(new RerankConfigService.Runtime(url,"saved-reranker","",8));
        assertFalse(models.rerankEnabled()); assertThrows(Exception.class,()->models.rerank("growth",List.of("a","b")));
    }
    @Test void savedConfigurationOverridesEnvironmentAndSplitsBatches() throws Exception {
        var batches=new ArrayList<Integer>(); var json=new ObjectMapper();
        server.createContext("/saved",x->{
            var request=json.readTree(x.getRequestBody()); int count=request.path("input").size(); batches.add(count);
            assertEquals("Bearer saved-key",x.getRequestHeaders().getFirst("Authorization"));
            assertEquals("saved-model",request.path("model").asText());
            var data=json.createObjectNode().putArray("data");
            for(int i=0;i<count;i++) data.addObject().put("index",i).putArray("embedding").add(1).add(0);
            byte[] raw=json.writeValueAsBytes(java.util.Map.of("data",data)); x.sendResponseHeaders(200,raw.length); x.getResponseBody().write(raw); x.close();
        }); server.start();
        var service=org.mockito.Mockito.mock(EmbeddingConfigService.class,org.mockito.Mockito.withSettings().mockMaker("mock-maker-subclass"));
        org.mockito.Mockito.when(service.runtime()).thenReturn(new EmbeddingConfigService.Runtime(
                "http://127.0.0.1:"+server.getAddress().getPort()+"/saved","saved-model","saved-key",2));
        ReflectionTestUtils.setField(models,"configuration",service);
        assertEquals(5,models.embed(List.of("a","b","c","d","e")).size());
        assertEquals(List.of(2,2,1),batches);
        var identity=models.embeddingIdentity();
        org.mockito.Mockito.when(service.runtime()).thenReturn(new EmbeddingConfigService.Runtime(
                "http://127.0.0.1:"+server.getAddress().getPort()+"/saved","saved-model","",2));
        assertFalse(models.embeddingEnabled()); assertEquals(identity,models.embeddingIdentity());
        assertThrows(Exception.class,()->models.embed(List.of("a")));
    }
}
