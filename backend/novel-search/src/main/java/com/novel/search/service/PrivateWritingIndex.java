package com.novel.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.internal.WritingText;
import com.novel.common.security.UserContext;
import com.novel.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;
import java.io.*;

/** Separate private indices. Owner and authorized chapter-version keys filter both BM25 and ANN. */
@Service @RequiredArgsConstructor
public class PrivateWritingIndex {
    private final ElasticsearchClient es;
    private String base="novel_writer_private_v1";
    public record Doc(String ownerId,String novelId,String sourceKey,Source source,List<Double> vector) {}
    private String vectorIndex(String identity){return base+"_"+WritingText.hash(identity).substring(0,12);}
    private synchronized void ensure(String index,int dims)throws IOException {
        if(es.indices().exists(e->e.index(index)).value())return;
        String mapping="{\"mappings\":{\"dynamic\":false,\"properties\":{\"ownerId\":{\"type\":\"keyword\"},\"novelId\":{\"type\":\"keyword\"},\"sourceKey\":{\"type\":\"keyword\"},\"source\":{\"properties\":{\"text\":{\"type\":\"text\",\"analyzer\":\"cjk\"}}}"+(dims>0?",\"vector\":{\"type\":\"dense_vector\",\"dims\":"+dims+",\"index\":true,\"similarity\":\"cosine\"}":"")+"}}}";
        try {es.indices().create(c->c.index(index).withJson(new StringReader(mapping)));}
        catch(co.elastic.clients.elasticsearch._types.ElasticsearchException e){if(!"resource_already_exists_exception".equals(e.error().type()))throw e;}
    }
    public void put(List<PrivateChunk> chunks)throws IOException {
        if(chunks==null||chunks.isEmpty()||chunks.size()>150)throw error("一次最多索引 150 个片段");
        String owner=UserContext.requireUserId().toString();ensure(base,0);
        for(var chunk:chunks){var source=chunk.source();if(source.text().length()>2000)throw error("片段过长");
            var doc=new Doc(owner,chunk.novelId(),source.chapterId()+":"+source.version(),source,null);
            String id=WritingText.hash(owner+":"+chunk.novelId()+":"+source.id());
            es.index(i->i.index(base).id(id).document(doc));
            if(chunk.vector()!=null&&!chunk.vector().isEmpty()) {
                String index=vectorIndex(chunk.embeddingId());ensure(index,chunk.vector().size());
                var vectorDoc=new Doc(owner,chunk.novelId(),doc.sourceKey(),source,chunk.vector());es.index(i->i.index(index).id(id).document(vectorDoc));
            }
        }
        es.indices().refresh(r->r.index(base));
        for(String index:chunks.stream().filter(c->c.vector()!=null&&!c.vector().isEmpty()).map(c->vectorIndex(c.embeddingId())).distinct().toList())es.indices().refresh(r->r.index(index));
    }
    private Query filter(PrivateRecall req) {
        var keys=req.sourceKeys().stream().map(co.elastic.clients.elasticsearch._types.FieldValue::of).toList();
        return Query.of(q->q.bool(b->b.filter(f->f.term(t->t.field("ownerId").value(UserContext.requireUserId().toString())))
                .filter(f->f.term(t->t.field("novelId").value(req.novelId())))
                .filter(f->f.terms(t->t.field("sourceKey").terms(v->v.value(keys))))));
    }
    public PrivateEvidence search(PrivateRecall req)throws IOException {
        if(req.sourceKeys()==null||req.sourceKeys().size()>10000||req.query()==null||req.query().isBlank()||req.query().length()>200||req.limit()<1||req.limit()>20)throw error("检索参数无效");
        UserContext.requireUserId();if(req.sourceKeys().isEmpty()||!es.indices().exists(e->e.index(base)).value())return new PrivateEvidence(List.of(),"EMPTY_INDEX");
        Query acl=filter(req);var lexical=es.search(s->s.index(base).size(20).query(q->q.bool(b->b.filter(acl).must(m->m.match(t->t.field("source.text").query(req.query()))))),Doc.class);
        var fused=new HashMap<String,Double>();var sources=new HashMap<String,Source>();int rank=0;
        for(var hit:lexical.hits().hits()){if(hit.source()==null)continue;String id=hit.id();sources.put(id,hit.source().source());fused.merge(id,1.0/(60+(++rank)),Double::sum);}
        boolean nativeVectorUsed=false;
        if(req.vector()!=null&&!req.vector().isEmpty()) {
            String index=vectorIndex(req.embeddingId());if(es.indices().exists(e->e.index(index)).value()) {
                nativeVectorUsed=true;
                var vector=req.vector().stream().map(Double::floatValue).toList();
                var semantic=es.search(s->s.index(index).size(20).knn(k->k.field("vector").queryVector(vector).k(20).numCandidates(100).filter(acl)),Doc.class);rank=0;
                for(var hit:semantic.hits().hits()){if(hit.source()==null)continue;String id=hit.id();sources.put(id,hit.source().source());fused.merge(id,1.0/(60+(++rank)),Double::sum);}
            }
        }
        return new PrivateEvidence(fused.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).limit(req.limit()).map(e->sources.get(e.getKey())).toList(),nativeVectorUsed?"BM25 + HNSW + RRF":"BM25");
    }
    public void delete(String novel)throws IOException {
        String owner=UserContext.requireUserId().toString();
        Query owned=Query.of(q->q.bool(b->b.filter(f->f.term(t->t.field("ownerId").value(owner)))
                .filter(f->f.term(t->t.field("novelId").value(novel)))));
        for(var index:es.indices().get(g->g.index(base+"*").allowNoIndices(true)).result().keySet())
            es.deleteByQuery(d->d.index(index).query(owned).refresh(true));
    }
    public void deleteChapter(String novel,String chapter)throws IOException {
        String owner=UserContext.requireUserId().toString();
        if(!chapter.matches("[1-9][0-9]{0,18}"))throw error("章节编号无效");
        Query owned=Query.of(q->q.bool(b->b.filter(f->f.term(t->t.field("ownerId").value(owner)))
                .filter(f->f.term(t->t.field("novelId").value(novel)))
                .filter(f->f.prefix(p->p.field("sourceKey").value(chapter+":")))));
        for(var index:es.indices().get(g->g.index(base+"*").allowNoIndices(true)).result().keySet())es.deleteByQuery(d->d.index(index).query(owned).refresh(true));
    }
    private BusinessException error(String message){return new BusinessException(422,message,422);}
}
