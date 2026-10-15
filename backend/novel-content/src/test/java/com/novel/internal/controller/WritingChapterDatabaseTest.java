package com.novel.internal.controller;

import com.novel.common.internal.WritingContent.*;
import com.novel.common.security.*;
import com.novel.common.enums.ChapterStatus;
import com.novel.common.exception.BusinessException;
import com.novel.modules.content.dto.ChapterSaveDTO;
import com.novel.modules.content.entity.*;
import com.novel.modules.content.mapper.*;
import com.novel.modules.content.service.*;
import com.novel.modules.content.service.impl.ChapterServiceImpl;
import com.novel.content.mq.producer.AiAuditNotifier;
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
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MySQL row locks with the production chapter service and adoption controller. */
@EnabledIfSystemProperty(named="writing.mysql",matches="true")
class WritingChapterDatabaseTest {
    static String schema,url,user,password;static DriverManagerDataSource ds;static JdbcTemplate jdbc;
    ChapterServiceImpl service;InternalWritingController controller;TransactionTemplate tx;
    @BeforeAll static void db() throws Exception {
        schema="writing_test_"+UUID.randomUUID().toString().replace("-","");
        url=System.getProperty("writing.test.url","jdbc:mysql://192.168.127.137:3306/?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000");user=System.getProperty("writing.test.user","root");password=System.getenv("WRITING_TEST_DB_PASSWORD");
        if(password==null)throw new IllegalStateException("Set WRITING_TEST_DB_PASSWORD");
        try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        ds=new DriverManagerDataSource(url.replace("3306/","3306/"+schema),user,password);jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE chapter(id BIGINT PRIMARY KEY,novel_id BIGINT,title VARCHAR(200),status INT)");
        jdbc.execute("CREATE TABLE chapter_content(chapter_id BIGINT PRIMARY KEY,content TEXT)");
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("../../docs/sql/writing-agent-mvp.sql"));}
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("../../docs/sql/writing-agent-extensions.sql"));}
    }
    @AfterAll static void drop() throws Exception {if(schema!=null&&schema.matches("writing_test_[a-f0-9]{32}"))try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("DROP DATABASE "+schema);}}
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM chapter_ai_adoption");jdbc.update("DELETE FROM chapter_content");jdbc.update("DELETE FROM chapter");
        jdbc.update("INSERT INTO chapter(id,novel_id,title,status) VALUES(10,1,'第二章',0)");jdbc.update("INSERT INTO chapter_content VALUES(10,'甲乙甲乙甲乙')");
        tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var chapters=mock(ChapterMapper.class);var bodies=mock(ChapterContentMapper.class);var guard=mock(NovelAccessGuard.class);
        when(chapters.selectById(10L)).thenAnswer(i->read(false));when(chapters.selectForUpdate(10L)).thenAnswer(i->read(true));
        when(guard.requireOwnedChapterForUpdate(10L)).thenAnswer(i->{owned();return read(true);});when(guard.requireOwnedChapter(10L)).thenAnswer(i->{owned();return read(false);});
        when(bodies.selectById(10L)).thenAnswer(i->{var b=new ChapterContent();b.setChapterId(10L);b.setContent(text());return b;});
        when(bodies.updateById(any(ChapterContent.class))).thenAnswer(i->{ChapterContent b=i.getArgument(0);return jdbc.update("UPDATE chapter_content SET content=? WHERE chapter_id=?",b.getContent(),b.getChapterId());});
        when(chapters.updateById(any(Chapter.class))).thenAnswer(i->{Chapter c=i.getArgument(0);return jdbc.update("UPDATE chapter SET title=?,status=?,content_version=? WHERE id=?",c.getTitle(),c.getStatus().getCode(),c.getContentVersion(),c.getId());});
        service=new ChapterServiceImpl(chapters,bodies,mock(NovelMapper.class),mock(NovelService.class),guard,mock(AuditLogService.class),mock(AiAuditNotifier.class));
        controller=new InternalWritingController(guard,chapters,bodies,service,jdbc);login();
    }
    void owned(){if(!Objects.equals(UserContext.requireUserId(),1L))throw new BusinessException(403,"拒绝",403);}
    void login(){var u=new LoginUser();u.setUserId(1L);u.setRoles(List.of("AUTHOR"));UserContext.set(u);}
    @AfterEach void clear(){UserContext.clear();}
    Chapter read(boolean lock){return jdbc.queryForObject("SELECT * FROM chapter WHERE id=10"+(lock?" FOR UPDATE":""),(r,n)->{var c=new Chapter();c.setId(10L);c.setNovelId(1L);c.setTitle(r.getString("title"));c.setStatus(r.getInt("status")==1?ChapterStatus.PENDING:ChapterStatus.DRAFT);c.setContentVersion(r.getLong("content_version"));return c;});}
    String text(){return jdbc.queryForObject("SELECT content FROM chapter_content WHERE chapter_id=10",String.class);}
    ChapterSaveDTO dto(long version,String text){var d=new ChapterSaveDTO();d.setTitle("第二章");d.setContent(text);d.setExpectedVersion(version);return d;}
    Patch patch(String key){return new Patch("task","draft",0,2,4,"甲乙","新",key);}
    @Test void versionProtectedSaveAndNoop(){var d=dto(0,"新正文");tx.execute(s->{service.saveContent(10L,d);return null;});assertEquals(1,d.getSavedVersion());tx.execute(s->{service.saveContent(10L,dto(1,"新正文"));return null;});assertEquals(1,read(false).getContentVersion());assertThrows(BusinessException.class,()->tx.execute(s->{service.saveContent(10L,dto(0,"覆盖"));return null;}));assertEquals("新正文",text());}
    @Test void exactPatchDuplicateAndConditionalUndo(){var result=tx.execute(s->controller.patch(10L,patch("key")).getData());assertEquals("甲乙新甲乙",text());var same=tx.execute(s->controller.patch(10L,patch("key")).getData());assertEquals(result,same);assertEquals(1,read(false).getContentVersion());var undo=new Undo("task",result.adoptionId(),1,"undo");tx.execute(s->controller.undo(10L,undo));assertEquals("甲乙甲乙甲乙",text());assertEquals(2,read(false).getContentVersion());tx.execute(s->controller.undo(10L,undo));assertEquals(2,read(false).getContentVersion());}
    @Test void laterEditBlocksUndo(){var applied=tx.execute(s->controller.patch(10L,patch("key")).getData());tx.execute(s->{service.saveContent(10L,dto(1,"作者后来修改"));return null;});assertThrows(BusinessException.class,()->tx.execute(s->controller.undo(10L,new Undo("task",applied.adoptionId(),2,"undo"))));assertEquals("作者后来修改",text());}
    @Test void concurrentEditorAndAgentOnlyOneWins() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        Callable<Boolean> normal=()->{login();ready.countDown();go.await();try{tx.execute(s->{service.saveContent(10L,dto(0,"普通编辑"));return null;});return true;}catch(BusinessException e){assertEquals(409,e.getCode());return false;}finally{UserContext.clear();}};
        Callable<Boolean> agent=()->{login();ready.countDown();go.await();try{tx.execute(s->controller.patch(10L,patch("key")));return true;}catch(BusinessException e){assertEquals(409,e.getCode());return false;}finally{UserContext.clear();}};
        try{var a=pool.submit(normal);var b=pool.submit(agent);assertTrue(ready.await(3,TimeUnit.SECONDS));go.countDown();assertEquals(1,(a.get()?1:0)+(b.get()?1:0));assertEquals(1,read(false).getContentVersion());}finally{pool.shutdownNow();}
    }
    @Test void pendingStateAndForeignIdentityCannotWrite(){jdbc.update("UPDATE chapter SET status=1");assertThrows(BusinessException.class,()->tx.execute(s->controller.patch(10L,patch("key"))));assertEquals("甲乙甲乙甲乙",text());var u=new LoginUser();u.setUserId(2L);UserContext.set(u);assertThrows(BusinessException.class,()->tx.execute(s->controller.patch(10L,patch("key"))));}
    @Test void accessDoesNotRequireOutlineText() {
        var guard=mock(NovelAccessGuard.class);when(guard.requireOwnedChapter(10L)).thenReturn(read(false));
        var node=new OutlineNode();node.setId(20L);node.setNovelId(1L);node.setChapterId(10L);node.setContent("");
        when(guard.requireOwnedOutlineNode(20L)).thenReturn(node);
        var endpoint=new InternalWritingController(guard,mock(ChapterMapper.class),mock(ChapterContentMapper.class),service,jdbc);
        assertTrue(endpoint.access(10L,20L).getData());
        node.setChapterId(99L);assertEquals(404,assertThrows(BusinessException.class,()->endpoint.access(10L,20L)).getHttpStatus());
    }
    @Test void threeWayMergePreservesIndependentAuthorEditAndUndoRestoresLatestBeforeMerge(){
        String base="开场。旧情节。结尾。";jdbc.update("UPDATE chapter_content SET content=? WHERE chapter_id=10",base);
        tx.execute(s->{service.saveContent(10L,dto(0,"新开场。旧情节。结尾。"));return null;});
        var request=new MergePatch("merge-task","draft",1,0,base,3,7,"旧情节。","新情节。","",false,"merge-key");
        assertTrue(controller.mergePreview(10L,request).getData().preview().clean());var applied=tx.execute(s->controller.merge(10L,request).getData());assertEquals("新开场。新情节。结尾。",text());assertEquals(applied,tx.execute(s->controller.merge(10L,request).getData()));
        tx.execute(s->controller.undo(10L,new Undo("merge-task",applied.adoptionId(),2,"merge-undo")));assertEquals("新开场。旧情节。结尾。",text());assertEquals(3,read(false).getContentVersion());
    }
    @Test void overlappingMergeNeedsExplicitResolutionAndNeverOverwritesAnotherLaterEdit(){
        String base="开场。旧情节。结尾。";jdbc.update("UPDATE chapter_content SET content=? WHERE chapter_id=10",base);tx.execute(s->{service.saveContent(10L,dto(0,"开场。作者情节。结尾。"));return null;});
        var request=new MergePatch("merge-conflict","draft",1,0,base,3,7,"旧情节。","新情节。","",false,"conflict-key");assertFalse(controller.mergePreview(10L,request).getData().preview().clean());assertThrows(BusinessException.class,()->tx.execute(s->controller.merge(10L,request)));assertEquals("开场。作者情节。结尾。",text());
        var resolved=new MergePatch("merge-conflict","draft",1,0,base,3,7,"旧情节。","新情节。","开场。作者确认情节。结尾。",true,"resolve-key");tx.execute(s->{service.saveContent(10L,dto(1,"作者又修改了"));return null;});assertThrows(BusinessException.class,()->tx.execute(s->controller.merge(10L,resolved)));assertEquals("作者又修改了",text());
    }
}
