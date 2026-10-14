package com.novel.modules.agent.bookfinder.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.*;
import com.novel.common.internal.BookKnowledge.*;
import com.novel.modules.agent.bookfinder.client.*;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Service @RequiredArgsConstructor @Slf4j
public class BookKnowledgeService {
    private final BookFinderContentClient content;
    private final BookFinderSearchClient search;
    private final RetrievalModels models;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final KnowledgeNotes notes;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(100),
            r->{var t=new Thread(r,"book-knowledge-builder");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public record BuildRequest(List<String> ids,int chapters,boolean vectors) {}
    public record Task(String id,String novelId,int chapters,boolean vectors,String status,int chunks,
                       String notice,String updatedAt,long userId) {}
    public record Retrieval(List<Hit> hits,List<String> unavailable,String mode,String notice,List<KnowledgeNotes.Note> reviewedNotes) {
        public Retrieval(List<Hit> hits,List<String> unavailable,String mode,String notice) { this(hits,unavailable,mode,notice,List.of()); }
    }
    private String lock(String id) { return "agent:knowledge:lock:"+id; }
    private String acquire(String id) {
        String token=UUID.randomUUID().toString();
        if(!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock(id),token,Duration.ofHours(2))))
            throw new BusinessException(409,"该作品的知识库正在处理，请稍后重试");
        return token;
    }
    private void release(String id,String token) {
        redis.execute(new DefaultRedisScript<Long>("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end",Long.class),List.of(lock(id)),token);
    }
    public Map<String,Object> status() { return models.status(); }
    public List<Document> catalog() { return InternalResults.unwrapBusiness(search.knowledgeCatalog(),"知识库目录"); }
    public List<BookFinderBook> candidates(BookFinderQuery query,String preference) throws Exception {
        if(preference==null || preference.isBlank() || query.keyword()!=null) return List.of();
        var shortlist=catalog().stream().filter(d->d.book()!=null && d.book().matches(query) && !query.excludeIds().contains(d.novelId()))
                .sorted(Comparator.<Document>comparingDouble(d->EvidenceRanking.lexical(preference,
                        d.book().title()+" "+d.book().intro()+" "+d.book().tags())).reversed().thenComparing(Document::novelId))
                .limit(1000).map(Document::novelId).toList();
        if(shortlist.isEmpty()) return List.of();
        List<Double> vector=List.of();
        if(models.embeddingEnabled()) {
            try { vector=models.embed(List.of(preference)).get(0); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
            catch(Exception e) { log.warn("Candidate embedding unavailable: {}",e.getClass().getSimpleName()); }
        }
        var recall=InternalResults.unwrapBusiness(search.recall(new RecallQuery(shortlist,preference,vector,models.embeddingIdentity())),"Native candidate recall");
        var ranked=recall.hits().stream().map(r->new EvidenceRanking.Ranked(r.document(),r.chunk(),r.score())).toList();
        var ids=ranked.stream().map(r->r.document().novelId()).distinct().limit(15).toList();
        if(ids.isEmpty()) return List.of();
        // Fresh details enforce publication and hard filters independently from index metadata.
        return InternalResults.unwrapBusiness(content.details(ids),"语义候选可见性").stream()
                .filter(b->b.matches(query)&&!query.excludeIds().contains(b.id())).toList();
    }
    public List<Task> tasks() {
        var ids=redis.opsForList().range("agent:knowledge:tasks",0,99); if(ids==null||ids.isEmpty()) return List.of();
        var result=new ArrayList<Task>();
        for(var id:ids) {
            String value=redis.opsForValue().get("agent:knowledge:task:"+id);
            if(value!=null) try {
                var t=json.readValue(value,Task.class);
                if(Set.of("QUEUED","RUNNING").contains(t.status()) && Instant.parse(t.updatedAt()).isBefore(Instant.now().minus(t.status().equals("QUEUED")?Duration.ofHours(2):Duration.ofMinutes(10))))
                    t=new Task(t.id(),t.novelId(),t.chapters(),t.vectors(),"INTERRUPTED",t.chunks(),"任务中断或租约过期，可重新提交",t.updatedAt(),t.userId());
                result.add(t);
            } catch(Exception e) { throw new IllegalStateException("知识任务记录格式错误"); }
        }
        return result;
    }
    public List<Task> submit(BuildRequest request,long userId) {
        if(request.ids()==null || request.ids().isEmpty() || request.ids().size()>50 || request.chapters()<1 || request.chapters()>50)
            throw new BusinessException(400,"一次最多选择 50 本书，每本支持前 1～50 章");
        new BookFinderQuery(null,null,null,null,null,null,request.ids(),20);
        if(request.vectors() && !models.embeddingEnabled()) throw new BusinessException(400,"请先配置独立的 Embedding 接口");
        var result=new ArrayList<Task>();
        for(var id:request.ids().stream().distinct().toList()) {
            String token=acquire(id);
            var task=new Task(UUID.randomUUID().toString(),id,request.chapters(),request.vectors(),"QUEUED",0,"等待构建",Instant.now().toString(),userId);
            try {
                save(task);
                redis.opsForList().leftPush("agent:knowledge:tasks",task.id()); redis.opsForList().trim("agent:knowledge:tasks",0,99);
                worker.execute(()->build(task,token)); result.add(task);
            } catch(RuntimeException e) {
                release(id,token);
                save(change(task,"FAILED",0,"构建队列暂时不可用"));
                throw new BusinessException(429,"构建队列暂时不可用，已提交的任务可在列表查看");
            }
        }
        return result;
    }
    public List<Task> reindex(List<String> ids,long userId) {
        if(ids==null || ids.isEmpty() || ids.size()>50) throw new BusinessException(400,"每批迁移1至50本书");
        for(int i=0;i<ids.size();i+=20) new BookFinderQuery(null,null,null,null,null,null,ids.subList(i,Math.min(i+20,ids.size())),20);
        var accepted=new ArrayList<Task>();
        for(var id:ids.stream().distinct().toList()) {
            String token=acquire(id);
            var task=new Task(UUID.randomUUID().toString(),id,0,false,"QUEUED",0,"等待迁移 ES 检索索引，不调用模型",Instant.now().toString(),userId);
            try {
                save(task); redis.opsForList().leftPush("agent:knowledge:tasks",task.id()); redis.opsForList().trim("agent:knowledge:tasks",0,99);
                worker.execute(()->{
                    try {
                        save(change(task,"RUNNING",0,"正在复用正文和已有向量迁移 ES 索引"));
                        var docs=InternalResults.unwrapBusiness(search.knowledge(List.of(id)),"迁移资料读取");
                        if(docs.isEmpty()) throw new BusinessException(404,"资料尚未构建");
                        var doc=docs.get(0);
                        var current=InternalResults.unwrapBusiness(content.snapshot(id,doc.requestedChapters()),"迁移资料版本复核");
                        if(current==null || !current.fingerprint().equals(doc.fingerprint()) || !token.equals(redis.opsForValue().get(lock(id)))) throw new BusinessException(409,"资料已过期，请重新构建");
                        InternalResults.unwrapBusiness(search.putKnowledge(doc),"ES 索引迁移");
                        save(change(task,"DONE",doc.chunks().size(),"ES 原生检索索引迁移完成；未调用模型"));
                    } catch(Exception e) { save(change(task,"FAILED",0,e instanceof BusinessException?e.getMessage():"迁移失败："+e.getClass().getSimpleName())); }
                    finally { release(id,token); }
                }); accepted.add(task);
            } catch(RuntimeException e) { release(id,token); save(change(task,"FAILED",0,"迁移队列已满")); throw new BusinessException(429,"队列已满，已提交任务请查看任务记录"); }
        }
        return accepted;
    }
    private void build(Task task,String token) {
        try {
            Long lease=redis.execute(new DefaultRedisScript<Long>("if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('EXPIRE',KEYS[1],600) else return 0 end",Long.class),List.of(lock(task.novelId())),token);
            if(lease==null || lease==0) throw new BusinessException(409,"任务租约已过期，请重新提交");
            save(change(task,"RUNNING",0,"正在读取已发布章节"));
            var snapshot=InternalResults.unwrapBusiness(content.snapshot(task.novelId(),task.chapters()),"知识库构建取数");
            if(snapshot==null) throw new BusinessException(404,"作品不存在或未上架");
            var old=InternalResults.unwrapBusiness(search.knowledge(List.of(task.novelId())),"知识库版本检查");
            String model=task.vectors()?models.embeddingIdentity():"";
            if(!old.isEmpty() && old.get(0).fingerprint().equals(snapshot.fingerprint()) && Objects.equals(old.get(0).embeddingModel(),model)) {
                save(change(task,"DONE",old.get(0).chunks().size(),"内容版本一致，复用已有索引，未调用 Embedding")); return;
            }
            var chunks=new ArrayList<>(EvidenceRanking.split(snapshot));
            if(task.vectors()) for(int start=0;start<chunks.size();start+=16) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int end=Math.min(chunks.size(),start+16);
                var vectors=models.embed(chunks.subList(start,end).stream().map(Chunk::text).toList());
                for(int i=start;i<end;i++) {
                    var c=chunks.get(i);
                    chunks.set(i,new Chunk(c.id(),c.chapterId(),c.chapterNumber(),c.chapterTitle(),c.start(),c.end(),c.text(),vectors.get(i-start)));
                }
                save(change(task,"RUNNING",end,"向量化进度 "+end+" / "+chunks.size()));
            }
            var current=InternalResults.unwrapBusiness(content.snapshot(task.novelId(),task.chapters()),"构建版本复核");
            if(current==null || !current.fingerprint().equals(snapshot.fingerprint()) || !token.equals(redis.opsForValue().get(lock(task.novelId()))))
                throw new BusinessException(409,"构建期间内容已变化或租约已过期，请重试");
            if(task.vectors() && !model.equals(models.embeddingIdentity()))
                throw new BusinessException(409,"构建期间向量模型配置已变化，请使用新配置重新构建");
            int chars=snapshot.chapters().stream().mapToInt(c->c.text().length()).sum();
            var document=new Document(task.novelId(),snapshot.book(),snapshot.fingerprint(),task.chapters(),snapshot.chapters().size(),
                    snapshot.totalChapters(),chars,snapshot.truncated(),Instant.now().toString(),model,chunks);
            InternalResults.unwrapBusiness(search.putKnowledge(document),"知识库写入");
            save(change(task,"DONE",chunks.size(),"构建完成；仅覆盖前 "+snapshot.chapters().size()+" 个已发布章节"+(snapshot.truncated()?"（正文达到长度上限，有截断）":"")));
        } catch(Exception e) {
            save(change(task,"FAILED",0,e instanceof BusinessException?e.getMessage():"知识构建失败："+e.getClass().getSimpleName()));
            log.warn("知识构建失败 | novel={} task={} errorType={}",task.novelId(),task.id(),e.getClass().getSimpleName());
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { release(task.novelId(),token); }
    }
    public void remove(String id) {
        new BookFinderQuery(null,null,null,null,null,null,List.of(id),20);
        String token=acquire(id);
        try { InternalResults.unwrapBusiness(search.deleteKnowledge(id),"删除知识库"); redis.opsForHash().delete("agent:knowledge:summaries",id); }
        finally { release(id,token); }
    }
    /** MQ reads latest source truth. Duplicate/late events do not invalidate an unchanged index. */
    public void synchronize(String id) {
        var docs=InternalResults.unwrapBusiness(search.knowledge(List.of(id)),"知识库事件查询");
        if(docs.isEmpty()) return;
        String token=acquire(id);
        try {
            docs=InternalResults.unwrapBusiness(search.knowledge(List.of(id)),"知识库事件复核");
            if(docs.isEmpty()) return;
            var doc=docs.get(0);
            var current=InternalResults.unwrapBusiness(content.snapshot(id,doc.requestedChapters()),"知识库事件取数");
            if(current==null || !current.fingerprint().equals(doc.fingerprint())) {
                InternalResults.unwrapBusiness(search.deleteKnowledge(id),"失效知识删除");
                redis.opsForHash().delete("agent:knowledge:summaries",id);
                var task=new Task(UUID.randomUUID().toString(),id,doc.requestedChapters(),false,"STALE",0,
                        "源内容变化，旧知识已移除，请重新构建",Instant.now().toString(),0);
                save(task); redis.opsForList().leftPush("agent:knowledge:tasks",task.id()); redis.opsForList().trim("agent:knowledge:tasks",0,99);
            }
        } finally { release(id,token); }
    }
    public Retrieval retrieve(EvidenceQuery query) throws Exception {
        var docs=InternalResults.unwrapBusiness(search.knowledgeMetadata(query.ids()),"正文证据读取");
        var valid=new ArrayList<Document>(); var unavailable=new ArrayList<String>();
        // Database truth protects against MQ delay, stale chapters and unpublished books.
        for(var doc:docs) {
            var current=InternalResults.unwrapBusiness(content.snapshot(doc.novelId(),doc.requestedChapters()),"正文证据版本复核");
            if(current!=null && current.fingerprint().equals(doc.fingerprint())) valid.add(doc); else unavailable.add(doc.novelId());
        }
        var indexed=new HashSet<String>(); valid.forEach(d->indexed.add(d.novelId()));
        query.ids().stream().filter(id->!indexed.contains(id)&&!unavailable.contains(id)).forEach(unavailable::add);
        List<Double> vector=List.of(); String mode="KEYWORD",notice="";
        if(models.embeddingEnabled() && valid.stream().anyMatch(d->Objects.equals(d.embeddingModel(),models.embeddingIdentity()))) {
            try { vector=models.embed(List.of(query.query())).get(0); mode="HYBRID_RRF"; }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
            catch(Exception e) { notice="Embedding 暂时不可用，本次使用关键词证据检索"; }
        }
        var recall=InternalResults.unwrapBusiness(search.recall(new RecallQuery(valid.stream().map(Document::novelId).toList().isEmpty()?query.ids():valid.stream().map(Document::novelId).toList(),query.query(),vector,models.embeddingIdentity())),"Native evidence recall");
        var allowed=new HashMap<String,String>(); valid.forEach(d->allowed.put(d.novelId(),d.fingerprint()));
        var ranked=recall.hits().stream().filter(r->Objects.equals(allowed.get(r.document().novelId()),r.document().fingerprint()))
                .map(r->new EvidenceRanking.Ranked(r.document(),r.chunk(),r.score())).toList();
        mode=recall.mode();

        if(models.rerankEnabled() && !ranked.isEmpty()) {
            try {
                var order=models.rerank(query.query(),ranked.stream().map(r->r.chunk().text()).toList());
                var original=ranked; ranked=order.stream().map(original::get).toList(); mode+="_RERANK";
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
            catch(Exception e) { notice+=(notice.isBlank()?"":"；")+"Rerank 暂时不可用，保留原排序"; }
        }
        var counts=new HashMap<String,Integer>(); var hits=new ArrayList<Hit>();
        var diverse=new ArrayList<EvidenceRanking.Ranked>();
        var seenBooks=new HashSet<String>();
        for(var r:ranked) if(seenBooks.add(r.document().novelId())) diverse.add(r);
        for(var r:ranked) if(!diverse.contains(r)) diverse.add(r);
        for(var r:diverse) {
            var d=r.document(); if(counts.getOrDefault(d.novelId(),0)>=2) continue;
            counts.merge(d.novelId(),1,Integer::sum);
            String quote=excerpt(r.chunk().text(),query.query());
            hits.add(new Hit(d.novelId(),r.chunk().id(),r.chunk().chapterId(),r.chunk().chapterNumber(),quote,r.score(),
                    d.coveredChapters(),d.totalChapters(),d.truncated(),d.fingerprint(),mode));
            if(hits.size()==6) break;
        }
        return new Retrieval(hits,unavailable,mode,notice,notes.reviewed(hits));
    }
    public Task submitSummary(String id,long userId) {
        var doc=notes.preview(id); String token=acquire(id);
        var task=new Task(UUID.randomUUID().toString(),id,doc.requestedChapters(),false,"QUEUED",0,"等待生成待审摘要",Instant.now().toString(),userId);
        try {
            save(task); redis.opsForList().leftPush("agent:knowledge:tasks",task.id()); redis.opsForList().trim("agent:knowledge:tasks",0,99);
            worker.execute(()->{
                try {
                    save(change(task,"RUNNING",0,"正在生成待审摘要")); notes.draftWithModel(id,userId);
                    save(change(task,"DONE",0,"摘要草稿已生成，需管理员审核才能参与推荐"));
                } catch(Exception e) { save(change(task,"FAILED",0,"摘要生成失败："+(e instanceof BusinessException?e.getMessage():e.getClass().getSimpleName()))); }
                finally { release(id,token); }
            }); return task;
        } catch(RuntimeException e) { release(id,token); save(change(task,"FAILED",0,"摘要队列暂时不可用")); throw new BusinessException(429,"摘要队列暂时不可用"); }
    }
    private String excerpt(String text,String query) {
        int position=text.length();
        for(var term:EvidenceRanking.terms(query)) { int found=text.indexOf(term); if(found>=0) position=Math.min(position,found); }
        int start=position==text.length()?0:Math.max(0,position-80);
        return text.substring(start,Math.min(text.length(),start+400));
    }
    public boolean fresh(Hit hit) {
        var docs=InternalResults.unwrapBusiness(search.knowledgeMetadata(List.of(hit.novelId())),"推荐证据版本查询");
        if(docs.isEmpty() || !docs.get(0).fingerprint().equals(hit.fingerprint())) return false;
        var snapshot=InternalResults.unwrapBusiness(content.snapshot(hit.novelId(),docs.get(0).requestedChapters()),"推荐证据版本复核");
        return snapshot!=null && snapshot.fingerprint().equals(hit.fingerprint());
    }
    private Task change(Task t,String status,int chunks,String notice) {
        return new Task(t.id(),t.novelId(),t.chapters(),t.vectors(),status,chunks,notice,Instant.now().toString(),t.userId());
    }
    private void save(Task task) {
        try { redis.opsForValue().set("agent:knowledge:task:"+task.id(),json.writeValueAsString(task),Duration.ofDays(7)); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    @PreDestroy public void shutdown() { worker.shutdownNow(); }
}
