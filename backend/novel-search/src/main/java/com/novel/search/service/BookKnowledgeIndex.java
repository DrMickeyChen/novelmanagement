package com.novel.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.novel.common.internal.BookKnowledge.Document;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.io.*;
import java.util.*;

/** One ES document per book: atomic replacement cannot expose half-built chapter chunks. */
@Service @RequiredArgsConstructor
public class BookKnowledgeIndex {
    private final ElasticsearchClient es;
    private static final String INDEX="novel_book_knowledge_v1";
    private static final String CATALOG_INDEX="novel_book_knowledge_catalog_v1";
    public record CatalogEntry(long sourceVersion,Document document) {}
    private boolean catalogIndexReady;
    private synchronized void ensureCatalogIndex() throws IOException {
        if(catalogIndexReady) return;
        if(!es.indices().exists(e->e.index(CATALOG_INDEX)).value()) {
            try { es.indices().create(c->c.index(CATALOG_INDEX).withJson(new StringReader("""
                {"mappings":{"dynamic":false,"properties":{"sourceVersion":{"type":"long"},"document":{"type":"object","enabled":false}}}}
                """))); }
            catch(co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
                if(!"resource_already_exists_exception".equals(e.error().type())) throw e;
            }
        }
        catalogIndexReady=true;
    }
    private Document summary(Document d) {
        return new Document(d.novelId(),d.book(),d.fingerprint(),d.requestedChapters(),d.coveredChapters(),
            d.totalChapters(),d.characterCount(),d.truncated(),d.builtAt(),d.embeddingModel(),null);
    }
    private void storeSummary(Document d,long version) throws IOException {
        ensureCatalogIndex();
        // Directory reads use real-time mget, so a per-book refresh wait is unnecessary.
        es.index(i->i.index(CATALOG_INDEX).id(d.novelId()).document(new CatalogEntry(version,summary(d))));
    }
    // Catalog responses omit body/vector data. Coalesce concurrent loads instead of
    // repeatedly parsing every large ES _source while a batch is being built.
    private List<Document> catalogCache;
    private long catalogExpiresAt;
    private static final long CATALOG_TTL_NANOS=java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
    private synchronized void invalidateCatalog() { catalogCache=null; catalogExpiresAt=0; }
    private synchronized void ensure() throws IOException {
        if(!es.indices().exists(e->e.index(INDEX)).value()) es.indices().create(c->c.index(INDEX).withJson(new StringReader("""
            {"mappings":{"dynamic":false,"properties":{"novelId":{"type":"keyword"},"builtAt":{"type":"date"},"chunks":{"type":"object","enabled":false}}}}
            """)));
    }
    public void put(Document doc) throws IOException {
        ensure(); new NativeKnowledgeRecall(es).write(doc);
        try {
            var committed=es.index(i->i.index(INDEX).id(doc.novelId()).document(doc).refresh(Refresh.WaitFor));
            storeSummary(doc,committed.version());
        }
        finally { invalidateCatalog(); }
    }
    public com.novel.common.internal.BookKnowledge.Recall recall(com.novel.common.internal.BookKnowledge.RecallQuery query) throws IOException {
        return new NativeKnowledgeRecall(es).search(query,metadata(query.ids()));
    }
    public List<Document> metadata(List<String> ids) throws IOException {
        if(ids.isEmpty() || !es.indices().exists(e->e.index(INDEX)).value()) return List.of();
        var versions=new LinkedHashMap<String,Long>();
        es.search(s->s.index(INDEX).size(ids.size()).query(q->q.ids(i->i.values(ids))).source(src->src.fetch(false)).version(true),Void.class)
            .hits().hits().forEach(h->versions.put(h.id(),h.version()));
        return summaries(versions);
    }
    private List<Document> summaries(Map<String,Long> versions) throws IOException {
        if(versions.isEmpty()) return List.of();
        ensureCatalogIndex();
        var documents=new HashMap<String,Document>();
        var response=es.mget(m->m.index(CATALOG_INDEX).ids(new ArrayList<>(versions.keySet())),CatalogEntry.class);
        for(var item:response.docs()) {
            if(item.isFailure()) throw new IOException("知识目录读取失败");
            var entry=item.result().source();
            if(entry!=null && Objects.equals(versions.get(item.result().id()),entry.sourceVersion()))
                documents.put(item.result().id(),entry.document());
        }
        // Repair missing or outdated projections in bounded batches. The original
        // document's version remains authoritative, including after partial writes.
        var missing=versions.keySet().stream().filter(id->!documents.containsKey(id)).toList();
        for(int start=0;start<missing.size();start+=20) {
            var batch=missing.subList(start,Math.min(start+20,missing.size()));
            var repaired=es.search(s->s.index(INDEX).size(batch.size()).query(q->q.ids(i->i.values(batch))).version(true)
                .source(src->src.filter(f->f.excludes("chunks"))),Document.class);
            for(var hit:repaired.hits().hits()) {
                if(hit.source()==null) continue;
                var d=summary(hit.source()); storeSummary(d,hit.version()); documents.put(d.novelId(),d);
            }
        }
        return versions.keySet().stream().map(documents::get).filter(Objects::nonNull).toList();
    }
    public List<Document> get(List<String> ids) throws IOException {
        if(ids.isEmpty() || !es.indices().exists(e->e.index(INDEX)).value()) return List.of();
        var result=es.mget(m->m.index(INDEX).ids(ids),Document.class);
        var docs=new ArrayList<Document>();
        for(var item:result.docs()) {
            if(item.isFailure()) throw new IOException("知识索引批量读取失败");
            if(item.result().found() && item.result().source()!=null) docs.add(item.result().source());
        }
        return docs;
    }
    public synchronized List<Document> catalog() throws IOException {
        if(catalogCache!=null && System.nanoTime()<catalogExpiresAt) return catalogCache;
        if(!es.indices().exists(e->e.index(INDEX)).value()) {
            catalogCache=List.of(); catalogExpiresAt=System.nanoTime()+CATALOG_TTL_NANOS;
            return catalogCache;
        }
        var versions=new LinkedHashMap<String,Long>();
        es.search(s->s.index(INDEX).size(1000).source(src->src.fetch(false)).version(true)
                .sort(o->o.field(f->f.field("builtAt").order(co.elastic.clients.elasticsearch._types.SortOrder.Desc))),Void.class)
                .hits().hits().forEach(h->versions.put(h.id(),h.version()));
        var result=summaries(versions);
        // Do not cache errors or replace them with an empty successful response.
        catalogCache=List.copyOf(result); catalogExpiresAt=System.nanoTime()+CATALOG_TTL_NANOS;
        return catalogCache;
    }
    public void delete(String id) throws IOException {
        try { if(es.indices().exists(e->e.index(INDEX)).value()) es.delete(d->d.index(INDEX).id(id).refresh(Refresh.WaitFor)); }
        finally { invalidateCatalog(); }
        if(es.indices().exists(e->e.index(CATALOG_INDEX)).value()) es.delete(d->d.index(CATALOG_INDEX).id(id).refresh(Refresh.WaitFor));
        new NativeKnowledgeRecall(es).delete(id);
    }
}
