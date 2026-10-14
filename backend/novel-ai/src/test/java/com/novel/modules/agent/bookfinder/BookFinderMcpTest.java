package com.novel.modules.agent.bookfinder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.modules.agent.bookfinder.controller.BookFinderMcpController;
import com.novel.modules.agent.bookfinder.service.*;
import com.novel.modules.agent.bookfinder.conversation.BookFinderStore;
import com.novel.common.internal.*;
import com.novel.common.security.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class BookFinderMcpTest {
    final ObjectMapper json=new ObjectMapper();
    final BookFinderTools tools=mock(BookFinderTools.class,withSettings().mockMaker("mock-maker-subclass"));
    final BookFinderStore store=mock(BookFinderStore.class,withSettings().mockMaker("mock-maker-subclass"));
    final BookFinderAgent agent=mock(BookFinderAgent.class,withSettings().mockMaker("mock-maker-subclass"));
    final BookFinderMcpController mcp=new BookFinderMcpController(json,tools,store,agent);
    @BeforeEach void setup() { ReflectionTestUtils.setField(mcp,"allowedOrigins","http://localhost:3000"); }
    @AfterEach void clear() { UserContext.clear(); }
    @Test void supportsInitializeNotificationDiscoveryAndUnknownMethod() throws Exception {
        var init=mcp.post(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"),null,null);
        assertEquals("2025-03-26",init.getBody().path("result").path("protocolVersion").asText());
        assertEquals(202,mcp.post(json.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),null,null).getStatusCode().value());
        var list=mcp.post(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"),null,null);
        assertEquals(3,list.getBody().path("result").path("tools").size());
        assertEquals(405,mcp.get(null).getStatusCode().value());
        assertThrows(com.novel.common.exception.BusinessException.class,()->mcp.get("https://evil.example"));
        verifyNoInteractions(tools,store);
    }
    @Test void unknownToolCannotReachBusinessLogicAndBadVersionIsRejected() throws Exception {
        var response=mcp.post(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"deleteBook\",\"arguments\":{}}}"),null,null);
        assertEquals(-32602,response.getBody().path("error").path("code").asInt());
        assertEquals(400,mcp.post(json.createObjectNode(),null,"wrong").getStatusCode().value()); verifyNoInteractions(tools,store);
    }
    @Test void httpTransportReturnsJsonRpcParseErrorAndSupportsValidRequests() {
        var invalid=mcp.http("{broken",null,null);
        assertEquals(400,invalid.getStatusCode().value()); assertEquals(-32700,invalid.getBody().path("error").path("code").asInt());
        var valid=mcp.http("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}",null,null);
        assertTrue(valid.getBody().has("result"));
    }
    @Test void toolsCallUsesSharedToolsAndReturnsMcpContent() throws Exception {
        var user=new LoginUser(); user.setUserId(1L); user.setRoles(List.of("ADMIN")); UserContext.set(user);
        when(tools.searchBooks(any())).thenReturn(List.of(new BookFinderBook("1","Test","Author","","history","","Intro",true,300000)));
        var result=mcp.post(json.readTree("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"searchBooks\",\"arguments\":{\"completed\":true}}}"),null,null).getBody().path("result");
        assertFalse(result.path("isError").asBoolean()); assertTrue(result.path("content").get(0).path("text").asText().contains("Test"));
        verify(tools).searchBooks(argThat(q->Boolean.TRUE.equals(q.completed())));
        verify(store,never()).acquireQuota(anyLong(),anyInt()); verify(store).runLog(eq(1L),anyString(),any());
    }
}
