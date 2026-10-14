package com.novel.modules.agent.bookfinder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.modules.agent.bookfinder.eval.BookFinderEvaluation;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
class BookFinderEvaluationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void reportSurvivesServiceRestartAndRejectsPathTraversal() throws Exception {
        var json=new ObjectMapper();
        var agent=org.mockito.Mockito.mock(com.novel.modules.agent.bookfinder.service.BookFinderAgent.class,org.mockito.Mockito.withSettings().mockMaker("mock-maker-subclass"));
        var store=org.mockito.Mockito.mock(com.novel.modules.agent.bookfinder.conversation.BookFinderStore.class,org.mockito.Mockito.withSettings().mockMaker("mock-maker-subclass"));
        var knowledge=org.mockito.Mockito.mock(com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService.class,org.mockito.Mockito.withSettings().mockMaker("mock-maker-subclass"));
        var notes=org.mockito.Mockito.mock(com.novel.modules.agent.bookfinder.knowledge.KnowledgeNotes.class,org.mockito.Mockito.withSettings().mockMaker("mock-maker-subclass"));
        org.mockito.Mockito.when(knowledge.status()).thenReturn(Map.of("embeddingConfigured",false));
        org.mockito.Mockito.when(knowledge.catalog()).thenReturn(List.of());
        var user=new com.novel.common.security.LoginUser(); user.setUserId(77L); user.setRoles(List.of("ADMIN")); com.novel.common.security.UserContext.set(user);
        var evaluation=new BookFinderEvaluation(json,agent,store,knowledge,notes);
        org.springframework.test.util.ReflectionTestUtils.setField(evaluation,"directory",directory.toString());
        var restarted=new BookFinderEvaluation(json,agent,store,knowledge,notes);
        org.springframework.test.util.ReflectionTestUtils.setField(restarted,"directory",directory.toString());
        try {
            var report=evaluation.start(new BookFinderEvaluation.Start("durability",List.of(new BookFinderEvaluation.Case("a","hello",null,null,null,true)),null,null,null));
            String id=report.path("id").asText();
            for(int i=0;i<200 && Set.of("RUNNING","QUEUED").contains(evaluation.get(id).path("status").asText());i++) Thread.sleep(10);
            var saved=restarted.get(id); assertEquals("DONE",saved.path("status").asText());
            assertEquals("FAILED",saved.path("rows").get(0).path("status").asText()); // simulated agent failure is retained, never scored as success
            assertEquals(1,restarted.list().size()); assertFalse(saved.path("agentCodeSha256").asText().isBlank());
            assertThrows(com.novel.common.exception.BusinessException.class,()->restarted.get("../../secret"));
        } finally { evaluation.close(); restarted.close(); com.novel.common.security.UserContext.clear(); }
    }
    @Test void calculatesMetricsWithoutInventingAccuracyForEmptyRecommendations() throws Exception {
        var json=new ObjectMapper(); var report=json.createObjectNode(); report.set("rows",json.readTree("""
          [{"recommended":0,"hardMatches":0,"automaticPass":false,"durationMs":100,"review":"PENDING"}]
          """));
        var input=new BookFinderEvaluation.Start("test",List.of(new BookFinderEvaluation.Case("a","hello",null,null,null,true)),null,null,null);
        BookFinderEvaluation.summarize(report,input);
        assertTrue(report.path("metrics").path("hardConditionRate").isNull());
        assertTrue(report.path("metrics").path("humanSupportRate").isNull());
        assertTrue(report.path("metrics").path("estimatedChatCost").isNull());
    }
    @Test void aggregatesFailuresLatencyCostAndHumanReviewSeparately() throws Exception {
        var json=new ObjectMapper(); var report=json.createObjectNode(); report.set("rows",json.readTree("""
          [{"recommended":2,"hardMatches":1,"automaticPass":false,"durationMs":100,"review":"SUPPORTED","answer":{"usage":{"totalTokens":300,"promptTokens":200,"completionTokens":100}}},
           {"recommended":1,"hardMatches":1,"automaticPass":true,"durationMs":200,"review":"NOT_SUPPORTED"},
           {"status":"FAILED","automaticPass":false,"durationMs":900,"review":"PENDING"}]
          """));
        var input=new BookFinderEvaluation.Start("test",List.of(new BookFinderEvaluation.Case("a","hello",null,null,null,true)),new BigDecimal("2"),new BigDecimal("4"),"CNY");
        BookFinderEvaluation.summarize(report,input); var m=report.path("metrics");
        assertEquals(2.0/3,m.path("hardConditionRate").asDouble()); assertEquals(0.5,m.path("humanSupportRate").asDouble());
        assertEquals(900,m.path("p95Ms").asLong()); assertEquals(0.0008,m.path("estimatedChatCost").asDouble(),1e-8);
    }
}
