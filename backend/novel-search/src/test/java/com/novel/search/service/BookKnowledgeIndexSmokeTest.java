package com.novel.search.service;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.elasticsearch.client.RestClient;
import org.apache.http.HttpHost;
import com.novel.common.internal.*;
import com.novel.common.internal.BookKnowledge.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, isolated unique document; never rebuilds or deletes existing book indexes. */
@EnabledIfSystemProperty(named="knowledge.es.live",matches="true")
class BookKnowledgeIndexSmokeTest {
    @Test void actualEsCanRoundTripAtomicKnowledgeDocuments() throws Exception {
        String url=System.getProperty("knowledge.es.url","http://192.168.127.137:9200");
        try(var transport=new RestClientTransport(RestClient.builder(HttpHost.create(url)).build(),new JacksonJsonpMapper())) {
            var es=new ElasticsearchClient(transport);
            var index=new BookKnowledgeIndex(es);
            String id=Long.toString(8_000_000_000_000_000_000L+Math.abs(UUID.randomUUID().getLeastSignificantBits()%1000000000000L));
            assertTrue(index.get(List.of(id)).isEmpty());
            var book=new BookFinderBook(id,"隔离测试资料","测试作者","","悬疑","调查","仅用于索引验收",true,300000);
            var chunk=new Chunk(id+":1:0","1",1,"第一章",0,6,"调查案件线索",List.of(1.0,0.0));
            var doc=new Document(id,book,"v1",10,1,20,6,false,Instant.now().toString(),"test-model",List.of(chunk));
            try {
                index.put(doc); var loaded=index.get(List.of(id)).get(0);
                var recalled=index.recall(new RecallQuery(List.of(id),"调查",List.of(1.0,0.0),"test-model"));
                assertEquals("ES_HYBRID_RRF",recalled.mode()); assertEquals(1,recalled.hits().size());
                assertEquals(chunk.id(),recalled.hits().get(0).chunk().id());
                assertTrue(recalled.hits().get(0).chunk().vector().isEmpty());
                assertNull(index.metadata(List.of(id)).get(0).chunks());
                long catalogStart=System.nanoTime();
                var initialCatalog=index.catalog();
                System.out.println("Knowledge catalog cold read: books="+initialCatalog.size()+", milliseconds="+((System.nanoTime()-catalogStart)/1000000));
                assertEquals(doc,loaded); assertTrue(initialCatalog.stream().anyMatch(d->d.novelId().equals(id)&&d.chunks()==null));
                var cached=index.catalog(); assertSame(cached,index.catalog(),"Repeated catalog requests reuse the same immutable metadata snapshot");
                var newer=new Document(id,book,"v2",10,1,20,6,false,Instant.now().toString(),"",List.of());
                index.put(newer); assertEquals("v2",index.get(List.of(id)).get(0).fingerprint()); assertTrue(index.get(List.of(id)).get(0).chunks().isEmpty());
                assertEquals("v2",index.catalog().stream().filter(d->d.novelId().equals(id)).findFirst().orElseThrow().fingerprint(),"Writes invalidate cached catalog immediately");
                es.index(i->i.index("novel_book_knowledge_catalog_v1").id(id).document(new BookKnowledgeIndex.CatalogEntry(0,doc)));
                var repaired=index.metadata(List.of(id)).get(0);
                assertEquals("v2",repaired.fingerprint(),"Outdated projections are repaired against the committed source version");
                assertNull(repaired.chunks(),"Projection repair must not leak body text or vectors");
                assertTrue(index.recall(new RecallQuery(List.of(id),"调查",List.of(1.0,0.0),"test-model")).hits().isEmpty(),"Uncommitted/stale generations must never be recalled");
            } finally { index.delete(id); }
            assertTrue(index.get(List.of(id)).isEmpty());
            assertTrue(index.catalog().stream().noneMatch(d->d.novelId().equals(id)),"Deletes invalidate cached catalog immediately");
        }
    }
}
