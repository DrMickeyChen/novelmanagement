package com.novel.modules.agent.writing;

import com.novel.modules.agent.writing.application.WritingService;
import com.novel.modules.agent.writing.context.WritingRetrieval;
import com.novel.modules.agent.writing.infrastructure.model.WritingModel;
import com.novel.modules.agent.writing.infrastructure.persistence.WritingStore;
import com.novel.modules.agent.writing.infrastructure.client.WritingContentClient;
import com.novel.modules.agent.writing.infrastructure.job.WritingIndexJobs;
import com.novel.modules.agent.writing.context.WritingContext;
import com.novel.modules.agent.writing.application.WritingBudgetFactory;
import com.novel.modules.agent.writing.infrastructure.job.WritingTaskWorker;
import com.novel.modules.agent.writing.domain.WritingLimits;
import com.novel.modules.agent.writing.skill.WritingSkillRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.result.Result;
import com.novel.common.security.*;
import com.novel.modules.ai.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses a UUID-named scratch schema, never mutates the novel database. */
@EnabledIfSystemProperty(named="writing.mysql",matches="true")
class WritingDatabaseTest {
    static String schema,url,user,password;static JdbcTemplate jdbc;static DriverManagerDataSource ds;
    ObjectMapper json=new ObjectMapper();WritingStore store;WritingService service;WritingTaskWorker worker;WritingContentClient client;
    Snapshot snapshot=new Snapshot("10","1",2,"第二章",0,"少年停在门前。","20","第二章\n进入宗门，谨慎询问，结尾见到师兄。","hash",List.of(new Source("chapter:9","9",1,"第一章",0,0,9,false,"他没有掌握法术。")));
    @BeforeAll static void database() throws Exception {
        schema="writing_test_"+UUID.randomUUID().toString().replace("-","");
        url=System.getProperty("writing.test.url","jdbc:mysql://192.168.127.137:3306/?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000");
        user=System.getProperty("writing.test.user","root");password=System.getenv("WRITING_TEST_DB_PASSWORD");
        if(password==null) throw new IllegalStateException("Set WRITING_TEST_DB_PASSWORD for isolated MySQL tests");
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        ds=new DriverManagerDataSource(url.replace("3306/","3306/"+schema),user,password);jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE chapter(id BIGINT PRIMARY KEY)");
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("../../docs/sql/writing-agent-mvp.sql"));ScriptUtils.executeSqlScript(c,new FileSystemResource("../../docs/sql/writing-story-library.sql"));}
    }
    @AfterAll static void drop() throws Exception {
        if(schema!=null && schema.matches("writing_test_[a-f0-9]{32}"))
            try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("DROP DATABASE "+schema);}
    }
    @BeforeEach void setup() {
        for(String table:List.of("writing_event","writing_attempt","writing_command","writing_task","writing_gate","writing_private_index_job"))jdbc.update("DELETE FROM "+table);
        store=new WritingStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),json);
        client=mock(WritingContentClient.class);when(client.snapshot(10L,20L)).thenAnswer(invocation->{
            var headers=new HashMap<String,String>();IdentityHeaders.write(headers,UserContext.get());
            assertNotNull(IdentityHeaders.read(headers::get),"Worker identity must survive the actual service header codec");
            return Result.success(snapshot);
        });
        when(client.access(10L,20L)).thenReturn(Result.success(true));
        when(client.context(eq(10L),any())).thenAnswer(invocation->client.snapshot(10L,20L));
        var model=new WritingModel(new com.novel.modules.agent.infrastructure.model.LangChain4jModelFactory(mock(AiConfigService.class)),json);
        ReflectionTestUtils.setField(model,"provider","mock");
        var skills=new WritingSkillRegistry();var limits=WritingLimits.defaults();var contexts=new WritingContext(client,json,skills);
        var usage=mock(AiUsageRecorder.class);var budgets=new WritingBudgetFactory(store,contexts,model,json,usage,limits);
        service=new WritingService(store,client,model,json,mock(AiConfigService.class),mock(AiQuotaService.class),contexts,budgets,skills,limits);
        worker=new WritingTaskWorker(store,service,contexts);
        login(1);
    }
    void login(long id){var u=new LoginUser();u.setUserId(id);u.setJti("fixture-session");u.setRoles(List.of("AUTHOR"));UserContext.set(u);}
    @AfterEach void cleanup(){if(worker!=null)worker.close();UserContext.clear();}
    @Test void runningTaskToolsChangeWithoutRestartAndWorkerWritesCannotRestoreOldPermissions(){
        var mcpJdbc=mock(JdbcTemplate.class);var row=new HashMap<String,Object>();
        row.put("enabled",true);row.put("version",1);row.put("catalog_hash","h");row.put("grants","[\"search\",\"extract\"]");row.put("catalog","[{\"name\":\"search\",\"inputSchema\":{\"type\":\"object\"}},{\"name\":\"extract\",\"inputSchema\":{\"type\":\"object\"}}]");
        when(mcpJdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row));
        var mcp=new com.novel.modules.agent.writing.application.WritingMcp(mcpJdbc,json,mock(com.novel.modules.agent.writing.application.WritingAssetService.class),new AiCrypto("test-secret"),mock(com.novel.modules.agent.writing.infrastructure.client.WritingMcpTransport.class));
        ReflectionTestUtils.setField(service,"mcp",mcp);var first=service.create(request(),"tools-live");String id=first.path("id").asText();
        var claimed=store.claim("fixture");assertEquals("PLANNING",claimed.path("status").asText());
        var req=json.createObjectNode().put("expectedToolsVersion",0).put("allowExternalQuery",true);req.putArray("mcpToolSelections").addObject().put("connectionId","conn").putArray("tools").add("search");
        var enabled=service.updateTools(id,req);assertEquals("PLANNING",enabled.path("status").asText());assertEquals(claimed.path("leaseEpoch"),enabled.path("leaseEpoch"));assertEquals(1,enabled.path("toolsVersion").asInt());
        assertThrows(BusinessException.class,()->service.updateTools(id,req));
        var permissions=new WritingBudgetFactory(store,new WritingContext(client,json,new WritingSkillRegistry()),new WritingModel(new com.novel.modules.agent.infrastructure.model.LangChain4jModelFactory(mock(AiConfigService.class)),json),json,mock(AiUsageRecorder.class),WritingLimits.defaults())
                .create(id,1,claimed.path("leaseEpoch").asLong(),null,System.nanoTime());
        assertEquals(1,permissions.externalPermissions(first.path("input")).path("mcpSnapshots").size());
        var off=json.createObjectNode().put("expectedToolsVersion",1).put("allowExternalQuery",false);off.putArray("mcpToolSelections");service.updateTools(id,off);
        store.mutate(id,1,claimed.path("leaseEpoch").asLong(),"state",state->state.put("modelCalls",1));
        var latest=service.get(id);assertEquals("PLANNING",latest.path("status").asText());assertEquals(2,latest.path("toolsVersion").asInt());assertTrue(latest.path("input").path("mcpSnapshots").isEmpty());
        assertTrue(permissions.externalPermissions(enabled.path("input")).path("mcpSnapshots").isEmpty());
        req.put("expectedToolsVersion",2);service.updateTools(id,req);assertEquals(1,permissions.externalPermissions(first.path("input")).path("mcpSnapshots").size());
        assertTrue(store.events(id,0).stream().anyMatch(event->event.toString().contains("tools_changed")));
        login(2);assertThrows(BusinessException.class,()->service.updateTools(id,req));
    }
    @Test void assistantInstructionsAreServerFrozenAndNewSettingsOnlyAffectNewTasks(){
        var settings=mock(com.novel.modules.agent.writing.application.WritingAssistantSettings.class);
        var saved=json.createObjectNode().put("scope","PERSONAL").put("version",1).put("instructions","优先保留原句。").put("hash","first");
        when(settings.snapshot(1)).thenReturn(saved);
        ReflectionTestUtils.setField(service,"assistantSettings",settings);
        var req=request();req.putObject("assistantInstructions").put("instructions","伪造系统快照");
        var first=service.create(req,"assistant-first");
        assertEquals("优先保留原句。",first.path("assistantInstructions").path("instructions").asText());
        assertFalse(first.path("input").has("assistantInstructions"));
        assertTrue(new WritingContext(client,json,new WritingSkillRegistry()).compile(first).contains("优先保留原句。"));
        when(settings.snapshot(1)).thenReturn(saved.deepCopy().put("instructions","新偏好。").put("version",2));
        var repeated=service.create(req,"assistant-first");assertEquals(first.path("id"),repeated.path("id"));
        assertEquals("优先保留原句。",repeated.path("assistantInstructions").path("instructions").asText());
        var next=service.create(req,"assistant-next");assertEquals("新偏好。",next.path("assistantInstructions").path("instructions").asText());
        assertEquals("优先保留原句。",service.get(first.path("id").asText()).path("assistantInstructions").path("instructions").asText());
    }
    @Test void taskSkillsAreServerFrozenAndIdempotentWithoutMutatingCaller(){
        var req=request();req.putArray("skillIds").add("restrained");
        req.putArray("skillSnapshots").addObject().put("id","restrained").put("instructions","untrusted-client-rule");
        var task=service.create(req,"skill-freeze");var selected=task.path("input").path("skillSnapshots").get(0);
        assertEquals("克制叙述，少解释，用动作表现情绪。",selected.path("instructions").asText());
        assertEquals(64,selected.path("hash").asText().length());
        assertEquals("untrusted-client-rule",req.path("skillSnapshots").get(0).path("instructions").asText());
        assertEquals(task.path("id"),service.create(req,"skill-freeze").path("id"));
    }
    ObjectNode request(){var r=json.createObjectNode().put("chapterId","10").put("nodeId","20").put("expectedChapterVersion",0).put("goal","进入宗门并留下悬念");r.putObject("selection").put("start",0).put("end",0).put("original","");r.putObject("profile").put("style","克制");r.putArray("skillIds").add("restrained");return r;}
    ObjectNode await(String id,String status) throws Exception {for(int i=0;i<100;i++){var t=store.load(id,1);if(t.path("status").asText().equals(status))return t;if(t.path("status").asText().equals("FAILED"))fail(t.path("error").asText());Thread.sleep(50);}throw new AssertionError("Task did not reach "+status);}
    @Test void completePlanDraftReviewAdoptUndoAndReplay() throws Exception {
        var t=service.create(request(),"create");String id=t.path("id").asText();
        assertEquals(id,service.create(request(),"create").path("id").asText());
        worker.dispatch();t=await(id,"AWAITING_CONFIRM");
        var confirm=json.createObjectNode().put("expectedTaskVersion",t.path("version").asLong()).put("planHash",t.path("planHash").asText());
        service.command(id,"CONFIRM",confirm,"confirm");worker.dispatch();t=await(id,"READY");
        assertTrue(t.path("draft").asText().contains("演示"));assertEquals(4,t.path("modelCalls").asInt());
        var events=store.events(id,0);assertFalse(events.isEmpty());
        long seq=0;for(var e:events){assertEquals(++seq,e.path("seq").asLong());assertEquals(e.path("seq"),e.path("payload").path("throughSeq"));}
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM writing_attempt WHERE status='SUCCEEDED'",Integer.class));
        when(client.result(10L,id,"ADOPT")).thenReturn(Result.success(null));
        when(client.patch(eq(10L),any())).thenReturn(Result.success(new Applied("a","10",1,"新正文","第二章")));
        var adoption=json.createObjectNode().put("expectedTaskVersion",t.path("version").asLong()).put("expectedChapterVersion",0).put("draftAttemptId",t.path("currentDraftAttemptId").asText()).put("draftStart",0).put("draftEnd",t.path("draft").asText().length()).put("acceptUnchecked",true);
        var applied=service.command(id,"ADOPT",adoption,"adopt");assertEquals("ADOPTED",applied.path("status").asText());
        service.command(id,"ADOPT",adoption,"adopt");verify(client,times(1)).patch(eq(10L),any());
        when(client.result(10L,id,"UNDO")).thenReturn(Result.success(null));when(client.undo(eq(10L),any())).thenReturn(Result.success(new Applied("u","10",2,snapshot.content(),"第二章")));
        var undone=service.command(id,"UNDO",json.createObjectNode().put("expectedTaskVersion",applied.path("version").asLong()).put("expectedChapterVersion",1).put("adoptionId","a"),"undo");
        assertEquals("UNDONE",undone.path("status").asText());
    }
    @Test void separateSessionsKeepTheirPlansAndHistoryMetadata() throws Exception {
        var first=service.create(request(),"session-one");String firstId=first.path("id").asText();
        worker.dispatch();first=await(firstId,"AWAITING_CONFIRM");
        var second=service.create(request().put("goal","师兄透露线索，结尾进入密室"),"session-two");
        String secondId=second.path("id").asText();assertNotEquals(firstId,secondId);
        worker.dispatch();await(secondId,"AWAITING_CONFIRM");
        assertEquals("AWAITING_CONFIRM",service.get(firstId).path("status").asText());
        assertEquals(first.path("planHash"),service.get(firstId).path("planHash"));
        var records=service.recent(10L,20L);assertEquals(2,records.size());
        var old=records.stream().filter(row->row.get("id").equals(firstId)).findFirst().orElseThrow();
        assertEquals("进入宗门并留下悬念",old.get("goal"));
        assertNotNull(java.time.Instant.parse(old.get("createdAt").toString()));
        assertNotNull(java.time.Instant.parse(old.get("updatedAt").toString()));
        login(2);assertTrue(service.recent(10L,20L).isEmpty());
        assertEquals(404,assertThrows(BusinessException.class,()->service.get(firstId)).getCode());
    }
    @Test void privateIndexJobsPersistProgressAndCannotBeReadByAnotherAuthor()throws Exception {
        when(client.chapters(10L,20L)).thenReturn(Result.success(List.of(Map.of("id",9L,"version",0))));
        var retrieval=mock(WritingRetrieval.class);
        when(retrieval.build(10,20,List.of(9L),false)).thenAnswer(i->{assertEquals(1L,UserContext.requireUserId());assertNotNull(UserContext.get().getJti());return Map.of("chunks",2,"notice","fixture");});
        var jobs=new WritingIndexJobs(jdbc,new DataSourceTransactionManager(ds),retrieval,client,json);
        try {
            var req=json.createObjectNode().put("nodeId",20);req.putArray("chapterIds").add(9);var job=jobs.submit(10,req);String id=job.get("id").toString();
            assertEquals("QUEUED",job.get("state"));login(2);assertThrows(BusinessException.class,()->jobs.get(id));login(1);
            jobs.work();for(int i=0;i<100&&!jobs.get(id).get("state").equals("DONE");i++)Thread.sleep(30);
            assertEquals("DONE",jobs.get(id).get("state"));assertTrue(jobs.get(id).get("result").toString().contains("chunks"));verify(retrieval,times(1)).build(10,20,List.of(9L),false);
        } finally { jobs.close(); }
    }
    @Test void legacyTaskFingerprintsRemainCompatibleAfterContextUpgrade()throws Exception {
        var task=service.create(request(),"legacy");String id=task.path("id").asText();worker.dispatch();task=await(id,"AWAITING_CONFIRM");
        var old=(ObjectNode)json.valueToTree(snapshot);old.remove(List.of("notes","settings","coverage","catalogHash"));
        store.mutate(id,1,null,"legacy-fixture",t->{((ObjectNode)t.get("input")).remove("contextVersion");t.put("sourceHash",com.novel.common.internal.WritingText.hash(old.toString()));});
        task=store.load(id,1);var confirm=json.createObjectNode().put("expectedTaskVersion",task.path("version").asLong()).put("planHash",task.path("planHash").asText());
        assertEquals("QUEUED_DRAFT",service.command(id,"CONFIRM",confirm,"legacy-confirm").path("status").asText());
    }

    @Test void skillsCanBeChangedAndRemovedBeforeNextGeneration() throws Exception {
        var malformed=request();malformed.put("skillIds","dialogue");
        assertEquals(422,assertThrows(BusinessException.class,()->service.create(malformed,"invalid-create")).getHttpStatus());
        var t=service.create(request(),"skills-create");String id=t.path("id").asText();
        worker.dispatch();t=await(id,"AWAITING_CONFIRM");
        var confirm=json.createObjectNode().put("expectedTaskVersion",t.path("version").asLong()).put("planHash",t.path("planHash").asText());
        confirm.putArray("skillIds").add("dialogue");
        var queued=service.command(id,"CONFIRM",confirm,"skills-confirm");
        assertEquals(List.of("dialogue"),json.convertValue(queued.path("input").path("skillIds"),List.class));
        worker.dispatch();t=await(id,"READY");
        long version=t.path("version").asLong();
        var revise=json.createObjectNode().put("expectedTaskVersion",version).put("draftAttemptId",t.path("currentDraftAttemptId").asText()).put("instruction","保留人物动作，减少对话");
        revise.putArray("skillIds").add("unknown");
        assertEquals(422,assertThrows(BusinessException.class,()->service.command(id,"REVISE",revise,"invalid-skill")).getHttpStatus());
        assertEquals(version,store.load(id,1).path("version").asLong());
        assertEquals("dialogue",store.load(id,1).path("input").path("skillIds").get(0).asText());
        revise.putArray("skillIds");
        var cleared=service.command(id,"REVISE",revise,"clear-skills");
        assertEquals("QUEUED_DRAFT",cleared.path("status").asText());
        assertTrue(cleared.path("input").path("skillIds").isArray());
        assertEquals(0,cleared.path("input").path("skillIds").size());
    }

    @Test void crossOwnerAndChangedSourcesFailBeforeGeneration() throws Exception {
        var t=service.create(request(),"create");String id=t.path("id").asText();login(2);assertEquals(404,assertThrows(BusinessException.class,()->service.get(id)).getCode());login(1);
        when(client.snapshot(10L,20L)).thenReturn(Result.success(new Snapshot("10","1",2,"第二章",1,snapshot.content(),"20",snapshot.outline(),"hash",snapshot.sources())));
        worker.dispatch();for(int i=0;i<50&&store.load(id,1).path("status").asText().equals("PLANNING");i++)Thread.sleep(50);
        assertEquals("INPUT_STALE",store.load(id,1).path("status").asText());assertEquals(0,store.load(id,1).path("modelCalls").asInt());
    }
    @Test void commandsAndLateWorkerWritesHaveStrongGuards() {
        var t=service.create(request(),"create");String id=t.path("id").asText();var claimed=store.claim("worker");long epoch=claimed.path("leaseEpoch").asLong();
        service.command(id,"CANCEL",json.createObjectNode().put("expectedTaskVersion",claimed.path("version").asLong()),"cancel");
        assertThrows(BusinessException.class,()->store.mutate(id,1,epoch,"state",s->s.put("draft","late")));
        assertEquals("CANCELLED",store.load(id,1).path("status").asText());
        var altered=request().put("goal","另一个目标");assertThrows(BusinessException.class,()->service.create(altered,"create"));
    }
    @Test void emptyOutlineAllowsHistoryButRejectsCreationAs422() {
        when(client.snapshot(10L,20L)).thenThrow(new BusinessException(422,"请填写当前章节剧情笔记",422));
        assertTrue(service.recent(10L,20L).isEmpty());
        verify(client,never()).snapshot(anyLong(),anyLong());
        var failure=assertThrows(BusinessException.class,()->service.create(request(),"create"));
        assertEquals(422,failure.getHttpStatus());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM writing_task",Integer.class));
    }
    @Test void existingTaskCanBeViewedAfterOutlineCleared() {
        var t=service.create(request(),"create");
        when(client.snapshot(10L,20L)).thenThrow(new BusinessException(422,"请填写当前章节剧情笔记",422));
        assertEquals(t.path("id"),service.get(t.path("id").asText()).path("id"));
    }
    @Test void openingChapterSkipsModelRecapAndStillPlans() throws Exception {
        snapshot=new Snapshot("10","1",1,"第一章",0,"","20",snapshot.outline(),"hash",List.of());
        var req=request();req.putObject("selection").put("start",0).put("end",0).put("original","");
        var task=service.create(req,"opening");String id=task.path("id").asText();
        worker.dispatch();var planned=await(id,"AWAITING_CONFIRM");
        assertEquals(1,planned.path("modelCalls").asInt());
        assertEquals("system",planned.path("recap").path("generatedBy").asText());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM writing_attempt WHERE task_id=? AND phase='RECAP'",Integer.class,id));
    }
    @Test void invalidPlanIsRepairedOnceAndOriginalOutputRemains() throws Exception {
        var fake=new WritingModel(new com.novel.modules.agent.infrastructure.model.LangChain4jModelFactory(mock(AiConfigService.class)),json) {
            @Override public String structured(String phase,String prompt,Snapshot snap,com.fasterxml.jackson.databind.JsonNode input,AiRuntimeConfig cfg,Budget budget) {
                budget.before(prompt,2048);
                budget.after(dev.langchain4j.model.chat.response.ChatResponse.builder().aiMessage(dev.langchain4j.data.message.AiMessage.from("fixture")).build(),null);
                return switch(phase) {
                    case "RECAP" -> "{\"summary\":\"前文回顾\",\"sourceIds\":[]}";
                    case "PLAN" -> "这一章先相遇，再留下悬念。";
                    case "REPAIR" -> "{\"summary\":\"相遇\",\"events\":[\"相遇\"],\"ending\":\"悬念\",\"needsInput\":false}";
                    default -> "{\"issues\":[]}";
                };
            }
        };
        ReflectionTestUtils.setField(fake,"provider","mock");ReflectionTestUtils.setField(service,"model",fake);
        var task=service.create(request(),"repair");String id=task.path("id").asText();worker.dispatch();var planned=await(id,"AWAITING_CONFIRM");
        assertEquals(1,planned.path("formatRepairs").asInt());assertEquals(3,planned.path("modelCalls").asInt());
        assertEquals("这一章先相遇，再留下悬念。",jdbc.queryForObject("SELECT output_text FROM writing_attempt WHERE task_id=? AND phase='PLAN'",String.class,id));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM writing_attempt WHERE task_id=? AND phase='PLAN'",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM writing_attempt WHERE task_id=? AND phase='REPAIR'",Integer.class,id));
    }
    @Test void failedRepairDoesNotLoopAndManualRetryKeepsRepairLimit() throws Exception {
        var fake=new WritingModel(new com.novel.modules.agent.infrastructure.model.LangChain4jModelFactory(mock(AiConfigService.class)),json) {
            @Override public String structured(String phase,String prompt,Snapshot snap,com.fasterxml.jackson.databind.JsonNode input,AiRuntimeConfig cfg,Budget budget) {
                budget.before(prompt,2048);budget.after(null,null);
                return phase.equals("RECAP")?"{\"summary\":\"前文\",\"sourceIds\":[]}":"仍然不是 JSON";
            }
        };
        ReflectionTestUtils.setField(fake,"provider","mock");ReflectionTestUtils.setField(service,"model",fake);
        var task=service.create(request(),"bad-format");String id=task.path("id").asText();worker.dispatch();var failed=await(id,"FAILED");
        assertEquals(3,failed.path("modelCalls").asInt());
        assertEquals("仍然不是 JSON",jdbc.queryForObject("SELECT output_text FROM writing_attempt WHERE task_id=? AND phase='REPAIR'",String.class,id));
        service.command(id,"RETRY",json.createObjectNode().put("expectedTaskVersion",failed.path("version").asLong()).put("failedAttemptId",failed.path("activeAttemptId").asText()),"manual");
        worker.dispatch();var retried=await(id,"FAILED");assertEquals(4,retried.path("modelCalls").asInt());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM writing_attempt WHERE task_id=? AND phase='REPAIR'",Integer.class,id));
        assertTrue(jdbc.queryForList("SELECT output_text FROM writing_attempt WHERE task_id=? AND phase='PLAN'",id).stream().allMatch(row->row.get("output_text").equals("仍然不是 JSON")));
    }
    @Test void onlyOneWorkerCanClaimAndEventsArePersistent() throws Exception {
        service.create(request(),"create");var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->store.claim("a"));var b=pool.submit(()->store.claim("b"));assertEquals(1,(a.get()!=null?1:0)+(b.get()!=null?1:0));}finally{pool.shutdownNow();}
        var restored=new WritingStore(jdbc,new TransactionTemplate(new DataSourceTransactionManager(ds)),json);
        String id=jdbc.queryForObject("SELECT id FROM writing_task",String.class);
        assertEquals(store.load(id,1),restored.load(id,1));assertEquals(store.events(id,0),restored.events(id,0));
    }
}
