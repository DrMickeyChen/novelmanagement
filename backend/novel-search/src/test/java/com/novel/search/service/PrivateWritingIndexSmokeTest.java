package com.novel.search.service;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.elasticsearch.client.RestClient;
import org.apache.http.HttpHost;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.security.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="writing.es.live",matches="true")
class PrivateWritingIndexSmokeTest {
    void login(long id){var u=new LoginUser();u.setUserId(id);u.setJti("fixture");u.setRoles(List.of("AUTHOR"));UserContext.set(u);}
    @Test void ownerBookAndVersionFiltersApplyBeforeLexicalAndNativeKnnRecall()throws Exception{
        String prefix="writer_test_"+UUID.randomUUID().toString().replace("-","");
        try(var transport=new RestClientTransport(RestClient.builder(HttpHost.create("http://192.168.127.137:9200")).build(),new JacksonJsonpMapper())){
            var es=new ElasticsearchClient(transport);var index=new PrivateWritingIndex(es);ReflectionTestUtils.setField(index,"base",prefix);
            try{
                var source=new Source("chapter:1:0","1",1,"第一章",0,0,7,true,"玉佩不能飞行。");
                login(1);index.put(List.of(new PrivateChunk("book-one",source,List.of(1.0,0.0),"fixture-two-dims")));
                var request=new PrivateRecall("book-one",List.of("1:0"),"玉佩",List.of(1.0,0.0),"fixture-two-dims",5);
                assertEquals(1,index.search(request).sources().size());
                assertTrue(index.search(new PrivateRecall("book-two",request.sourceKeys(),request.query(),request.vector(),request.embeddingId(),5)).sources().isEmpty());
                assertTrue(index.search(new PrivateRecall("book-one",List.of("1:1"),request.query(),request.vector(),request.embeddingId(),5)).sources().isEmpty());
                login(2);assertTrue(index.search(request).sources().isEmpty());
                index.put(List.of(new PrivateChunk("book-one",source,List.of(1.0,0.0),"fixture-two-dims")));
                assertEquals(1,index.search(request).sources().size());index.delete("book-one");assertTrue(index.search(request).sources().isEmpty());
                login(1);assertEquals(1,index.search(request).sources().size());
                index.deleteChapter("book-one","1");assertTrue(index.search(request).sources().isEmpty());
            }finally{
                try {
                    if(prefix.matches("writer_test_[a-f0-9]{32}")) {
                        for(String name:es.indices().get(g->g.index(prefix+"*").allowNoIndices(true)).result().keySet()) {
                            if(name.equals(prefix)||name.startsWith(prefix+"_"))es.indices().delete(d->d.index(name));
                        }
                    }
                } finally { UserContext.clear(); }
            }
        }
    }
}
