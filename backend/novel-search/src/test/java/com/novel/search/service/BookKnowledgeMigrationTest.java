package com.novel.search.service;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.elasticsearch.client.RestClient;
import org.apache.http.HttpHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Explicit opt-in migration: copies existing vectors only, never changes committed book documents. */
@EnabledIfSystemProperty(named="knowledge.es.migrate",matches="true")
class BookKnowledgeMigrationTest {
    @Test void copyExistingKnowledgeWithoutCallingModels() throws Exception {
        try(var transport=new RestClientTransport(RestClient.builder(HttpHost.create(System.getProperty("knowledge.es.url","http://192.168.127.137:9200"))).build(),new JacksonJsonpMapper())) {
            var es=new ElasticsearchClient(transport); var index=new BookKnowledgeIndex(es); var nativeIndex=new NativeKnowledgeRecall(es);
            var catalog=index.catalog(); int books=0,chunks=0,vectors=0;
            var versions=new ArrayList<Map<String,Object>>();
            for(var item:catalog) {
                var docs=index.get(List.of(item.novelId())); if(docs.isEmpty()) continue;
                var doc=docs.get(0); nativeIndex.write(doc); books++; chunks+=doc.chunks().size();
                vectors+=(int)doc.chunks().stream().filter(c->c.vector()!=null&&!c.vector().isEmpty()).count();
                versions.add(Map.of("bookId",doc.novelId(),"fingerprint",doc.fingerprint(),"chunks",doc.chunks().size()));
            }
            var report=Map.of("time",Instant.now().toString(),"books",books,"chunks",chunks,"vectorChunks",vectors,"paidModelCalls",0,"originalDocumentsModified",false,"versions",versions);
            Path output=Path.of(System.getProperty("knowledge.migration.report","../../docs/evals/book-finder/native-index-migration.json"));
            Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("Native index copied: books="+books+", chunks="+chunks+", vectorChunks="+vectors+", paidModelCalls=0");
        }
    }
}
