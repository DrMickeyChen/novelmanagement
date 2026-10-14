package com.novel.modules.agent.bookfinder;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.*;
import com.novel.common.result.Result;
import com.novel.modules.agent.bookfinder.BookFinderTypes.*;
import com.novel.modules.agent.bookfinder.client.*;
import com.novel.modules.agent.bookfinder.conversation.BookFinderStore;
import com.novel.modules.agent.bookfinder.model.BookFinderModel;
import com.novel.modules.agent.bookfinder.service.BookFinderAgent;
import com.novel.modules.ai.service.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookFinderAgentTest {
    @AfterEach void clearIdentity() { com.novel.common.security.UserContext.clear(); }
    private void login(String role) {
        var user=new com.novel.common.security.LoginUser(); user.setUserId(99L); user.setRoles(List.of(role));
        com.novel.common.security.UserContext.set(user);
    }
    @Test void adminSkipsDailyQuotaAndRejectedRunDoesNotRefund() {
        login("ADMIN"); when(store.load(anyLong(),anyString())).thenReturn(state);
        var prepared=agent.prepare(99,ticket.request());
        assertFalse(prepared.quotaCharged());
        agent.rejected(prepared);
        verify(store,never()).acquireQuota(anyLong(),anyInt());
        verify(store,never()).refundQuota(anyLong());
    }
    @Test void readerStillChargesAndRefundsRejectedRun() {
        login("READER"); when(store.load(anyLong(),anyString())).thenReturn(state);
        var prepared=agent.prepare(99,ticket.request());
        assertTrue(prepared.quotaCharged()); agent.rejected(prepared);
        verify(store).acquireQuota(99,20); verify(store).refundQuota(99);
    }
    private final ObjectMapper json=new ObjectMapper();
    private final BookFinderSearchClient search=mock(BookFinderSearchClient.class,withSettings().mockMaker("mock-maker-subclass"));
    private final BookFinderContentClient content=mock(BookFinderContentClient.class,withSettings().mockMaker("mock-maker-subclass"));
    private final BookFinderStore store=mock(BookFinderStore.class,withSettings().mockMaker("mock-maker-subclass"));
    private final AiConfigService config=mock(AiConfigService.class,withSettings().mockMaker("mock-maker-subclass"));
    private final BookFinderModel model=mock(BookFinderModel.class,withSettings().mockMaker("mock-maker-subclass"));
    private final BookFinderAgent agent=new BookFinderAgent(json,search,content,model,store,config);
    private final BookFinderBook book=new BookFinderBook("9007199254740993","测试书","测试作者","/uploads/test.jpg","悬疑","悬疑,推理","一个调查谜团的故事。",true,1200000);
    private Conversation state;
    private BookFinderAgent.Ticket ticket;
    @BeforeEach void setup() {
        state=new Conversation(); state.setId(UUID.randomUUID().toString());
        ticket=new BookFinderAgent.Ticket(99,new ChatRequest("找悬疑小说",UUID.randomUUID().toString(),state.getId()),state,null,"fingerprint",null,System.currentTimeMillis());
        when(config.dailyLimit()).thenReturn(50);
        when(search.search(any())).thenReturn(Result.success(List.of(book)));
        when(content.details(any())).thenReturn(Result.success(List.of(book)));
    }
    @Test void genreAbbreviationUsesRealCategoryWithoutRepeatingKeyword() {
        var q=new BookFinderQuery("悬疑","悬疑",null,true,1000000,null,List.of(),20);
        var actual=BookFinderAgent.normalizeCategory(q,List.of("悬疑推理","历史"));
        assertEquals("悬疑推理",actual.category()); assertNull(actual.keyword());
        assertEquals(1000000,actual.minWords()); assertTrue(actual.completed());
        assertThrows(BusinessException.class,()->BookFinderAgent.normalizeCategory(q,List.of("悬疑推理","悬疑灵异")));
    }
    private BookFinderModel.Reply call(String tool,String args) throws Exception {
        ObjectNode m=json.createObjectNode(); m.put("role","assistant");
        var c=m.putArray("tool_calls").addObject(); c.put("id","call_"+UUID.randomUUID()); c.put("type","function");
        c.putObject("function").put("name",tool).put("arguments",args);
        return new BookFinderModel.Reply(m,AiChatUsage.ZERO);
    }
    private BookFinderModel.Reply answer(String id,String quote) throws Exception {
        var data=json.createObjectNode(); data.put("answer","结果");data.put("needsClarification",false);
        data.putArray("recommendations").addObject().put("id",id).put("quote",quote);
        var m=json.createObjectNode();m.put("role","assistant");m.put("content",data.toString());
        return new BookFinderModel.Reply(m,AiChatUsage.ZERO);
    }
    private void replies(BookFinderModel.Reply... replies) throws Exception {
        var stub=when(model.call(anyLong(),any(),any(),any(),anyLong(),any(),anyString()));
        for(var reply:replies) stub=stub.thenReturn(reply);
    }
    private BookFinderModel.Reply clarification() {
        var data=json.createObjectNode().put("answer","请告诉我想看的分类").put("needsClarification",true);
        data.putArray("recommendations");
        return new BookFinderModel.Reply(json.createObjectNode().put("role","assistant").put("content",data.toString()),AiChatUsage.ZERO);
    }
    private Answer nextTurn(String message) throws Exception {
        return agent.run(new BookFinderAgent.Ticket(99,new ChatRequest(message,UUID.randomUUID().toString(),state.getId()),
                state,null,"fp",null,System.currentTimeMillis()),s->{});
    }
    @Test void inferredSimilarityTagBecomesSoftPreferenceAndKeepsExplicitFilters() throws Exception {
        state.setConditions(new BookFinderQuery(null,"悬疑","推理",true,200000,2000000,List.of(),20));
        state.setLastBooks(List.of(book,book,book,book));
        replies(call("searchBooks","{\"tag\":\"温暖\"}"),
                call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),""));
        nextTurn("类似第4本的还有吗");
        var query=org.mockito.ArgumentCaptor.forClass(BookFinderQuery.class);
        verify(search).search(query.capture());
        assertEquals("推理",query.getValue().tag());
        assertTrue(query.getValue().completed()); assertEquals(200000,query.getValue().minWords());
        assertEquals(2000000,query.getValue().maxWords());
        assertEquals(List.of(new Preference("温暖",false)),state.getPreferences());
        assertEquals("温暖",state.getPreference());
    }
    @Test void explicitlyRequestedTagIsNotConvertedToSoftPreference() throws Exception {
        state.setLastBooks(List.of(book,book,book,book));
        replies(call("searchBooks","{\"tag\":\"推理\"}"),
                call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),""));
        var result=nextTurn("类似第4本的还有吗，标签必须是推理");
        assertEquals("推理",result.conditions().tag());
        assertTrue(state.getPreferences().isEmpty());
    }
    @Test void inferredSimilarityTagDoesNotChangeHardConditionsOnRetry() throws Exception {
        state.setLastBooks(List.of(book,book,book,book));
        replies(call("searchBooks","{\"tag\":\"温暖\"}"),call("searchBooks","{\"tag\":\"温暖\"}"),
                call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),""));
        nextTurn("类似第4本的还有吗");
        var queries=org.mockito.ArgumentCaptor.forClass(BookFinderQuery.class);
        verify(search,times(2)).search(queries.capture());
        assertTrue(queries.getAllValues().stream().allMatch(q->q.tag()==null));
        assertEquals(1,state.getPreferences().size());
    }
    @Test void missingOrdinalStopsBeforeModelSearchAndRecovery() throws Exception {
        var result=nextTurn("类似第99本的还有吗");
        assertTrue(result.needsClarification()); assertTrue(result.recommendations().isEmpty());
        assertEquals(0,result.usage().modelCalls()); assertEquals(0,result.usage().toolCalls());
        verify(model,never()).call(anyLong(),any(),any(),any(),anyLong(),any(),anyString());
        verifyNoInteractions(search,content);
        state.setLastBooks(List.of(book,book,book,book,book));
        assertTrue(nextTurn("类似第六本的还有吗").recommendations().isEmpty());
        assertTrue(nextTurn("类似第0本的还有吗").needsClarification());
        assertEquals(99,com.novel.modules.agent.bookfinder.conversation.BookFinderIntent.referencePosition("第九十九本"));
        assertEquals(4,com.novel.modules.agent.bookfinder.conversation.BookFinderIntent.referencePosition("第四本"));
    }
    @Test void similarityDoesNotInheritReferenceCompletionOrWordCount() throws Exception {
        state.setLastBooks(List.of(book,book,book,book));
        replies(call("searchBooks","{\"completed\":true,\"minWords\":200000,\"maxWords\":300000}"),
                call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),""));
        var result=nextTurn("类似第4本的还有吗");
        assertNull(result.conditions().completed()); assertNull(result.conditions().minWords()); assertNull(result.conditions().maxWords());
        assertEquals(1,result.recommendations().size());
    }
    @Test void inferredSimilarityKeywordIsSoftAndRemainsSoOnRetry() throws Exception {
        state.setLastBooks(List.of(book,book,book,book));
        state.setConditions(new BookFinderQuery(null,"悬疑",null,true,200000,2000000,List.of(),20));
        replies(call("searchBooks","{\"keyword\":\"智谋\"}"),call("searchBooks","{\"keyword\":\"智谋\"}"),
                call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),""));
        var result=nextTurn("类似第4本的还有吗");
        var queries=org.mockito.ArgumentCaptor.forClass(BookFinderQuery.class);
        verify(search,times(2)).search(queries.capture());
        assertTrue(queries.getAllValues().stream().allMatch(q->q.keyword()==null));
        assertEquals("智谋",state.getPreference());
        assertEquals(List.of(new Preference("智谋",false)),state.getPreferences());
        assertTrue(result.conditions().completed()); assertEquals(200000,result.conditions().minWords());
    }
    @Test void explicitlyRequestedKeywordIsKeptForSimilarSearch() throws Exception {
        state.setLastBooks(List.of(book,book,book,book));
        replies(call("searchBooks","{\"keyword\":\"调查\"}"),
                call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),""));
        assertEquals("调查",nextTurn("类似第4本的还有吗，关键词要调查").conditions().keyword());
        assertTrue(state.getPreferences().isEmpty());
    }
    @Test void similarCandidatesCanBeReturnedWithUnknownStyleWithoutInventingSupport() throws Exception {
        state.setLastBooks(List.of(book,book,book,book));
        replies(call("searchBooks","{\"keyword\":\"智谋\"}"),clarification());
        var result=nextTurn("类似第4本的还有吗");
        assertEquals(1,result.recommendations().size());
        var recommendation=result.recommendations().get(0);
        assertEquals("UNKNOWN",recommendation.assessments().get(0).verdict());
        assertTrue(recommendation.sources().isEmpty());
        assertTrue(recommendation.reason().contains("尚未确认"));
        assertFalse(result.needsClarification());
    }
    @Test void emptySearchResultCorrectsFalseClarificationFlag() throws Exception {
        when(search.search(any())).thenReturn(Result.success(List.of()));
        var data=json.createObjectNode().put("answer","没有找到作品，可以放宽条件再找。").put("needsClarification",false);
        data.putArray("recommendations");
        replies(call("searchBooks","{}"),new BookFinderModel.Reply(json.createObjectNode().put("role","assistant").put("content",data.toString()),AiChatUsage.ZERO));
        assertTrue(nextTurn("找悬疑小说").needsClarification());
    }
    @Test void missingFinalFieldsAreNamedInRepairInstruction() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(anyLong(),any(),any(),any(),anyLong(),any(),anyString())).thenAnswer(invocation->{
            if(calls.getAndIncrement()==0) return new BookFinderModel.Reply(json.createObjectNode().put("role","assistant").put("content","{\"answer\":\"你好\"}"),AiChatUsage.ZERO);
            String context=((ArrayNode)invocation.getArgument(2)).toString();
            assertTrue(context.contains("recommendations 必须是数组"));
            assertTrue(context.contains("needsClarification 必须是布尔值"));
            return clarification();
        });
        assertTrue(nextTurn("你好").needsClarification());
        assertEquals(2,calls.get());
    }
    @Test void evidenceToolAutomaticallyFetchesMissingCandidateDetails() throws Exception {
        var k=advancedKnowledge();
        var hit=new BookKnowledge.Hit(book.id(),"chunk-1","chapter-1",1,"调查线索",0.9,10,100,false,"v1","KEYWORD");
        when(k.retrieve(any())).thenReturn(new com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService.Retrieval(List.of(hit),List.of(),"KEYWORD",""));
        when(k.fresh(hit)).thenReturn(true);
        replies(call("searchBooks","{}"),call("searchBookEvidence","{\"ids\":[\""+book.id()+"\"],\"query\":\"调查\"}"),evidenceAnswer("chunk-1"));
        var result=nextTurn("推荐开篇有调查的小说");
        assertEquals(1,result.recommendations().size()); assertEquals(3,result.usage().toolCalls());
        var order=inOrder(content,k);
        order.verify(content).details(List.of(book.id()));
        order.verify(k).retrieve(any());
        verify(content,times(2)).details(List.of(book.id()));
    }
    @Test void repairReceivesActualMalformedOutputWithoutRepeatingTools() throws Exception {
        var broken=json.createObjectNode().put("role","assistant").put("content","{\"answer\":\"未闭合");
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(anyLong(),any(),any(),any(),anyLong(),any(),anyString())).thenAnswer(invocation->{
            if(calls.getAndIncrement()==0) return new BookFinderModel.Reply(broken,AiChatUsage.ZERO);
            var messages=(ArrayNode)invocation.getArgument(2);
            assertTrue(messages.toString().contains("未闭合"));
            assertNull(invocation.getArgument(3));
            return clarification();
        });
        assertTrue(nextTurn("你好").needsClarification());
        assertEquals(2,calls.get()); verifyNoInteractions(search);
    }
    @Test void greetingQuestionCorrectsFalseClarificationFlag() throws Exception {
        var data=json.createObjectNode().put("answer","你好！想看什么类型的小说呢？").put("needsClarification",false);
        data.putArray("recommendations");
        replies(new BookFinderModel.Reply(json.createObjectNode().put("role","assistant").put("content",data.toString()),AiChatUsage.ZERO));
        var result=nextTurn("你好！");
        assertTrue(result.needsClarification()); assertTrue(result.recommendations().isEmpty());
        verifyNoInteractions(search);
        verify(content,never()).details(any());
        assertTrue(com.novel.modules.agent.bookfinder.conversation.BookFinderIntent.greeting("Hello!"));
        assertFalse(com.novel.modules.agent.bookfinder.conversation.BookFinderIntent.greeting("你好，推荐几本小说"));
    }
    @Test void emptyRecommendationRecoversCandidatesAndLogsActualDetailCall() throws Exception {
        replies(call("searchBooks","{}"),clarification());
        var result=nextTurn("推荐几本小说");
        assertEquals(List.of(book.id()),result.recommendations().stream().map(r->r.book().id()).toList());
        assertFalse(result.needsClarification());
        assertEquals(2,result.usage().toolCalls());
        verify(content,times(2)).details(List.of(book.id())); // recovery and final live validation
        var capture=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(store).runLog(eq(99L),anyString(),capture.capture());
        assertTrue(capture.getValue().toString().contains("EMPTY_RECOMMENDATIONS_RECOVERY"));
    }
    @Test void emptyRecommendationDoesNotOverrideStyleClarification() throws Exception {
        replies(call("searchBooks","{\"preferences\":[{\"criterion\":\"虐文\",\"required\":true}]}"),clarification());
        assertTrue(nextTurn("必须是虐文").recommendations().isEmpty());
        verify(content,never()).details(any());
    }
    @Test void noCandidatesOrGreetingDoesNotInventRecommendations() throws Exception {
        when(search.search(any())).thenReturn(Result.success(List.of()));
        replies(call("searchBooks","{}"),clarification());
        assertTrue(nextTurn("推荐几本小说").recommendations().isEmpty());
        replies(clarification());
        assertTrue(nextTurn("你好").recommendations().isEmpty());
        verify(content,never()).details(any());
    }
    @Test void recoveredCandidateStillMustPassFinalVisibilityCheck() throws Exception {
        when(content.details(any())).thenReturn(Result.success(List.of(book)),Result.success(List.of()));
        replies(call("searchBooks","{}"),clarification());
        assertTrue(nextTurn("推荐几本小说").recommendations().isEmpty());
        assertTrue(state.getShown().isEmpty());
    }
    @Test void recommendationRefreshAndFourthReferenceExecuteThreeRealAgentTurns() throws Exception {
        var books=new ArrayList<BookFinderBook>();
        for(int i=1;i<=15;i++) books.add(new BookFinderBook(Integer.toString(i),"验收书"+i,"作者","","悬疑","推理","调查谜团",true,300000));
        when(search.search(any())).thenReturn(Result.success(books)); // deliberately returns excluded IDs as well
        when(content.details(any())).thenAnswer(invocation->{
            List<String> requested=invocation.getArgument(0);
            var response=new ArrayList<>(books.stream().filter(b->requested.contains(b.id())).toList());
            Collections.reverse(response); // content service order must not change card positions
            return Result.success(response);
        });
        var step=new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(anyLong(),any(),any(),any(),anyLong(),any(),anyString())).thenAnswer(invocation->{
            int n=step.getAndIncrement();
            if(n==0 || n==2) return call("searchBooks",n==0?"{\"completed\":true,\"minWords\":200000}":"{}");
            if(n==1 || n==3) return clarification();
            if(n==4) {
                String context=((ArrayNode)invocation.getArgument(2)).get(0).path("content").asText();
                assertTrue(context.contains("\"requestMode\":\"CONTINUE\""));
                assertTrue(context.contains("\"id\":\"9\",\"title\":\"验收书9\""));
                assertTrue(context.contains("\"position\":4"));
                assertEquals("9",state.getLastBooks().get(3).id());
                return call("getBookDetails","{\"ids\":[\"9\"]}"); // resolve and inspect the actual fourth card
            }
            if(n==5) return call("searchBooks","{}");
            if(n==6) return call("getBookDetails","{\"ids\":[\"11\"]}");
            return answer("11","调查谜团");
        });
        var first=nextTurn("推荐已完结、至少二十万字的小说");
        var second=nextTurn("换一批");
        assertEquals(List.of("1","2","3","4","5"),first.recommendations().stream().map(r->r.book().id()).toList());
        assertEquals(List.of("6","7","8","9","10"),second.recommendations().stream().map(r->r.book().id()).toList());
        var third=nextTurn("类似第4本的还有吗");
        assertEquals("11",third.recommendations().get(0).book().id());
        assertTrue(third.conditions().completed()); assertEquals(200000,third.conditions().minWords());
        assertEquals(11,state.getShown().size());
        var queries=org.mockito.ArgumentCaptor.forClass(BookFinderQuery.class);
        verify(search,times(3)).search(queries.capture());
        assertEquals(List.of("1","2","3","4","5"),queries.getAllValues().get(1).excludeIds());
        assertEquals(10,queries.getAllValues().get(2).excludeIds().size());
        verify(store,times(3)).complete(eq(99L),anyString(),anyString(),eq(state),any());
    }
    @Test void fourthBookReferenceRetainsOrderedCardsAndSurvivesEmptyClarification() throws Exception {
        var previous=new ArrayList<BookFinderBook>();
        for(int i=1;i<=5;i++) previous.add(new BookFinderBook(Integer.toString(i),"上轮第"+i+"本","作者","","玄幻","修仙","修仙小说简介",true,300000));
        state.setLastBooks(previous);
        state.setConditions(new BookFinderQuery(null,"玄幻",null,null,null,null,List.of(),20));
        state.getHistory().add(new Turn("有修仙小说吗","找到五本作品"));
        var request=new ChatRequest("类似第4本的还有吗，要求有恋爱情节",UUID.randomUUID().toString(),state.getId());
        var continued=new BookFinderAgent.Ticket(99,request,state,null,"fingerprint",null,System.currentTimeMillis());
        var data=json.createObjectNode().put("answer","请补充爱情线偏好").put("needsClarification",true);
        data.putArray("recommendations");
        when(model.call(anyLong(),any(),any(),any(),anyLong(),any(),anyString())).thenAnswer(invocation->{
            var messages=(ArrayNode)invocation.getArgument(2);
            String system=messages.get(0).path("content").asText();
            assertTrue(system.contains("\"requestMode\":\"CONTINUE\""));
            assertTrue(system.contains("上轮第4本"));
            assertTrue(system.contains("\"position\":4"));
            assertEquals("玄幻",state.getConditions().category());
            assertEquals(1,state.getHistory().size());
            return new BookFinderModel.Reply(json.createObjectNode().put("role","assistant").put("content",data.toString()),AiChatUsage.ZERO);
        });
        var answer=agent.run(continued,s->{});
        assertTrue(answer.recommendations().isEmpty());
        assertEquals(previous,state.getLastBooks());
    }
    @Test void malformedFinalAnswerIsRepairedOnceWithoutRepeatingTools() throws Exception {
        var invalid=json.createObjectNode().put("role","assistant").put("content","这里有几本推荐小说。");
        replies(call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),
                new BookFinderModel.Reply(invalid,AiChatUsage.ZERO),answer(book.id(),"调查谜团"));
        var result=agent.run(ticket,s->{});
        assertEquals(4,result.usage().modelCalls()); assertEquals(2,result.usage().toolCalls());
        verify(search,times(1)).search(any());
        verify(model).call(anyLong(),any(),any(),isNull(),anyLong(),any(),anyString());
    }
    @Test void requiredPreferenceCannotBeSilentlyIgnoredWhenOnlyIntroIsAvailable() throws Exception {
        replies(call("searchBooks","{\"preferences\":[{\"criterion\":\"虐文\",\"required\":true}]}"),
                call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),"调查谜团"));
        var result=agent.run(ticket,s->{});
        assertTrue(result.recommendations().isEmpty()); assertTrue(result.needsClarification());
        assertTrue(result.answer().contains("必要偏好")); assertEquals("虐文",state.getPreference());
    }
    @Test void incompleteJsonGetsOneRepairAndPersistentFailureIsActionable() throws Exception {
        var invalid=json.createObjectNode().put("role","assistant").put("content","{\"answer\":\"你好\"}");
        replies(new BookFinderModel.Reply(invalid,AiChatUsage.ZERO));
        var error=assertThrows(BusinessException.class,()->agent.run(ticket,s->{}));
        assertEquals(502,error.getCode()); assertTrue(error.getMessage().contains("格式异常"));
        verify(model,times(2)).call(anyLong(),any(),any(),any(),anyLong(),any(),anyString());
        verifyNoInteractions(search);
    }
    @Test void lastRegularRoundStillHasOneBoundedFormatRepair() throws Exception {
        var invalid=json.createObjectNode().put("role","assistant").put("content","推荐如下");
        replies(call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),
                call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),
                new BookFinderModel.Reply(invalid,AiChatUsage.ZERO),answer(book.id(),"调查谜团"));
        var result=agent.run(ticket,s->{});
        assertEquals(6,result.usage().modelCalls()); assertEquals(4,result.usage().toolCalls());
    }
    @Test void fullToolLoopReturnsGroundedStringIdAndStoresState() throws Exception {
        replies(call("searchBooks","{\"category\":\"悬疑\",\"completed\":true,\"minWords\":1000000}"),
                call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),"调查谜团"));
        var result=agent.run(ticket,s->{});
        assertEquals(book.id(),result.recommendations().get(0).book().id());
        assertEquals("调查谜团",result.recommendations().get(0).evidence());
        assertEquals(3,result.usage().modelCalls()); assertEquals(2,result.usage().toolCalls());
        assertEquals(List.of(book.id()),state.getShown()); assertTrue(state.getConditions().completed());
        verify(store).complete(99L,ticket.request().requestId(),ticket.fingerprint(),state,result);
    }
    @Test void newTopicResetsOldFiltersAndDoesNotSendOldTopicToModel() throws Exception {
        state.setConditions(new BookFinderQuery("火影","同人",null,true,1000000,null,List.of(),20));
        state.setPreference("二次元"); state.getHistory().add(new Turn("推荐同人","同人推荐"));
        ticket=new BookFinderAgent.Ticket(99,new ChatRequest("有没有主角不是无脑强的修仙爽文",UUID.randomUUID().toString(),state.getId()),state,null,"fp",null,System.currentTimeMillis());
        replies(call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),"调查谜团"));
        var result=agent.run(ticket,s->{});
        assertNull(result.conditions().category()); assertNull(result.conditions().keyword());
        assertNull(result.conditions().completed()); assertNull(result.conditions().minWords());
        var capture=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ArrayNode.class);
        verify(model,atLeastOnce()).call(anyLong(),any(),capture.capture(),any(),anyLong(),any(),anyString());
        assertFalse(capture.getAllValues().get(0).toString().contains("火影"));
        assertFalse(capture.getAllValues().get(0).toString().contains("二次元"));
    }
    @Test void explicitContinuationStillInheritsFilters() throws Exception {
        state.setConditions(new BookFinderQuery(null,"悬疑",null,true,1000000,null,List.of(),20));
        ticket=new BookFinderAgent.Ticket(99,new ChatRequest("换一批",UUID.randomUUID().toString(),state.getId()),state,null,"fp",null,System.currentTimeMillis());
        replies(call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),"调查谜团"));
        var result=agent.run(ticket,s->{});
        assertEquals("悬疑",result.conditions().category()); assertTrue(result.conditions().completed());
        assertEquals(1000000,result.conditions().minWords());
    }
    @Test void fabricatedIdIsRejectedWithoutCommittingConversation() throws Exception {
        replies(call("searchBooks","{}"),answer("999",""));
        assertThrows(BusinessException.class,()->agent.run(ticket,s->{}));
        verify(store,never()).complete(anyLong(),anyString(),anyString(),any(),any());
    }
    @Test void disappearingBookIsRemovedByFinalVisibilityCheck() throws Exception {
        when(content.details(any())).thenReturn(Result.success(List.of(book)),Result.success(List.of()));
        replies(call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),"故事"));
        assertTrue(agent.run(ticket,s->{}).recommendations().isEmpty());
    }
    @Test void unsupportedEvidenceIsDiscarded() throws Exception {
        replies(call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),answer(book.id(),"主角不无敌"));
        var r=agent.run(ticket,s->{}).recommendations().get(0);
        assertEquals("",r.evidence());assertFalse(r.reason().contains("主角不无敌"));
    }
    @Test void followupPreservesHardConditionsAndExcludesShownIds() throws Exception {
        var old=new BookFinderQuery(null,"悬疑",null,true,1000000,null,List.of(),20);
        var q=agent.merge(old,json.readTree("{\"keyword\":\"调查\"}"),List.of(book.id()));
        assertTrue(q.completed());assertEquals(1000000,q.minWords());assertEquals(List.of(book.id()),q.excludeIds());
        assertThrows(BusinessException.class,()->agent.merge(old,json.readTree("{\"completed\":\"yes\"}"),List.of()));
    }
    @Test void executionCannotLoopForever() throws Exception {
        when(search.search(any())).thenReturn(Result.success(List.of()));
        replies(call("searchBooks","{}"),call("searchBooks","{}"),call("searchBooks","{}"),call("searchBooks","{}"));
        assertThrows(BusinessException.class,()->agent.run(ticket,s->{}));
        verify(model,times(5)).call(anyLong(),any(),any(),any(),anyLong(),any(),anyString());
        verify(search,times(4)).search(any());
    }
    @Test void unknownToolNeverInvokesBackend() throws Exception {
        replies(call("deleteBook","{}"),answer("999",""));
        assertThrows(BusinessException.class,()->agent.run(ticket,s->{}));
        verifyNoInteractions(search);
        verify(content,never()).details(any());
    }
    @Test void cachedRetryDoesNotAcquireQuotaOrCallModel() throws Exception {
        var req=ticket.request();
        when(store.load(anyLong(),anyString())).thenReturn(state);
        var first=agent.prepare(99,req);
        var cached=new Answer(state.getId(),req.requestId(),"done",state.getConditions(),List.of(),false,true,new Usage(0,0,0,0));
        when(store.request(99,req.requestId())).thenReturn(new RequestRecord(first.fingerprint(),"done",cached));
        var replay=agent.prepare(99,req);
        assertEquals(cached,agent.run(replay,s->{}));
        verify(store,times(1)).acquireQuota(99,20);
        verify(model,never()).call(anyLong(),any(),any(),any(),anyLong(),any(),anyString());
    }
    private com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService advancedKnowledge() {
        var knowledge=mock(com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService.class,withSettings().mockMaker("mock-maker-subclass"));
        agent.advanced(knowledge,null); return knowledge;
    }
    private BookFinderModel.Reply evidenceAnswer(String ref) throws Exception {
        var reply=answer(book.id(),"调查谜团");
        var value=json.readTree(reply.message().path("content").asText());
        ((ObjectNode)value).put("answer","真凶是某人，结局有人死亡");
        ((ObjectNode)value.path("recommendations").get(0)).putArray("evidenceIds").add(ref);
        reply.message().put("content",value.toString()); return reply;
    }
    @Test void bodyEvidenceHasValidatedProvenanceWithoutLeakingPlot() throws Exception {
        var k=advancedKnowledge();
        var hit=new com.novel.common.internal.BookKnowledge.Hit(book.id(),"chunk-1","chapter-1",1,"真凶是某人，结局有人死亡",0.9,10,100,false,"v1","KEYWORD");
        when(k.retrieve(any())).thenReturn(new com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService.Retrieval(List.of(hit),List.of(),"KEYWORD",""));
        when(k.fresh(hit)).thenReturn(true);
        replies(call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),
                call("searchBookEvidence","{\"ids\":[\""+book.id()+"\"],\"query\":\"调查\"}"),evidenceAnswer("chunk-1"));
        var answer=agent.run(ticket,s->{});
        assertEquals(1,answer.recommendations().get(0).sources().size());
        assertEquals(10,answer.recommendations().get(0).sources().get(0).coveredChapters());
        assertFalse(json.writeValueAsString(answer).contains("真凶")); assertFalse(json.writeValueAsString(answer).contains("死亡"));
    }
    @Test void inventedBodyCitationIsRejected() throws Exception {
        advancedKnowledge();
        replies(call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),evidenceAnswer("invented-chunk"));
        assertThrows(BusinessException.class,()->agent.run(ticket,s->{}));
        verify(store,never()).complete(anyLong(),anyString(),anyString(),any(),any());
    }
    @Test void evidenceOutsideCandidateSetNeverInvokesKnowledge() throws Exception {
        var k=advancedKnowledge();
        replies(call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),
                call("searchBookEvidence","{\"ids\":[\"123\"],\"query\":\"调查\"}"),answer(book.id(),"调查谜团"));
        assertEquals(1,agent.run(ticket,s->{}).recommendations().size()); verify(k,never()).retrieve(any());
    }
    @Test void failingEvidenceServiceKeepsBasicSearchAvailable() throws Exception {
        var k=advancedKnowledge(); when(k.retrieve(any())).thenThrow(new IllegalStateException("offline"));
        replies(call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),
                call("searchBookEvidence","{\"ids\":[\""+book.id()+"\"],\"query\":\"调查\"}"),answer(book.id(),"调查谜团"));
        var result=agent.run(ticket,s->{}); assertEquals(1,result.recommendations().size()); assertTrue(result.recommendations().get(0).sources().isEmpty());
    }
    @Test void cumulativeBudgetAllowsAdvancedLoopBeyondTwentyThousand() throws Exception {
        var finalReply=answer(book.id(),"调查谜团");
        replies(call("searchBooks","{}"),call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),
                new BookFinderModel.Reply(finalReply.message(),new AiChatUsage(22000,1098,23098)));
        assertEquals(23098,agent.run(ticket,s->{}).usage().totalTokens());
    }
    @Test void cumulativeBudgetStillStopsOverSixtyThousand() throws Exception {
        var reply=answer(book.id(),"");
        replies(new BookFinderModel.Reply(reply.message(),new AiChatUsage(60000,1,60001)));
        var error=assertThrows(BusinessException.class,()->agent.run(ticket,s->{}));
        assertEquals(429,error.getCode()); assertTrue(error.getMessage().contains("Token"));
    }
    @Test void unindexedBodyIsExplicitlyReportedInsteadOfRepeatingIntro() throws Exception {
        var k=advancedKnowledge(); when(k.candidates(any(),anyString())).thenReturn(List.of());
        when(k.retrieve(any())).thenReturn(new com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService.Retrieval(
                List.of(),List.of(book.id()),"KEYWORD",""));
        replies(call("searchBooks","{\"preference\":\"主角逐步成长\"}"),
                call("getBookDetails","{\"ids\":[\""+book.id()+"\"]}"),
                call("searchBookEvidence","{\"ids\":[\""+book.id()+"\"],\"query\":\"成长\"}"),answer(book.id(),"调查谜团"));
        var recommendation=agent.run(ticket,s->{}).recommendations().get(0);
        assertTrue(recommendation.reason().contains("\u6b63\u6587\u77e5\u8bc6\u5e93\u672a\u6784\u5efa"));
        assertFalse(recommendation.reason().contains("\u7b80\u4ecb\u4f9d\u636e")); assertTrue(recommendation.sources().isEmpty());
    }

}
