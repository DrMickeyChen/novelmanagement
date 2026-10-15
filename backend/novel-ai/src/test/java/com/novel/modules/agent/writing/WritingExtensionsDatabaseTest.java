package com.novel.modules.agent.writing;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.internal.*;
import com.novel.common.security.*;
import com.novel.common.exception.BusinessException;
import com.novel.modules.agent.writing.application.*;
import com.novel.modules.agent.writing.context.WritingExtendedContext;
import com.novel.modules.agent.writing.skill.*;
import com.novel.modules.agent.writing.infrastructure.client.*;
import com.novel.modules.agent.writing.infrastructure.model.WritingDerivedModel;
import com.novel.modules.agent.bookfinder.knowledge.RetrievalModels;
import com.novel.modules.ai.service.*;
import com.novel.common.result.Result;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="writing.mysql",matches="true")
class WritingExtensionsDatabaseTest {
    static String schema,url,password;static DriverManagerDataSource ds;static JdbcTemplate jdbc;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();WritingSyncClient content;WritingAssetService assets;WritingDeletion deletion;TransactionTemplate tx;
    @org.junit.jupiter.api.io.TempDir Path journalDirectory;
    @BeforeAll static void db()throws Exception{
        schema="writing_test_"+UUID.randomUUID().toString().replace("-","");url="jdbc:mysql://192.168.127.137:3306/?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000";password=System.getenv("WRITING_TEST_DB_PASSWORD");if(password==null)throw new IllegalStateException("WRITING_TEST_DB_PASSWORD required");
        try(var c=DriverManager.getConnection(url,"root",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        ds=new DriverManagerDataSource(url.replace("3306/","3306/"+schema),"root",password);jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE novel(id BIGINT PRIMARY KEY,author_id BIGINT,deleted INT DEFAULT 0)");jdbc.execute("CREATE TABLE chapter(id BIGINT PRIMARY KEY,novel_id BIGINT,chapter_no INT,title VARCHAR(100),status INT,deleted INT DEFAULT 0)");jdbc.execute("CREATE TABLE chapter_content(chapter_id BIGINT PRIMARY KEY,content MEDIUMTEXT)");
        try(var c=ds.getConnection()){for(String file:List.of("writing-agent-mvp.sql","writing-story-library.sql","writing-agent-extensions.sql"))ScriptUtils.executeSqlScript(c,new FileSystemResource("../../docs/sql/"+file));for(String statement:Files.readString(Path.of("../../docs/sql/writing-change-triggers.sql")).split("(?m)^-- @statement\\s*$")){String sql=statement.replaceAll("(?m)^--.*$","").trim();if(!sql.isBlank())try(var s=c.createStatement()){s.execute(sql);}}}
    }
    @AfterAll static void drop()throws Exception{if(schema!=null&&schema.matches("writing_test_[a-f0-9]{32}"))try(var c=DriverManager.getConnection(url,"root",password);var s=c.createStatement()){s.execute("DROP DATABASE "+schema);}}
    void login(long uid){var user=new LoginUser();user.setUserId(uid);user.setRoles(List.of("AUTHOR"));UserContext.set(user);}
    @AfterEach void clear(){UserContext.clear();}
    @BeforeEach void clearIndexReceipts(){jdbc.update("DELETE FROM writing_index_receipt");}
    @BeforeEach void clearAssistantSettings(){jdbc.update("DELETE FROM writing_assistant_settings");}
    @BeforeEach void setup(){
        for(String table:List.of("writing_asset_revision","writing_asset","writing_sync_config","writing_derivation_job","writing_draft_revision","writing_task_dependency","writing_delete_ledger","writing_cumulative_recap","writing_chapter_recap","writing_mcp_connection","writing_mcp_audit","writing_attempt","writing_event","writing_command","writing_task","writing_private_index_job","chapter_content","chapter","novel"))jdbc.update("DELETE FROM "+table);jdbc.update("DELETE FROM writing_source_event");
        jdbc.update("INSERT INTO novel(id,author_id) VALUES(1,1),(2,2)");jdbc.update("INSERT INTO chapter(id,novel_id,chapter_no,title,status) VALUES(10,1,1,'第一章',0),(20,1,2,'第二章',0)");jdbc.update("INSERT INTO chapter_content VALUES(10,'林舟不能御剑。'),(20,'林舟找到玉佩。')");jdbc.update("DELETE FROM writing_source_event");
        content=mock(WritingSyncClient.class);when(content.owned(anyLong())).thenAnswer(i->{long novel=i.getArgument(0);if(novel!=UserContext.requireUserId())throw new BusinessException(403,"拒绝",403);return Result.success(true);});
        assets=new WritingAssetService(jdbc,json,content);deletion=new WritingDeletion(jdbc,json);org.springframework.test.util.ReflectionTestUtils.setField(assets,"deletion",deletion);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));login(1);
    }
    ObjectNode asset(String kind){return json.createObjectNode().put("kind",kind).put("name","测试资料").put("body","林舟不能御剑。").put("state","APPROVED");}
    @Test void assistantSettingsInheritOverrideResetAndRejectStaleOrUnauthorizedSaves(){
        var settings=new WritingAssistantSettings(jdbc,json,assets);
        assertEquals("DEFAULT",settings.snapshot(1).path("scope").asText());
        var personal=json.createObjectNode().put("enabled",true).put("instructions","你擅长克制描写，优先保留原句。").put("expectedVersion",0);
        tx.execute(t->settings.save(0,personal));var frozen=settings.snapshot(1).deepCopy();
        assertEquals("PERSONAL",frozen.path("scope").asText());
        assertEquals(64,frozen.path("hash").asText().length());
        var book=json.createObjectNode().put("enabled",true).put("instructions","本书使用简洁、紧张的悬疑叙述。").put("expectedVersion",0);
        tx.execute(t->settings.save(1,book));assertEquals("BOOK",settings.snapshot(1).path("scope").asText());
        assertThrows(BusinessException.class,()->tx.execute(t->settings.save(1,book)));
        personal.put("expectedVersion",1).put("instructions","个人默认已修改。");tx.execute(t->settings.save(0,personal));
        assertEquals("你擅长克制描写，优先保留原句。",frozen.path("instructions").asText());
        assertEquals(book.path("instructions"),settings.snapshot(1).path("instructions"));
        book.put("enabled",false).put("expectedVersion",1);tx.execute(t->settings.save(1,book));
        assertEquals("个人默认已修改。",settings.snapshot(1).path("instructions").asText());
        personal.put("enabled",false).put("expectedVersion",2);tx.execute(t->settings.save(0,personal));
        assertEquals("DEFAULT",settings.snapshot(1).path("scope").asText());
        assertThrows(BusinessException.class,()->settings.save(0,personal.deepCopy().put("enabled",true).put("instructions"," ")));
        assertThrows(BusinessException.class,()->settings.save(0,personal.deepCopy().put("instructions","字".repeat(6001))));
        login(2);assertEquals("DEFAULT",settings.snapshot(2).path("scope").asText());
        assertThrows(BusinessException.class,()->settings.get(1));assertThrows(BusinessException.class,()->settings.save(1,book));
        var reader=new LoginUser();reader.setUserId(3L);reader.setRoles(List.of("READER"));UserContext.set(reader);
        assertThrows(BusinessException.class,()->settings.get(0));
    }
    @Test void deletingBookErasesItsAssistantSettingsButKeepsPersonalDefault(){
        var settings=new WritingAssistantSettings(jdbc,json,assets);
        var req=json.createObjectNode().put("enabled",true).put("instructions","描写要求").put("expectedVersion",0);
        tx.execute(t->settings.save(0,req));tx.execute(t->settings.save(1,req));
        tx.execute(t->deletion.purge(1,1,null,List.of(),501));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM writing_assistant_settings WHERE novel_id=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM writing_assistant_settings WHERE novel_id=0",Integer.class));
    }
    @Test void independentDeletionJournalContainsNoTextAndDeduplicatesRestoredEventCounters()throws Exception{
        jdbc.update("INSERT INTO writing_delete_ledger(id,owner_user_id,novel_id,source_kind,source_id,state,result,created_at,updated_at) VALUES('event:1',1,1,'CHAPTER_DELETE','10','DONE','{}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");var journal=new com.novel.modules.agent.writing.infrastructure.persistence.WritingDeletionJournal(jdbc,json,journalDirectory.resolve("deletions.ndjson").toString());assertEquals(1,journal.flush().get("added"));assertEquals(0,journal.flush().get("added"));
        jdbc.update("DELETE FROM writing_delete_ledger");jdbc.update("INSERT INTO writing_delete_ledger(id,owner_user_id,novel_id,source_kind,source_id,state,result,created_at,updated_at) VALUES('event:1',1,1,'CHAPTER_DELETE','20','DONE','{}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");assertEquals(1,journal.flush().get("added"));assertEquals(2,journal.entries().size());String raw=Files.readString(journalDirectory.resolve("deletions.ndjson"));assertFalse(raw.contains("林舟"));assertFalse(raw.contains("taskIds"));
    }
    @Test void transactionalTriggersCoverCommitRollbackBodyAndLogicalDeletion(){
        tx.execute(s->{jdbc.update("UPDATE chapter SET content_version=1 WHERE id=10");jdbc.update("UPDATE chapter_content SET content='变化' WHERE chapter_id=10");s.setRollbackOnly();return null;});assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM writing_source_event",Integer.class));
        tx.execute(s->{jdbc.update("UPDATE chapter SET content_version=1 WHERE id=10");jdbc.update("UPDATE chapter_content SET content='变化' WHERE chapter_id=10");return null;});assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM writing_source_event WHERE kind='CHAPTER_UPDATE'",Integer.class));jdbc.update("UPDATE chapter SET deleted=1 WHERE id=10");assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM writing_source_event WHERE kind='CHAPTER_DELETE'",Integer.class));jdbc.update("UPDATE novel SET deleted=1 WHERE id=1");assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM writing_source_event WHERE kind='NOVEL_DELETE'",Integer.class));
    }
    @Test void assetVersionsAclGlobalSkillsAndConfirmedSnapshots(){
        var row=tx.execute(t->assets.save(1,null,asset("SKILL")));String id=row.get("id").toString();var registry=new WritingSkills(new WritingSkillRegistry(),assets,json);var input=json.createObjectNode();registry.freeze(input,json.createArrayNode().add("personal:"+id),1);assertEquals("林舟不能御剑。",input.path("skillSnapshots").get(0).path("instructions").asText());
        tx.execute(t->assets.save(1,id,asset("SKILL").put("body","新规则").put("expectedVersion",1)));assertEquals(2,assets.history(1,id).size());assertThrows(BusinessException.class,()->tx.execute(t->assets.save(1,id,asset("SKILL").put("expectedVersion",1))));registry.freeze(input,json.createArrayNode().add("personal:"+id),1);assertEquals("林舟不能御剑。",input.path("skillSnapshots").get(0).path("instructions").asText());login(2);assertThrows(BusinessException.class,()->assets.get(2,id));
    }
    @Test void changingSamplesInvalidatesProfilesAndDeletionErasesAllRevisions()throws Exception{
        var sample=tx.execute(t->assets.save(1,null,asset("SAMPLE")));String id=sample.get("id").toString();var analysis=json.readTree("{\"preferences\":{\"sentenceLength\":\"SHORT\",\"narrativeDistance\":\"CLOSE\",\"dialogueRatio\":\"HIGH\",\"pacing\":\"FAST\",\"emotion\":\"SHOW\",\"imagery\":\"MINIMAL\"},\"observations\":[{\"quote\":\"不能御剑\"}]}");
        var profile=tx.execute(t->assets.styleDraft(1,id,1,sample.get("content_hash").toString(),analysis));assertEquals("PENDING",profile.get("state"));assertFalse(profile.get("body").toString().contains("林舟"));tx.execute(t->assets.save(1,id,asset("SAMPLE").put("expectedVersion",1).put("body","改写样文")));assertEquals("INACTIVE",assets.get(1,profile.get("id").toString()).get("state"));tx.execute(t->{assets.delete(1,id,2);return null;});assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM writing_asset",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM writing_asset_revision",Integer.class));
    }
    @Test void automaticKeywordSyncAndCumulativeRecapsUseFreshSourcesAndInvalidateChangedVersions()throws Exception{
        when(content.source(anyLong())).thenAnswer(inv->{long id=inv.getArgument(0);var row=jdbc.queryForMap("SELECT c.*,b.content FROM chapter c JOIN chapter_content b ON b.chapter_id=c.id WHERE c.id=?",id);row.put("contentHash",WritingText.hash(row.get("content").toString()));row.put("eligible",true);return Result.success(row);});
        when(content.catalog(1)).thenAnswer(inv->Result.success(jdbc.queryForList("SELECT c.id,c.chapter_no,c.content_version,c.status,SHA2(b.content,256) AS content_hash FROM chapter c JOIN chapter_content b ON b.chapter_id=c.id WHERE c.novel_id=1 AND c.deleted=0 ORDER BY c.chapter_no")));
        var model=mock(WritingDerivedModel.class);when(model.identity()).thenReturn("fake-model");when(model.derive(eq(1L),anyString(),anyString(),any(Runnable.class))).thenAnswer(inv->{((Runnable)inv.getArgument(3)).run();String instructions=inv.getArgument(1),source=inv.getArgument(2);if(instructions.startsWith("压缩")){var result=json.createObjectNode().put("summary","累计剧情回顾");result.putArray("unresolved");return result;}var result=json.createObjectNode().put("summary","章节回顾");result.putArray("facts").addObject().put("statement","正文线索").put("quote",source);return result;});
        var search=mock(WritingSearchClient.class);when(search.deleteChapter(anyString(),anyString())).thenReturn(Result.success());when(search.put(anyList())).thenReturn(Result.success());var vectors=mock(RetrievalModels.class);var quota=mock(AiQuotaService.class);var cfg=mock(AiConfigService.class);when(cfg.requireUsable()).thenReturn(new AiRuntimeConfig("https://unused","fake-model",java.math.BigDecimal.ZERO,2048,50));
        var derive=new WritingDerivations(jdbc,json,assets,content,model);var sync=new WritingSynchronization(jdbc,json,assets,content,search,vectors,derive,model,deletion,quota,cfg);
        sync.configure(1,json.createObjectNode().put("enabled",true).put("summaries",true).put("acceptCost",true).put("expectedVersion",0));
        for(long id:List.of(10L,20L))sync.update(Map.of("novel_id",1L,"chapter_id",id),()->{});sync.update(Map.of("novel_id",1L,"chapter_id",20L),()->{});assertEquals(2,sync.recaps(1).size());verifyNoInteractions(vectors);verify(search,times(2)).put(anyList());
        var snapshot=new WritingContent.Snapshot("20","1",2,"第二章",0,"", "99","大纲","h",List.of());var extra=new WritingExtendedContext(jdbc,json,assets,content).freeze(snapshot,json.createObjectNode());assertEquals(1,extra.path("memory").path("throughChapterNo").asInt());
        jdbc.update("UPDATE chapter SET content_version=1 WHERE id=10");jdbc.update("UPDATE chapter_content SET content='林舟学会御剑。' WHERE chapter_id=10");assertThrows(BusinessException.class,()->new WritingExtendedContext(jdbc,json,assets,content).validate(snapshot,extra));sync.update(Map.of("novel_id",1L,"chapter_id",10L),()->{});assertEquals(2,sync.recaps(1).size());var keys=json.readTree(sync.recaps(1).getFirst().get("source_keys").toString());assertEquals(1,keys.get(0).path("version").asInt());
    }
    ObjectNode task(String id,String chapter){var task=json.createObjectNode().put("id",id).put("sourceHash","h").put("draft","草稿").put("currentDraftAttemptId",UUID.randomUUID().toString());task.putObject("snapshot").put("novelId","1").put("chapterId",chapter);task.putObject("check").put("status","OK");return task;}
    @Test void revisionDagKeepsParentsAndAllowsOwnedVersionSelection(){var drafts=new WritingDrafts(jdbc,json);var task=task(UUID.randomUUID().toString(),"20");tx.execute(t->{drafts.capture(task);return null;});String parent=task.path("currentRevisionId").asText();task.put("draft","新稿");tx.execute(t->{drafts.capture(task);return null;});var rows=drafts.list(task);assertEquals(2,rows.size());assertEquals(parent,rows.getFirst().get("parent_id"));drafts.select(task,parent);assertEquals("草稿",task.path("draft").asText());login(2);assertTrue(drafts.list(task).isEmpty());}
    @Test void dependentTaskAndHistoricalTextDeletionCanResumeWithoutLosingCleanupIds(){String id=UUID.randomUUID().toString();var task=task(id,"20");task.putArray("researchSources").addObject().put("chapterId","10").put("text","私有原文");jdbc.update("INSERT INTO writing_task(id,owner_user_id,chapter_id,novel_id,status,payload,created_at,updated_at) VALUES(?,1,20,1,'READY',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,task.toString());jdbc.update("INSERT INTO writing_event(task_id,seq,event_type,payload,created_at) VALUES(?,1,'state',?,UTC_TIMESTAMP(3))",id,task.toString());
        var first=tx.execute(t->deletion.purge(1,1,"10",List.of(),99));var resumed=tx.execute(t->deletion.purge(1,1,"10",List.of(),99));assertEquals(List.of(id),first);assertEquals(first,resumed);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM writing_task",Integer.class));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM writing_event",Integer.class));assertFalse(jdbc.queryForObject("SELECT result FROM writing_delete_ledger WHERE id='event:99'",String.class).contains("私有原文"));}
}
