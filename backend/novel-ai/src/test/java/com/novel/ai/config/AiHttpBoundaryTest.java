package com.novel.ai.config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.security.*;
import com.novel.common.security.filter.HeaderAuthenticationFilter;
import com.novel.modules.ai.controller.AiController;
import com.novel.modules.ai.service.*;
import com.novel.modules.ai.vo.RenderedPromptVO;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.Filter;
import java.util.concurrent.atomic.AtomicReference;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiHttpBoundaryTest {
 private AnnotationConfigWebApplicationContext context;
 private MockMvc mvc;
 @BeforeEach void setup(){
  context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(Fixture.class,AiSecurityConfig.class);context.refresh();
  mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
 }
 @AfterEach void close(){context.close();UserContext.clear();}
 private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder identity(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r,String role){
  return r.header("X-Internal-Token","test-internal").header("X-User-Id","99").header("X-Jti","test-session").header("X-Username","tester").header("X-Roles",role);
 }
 @Test void forgedIdentityWithoutInternalTokenIsRejected() throws Exception {
  mvc.perform(get("/api/ai/quota").header("X-User-Id","99").header("X-Jti","test-session").header("X-Roles","ADMIN")).andExpect(status().isUnauthorized());
 }
 @Test void readerCannotAccessAuthorAi() throws Exception {mvc.perform(identity(get("/api/ai/quota"),"READER")).andExpect(status().isForbidden());}
 @Test void readerCanAccessOnlyDedicatedBookFinder() throws Exception {
  mvc.perform(identity(get("/api/ai/book-finder/test"),"READER")).andExpect(status().isOk());
  mvc.perform(get("/api/ai/book-finder/test")).andExpect(status().isUnauthorized());
 }
 @Test void authorCannotAccessAdminConfiguration() throws Exception {mvc.perform(identity(get("/api/admin/ai/config"),"AUTHOR")).andExpect(status().isForbidden());}
 @Test void knowledgeManagementIsAdminOnly() throws Exception {
  mvc.perform(get("/api/admin/ai/book-finder/status")).andExpect(status().isUnauthorized());
  mvc.perform(identity(get("/api/admin/ai/book-finder/status"),"READER")).andExpect(status().isForbidden());
  mvc.perform(identity(get("/api/admin/ai/book-finder/status"),"AUTHOR")).andExpect(status().isForbidden());
  mvc.perform(identity(get("/api/admin/ai/book-finder/status"),"ADMIN")).andExpect(status().isOk());
 }
 @Test void authorReceivesSseCompletionAndWorkerIdentity() throws Exception {
  MvcResult first=mvc.perform(identity(post("/api/ai/write"),"AUTHOR").contentType("application/json").accept("text/event-stream, application/json;q=0.9").content("{\"prompt\":\"continue\"}")).andExpect(request().asyncStarted()).andReturn();
  first.getAsyncResult(5000);
  MvcResult result=mvc.perform(asyncDispatch(first)).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/event-stream")).andReturn();
  assertTrue(result.getResponse().getContentAsString().contains("[DONE]"));
  assertEquals(99L,context.getBean(Fixture.class).workerUser.get());
 }
 @Configuration @EnableWebMvc @EnableWebSecurity
 static class Fixture {
  final AtomicReference<Long> workerUser=new AtomicReference<>();
  private static <T> T mock(Class<T> c){return org.mockito.Mockito.mock(c,withSettings().mockMaker("mock-maker-subclass"));}
  @Bean ObjectMapper objectMapper(){return new ObjectMapper();}
  @Bean RestAuthenticationEntryPoint entry(){return new RestAuthenticationEntryPoint(objectMapper());}
  @Bean RestAccessDeniedHandler denied(){return new RestAccessDeniedHandler(objectMapper());}
  @Bean HeaderAuthenticationFilter header(){return new HeaderAuthenticationFilter(objectMapper(),"test-internal");}
  @Bean BookFinderProbe bookFinderProbe(){return new BookFinderProbe();}
  @Bean(destroyMethod="shutdown") ThreadPoolTaskExecutor executor(){ThreadPoolTaskExecutor e=new ThreadPoolTaskExecutor();e.setCorePoolSize(1);e.setMaxPoolSize(1);e.initialize();return e;}
  @Bean AiController controller(){
   AiPreflight preflight=mock(AiPreflight.class);RenderedPromptVO prompt=new RenderedPromptVO();prompt.setSystemPrompt("test");prompt.setUserMessage("test");when(preflight.check(any(),any())).thenReturn(prompt);
   AiConfigService config=mock(AiConfigService.class);when(config.dailyLimit()).thenReturn(50);
   AiStreamSource stream=(scene,req,handler)->{workerUser.set(UserContext.getUserId());handler.onChunk("test");handler.onComplete();};
   return new AiController(stream,executor(),config,mock(AiQuotaService.class),preflight,mock(AiSummaryService.class),"mock");
  }
 }
 @org.springframework.web.bind.annotation.RestController
 static class BookFinderProbe {
  @org.springframework.web.bind.annotation.GetMapping({"/api/ai/book-finder/test","/api/admin/ai/book-finder/status"})
  public String test(){return "ok";}
 }
}
