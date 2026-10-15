package com.novel.ai.config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.common.security.*;
import com.novel.common.security.filter.HeaderAuthenticationFilter;
import com.novel.modules.ai.controller.AiSummaryController;
import com.novel.modules.ai.service.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.Filter;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
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
  mvc.perform(post("/api/ai/summary").header("X-User-Id","99").header("X-Jti","test-session").header("X-Roles","ADMIN")).andExpect(status().isUnauthorized());
 }
 @Test void readerCannotAccessAuthorAi() throws Exception {mvc.perform(identity(post("/api/ai/summary"),"READER")).andExpect(status().isForbidden());}
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
 @Test void retiredGenerationRoutesAreAbsentAndNeverInvokeModels() throws Exception {
  for(String role:new String[]{"AUTHOR","ADMIN"}) {
   for(String route:new String[]{"outline","write","inspire","polish"}) {
    mvc.perform(identity(post("/api/ai/"+route),role).contentType("application/json").content("{\"prompt\":\"continue\"}")).andExpect(status().isNotFound());
   }
   mvc.perform(identity(get("/api/ai/quota"),role)).andExpect(status().isNotFound());
  }
  verifyNoInteractions(context.getBean(AiSummaryService.class));
 }
 @Test void summaryRouteRemainsAvailable() throws Exception {
  mvc.perform(identity(post("/api/ai/summary"),"AUTHOR").contentType("application/json").content("{\"novelId\":1,\"endChapterNo\":1}")).andExpect(status().isOk());
  verify(context.getBean(AiSummaryService.class)).generate(any());
 }
 @Configuration @EnableWebMvc @EnableWebSecurity
 static class Fixture {
  private static <T> T mock(Class<T> c){return org.mockito.Mockito.mock(c,withSettings().mockMaker("mock-maker-subclass"));}
  @Bean ObjectMapper objectMapper(){return new ObjectMapper();}
  @Bean RestAuthenticationEntryPoint entry(){return new RestAuthenticationEntryPoint(objectMapper());}
  @Bean RestAccessDeniedHandler denied(){return new RestAccessDeniedHandler(objectMapper());}
  @Bean HeaderAuthenticationFilter header(){return new HeaderAuthenticationFilter(objectMapper(),"test-internal");}
  @Bean BookFinderProbe bookFinderProbe(){return new BookFinderProbe();}
  @Bean AiSummaryService summaryService(){return mock(AiSummaryService.class);}
  @Bean AiSummaryController controller(){return new AiSummaryController(summaryService());}
 }
 @org.springframework.web.bind.annotation.RestController
 static class BookFinderProbe {
  @org.springframework.web.bind.annotation.GetMapping({"/api/ai/book-finder/test","/api/admin/ai/book-finder/status"})
  public String test(){return "ok";}
 }
}
