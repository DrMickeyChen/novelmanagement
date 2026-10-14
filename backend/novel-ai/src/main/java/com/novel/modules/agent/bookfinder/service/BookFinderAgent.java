package com.novel.modules.agent.bookfinder.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.*;
import com.novel.modules.agent.bookfinder.BookFinderTypes.*;
import com.novel.modules.agent.bookfinder.client.*;
import com.novel.modules.agent.bookfinder.conversation.BookFinderStore;
import com.novel.modules.agent.bookfinder.model.BookFinderModel;
import com.novel.modules.ai.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

@Service @RequiredArgsConstructor @Slf4j
public class BookFinderAgent {
    @org.springframework.beans.factory.annotation.Value("${novel.ai.book-finder.max-total-tokens:60000}")
    private int maxTotalTokens=60000;
    @org.springframework.beans.factory.annotation.Value("${novel.ai.book-finder.engine:langchain4j}")
    private String engine="native";
    private com.novel.modules.agent.bookfinder.model.BookFinderAiServices aiServices;
    @org.springframework.beans.factory.annotation.Autowired
    public void framework(com.novel.modules.agent.bookfinder.model.BookFinderAiServices aiServices) { this.aiServices=aiServices; }
    public String engine() { return engine; }
    private final ObjectMapper json;
    private final BookFinderSearchClient search;
    private final BookFinderContentClient content;
    private final BookFinderModel model;
    private final BookFinderStore store;
    private final AiConfigService config;
    private com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService knowledge;
    private com.novel.modules.agent.bookfinder.skill.BookFinderSkills skills;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void advanced(com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService knowledge,
                         com.novel.modules.agent.bookfinder.skill.BookFinderSkills skills) { this.knowledge=knowledge; this.skills=skills; }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private BookFinderTools sharedTools;
    public record Ticket(long userId, ChatRequest request, Conversation state, AiRuntimeConfig config,
                         String fingerprint, Answer replay, long startedAt, boolean quotaCharged) {
        public Ticket(long userId, ChatRequest request, Conversation state, AiRuntimeConfig config,
                      String fingerprint, Answer replay, long startedAt) {
            this(userId,request,state,config,fingerprint,replay,startedAt,replay==null);
        }
    }

    public Ticket prepare(long userId, ChatRequest request) {
        validateUuid(request.requestId());
        if(request.conversationId()!=null) validateUuid(request.conversationId());
        String fingerprint=fingerprint(request);
        store.lock(userId,request.requestId());
        try {
            var previous=store.request(userId,request.requestId());
            if(previous!=null) {
                if(!previous.fingerprint().equals(fingerprint)) throw new BusinessException(409,"请求 ID 已用于其他消息");
                if(!"done".equals(previous.status())) throw new BusinessException(409,"该请求已执行或正在执行，请开始新的请求");
                return new Ticket(userId,request,null,null,fingerprint,previous.answer(),System.currentTimeMillis());
            }
            var state=store.load(userId,request.conversationId());
            AiRuntimeConfig cfg;
            try { cfg=model.mock()?null:config.requireUsable(); }
            catch(BusinessException e) { throw e.withStatus(503); }
            boolean quotaCharged=!(Objects.equals(com.novel.common.security.UserContext.getUserId(),userId)
                    && com.novel.common.security.UserContext.hasRole("ADMIN"));
            if(request.conversationId()==null) store.save(userId,state);
            if(quotaCharged) store.acquireQuota(userId,dailyLimit());
            store.request(userId,request.requestId(),new RequestRecord(fingerprint,"running",null));
            return new Ticket(userId,request,state,cfg,fingerprint,null,System.currentTimeMillis(),quotaCharged);
        } catch(RuntimeException e) { store.unlock(userId,request.requestId()); throw e; }
    }
    public int dailyLimit() { return Math.min(20,Math.max(0,config.dailyLimit())); }
    public boolean mock() { return model.mock(); }
    public List<String> categories() {
        var result=content.categories();
        return result==null?List.of():InternalResults.unwrapBusiness(result,"可用分类查询");
    }
    public Conversation view(long uid,String id) {
        var state=store.load(uid,id);
        var last=state.getLastAnswer();
        if(last==null || last.recommendations().isEmpty()) return state;
        var ids=last.recommendations().stream().map(r->r.book().id()).toList();
        var fresh=InternalResults.unwrapBusiness(content.details(ids),"会话推荐可见性校验");
        if(fresh==null) throw new BusinessException(503,"内容服务暂时不可用");
        var byId=new HashMap<String,BookFinderBook>(); fresh.forEach(b->byId.put(b.id(),b));
        var visible=new ArrayList<Recommendation>();
        for(var old:last.recommendations()) {
            var b=byId.get(old.book().id());
            if(b!=null && b.matches(last.conditions())) {
                String quote=b.intro()!=null && b.intro().contains(old.evidence())?old.evidence():"";
                visible.add(new Recommendation(b,"上次推荐，已重新核对作品可见性；请以当前简介和状态为准。",quote,old.matches()));
            }
        }
        state.setLastBooks(visible.stream().map(Recommendation::book).toList());
        state.setLastAnswer(new Answer(last.conversationId(),last.requestId(),last.answer(),last.conditions(),visible,
                last.needsClarification(),last.mock(),last.usage()));
        return state;
    }
    public void release(Ticket t) { store.unlock(t.userId(),t.request().requestId()); }
    public void rejected(Ticket t) {
        if(t.replay()==null) {
            store.request(t.userId(),t.request().requestId(),new RequestRecord(t.fingerprint(),"rejected",null));
            if(t.quotaCharged()) store.refundQuota(t.userId());
        }
    }

