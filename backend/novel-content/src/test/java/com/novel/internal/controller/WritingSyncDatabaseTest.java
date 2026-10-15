package com.novel.internal.controller;
import com.novel.common.security.*;
import com.novel.common.exception.BusinessException;
import com.novel.modules.content.service.NovelAccessGuard;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named="writing.mysql",matches="true")
class WritingSyncDatabaseTest {
    static String schema,url,password;static DriverManagerDataSource ds;static JdbcTemplate jdbc;
    InternalWritingSyncController controller;TransactionTemplate tx;
    @BeforeAll static void db()throws Exception{schema="writing_test_"+UUID.randomUUID().toString().replace("-","");url="jdbc:mysql://192.168.127.137:3306/?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000";password=System.getenv("WRITING_TEST_DB_PASSWORD");if(password==null)throw new IllegalStateException("WRITING_TEST_DB_PASSWORD required");try(var c=DriverManager.getConnection(url,"root",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}ds=new DriverManagerDataSource(url.replace("3306/","3306/"+schema),"root",password);jdbc=new JdbcTemplate(ds);jdbc.execute("CREATE TABLE novel(id BIGINT PRIMARY KEY,author_id BIGINT,deleted INT DEFAULT 0)");jdbc.execute("CREATE TABLE chapter(id BIGINT PRIMARY KEY,novel_id BIGINT,deleted INT DEFAULT 0)");try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("../../docs/sql/writing-agent-mvp.sql"));ScriptUtils.executeSqlScript(c,new FileSystemResource("../../docs/sql/writing-agent-extensions.sql"));ScriptUtils.executeSqlScript(c,new FileSystemResource("../../docs/sql/writing-story-library.sql"));}}
    @AfterAll static void drop()throws Exception{if(schema!=null&&schema.matches("writing_test_[a-f0-9]{32}"))try(var c=DriverManager.getConnection(url,"root",password);var s=c.createStatement()){s.execute("DROP DATABASE "+schema);}}
    void login(boolean admin){var user=new LoginUser();user.setUserId(1L);user.setRoles(List.of(admin?"ADMIN":"AUTHOR"));UserContext.set(user);}
    @BeforeEach void setup(){jdbc.update("DELETE FROM writing_source_event");jdbc.update("DELETE FROM chapter");jdbc.update("DELETE FROM novel");jdbc.update("INSERT INTO novel(id,author_id) VALUES(1,1),(2,2)");controller=new InternalWritingSyncController(jdbc,mock(NovelAccessGuard.class));tx=new TransactionTemplate(new DataSourceTransactionManager(ds));login(true);}
    @AfterEach void clear(){UserContext.clear();}
    @BeforeEach void cleanAdoptions(){jdbc.update("DELETE FROM chapter_ai_adoption");}
    @BeforeEach void ensureBodyTable(){jdbc.execute("CREATE TABLE IF NOT EXISTS chapter_content(chapter_id BIGINT PRIMARY KEY,content MEDIUMTEXT)");jdbc.update("DELETE FROM chapter_content");}
    void event(long owner,long novel,long chapter){jdbc.update("INSERT INTO writing_source_event(owner_user_id,novel_id,chapter_id,kind) VALUES(?,?,?,'CHAPTER_UPDATE')",owner,novel,chapter);}
    @Test void concurrentClaimsNeverRunTwoStepsOfOneBook()throws Exception{event(1,1,10);event(1,1,20);var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);Callable<Map<String,Object>> claim=()->{login(true);ready.countDown();go.await();try{return tx.execute(t->controller.claim().getData());}finally{UserContext.clear();}};try{var a=pool.submit(claim);var b=pool.submit(claim);assertTrue(ready.await(3,TimeUnit.SECONDS));go.countDown();int count=(a.get()!=null?1:0)+(b.get()!=null?1:0);assertEquals(1,count);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM writing_source_event WHERE state='RUNNING'",Integer.class));}finally{pool.shutdownNow();}}
    @Test void differentBooksCanRunAndHeartbeatsAndFinishRequireExactLease(){event(1,1,10);event(2,2,20);var first=tx.execute(t->controller.claim().getData());var second=tx.execute(t->controller.claim().getData());assertNotNull(first);assertNotNull(second);assertNotEquals(first.get("novel_id"),second.get("novel_id"));long id=((Number)first.get("id")).longValue();assertFalse(controller.heartbeat(id,Map.of("leaseToken","wrong")).getData());assertTrue(controller.finish(id,Map.of("leaseToken",first.get("lease_token").toString(),"state","DONE")).getData());assertFalse(controller.finish(id,Map.of("leaseToken",first.get("lease_token").toString(),"state","DONE")).getData());}
    @Test void duplicateSaveEventCannotAutomaticallyRepeatFailedPaidWork(){
        jdbc.update("INSERT INTO chapter(id,novel_id) VALUES(10,1)");jdbc.update("INSERT INTO chapter_content VALUES(10,'正文')");event(1,1,10);event(1,1,10);var first=tx.execute(t->controller.claim().getData());long id=((Number)first.get("id")).longValue();String token=first.get("lease_token").toString();assertTrue(controller.stamp(id,Map.of("leaseToken",token,"version",0,"hash",com.novel.common.internal.WritingText.hash("正文"))).getData());controller.finish(id,Map.of("leaseToken",token,"state","NEEDS_RETRY"));assertNull(tx.execute(t->controller.claim().getData()));assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM writing_source_event WHERE state='NEEDS_RETRY'",Integer.class));
        jdbc.update("UPDATE chapter_content SET content='更新正文' WHERE chapter_id=10");event(1,1,10);assertNotNull(tx.execute(t->controller.claim().getData()));
    }
    @Test void expiredPaidWorkDoesNotRestartAutomaticallyAndAuthorsCannotPollGlobalQueue(){event(1,1,10);var first=tx.execute(t->controller.claim().getData());jdbc.update("UPDATE writing_source_event SET lease_until=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",first.get("id"));assertNull(tx.execute(t->controller.claim().getData()));assertEquals("NEEDS_RETRY",jdbc.queryForObject("SELECT state FROM writing_source_event WHERE id=?",String.class,first.get("id")));login(false);assertThrows(BusinessException.class,()->controller.claim());}
    @Test void restoreTombstoneCannotDeleteAnotherOwnersSource(){jdbc.update("INSERT INTO chapter(id,novel_id) VALUES(10,2)");tx.execute(t->controller.replay(Map.of("owner_user_id",1,"novel_id",2,"source_kind","CHAPTER_DELETE","source_id","10")));assertEquals(0,jdbc.queryForObject("SELECT deleted FROM chapter WHERE id=10",Integer.class));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM writing_source_event WHERE owner_user_id=1",Integer.class));}
    @Test void deletingPhysicallyMissingChapterErasesAdoptionSnapshotsWithoutTouchingAnotherBook(){
        String id=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();for(var entry:List.of(Map.of("id",id,"novel",1,"chapter",10),Map.of("id",other,"novel",2,"chapter",20)))jdbc.update("INSERT INTO chapter_ai_adoption(id,owner_user_id,novel_id,chapter_id,task_id,operation,command_key,request_hash,before_version,after_version,before_content,result_content,title,created_at) VALUES(?,1,?,?,?,'ADOPT',?,'h',0,1,'private-before','private-after','test',UTC_TIMESTAMP(3))",entry.get("id"),entry.get("novel"),entry.get("chapter"),UUID.randomUUID().toString(),entry.get("id"));
        jdbc.update("INSERT INTO writing_source_event(owner_user_id,novel_id,chapter_id,kind) VALUES(1,1,10,'CHAPTER_DELETE')");var event=tx.execute(t->controller.claim().getData());tx.execute(t->controller.purge(Map.of("eventId",event.get("id"),"leaseToken",event.get("lease_token"))));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM chapter_ai_adoption WHERE id=?",Integer.class,id));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM chapter_ai_adoption WHERE id=?",Integer.class,other));
    }
}
