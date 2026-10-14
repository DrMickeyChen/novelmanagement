package com.novel.modules.agent.bookfinder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.ai.config.AiSecurityConfig;
import com.novel.common.security.*;
import com.novel.common.security.filter.HeaderAuthenticationFilter;
import com.novel.modules.agent.bookfinder.BookFinderTypes.*;
import com.novel.modules.agent.bookfinder.controller.BookFinderController;
import com.novel.modules.agent.bookfinder.conversation.BookFinderStore;
import com.novel.modules.agent.bookfinder.service.BookFinderAgent;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.Filter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookFinderControllerHttpTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    @BeforeEach void setup() {
        context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());
        context.register(Fixture.class,AiSecurityConfig.class);context.refresh();
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
    }
    @AfterEach void close() { context.close();UserContext.clear(); }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder reader(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
        return request.header("X-Internal-Token","test-internal").header("X-User-Id","99").header("X-Jti","test-session").header("X-Roles","READER");
    }
    @Test void readerGetsRealSseEventsAndWorkerRetainsIdentity() throws Exception {
        String payload="{\"message\":\"找书\",\"requestId\":\""+UUID.randomUUID()+"\"}";
        var first=mvc.perform(reader(post("/api/ai/book-finder/chat")).contentType("application/json")
                .accept("text/event-stream, application/json;q=0.9").content(payload)).andExpect(request().asyncStarted()).andReturn();
        first.getAsyncResult(5000);
        var result=mvc.perform(asyncDispatch(first)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/event-stream")).andReturn();
        String body=result.getResponse().getContentAsString();
        for(String event:List.of("session","progress","text","result","done")) assertTrue(body.contains("event:"+event));
        assertEquals(99L,Fixture.workerUser.get());
        verify(context.getBean(BookFinderAgent.class),timeout(2000)).release(any());
    }
    @Test void anonymousCannotSpendAndInvalidRequestsNeverReachAgent() throws Exception {
        mvc.perform(post("/api/ai/book-finder/chat").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(reader(post("/api/ai/book-finder/chat")).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        verify(context.getBean(BookFinderAgent.class),never()).prepare(anyLong(),any());
    }
    @Test void adminQuotaReportsUnlimitedWithoutReadingDailyCounter() throws Exception {
        mvc.perform(get("/api/ai/book-finder/quota").header("X-Internal-Token","test-internal")
                .header("X-User-Id","99").header("X-Jti","test-session").header("X-Roles","ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.unlimited").value(true))
                .andExpect(jsonPath("$.data.remaining").doesNotExist());
        verify(context.getBean(BookFinderStore.class),never()).quota(anyLong(),anyInt());
    }
    @Test void consecutiveRequestsEachCompleteAndReleaseTheirWorker() throws Exception {
        for(int i=0;i<12;i++) {
            var first=mvc.perform(reader(post("/api/ai/book-finder/chat"))
                    .contentType("application/json").accept("text/event-stream, application/json;q=0.9")
                    .content("{\"message\":\"找书\",\"requestId\":\""+UUID.randomUUID()+"\"}"))
                    .andExpect(request().asyncStarted()).andReturn();
            first.getAsyncResult(5000);
            mvc.perform(asyncDispatch(first)).andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("event:done")));
        }
        verify(context.getBean(BookFinderAgent.class),timeout(2000).times(12)).release(any());
    }
    @Configuration @EnableWebMvc @EnableWebSecurity
    static class Fixture {
        static AtomicReference<Long> workerUser=new AtomicReference<>();
        @Bean ObjectMapper json(){return new ObjectMapper();}
        @Bean RestAuthenticationEntryPoint entry(){return new RestAuthenticationEntryPoint(json());}
        @Bean RestAccessDeniedHandler denied(){return new RestAccessDeniedHandler(json());}
        @Bean HeaderAuthenticationFilter filter(){return new HeaderAuthenticationFilter(json(),"test-internal");}
        @Bean BookFinderStore store(){return mock(BookFinderStore.class,withSettings().mockMaker("mock-maker-subclass"));}
        @Bean BookFinderAgent agent() throws Exception {
            var agent=mock(BookFinderAgent.class,withSettings().mockMaker("mock-maker-subclass"));
            when(agent.prepare(anyLong(),any())).thenAnswer(invocation->{
                var state=new Conversation();state.setId(UUID.randomUUID().toString());
                return new BookFinderAgent.Ticket(invocation.getArgument(0),invocation.getArgument(1),state,null,"fp",null,System.currentTimeMillis());
            });
            when(agent.run(any(),any())).thenAnswer(invocation->{
                workerUser.set(UserContext.getUserId());
                Consumer<String> progress=invocation.getArgument(1);progress.accept("正在搜索");
                BookFinderAgent.Ticket t=invocation.getArgument(0);
                return new Answer(t.state().getId(),t.request().requestId(),"测试结果",t.state().getConditions(),List.of(),false,true,new Usage(0,0,0,1));
            });
            return agent;
        }
        @Bean BookFinderController controller() throws Exception {return new BookFinderController(agent(),store());}
    }
}