    public Answer run(Ticket ticket, Consumer<String> progress) throws Exception {
        if(ticket.replay()!=null) {
            var old=ticket.replay();
            store.load(ticket.userId(),old.conversationId());
            var ids=old.recommendations().stream().map(r->r.book().id()).toList();
            var fresh=ids.isEmpty()?List.<BookFinderBook>of():InternalResults.unwrapBusiness(content.details(ids),"重放可见性校验");
            if(fresh==null) throw new BusinessException(503,"内容服务暂时不可用");
            var valid=fresh.stream().filter(b->b.matches(old.conditions())).map(BookFinderBook::id).collect(java.util.stream.Collectors.toSet());
            if(!valid.containsAll(ids)) throw new BusinessException(409,"此前推荐的作品状态已变化，请发送新的找书请求");
            var byId=new HashMap<String,BookFinderBook>(); fresh.forEach(b->byId.put(b.id(),b));
            var refreshed=old.recommendations().stream().map(r->{
                var b=byId.get(r.book().id());
                String quote=b.intro()!=null && b.intro().contains(r.evidence())?r.evidence():"";
                return new Recommendation(b,"上次推荐，已核对当前作品状态；正文依据需重新查询。",quote,r.matches());
            }).toList();
            return new Answer(old.conversationId(),old.requestId(),old.answer(),old.conditions(),refreshed,old.needsClarification(),old.mock(),old.usage());
        }
        var state=ticket.state();
        boolean continuation=com.novel.modules.agent.bookfinder.conversation.BookFinderIntent.continues(ticket.request().message());
        if(!continuation) {
            state.setConditions(new BookFinderQuery(null,null,null,null,null,null,List.of(),20));
            state.setPreference("");
            state.setPreferences(new ArrayList<>());
            state.setLastBooks(new ArrayList<>());
            state.setHistory(new ArrayList<>());
            state.setShown(new ArrayList<>());
        }
        long deadline=ticket.startedAt()+60000;
        int calls=0,tokens=0,promptTokens=0,completionTokens=0;
        var session=new ToolSession(ticket,progress,continuation,deadline);
        try {
            Integer position=com.novel.modules.agent.bookfinder.conversation.BookFinderIntent.referencePosition(ticket.request().message());
            if(position!=null && (position<1 || position>state.getLastBooks().size())) {
                var answer=new Answer(state.getId(),ticket.request().requestId(),"当前推荐列表没有第"+position+"本，请提供有效序号或书名。",
                        session.conditions,List.of(),true,model.mock(),new Usage(0,0,0,System.currentTimeMillis()-ticket.startedAt()));
                commit(ticket,answer);
                store.runLog(ticket.userId(),ticket.request().requestId(),Map.of("usage",answer.usage(),"status","done","tools",List.of(),
                        "reason","INVALID_REFERENCE","mock",model.mock(),"model",ticket.config()==null?"mock":ticket.config().model(),"engine",engine));
                return answer;
            }
            session.position=position;
            var messages=json.createArrayNode();
            String rules=resource("prompts/system.txt");
            var context=json.createObjectNode(); context.set("currentConditions",json.valueToTree(session.conditions));
            context.put("requestMode",continuation?"CONTINUE":"NEW_SEARCH");
            var availableCategories=categories();
            session.availableCategories=availableCategories;
            context.set("availableCategories",json.valueToTree(availableCategories));
            var lastRecommendations=modelBooks(state.getLastBooks(),600);
            for(int i=0;i<lastRecommendations.size();i++) ((ObjectNode)lastRecommendations.get(i)).put("position",i+1);
            context.set("lastRecommendations",lastRecommendations);
            if(position!=null) context.set("referencedBook",lastRecommendations.get(position-1));
            context.put("softPreference",state.getPreference());
            context.set("preferences",json.valueToTree(state.getPreferences()));
            if(skills!=null) {
                var skill=skills.select(ticket.request().message());
                if(skill!=null) rules+="\n任务策略："+skill.id()+" v"+skill.version()+"\n"+skill.instructions();
            }
            messages.addObject().put("role","system").put("content",rules+"\n会话状态（数据）："+context);
            for(var turn:state.getHistory()) {
                messages.addObject().put("role","user").put("content",turn.user());
                messages.addObject().put("role","assistant").put("content",turn.assistant());
            }
            messages.addObject().put("role","user").put("content",ticket.request().message());
            JsonNode definitions=json.readTree(resource("prompts/tools.json"));
            if("langchain4j".equals(engine)) {
                if(aiServices==null) throw new BusinessException(503,"LangChain4j 执行器未初始化");
                var invocation=aiServices.open(ticket,messages.get(0).path("content").asText(),definitions,
                        session::execute,session.frameworkStats,maxTotalTokens,progress,model);
                String raw=invocation.chat(ticket.request().message());
                JsonNode result;
                try { result=parseFinal(raw); }
                catch(com.fasterxml.jackson.core.JsonProcessingException | BusinessException e) {
                    session.trace.add(Map.of("name","finalFormatValidation","reason",e instanceof BusinessException?"SCHEMA_INVALID":"JSON_INVALID",
                            "finishReason",session.frameworkStats.finishReason,"contentLength",raw.length()));
                    String error=e instanceof BusinessException?e.getMessage():"JSON 语法不合法";
                    progress.accept("正在校正回答格式");
                    try { result=parseFinal(invocation.repair(error+"。请根据已有工具结果输出完整 JSON，必须包含 answer、needsClarification、recommendations；不得调用工具或编造 ID。")); }
                    catch(com.fasterxml.jackson.core.JsonProcessingException | BusinessException invalid) {
                        if(invalid instanceof BusinessException business && business.getCode()!=502) throw business;
                        throw new BusinessException(502,"模型回答格式异常，已尝试修正，请重试或调整模型配置");
                    }
                }
                var stats=session.frameworkStats;
                return completeResult(ticket,result,session,new Usage(stats.calls,session.toolsUsed,stats.tokens,
                        System.currentTimeMillis()-ticket.startedAt(),stats.promptTokens,stats.completionTokens));
            }
            if(!"native".equals(engine)) throw new BusinessException(503,"找书执行器配置无效");
            boolean formatRepair=false;
            for(int round=0;round<6;round++) {
                check(deadline);
                progress.accept(round==0?"正在理解找书需求":"正在整理查询结果");
                calls++;
                var reply=model.call(ticket.userId(),ticket.config(),messages,round>=4 || formatRepair?null:definitions,
                        deadline,state,ticket.request().message());
                tokens+=reply.usage().totalTokens(); promptTokens+=reply.usage().promptTokens(); completionTokens+=reply.usage().completionTokens();
                if(tokens>Math.max(1000,maxTotalTokens)) throw new BusinessException(429,"本次找书已达到任务 Token 预算，请重试或由管理员调整预算");
                var message=reply.message();
                var toolCalls=message.path("tool_calls");
                if(!toolCalls.isArray() || toolCalls.isEmpty()) {
                    JsonNode result;
                    try { result=parseFinal(message.path("content").asText()); }
                    catch(com.fasterxml.jackson.core.JsonProcessingException | BusinessException e) {
                        session.trace.add(Map.of("name","finalFormatValidation","reason",e instanceof BusinessException?"SCHEMA_INVALID":"JSON_INVALID",
                                "round",round+1,"finishReason",reply.finishReason(),"contentLength",message.path("content").asText().length()));
                        if(!formatRepair && round<5) {
                            formatRepair=true;
                            // Give the repair call the actual invalid response, not only a generic instruction.
                            var invalid=json.createObjectNode().put("role","assistant").put("content",
                                    message.path("content").asText().substring(0,Math.min(12000,message.path("content").asText().length())));
                            messages.add(invalid);
                            messages.addObject().put("role","user").put("content",e instanceof BusinessException?e.getMessage():"上一条回答不是合法 JSON，请修正语法");
                            messages.addObject().put("role","user").put("content","请将最终回答修正为 system 要求的 JSON 对象，包含 answer、needsClarification 和 recommendations。只使用已有工具结果，不调用新工具；没有可核实作品时 recommendations 为空数组。不要输出解释、推理或代码块。");
                            progress.accept("正在校正回答格式");
                            continue;
                        }
                        throw new BusinessException(502,"模型回答格式异常，已尝试修正，请重试或调整模型配置");
                    }
                    return completeResult(ticket,result,session,
                            new Usage(calls,session.toolsUsed,tokens,System.currentTimeMillis()-ticket.startedAt(),promptTokens,completionTokens));
                }
                if(round>=4 || formatRepair || toolCalls.size()+session.toolsUsed>8) throw new BusinessException(422,"找书执行次数达到上限，请简化条件后重试");
                messages.add(message);
                for(var call:toolCalls) {
                    if(session.toolsUsed>=8) throw new BusinessException(422,"找书工具执行次数达到上限");
                    check(deadline);
                    String name=call.path("function").path("name").asText();
                    String callId=call.path("id").asText();
                    if(callId.isBlank()||callId.length()>200) throw new BusinessException(502,"模型工具调用格式不合法");
                    var output=session.execute(name,call.path("function").path("arguments").asText());
                    messages.addObject().put("role","tool").put("tool_call_id",callId).put("content",output.toString());
                }
            }
            throw new BusinessException(422,"找书执行次数达到上限");
        } catch(Exception e) {
            calls=Math.max(calls,session.frameworkStats.calls); tokens=Math.max(tokens,session.frameworkStats.tokens);
            promptTokens=Math.max(promptTokens,session.frameworkStats.promptTokens); completionTokens=Math.max(completionTokens,session.frameworkStats.completionTokens);
            boolean interrupted=Thread.interrupted();
            try {
                store.request(ticket.userId(),ticket.request().requestId(),new RequestRecord(ticket.fingerprint(),"failed",null));
                store.runLog(ticket.userId(),ticket.request().requestId(),Map.of("status","failed","modelCalls",calls,
                        "toolCalls",session.toolsUsed,"tokens",tokens,"tools",session.trace,"errorType",e.getClass().getSimpleName(),
                        "usage",new Usage(calls,session.toolsUsed,tokens,System.currentTimeMillis()-ticket.startedAt(),promptTokens,completionTokens),
                        "model",ticket.config()==null?"mock":ticket.config().model(),"engine",engine));
            } catch(RuntimeException cleanup) { log.warn("找书失败记录写入失败 | user={} request={}",ticket.userId(),ticket.request().requestId()); }
            finally { if(interrupted) Thread.currentThread().interrupt(); }
            log.warn("找书失败 | user={} request={} models={} tools={} errorType={}",ticket.userId(),ticket.request().requestId(),calls,session.toolsUsed,e.getClass().getSimpleName());
            throw e;
        }
    }

