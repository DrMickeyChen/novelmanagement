package com.novel.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.internal.BookKnowledge.*;
import java.io.*;
import java.util.*;

/** Per-chunk BM25/HNSW indexes. The atomic book metadata is the committed generation. */
public final class NativeKnowledgeRecall {
    private final ElasticsearchClient es;
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final String PREFIX="novel_book_chunks_v2_";
    public NativeKnowledgeRecall(ElasticsearchClient es) { this.es=es; }
    private String index(int dims) { return PREFIX+dims; }
    private synchronized void ensure(int dims) throws IOException {
        if(es.indices().exists(e->e.index(index(dims))).value()) return;
        var props=new LinkedHashMap<String,Object>();
        for(var field:List.of("novelId","fingerprint","model","chunkId")) props.put(field,Map.of("type","keyword"));
        props.put("text",Map.of("type","text"));
        props.put("metadata",Map.of("type","object","enabled",false));
        props.put("chunk",Map.of("type","object","enabled",false));
        if(dims>0) props.put("vector",Map.of("type","dense_vector","dims",dims,"index",true,"similarity","cosine","index_options",Map.of("type","hnsw","m",16,"ef_construction",100)));
        String mapping=JSON.writeValueAsString(Map.of("mappings",Map.of("dynamic",false,"properties",props)));
        try { es.indices().create(c->c.index(index(dims)).withJson(new StringReader(mapping))); }
        catch(co.elastic.clients.elasticsearch._types.ElasticsearchException e) {
            if(!"resource_already_exists_exception".equals(e.error().type())) throw e;
        }
    }
    private Document metadata(Document d) {
        return new Document(d.novelId(),d.book(),d.fingerprint(),d.requestedChapters(),d.coveredChapters(),d.totalChapters(),d.characterCount(),d.truncated(),d.builtAt(),d.embeddingModel(),null);
    }
    public void write(Document d) throws IOException {
        if(d.chunks()==null || d.chunks().isEmpty()) return;
        int dims=d.chunks().get(0).vector()==null?0:d.chunks().get(0).vector().size();
        if(dims>4096) throw new IOException("Invalid embedding dimension");
        for(var c:d.chunks()) {
            if((c.vector()==null?0:c.vector().size())!=dims || (dims>0 && (c.vector().stream().anyMatch(v->v==null||!Double.isFinite(v)) || c.vector().stream().allMatch(v->v==0)))) throw new IOException("Invalid embedding vector");
        }
        ensure(dims);
        var response=es.bulk(b->{
            b.refresh(Refresh.WaitFor);
            for(var c:d.chunks()) {
                var row=new LinkedHashMap<String,Object>();
                row.put("novelId",d.novelId()); row.put("fingerprint",d.fingerprint()); row.put("model",Objects.toString(d.embeddingModel(),""));
                row.put("chunkId",c.id()); row.put("text",c.text()); row.put("metadata",metadata(d));
                row.put("chunk",new Chunk(c.id(),c.chapterId(),c.chapterNumber(),c.chapterTitle(),c.start(),c.end(),c.text(),List.of()));
                if(dims>0) row.put("vector",c.vector());
                // Model identity is part of generation: switching models with same source cannot overwrite committed data.
                String id=d.novelId()+":"+d.fingerprint()+":"+Objects.toString(d.embeddingModel(),"").hashCode()+":"+c.id();
                b.operations(o->o.index(i->i.index(index(dims)).id(id).document(row)));
            }
            return b;
        });
        if(response.errors()) throw new IOException("Chunk index bulk write failed; book generation not committed");
    }
    public void delete(String id) throws IOException {
        es.deleteByQuery(d->d.index(PREFIX+"*").ignoreUnavailable(true).refresh(true).query(q->q.term(t->t.field("novelId").value(id))));
    }
    private Map<String,Object> filter(List<Document> docs,String model) {
        var clauses=new ArrayList<Object>();
        for(var d:docs) {
            if(model!=null && !Objects.equals(model,d.embeddingModel())) continue;
            clauses.add(Map.of("bool",Map.of("filter",List.of(Map.of("term",Map.of("novelId",d.novelId())),Map.of("term",Map.of("fingerprint",d.fingerprint())),Map.of("term",Map.of("model",Objects.toString(d.embeddingModel(),"")))))));
        }
        return clauses.isEmpty()?Map.of("match_none",Map.of()):Map.of("bool",Map.of("should",clauses,"minimum_should_match",1));
    }
    @SuppressWarnings("unchecked")
    private List<RecallHit> query(String name,Map<String,Object> body) throws IOException {
        String request=JSON.writeValueAsString(body);
        var rows=es.search(s->s.index(name).ignoreUnavailable(true).withJson(new StringReader(request)),Map.class);
        var result=new ArrayList<RecallHit>();
        for(var h:rows.hits().hits()) if(h.source()!=null) {
            Map<String,Object> row=h.source();
            result.add(new RecallHit(JSON.convertValue(row.get("metadata"),Document.class),JSON.convertValue(row.get("chunk"),Chunk.class),h.score()==null?0:h.score()));
        }
        return result;
    }
    public Recall search(RecallQuery q,List<Document> committed) throws IOException {
        if(committed.isEmpty()) return new Recall(List.of(),"ES_BM25");
        var lexical=query(PREFIX+"*",Map.of("size",60,"_source",Map.of("excludes",List.of("vector","text")),"query",Map.of("bool",Map.of("filter",List.of(filter(committed,null)),"must",List.of(Map.of("match",Map.of("text",q.query())))))));
        List<RecallHit> semantic=List.of();
        if(!q.vector().isEmpty() && es.indices().exists(e->e.index(index(q.vector().size()))).value()) {
            semantic=query(index(q.vector().size()),Map.of("size",60,"_source",Map.of("excludes",List.of("vector","text")),"knn",Map.of("field","vector","query_vector",q.vector(),"k",60,"num_candidates",200,"filter",filter(committed,q.embeddingModel()))));
        }
        var rows=new HashMap<String,RecallHit>(); var scores=new HashMap<String,Double>();
        for(var list:List.of(lexical,semantic)) for(int i=0;i<list.size();i++) {
            var r=list.get(i); rows.put(r.chunk().id(),r); scores.merge(r.chunk().id(),1.0/(61+i),Double::sum);
        }
        var ranked=rows.values().stream().map(r->new RecallHit(r.document(),r.chunk(),scores.get(r.chunk().id())))
                .sorted(Comparator.comparingDouble(RecallHit::score).reversed().thenComparing(r->r.chunk().id())).toList();
        var diverse=new ArrayList<RecallHit>(); var books=new HashSet<String>();
        for(var r:ranked) if(books.add(r.document().novelId())) diverse.add(r);
        for(var r:ranked) if(!diverse.contains(r)) diverse.add(r);
        var hits=diverse.stream().limit(20).toList();
        return new Recall(hits,q.vector().isEmpty()?"ES_BM25":"ES_HYBRID_RRF");
    }
}
