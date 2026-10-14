package com.novel.modules.agent.bookfinder.eval;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.BookFinderQuery;
import com.novel.common.security.UserContext;
import com.novel.modules.agent.bookfinder.BookFinderTypes.*;
import com.novel.modules.agent.bookfinder.service.BookFinderAgent;
import com.novel.modules.agent.bookfinder.conversation.BookFinderStore;
import com.novel.modules.agent.bookfinder.knowledge.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.math.BigDecimal;

/** Durable, exportable evaluation artifacts; no keys or hidden reasoning. */
@Service @RequiredArgsConstructor
public class BookFinderEvaluation {
    private final ObjectMapper json;
    private final BookFinderAgent agent;
    private final BookFinderStore store;
    private final BookKnowledgeService knowledge;
    private final KnowledgeNotes notes;
    @Value("${novel.book-finder.eval.directory:logs/book-finder-evals}") private String directory="logs/book-finder-evals";
    @Value("${novel.build-version:unrecorded}") private String codeVersion="unrecorded";
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{var t=new Thread(r,"book-finder-evaluation");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    public record Case(String id,String prompt,String group,BookFinderQuery expected,Boolean clarify,boolean allowEmpty) {}
    public record Start(String label,List<Case> cases,BigDecimal inputPricePerMillion,BigDecimal outputPricePerMillion,String pricingNote) {}
    public record Review(String verdict,String note) {}
    private Path path(String id) {
        try { if(!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch(Exception e) { throw new BusinessException(400,"评测 ID 不合法"); }
        return Path.of(directory).toAbsolutePath().normalize().resolve(id+".json");
    }
    private synchronized void save(String id,ObjectNode report) {
        try {
            Path target=path(id); Files.createDirectories(target.getParent());
            Path temp=Files.createTempFile(target.getParent(),"evaluation-",".tmp");
            try { Files.writeString(temp,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
                try { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
                catch(AtomicMoveNotSupportedException e) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
            } finally { Files.deleteIfExists(temp); }
        } catch(Exception e) { throw new IllegalStateException("评测报告保存失败",e); }
    }
    public synchronized ObjectNode get(String id) {
        try { if(!Files.exists(path(id))) throw new BusinessException(404,"评测报告不存在");
            var report=(ObjectNode)json.readTree(Files.readString(path(id)));
            if(Set.of("QUEUED","RUNNING").contains(report.path("status").asText()) && !active.contains(id)) report.put("status","INTERRUPTED");
            return report;
        } catch(BusinessException e) { throw e; } catch(Exception e) { throw new IllegalStateException("评测报告读取失败",e); }
    }
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    private final Set<String> cancelled=ConcurrentHashMap.newKeySet();
    public List<JsonNode> list() {
        try {
            var root=Path.of(directory); if(!Files.exists(root)) return List.of();
            try(var files=Files.list(root)) {
                return files.filter(p->p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).sorted(Comparator.comparingLong((Path p)->p.toFile().lastModified()).reversed()).limit(100)
                    .map(p->{var r=get(p.getFileName().toString().replace(".json","")); r.remove("rows"); return (JsonNode)r;}).toList();
            }
        } catch(Exception e) { throw new IllegalStateException("评测报告列表读取失败",e); }
    }
    public synchronized ObjectNode start(Start input) {
        if(!UserContext.hasRole("ADMIN")) throw new BusinessException(403,"仅管理员可以执行评测");
        if(input.label()==null || input.label().isBlank() || input.label().length()>100 || input.cases()==null || input.cases().isEmpty() || input.cases().size()>30) throw new BusinessException(400,"评测名称最多100字，每批1至30题");
        if(input.cases().stream().map(Case::id).distinct().count()!=input.cases().size()) throw new BusinessException(400,"测试题ID不能重复");
        for(var c:input.cases()) if(c.id()==null || !c.id().matches("[A-Za-z0-9_-]{1,40}") || c.prompt()==null || c.prompt().isBlank() || c.prompt().length()>1200 || c.group()!=null && c.group().length()>40) throw new BusinessException(400,"评测题目不合法");
        for(var price:Arrays.asList(input.inputPricePerMillion(),input.outputPricePerMillion())) if(price!=null && (price.signum()<0 || price.compareTo(new BigDecimal("100000"))>0)) throw new BusinessException(400,"价格不合法");
        if(input.pricingNote()!=null && input.pricingNote().length()>300) throw new BusinessException(400,"价格说明过长");
        if(!active.isEmpty()) throw new BusinessException(409,"已有评测执行中");
        String id=UUID.randomUUID().toString(); var report=json.createObjectNode();
        report.put("id",id).put("label",input.label()).put("startedAt",Instant.now().toString()).put("status","QUEUED").put("codeVersion",codeVersion).put("mock",agent.mock());
        report.set("suite",json.valueToTree(input)); report.putArray("rows");
        report.put("engine",agent.engine());
        report.put("engineVersion","langchain4j".equals(agent.engine())?"1.22.0":"builtin");
        report.put("costScope","仅聊天模型费用估算；不含缓存优惠、Embedding、Rerank及失败未返回的用量");
        active.add(id);
        var response=report.deepCopy();
        try { save(id,report); var user=UserContext.get(); worker.execute(()->{UserContext.set(user); try { execute(id,input,report); } finally { UserContext.clear(); active.remove(id); cancelled.remove(id); }}); }
        catch(RuntimeException e) { active.remove(id); report.put("status","FAILED"); save(id,report); throw e; }
        return response;
    }
    private void execute(String id,Start input,ObjectNode report) {
        var sessions=new HashMap<String,String>(); var shown=new HashMap<String,Set<String>>();
        report.put("status","RUNNING");
        try {
            report.set("retrievalConfig",json.valueToTree(knowledge.status()));
            report.set("knowledgeVersions",json.valueToTree(knowledge.catalog().stream().map(d->Map.of("bookId",d.novelId(),"fingerprint",d.fingerprint(),"embeddingModel",Objects.toString(d.embeddingModel(),""),"coveredChapters",d.coveredChapters())).toList()));
            try(var in=new org.springframework.core.io.ClassPathResource("agent/bookfinder/prompts/system.txt").getInputStream()) {
                report.put("promptSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
            }
            try(var in=BookFinderAgent.class.getResourceAsStream("BookFinderAgent.class")) {
                report.put("agentCodeSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
            }
            if("langchain4j".equals(agent.engine())) {
                var framework=com.novel.modules.agent.bookfinder.model.BookFinderAiServices.class;
                try(var in=framework.getResourceAsStream("BookFinderAiServices.class")) {
                    report.put("frameworkCodeSha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
                }
            }
            for(var c:input.cases()) {
                if(cancelled.contains(id) || Thread.currentThread().isInterrupted()) break;
                var row=json.createObjectNode(); row.set("case",json.valueToTree(c)); row.put("review","PENDING");
                long start=System.currentTimeMillis(); BookFinderAgent.Ticket ticket=null; String requestId=UUID.randomUUID().toString();
                try {
                    String group=c.group()==null || c.group().isBlank()?c.id():c.group();
                    ticket=agent.prepare(UserContext.requireUserId(),new ChatRequest(c.prompt(),requestId,sessions.get(group)));
                    row.put("model",ticket.config()==null?"mock":ticket.config().model());
                    var answer=agent.run(ticket,p->{}); sessions.put(group,answer.conversationId()); row.set("answer",json.valueToTree(answer));
                    var previous=shown.computeIfAbsent(group,g->new HashSet<>());
                    int hard=0,duplicates=0; var proofs=row.putArray("evidenceSnapshots");
                    for(var r:answer.recommendations()) {
                        if(c.expected()==null || r.book().matches(c.expected())) hard++;
                        if(previous.contains(r.book().id())) duplicates++;
                        previous.add(r.book().id());
                        if(r.sources()!=null && !r.sources().isEmpty()) {
                            try {
                                var doc=notes.preview(r.book().id()); var ids=r.sources().stream().filter(source->doc.fingerprint().equals(source.fingerprint())).map(Source::id).toList();
                                for(var chunk:doc.chunks()) if(ids.contains(chunk.id())) proofs.addObject().put("bookId",r.book().id()).put("chunkId",chunk.id()).put("fingerprint",doc.fingerprint()).put("text",chunk.text());
                            } catch(RuntimeException e) { row.put("evidenceSnapshotNotice","部分证据快照暂不可用，需要人工复查"); }
                        }
                    }
                    int count=answer.recommendations().size();
                    boolean pass=hard==count && duplicates==0 && (count>0 || c.allowEmpty()) && (c.clarify()==null || c.clarify()==answer.needsClarification());
                    row.put("recommended",count).put("hardMatches",hard).put("duplicates",duplicates).put("automaticPass",pass).put("status","DONE");
                } catch(Exception e) { row.put("status","FAILED").put("automaticPass",false).put("error",e instanceof BusinessException?e.getMessage():e.getClass().getSimpleName()); }
                finally {
                    if(ticket!=null) agent.release(ticket);
                    row.put("durationMs",System.currentTimeMillis()-start); row.put("requestId",requestId);
                    row.set("trace",json.valueToTree(store.run(UserContext.requireUserId(),requestId)));
                    ((ArrayNode)report.path("rows")).add(row); summarize(report,input); save(id,report);
                }
            }
            report.put("status",cancelled.contains(id)?"CANCELLED":Thread.currentThread().isInterrupted()?"INTERRUPTED":"DONE");
        } catch(Exception e) { report.put("status","FAILED").put("error",e.getClass().getSimpleName()); }
        report.put("finishedAt",Instant.now().toString()); summarize(report,input); save(id,report);
    }
    public static void summarize(ObjectNode report,Start input) {
        int done=0,pass=0,books=0,hard=0,tokens=0,inTokens=0,outTokens=0,reviewed=0,supported=0;
        var durations=new ArrayList<Long>();
        for(var row:report.path("rows")) {
            done++; if(row.path("automaticPass").asBoolean()) pass++;
            books+=row.path("recommended").asInt(); hard+=row.path("hardMatches").asInt();
            durations.add(row.path("durationMs").asLong());
            var usage=row.path("answer").has("usage")?row.path("answer").path("usage"):row.path("trace").path("usage"); tokens+=usage.path("totalTokens").asInt(); inTokens+=usage.path("promptTokens").asInt(); outTokens+=usage.path("completionTokens").asInt();
            if(Set.of("SUPPORTED","NOT_SUPPORTED","INSUFFICIENT").contains(row.path("review").asText())) { reviewed++; if(row.path("review").asText().equals("SUPPORTED")) supported++; }
        }
        durations.sort(Long::compare); var m=report.putObject("metrics");
        m.put("executed",done).put("planned",input.cases().size()).put("automaticPassed",pass).put("recommendedBooks",books).put("hardMatchedBooks",hard).put("reportedTotalTokens",tokens).put("reviewed",reviewed).put("humanSupported",supported);
        if(books>0) m.put("hardConditionRate",hard/(double)books); else m.putNull("hardConditionRate");
        if(done>0) { m.put("automaticPassRate",pass/(double)done); m.put("p95Ms",durations.get(Math.max(0,(int)Math.ceil(done*0.95)-1))); m.put("averageMs",durations.stream().mapToLong(Long::longValue).average().orElse(0)); }
        if(reviewed>0) m.put("humanSupportRate",supported/(double)reviewed); else m.putNull("humanSupportRate");
        if(input.inputPricePerMillion()!=null && input.outputPricePerMillion()!=null) m.put("estimatedChatCost",input.inputPricePerMillion().multiply(BigDecimal.valueOf(inTokens)).add(input.outputPricePerMillion().multiply(BigDecimal.valueOf(outTokens))).divide(BigDecimal.valueOf(1000000)));
        else m.putNull("estimatedChatCost");
    }
    public void cancel(String id) { get(id); cancelled.add(id); }
    public synchronized ObjectNode review(String id,String caseId,Review review) {
        if(active.contains(id)) throw new BusinessException(409,"请等待评测结束后审核");
        if(!Set.of("SUPPORTED","NOT_SUPPORTED","INSUFFICIENT").contains(review.verdict()) || review.note()==null || review.note().isBlank() || review.note().length()>1000) throw new BusinessException(400,"请选择审核结论并填写依据，最多1000字");
        var report=get(id); boolean found=false;
        for(var row:report.path("rows")) if(row.path("case").path("id").asText().equals(caseId)) {
            var item=(ObjectNode)row; var history=item.withArray("reviewHistory");
            history.addObject().put("verdict",review.verdict()).put("note",review.note()).put("reviewer",UserContext.requireUserId()).put("reviewedAt",Instant.now().toString());
            item.put("review",review.verdict()).put("reviewNote",review.note()).put("reviewer",UserContext.requireUserId()).put("reviewedAt",Instant.now().toString()); found=true;
        }
        if(!found) throw new BusinessException(404,"题目不存在");
        try { summarize(report,json.treeToValue(report.path("suite"),Start.class)); } catch(Exception e) { throw new IllegalStateException(e); }
        save(id,report); return report;
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