    /** Per-request tool state. Both engines pass through exactly the same business guards. */
    private final class ToolSession {
        final Ticket ticket;
        final Conversation state;
        final Consumer<String> progress;
        final boolean continuation;
        final long deadline;
        Integer position;
        List<String> availableCategories=List.of();
        final List<Map<String,Object>> trace=new ArrayList<>();
        final Map<String,BookFinderBook> candidates=new LinkedHashMap<>(), detailed=new LinkedHashMap<>();
        final Map<String,BookKnowledge.Hit> evidence=new LinkedHashMap<>();
        final Set<String> unavailableEvidence=new HashSet<>(), checkedEvidence=new HashSet<>();
        BookFinderQuery conditions;
        boolean searched;
        int toolsUsed;
        final com.novel.modules.agent.bookfinder.model.BookFinderAiServices.Stats frameworkStats=new com.novel.modules.agent.bookfinder.model.BookFinderAiServices.Stats();
        ToolSession(Ticket ticket,Consumer<String> progress,boolean continuation,long deadline) {
            this.ticket=ticket; this.state=ticket.state(); this.conditions=state.getConditions();
            this.progress=progress; this.continuation=continuation; this.deadline=deadline;
        }
        ObjectNode execute(String name,String raw) throws Exception {
            check(deadline);
            if(toolsUsed>=8) throw new BusinessException(422,"找书工具执行次数达到上限");
            toolsUsed++;
        long start=System.currentTimeMillis();
        var output=json.createObjectNode(); output.put("tool",name);
        try {
            if(raw.length()>4000) throw new BusinessException(400,"工具参数过长");
            var args=json.readTree(raw);
            if(args==null||!args.isObject()) throw new BusinessException(400,"工具参数必须是对象");
            List<BookFinderBook> books=List.of();
            switch(name) {
                case "searchBooks" -> {
                    progress.accept("正在搜索符合条件的作品");
                    if(args.has("preferences")) {
                        if(searched || !args.path("preferences").isArray() || args.path("preferences").size()>6) throw new BusinessException(400,"Invalid preferences");
                        var prefs=new ArrayList<Preference>();
                        for(var p:args.path("preferences")) {
                            if(!p.path("criterion").isTextual() || p.path("criterion").asText().isBlank() || p.path("criterion").asText().length()>60 || !p.path("required").isBoolean()) throw new BusinessException(400,"Invalid preference criterion");
                            prefs.add(new Preference(p.path("criterion").asText().trim(),p.path("required").asBoolean()));
                        }
                        state.setPreferences(prefs);
                        String combined=prefs.stream().map(Preference::criterion).collect(java.util.stream.Collectors.joining("、"));
                        state.setPreference(combined.substring(0,Math.min(200,combined.length())));
                        ((ObjectNode)args).remove("preferences");
                    }
                    if(args.has("preference")) {
                        if(searched) throw new BusinessException(400,"本轮软偏好已确定，后续搜索请省略 preference");
                        var pref=args.get("preference");
                        if(!pref.isNull() && (!pref.isTextual() || pref.asText().length()>200)) throw new BusinessException(400,"软偏好最多 200 字");
                        state.setPreference(pref.isNull()?"":pref.asText());
                        ((ObjectNode)args).remove("preference");
                    }
                    // Similarity is not an authorization to invent an exact tag filter.
                    // Keep existing explicit tags; move newly inferred tags to soft retrieval.
                    if(continuation && args.path("tag").isTextual()
                            && !Objects.equals(conditions.tag(),args.path("tag").asText())
                            && !ticket.request().message().contains(args.path("tag").asText())) {
                        String inferred=args.path("tag").asText().trim();
                        if(!inferred.isBlank() && inferred.length()<=60 && !searched) {
                            var prefs=new ArrayList<>(state.getPreferences()==null?List.<Preference>of():state.getPreferences());
                            if(prefs.size()<6 && prefs.stream().noneMatch(p->p.criterion().equals(inferred))) prefs.add(new Preference(inferred,false));
                            state.setPreferences(prefs);
                            String preference=state.getPreference().isBlank()?inferred:state.getPreference()+"、"+inferred;
                            state.setPreference(preference.substring(0,Math.min(200,preference.length())));
                        }
                        ((ObjectNode)args).remove("tag");
                        output.put("filterNotice","未明确要求的新标签已移出硬筛选；相似风格仅作为软偏好，不保证符合");
                        trace.add(Map.of("name","filterCorrection","field","tag","reason","INFERRED_SIMILARITY_TAG"));
                    }
                    if(position!=null) {
                        String input=ticket.request().message();
                        if(args.path("keyword").isTextual() && !Objects.equals(conditions.keyword(),args.path("keyword").asText())
                                && !input.contains(args.path("keyword").asText())) {
                            String inferred=args.path("keyword").asText().trim();
                            if(!inferred.isBlank() && inferred.length()<=100 && !searched) {
                                String combined=state.getPreference().isBlank()?inferred:state.getPreference()+"、"+inferred;
                                state.setPreference(combined.substring(0,Math.min(200,combined.length())));
                                var prefs=new ArrayList<>(state.getPreferences()==null?List.<Preference>of():state.getPreferences());
                                if(inferred.length()<=60 && prefs.size()<6 && prefs.stream().noneMatch(p->p.criterion().equals(inferred)))
                                    prefs.add(new Preference(inferred,false));
                                state.setPreferences(prefs);
                            }
                            ((ObjectNode)args).remove("keyword");
                            output.put("keywordNotice","推断的相似风格已转为软偏好，不作为关键词硬过滤");
                            trace.add(Map.of("name","filterCorrection","field","keyword","reason","INFERRED_SIMILARITY_KEYWORD"));
                        }
                        if(!input.matches(".*(完结|连载|状态).*")) ((ObjectNode)args).remove("completed");
                        if(!input.matches(".*(字|万|篇幅).*")) {
                            ((ObjectNode)args).remove("minWords"); ((ObjectNode)args).remove("maxWords");
                        }
                    }
                    var next=normalizeCategory(merge(conditions,args,state.getShown()),availableCategories);
                    if(searched && !sameHardConditions(conditions,next))
                        throw new BusinessException(400,"本轮不能擅自放宽硬条件，请保留首次搜索条件");
                    conditions=next; searched=true;
                    books=sharedTools!=null?sharedTools.searchBooks(conditions):InternalResults.unwrapBusiness(search.search(conditions),"找书检索");
                    if(books==null) throw new BusinessException(503,"搜索服务未返回有效结果");
                    if(knowledge!=null && !state.getPreference().isBlank()) {
                        try {
                            var combined=new LinkedHashMap<String,BookFinderBook>();
                            knowledge.candidates(conditions,state.getPreference()).forEach(b->combined.put(b.id(),b));
                            books.forEach(b->combined.putIfAbsent(b.id(),b)); books=new ArrayList<>(combined.values());
                        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
                        catch(Exception e) { output.put("retrievalNotice","知识候选检索暂时不可用，本次使用结构化搜索"); }
                    }
                    books=books.stream().filter(b->b.matches(next)&&!state.getShown().contains(b.id())).limit(20).toList();
                    for(var b:books) candidates.put(b.id(),b);
                    output.set("conditions",json.valueToTree(conditions));
                }
                case "searchBookEvidence" -> {
                    progress.accept("正在检索正文证据并复核版本");
                    rejectUnknown(args,Set.of("ids","query"));
                    if(knowledge==null) throw new BusinessException(400,"正文检索尚未启用");
                    var ids=args.path("ids");
                    if(!ids.isArray()||ids.isEmpty()||ids.size()>20||!args.path("query").isTextual()) throw new BusinessException(400,"证据查询参数不正确");
                    var requested=new ArrayList<String>();
                    for(var id:ids) {
                        if(!id.isTextual()||!candidates.containsKey(id.asText())) throw new BusinessException(400,"只能检索本轮搜索候选作品");
                        requested.add(id.asText());
                    }
                    var missing=requested.stream().distinct().filter(id->!detailed.containsKey(id)).toList();
                    if(!missing.isEmpty()) {
                        if(toolsUsed>=8) throw new BusinessException(422,"找书工具执行次数达到上限");
                        check(deadline); toolsUsed++;
                        long detailsStart=System.currentTimeMillis();
                        var fetched=sharedTools!=null?sharedTools.getBookDetails(missing):InternalResults.unwrapBusiness(content.details(missing),"证据检索前详情补查");
                        if(fetched==null) throw new BusinessException(503,"内容服务未返回有效结果");
                        for(var b:fetched) if(missing.contains(b.id())) detailed.put(b.id(),b);
                        trace.add(Map.of("name","getBookDetails","count",fetched.size(),"durationMs",System.currentTimeMillis()-detailsStart,"reason","EVIDENCE_PREREQUISITE"));
                        output.set("detailBooks",modelBooks(fetched,600));
                    }
                    var visible=requested.stream().filter(detailed::containsKey).distinct().toList();
                    if(visible.isEmpty()) throw new BusinessException(400,"候选作品当前不可见，不能检索正文；这不表示知识库未构建");
                    var query=new BookKnowledge.EvidenceQuery(visible,args.path("query").asText());
                    com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService.Retrieval retrieval;
                    try { retrieval=sharedTools!=null?sharedTools.searchBookEvidence(query):knowledge.retrieve(query); }
                    catch(InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
                    catch(Exception e) { retrieval=new com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService.Retrieval(
                            List.of(),requested,"UNAVAILABLE","正文检索暂时不可用，不能确认软偏好"); }
                    checkedEvidence.addAll(requested);
                    unavailableEvidence.addAll(retrieval.unavailable());
                    retrieval.hits().forEach(hit->evidence.put(hit.chunkId(),hit));
                    output.set("evidence",json.valueToTree(retrieval));
                    trace.add(Map.of("name",name,"mode",retrieval.mode(),"hits",retrieval.hits().size(),"unavailable",retrieval.unavailable().size(),
                            "notice",retrieval.notice(),"durationMs",System.currentTimeMillis()-start));
                }
                case "getBookDetails" -> {
                    progress.accept("正在查询简介和标签");
                    rejectUnknown(args,Set.of("ids"));
                    var ids=args.path("ids");
                    if(!ids.isArray()||ids.isEmpty()||ids.size()>20) throw new BusinessException(400,"详情查询需要 1～20 个作品 ID");
                    var requested=new ArrayList<String>();
                    var allowed=new HashSet<>(candidates.keySet());
                    state.getLastBooks().forEach(b->allowed.add(b.id()));
                    for(var id:ids) {
                        if(!id.isTextual()||!allowed.contains(id.asText())) throw new BusinessException(400,"只能查询搜索候选或上轮推荐");
                        requested.add(id.asText());
                    }
                    books=sharedTools!=null?sharedTools.getBookDetails(requested):InternalResults.unwrapBusiness(content.details(requested),"找书详情");
                    if(books==null) throw new BusinessException(503,"内容服务未返回有效结果");
                    for(var b:books) if(requested.contains(b.id())) detailed.put(b.id(),b);
                }
                default -> throw new BusinessException(400,"不允许调用该工具");
            }
            output.set("books",modelBooks(books,"searchBooks".equals(name)?120:600));
            trace.add(Map.of("name",name,"durationMs",System.currentTimeMillis()-start,"count",books.size()));
        } catch(BusinessException e) {
            if(e.getCode()>=500) throw e;
            output.put("error",e.getMessage());
            trace.add(Map.of("name",name,"durationMs",System.currentTimeMillis()-start,"errorCode",e.getCode(),"reason",e.getMessage()));
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { output.put("error","工具参数不是合法 JSON"); }
            return output;
        }
    }

    private Answer completeResult(Ticket ticket,JsonNode result,ToolSession session,Usage usage) throws Exception {
        var state=ticket.state();
        var position=session.position;
        var progress=session.progress;
        // Recover actionable basic or optional-similarity candidates when the
        // model unnecessarily asks for more details after finding candidates.
        // Never synthesize style judgments or bypass the final visibility check.
        if(result.path("recommendations").isEmpty() && session.searched && !session.candidates.isEmpty()
                && ((state.getPreference().isBlank() && (state.getPreferences()==null || state.getPreferences().isEmpty()))
                || (position!=null && (state.getPreferences()==null || state.getPreferences().stream().noneMatch(Preference::required))))) {
            var eligible=new ArrayList<String>();
            for(var b:session.candidates.values())
                if(b.matches(session.conditions) && !state.getShown().contains(b.id()) && eligible.size()<20) eligible.add(b.id());
            var missing=eligible.stream().filter(id->!session.detailed.containsKey(id)).toList();
            if(!missing.isEmpty()) {
                check(session.deadline);
                if(session.toolsUsed>=8) throw new BusinessException(422,"找书执行次数达到上限，请简化条件后重试");
                session.toolsUsed++;
                long started=System.currentTimeMillis();
                var books=sharedTools!=null?sharedTools.getBookDetails(missing):InternalResults.unwrapBusiness(content.details(missing),"候选详情补查");
                if(books==null) throw new BusinessException(503,"内容服务未返回有效结果");
                for(var b:books) if(missing.contains(b.id())) session.detailed.put(b.id(),b);
                session.trace.add(Map.of("name","getBookDetails","durationMs",System.currentTimeMillis()-started,
                        "count",books.size(),"reason","EMPTY_RECOMMENDATIONS_RECOVERY"));
            }
            var recovered=json.createArrayNode();
            for(var id:eligible) {
                var b=session.detailed.get(id);
                if(b!=null && b.matches(session.conditions)) recovered.addObject().put("id",id).put("quote","");
                if(recovered.size()==5) break;
            }
            if(!recovered.isEmpty()) {
                ((ObjectNode)result).set("recommendations",recovered);
                ((ObjectNode)result).put("needsClarification",false);
                session.trace.add(Map.of("name","recommendationRecovery","count",recovered.size(),"reason",
                        state.getPreference().isBlank()?"NO_SOFT_PREFERENCE":"OPTIONAL_SIMILARITY_CANDIDATES"));
            }
        }
        progress.accept("正在核对作品可见性和筛选条件");
        var answer=finish(ticket,result,session.conditions,session.candidates,session.detailed,session.evidence,session.checkedEvidence,session.unavailableEvidence,
                new Usage(usage.modelCalls(),session.toolsUsed,usage.totalTokens(),System.currentTimeMillis()-ticket.startedAt(),usage.promptTokens(),usage.completionTokens()));
        if(answer.recommendations().isEmpty() && session.checkedEvidence.isEmpty()
                && session.trace.stream().anyMatch(entry->"searchBookEvidence".equals(entry.get("name")) && entry.containsKey("errorCode")))
            answer=new Answer(answer.conversationId(),answer.requestId(),"本次正文核对未成功执行，无法确认相似偏好；这不表示知识库未构建。请重试或调整条件。",
                    answer.conditions(),List.of(),true,answer.mock(),answer.usage());
        check(session.deadline);
        commit(ticket,answer);
        try { store.runLog(ticket.userId(),ticket.request().requestId(),Map.of("usage",answer.usage(),"tools",session.trace,"status","done","mock",model.mock(),"model",ticket.config()==null?"mock":ticket.config().model(),"startedAt",ticket.startedAt(),"engine",engine)); }
        catch(RuntimeException e) { log.warn("找书运行记录写入失败 | user={} request={}",ticket.userId(),ticket.request().requestId()); }
        log.info("找书完成 | user={} request={} models={} tools={} tokens={} durationMs={}",
                ticket.userId(),ticket.request().requestId(),usage.modelCalls(),session.toolsUsed,usage.totalTokens(),answer.usage().durationMs());
        return answer;
    }

    private Answer finish(Ticket t, JsonNode result, BookFinderQuery conditions,
                          Map<String,BookFinderBook> candidates, Map<String,BookFinderBook> detailed,
                          Map<String,BookKnowledge.Hit> evidence, Set<String> checkedEvidence,
                          Set<String> unavailableEvidence, Usage usage) throws Exception {
        var proposals=result.path("recommendations");
        if(!proposals.isArray()||proposals.size()>5) throw new BusinessException(502,"模型推荐格式不合法");
        var ids=new LinkedHashSet<String>();
        var quotes=new HashMap<String,String>();
        var references=new HashMap<String,List<BookKnowledge.Hit>>();
        var judgments=new HashMap<String,JsonNode>();
        for(var item:proposals) {
            String id=item.path("id").asText();
            if(!candidates.containsKey(id)||!detailed.containsKey(id)) throw new BusinessException(502,"模型推荐了未经工具核实的作品");
            ids.add(id); quotes.put(id,item.path("quote").asText());
            judgments.put(id,item.path("assessments"));
            var refs=new ArrayList<BookKnowledge.Hit>();
            if(item.has("evidenceIds")) {
                var proposed=item.get("evidenceIds");
                if(!proposed.isArray() || proposed.size()>2) throw new BusinessException(502,"正文证据引用格式不合法");
                for(var ref:proposed) {
                    var hit=evidence.get(ref.asText());
                    if(!ref.isTextual() || hit==null || !hit.novelId().equals(id)) throw new BusinessException(502,"模型引用了未经检索的正文证据");
                    refs.add(hit);
                }
            }
            references.put(id,refs);
        }
        var fresh=ids.isEmpty()?List.<BookFinderBook>of():InternalResults.unwrapBusiness(content.details(new ArrayList<>(ids)),"最终可见性校验");
        if(fresh==null) throw new BusinessException(503,"内容服务暂时不可用");
        var recommendations=new ArrayList<Recommendation>();
        var freshById=new HashMap<String,BookFinderBook>();
        for(var b:fresh) freshById.put(b.id(),b);
        // Keep the actual card order stable even if the content service reorders IDs.
        for(var id:ids) {
            var b=freshById.get(id);
            if(b==null) continue;
            if(!ids.contains(b.id())||!b.matches(conditions)||t.state().getShown().contains(b.id())) continue;
            String quote=quotes.getOrDefault(b.id(),"");
            if(quote.length()>160||b.intro()==null||!b.intro().contains(quote)) quote="";
            var matches=new ArrayList<String>();
            if(conditions.category()!=null) matches.add("分类："+b.category());
            if(conditions.completed()!=null) matches.add(b.completed()?"已完结":"连载中");
            if(conditions.minWords()!=null||conditions.maxWords()!=null) matches.add("字数："+b.wordCount());
            if(conditions.tag()!=null) matches.add("标签："+conditions.tag());
            String reason=matches.isEmpty()?"来自站内搜索，已核对作品详情。":"符合筛选条件："+String.join("、",matches)+"。";
            var sources=new ArrayList<Source>();
            var refs=references.getOrDefault(b.id(),List.of());
            boolean currentEvidence=false;
            if(!refs.isEmpty() && knowledge!=null) {
                try { currentEvidence=knowledge.fresh(refs.get(0)); }
                catch(RuntimeException e) { log.warn("推荐证据复核失败，省略正文依据 | novel={} errorType={}",b.id(),e.getClass().getSimpleName()); }
            }
            if(currentEvidence) {
                for(var hit:refs) if(hit.fingerprint().equals(refs.get(0).fingerprint())) sources.add(new Source(hit.chunkId(),"正文",hit.chapterNumber(),hit.coveredChapters(),hit.totalChapters(),
                        hit.truncated(),hit.mode(),"检索到相关片段；仅代表已覆盖章节，不能证明全书风格或不存在某元素",hit.fingerprint()));
                reason+=" 已检索到与偏好相关的正文线索，尚不能据此保证整本书符合偏好。";
            }
            if(!t.state().getPreference().isBlank() && sources.isEmpty()) {
                if(unavailableEvidence.contains(b.id())) reason+=" 正文知识库未构建、已失效或暂不可用；仅作为硬条件候选，尚未确认你的风格偏好。";
                else if(!checkedEvidence.contains(b.id())) reason+=" 本次未检索该书正文；尚未确认你的风格偏好。";
                else reason+=" 已检索正文，但没有可引用的可靠线索；尚未确认你的风格偏好。";
            } else if(!quote.isBlank()) reason+=" 简介依据："+quote;
            else if(sources.isEmpty()) reason+=" 本次依据书籍资料推荐，未进行正文风格核对。";
            var prefs=t.state().getPreferences();
            if((prefs==null || prefs.isEmpty()) && !t.state().getPreference().isBlank()) prefs=List.of(new Preference(t.state().getPreference(),false));
            if(prefs==null) prefs=List.of();
            var freshRefs=sources.stream().map(Source::id).collect(java.util.stream.Collectors.toSet());
            var assessments=com.novel.modules.agent.bookfinder.knowledge.PreferenceEvidence.assess(prefs,judgments.getOrDefault(b.id(),json.createArrayNode()),freshRefs);
            // Required style cannot be silently ignored or claimed from mere similarity.
            if(assessments.stream().anyMatch(a->a.required() && !a.verdict().equals("SUPPORTED"))) continue;
            recommendations.add(new Recommendation(b,reason,quote,matches,sources,assessments));
        }
        String text=result.path("answer").asText("");
        if(text.length()>1500) throw new BusinessException(502,"模型回答过长");
        boolean clarify=result.path("needsClarification").asBoolean(false);
        // Recommendations and their factual claims are assembled from live tool data.
        if(!recommendations.isEmpty()) {
            text="为你找到 "+recommendations.size()+" 本符合当前硬筛选条件的候选作品。正文核对情况见卡片；不能据此保证节奏、战力等软偏好。";
            clarify=false;
        }
        else if(!ids.isEmpty()) {
            text=t.state().getPreferences()!=null && t.state().getPreferences().stream().anyMatch(Preference::required)
                    ?"当前候选未能确认满足你要求的全部必要偏好，暂不推荐。可以补充正文资料，或明确放宽要求。"
                    :"候选作品的状态或条件已变化，暂时没有可推荐作品，请重新搜索。";
            clarify=true;
        }
        else if(!evidence.isEmpty() || text.isBlank()) { text="现有资料不足以确认你的偏好，请补充题材或调整筛选条件。"; clarify=true; }
        // With no actionable cards, the reader must supply information or change conditions.
        if(recommendations.isEmpty()) clarify=true;
        if(model.mock()) text="【本地演示模式，未调用大模型】\n"+text;
        var safeConditions=new BookFinderQuery(conditions.keyword(),conditions.category(),conditions.tag(),conditions.completed(),
                conditions.minWords(),conditions.maxWords(),List.of(),20);
        return new Answer(t.state().getId(),t.request().requestId(),text,safeConditions,recommendations,clarify,model.mock(),usage);
    }
    // Covers are for Vue rendering; never repeat their URLs in the model context.
    private ArrayNode modelBooks(List<BookFinderBook> books,int introLimit) {
        var result=json.createArrayNode();
        for(var book:books) {
            ObjectNode data=json.valueToTree(book);
            data.remove("cover");
            String intro=Objects.toString(book.intro(),"");
            data.put("intro",intro.substring(0,Math.min(introLimit,intro.length())));
            result.add(data);
        }
        return result;
    }
    private void commit(Ticket t, Answer answer) {
        var state=t.state(); state.setConditions(answer.conditions()); state.setLastAnswer(answer);
        // An empty clarification does not erase the last displayed recommendation batch.
        if(!answer.recommendations().isEmpty())
            state.setLastBooks(answer.recommendations().stream().map(Recommendation::book).toList());
        var shown=new LinkedHashSet<>(state.getShown()); answer.recommendations().forEach(r->shown.add(r.book().id()));
        if(shown.size()>500) throw new BusinessException(422,"本会话已推荐较多作品，请开始新对话");
        state.setShown(new ArrayList<>(shown));
        state.getHistory().add(new Turn(t.request().message(),answer.answer()));
        while(state.getHistory().size()>6) state.getHistory().remove(0);
        store.complete(t.userId(),t.request().requestId(),t.fingerprint(),state,answer);
    }
    public static BookFinderQuery normalizeCategory(BookFinderQuery q, List<String> available) {
        String category=q.category(), keyword=q.keyword();
        if(category==null) return q;
        if(!available.isEmpty() && !available.contains(category)) {
            String requested=category;
            var matches=available.stream().filter(c->c.contains(requested)).toList();
            if(matches.size()!=1) throw new BusinessException(400,"分类不明确，请从实际分类中选择："+String.join("、",available));
            category=matches.get(0);
        }
        if(Objects.equals(keyword,q.category()) || Objects.equals(keyword,category)) keyword=null;
        return new BookFinderQuery(keyword,category,q.tag(),q.completed(),q.minWords(),q.maxWords(),q.excludeIds(),q.limit());
    }
    public BookFinderQuery merge(BookFinderQuery old, JsonNode patch, List<String> shown) {
        rejectUnknown(patch,Set.of("keyword","category","tag","completed","minWords","maxWords"));
        return new BookFinderQuery(string(patch,"keyword",old.keyword()),string(patch,"category",old.category()),
                string(patch,"tag",old.tag()),bool(patch,"completed",old.completed()),
                integer(patch,"minWords",old.minWords()),integer(patch,"maxWords",old.maxWords()),shown,20);
    }
    private static boolean sameHardConditions(BookFinderQuery a,BookFinderQuery b) {
        return Objects.equals(a.category(),b.category())&&Objects.equals(a.tag(),b.tag())&&Objects.equals(a.completed(),b.completed())
                &&Objects.equals(a.minWords(),b.minWords())&&Objects.equals(a.maxWords(),b.maxWords());
    }
    private static String string(JsonNode p,String name,String old) {
        if(!p.has(name)) return old; if(p.get(name).isNull()) return null;
        if(!p.get(name).isTextual()) throw new BusinessException(400,"条件类型不合法"); return p.get(name).asText();
    }
    private static Boolean bool(JsonNode p,String name,Boolean old) {
        if(!p.has(name)) return old; if(p.get(name).isNull()) return null;
        if(!p.get(name).isBoolean()) throw new BusinessException(400,"完结条件必须是布尔值"); return p.get(name).asBoolean();
    }
    private static Integer integer(JsonNode p,String name,Integer old) {
        if(!p.has(name)) return old; if(p.get(name).isNull()) return null;
        if(!p.get(name).isIntegralNumber()||!p.get(name).canConvertToInt()) throw new BusinessException(400,"字数必须是整数"); return p.get(name).intValue();
    }
    private static void rejectUnknown(JsonNode args,Set<String> allowed) {
        args.fieldNames().forEachRemaining(k->{if(!allowed.contains(k)) throw new BusinessException(400,
                "不支持的工具参数："+k.substring(0,Math.min(50,k.length()))+"；允许字段："+String.join("、",new TreeSet<>(allowed))+"。请删除未定义字段后重试");});
    }
    private JsonNode parseFinal(String raw) throws Exception {
        if(raw.length()>12000) throw new BusinessException(502,"模型输出过长");
        String cleaned=raw.trim().replaceAll("^```(?:json)?\\s*","").replaceAll("\\s*```$","");
        var result=json.readTree(cleaned);
        if(result==null||!result.isObject()) throw new BusinessException(502,"最终结果必须是 JSON 对象");
        var invalid=new ArrayList<String>();
        if(!result.path("answer").isTextual()) invalid.add("answer 必须是字符串");
        if(!result.path("recommendations").isArray()) invalid.add("recommendations 必须是数组，未推荐时为 []");
        if(!result.path("needsClarification").isBoolean()) invalid.add("needsClarification 必须是布尔值");
        if(!invalid.isEmpty()) throw new BusinessException(502,"模型结构化字段异常："+String.join("；",invalid));
        return result;
    }
    private String resource(String path) throws Exception {
        try(var in=new ClassPathResource("agent/bookfinder/"+path).getInputStream()) { return new String(in.readAllBytes(),StandardCharsets.UTF_8); }
    }
    private static void check(long deadline) throws InterruptedException {
        if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if(System.currentTimeMillis()>=deadline) throw new BusinessException(504,"找书请求超时，请缩小条件");
    }
    private static void validateUuid(String raw) {
        try { if(!UUID.fromString(raw).toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException(); }
        catch(Exception e) { throw new BusinessException(400,"会话或请求 ID 格式不合法"); }
    }
    private static String fingerprint(ChatRequest request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((Objects.toString(request.conversationId(),"")+"\n"+request.message()).getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
