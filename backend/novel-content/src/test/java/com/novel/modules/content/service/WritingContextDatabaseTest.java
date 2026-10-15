package com.novel.modules.content.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.exception.BusinessException;
import com.novel.common.security.*;
import com.novel.common.enums.ChapterStatus;
import com.novel.modules.content.entity.*;
import com.novel.modules.content.enums.OutlineNodeType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.core.io.FileSystemResource;
import java.sql.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="writing.mysql",matches="true")
class WritingContextDatabaseTest {
    static String schema,url,password;static DriverManagerDataSource ds;static JdbcTemplate jdbc;
    ObjectMapper json=new ObjectMapper().findAndRegisterModules();NovelAccessGuard guard;WritingLibraryService library;WritingContextService contexts;TransactionTemplate tx;
    @BeforeAll static void db()throws Exception{
        schema="writing_test_"+UUID.randomUUID().toString().replace("-","");url="jdbc:mysql://192.168.127.137:3306/?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000";password=System.getenv("WRITING_TEST_DB_PASSWORD");
        if(password==null)throw new IllegalStateException("WRITING_TEST_DB_PASSWORD required");
        try(var c=DriverManager.getConnection(url,"root",password);var s=c.createStatement()){s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");}
        ds=new DriverManagerDataSource(url.replace("3306/","3306/"+schema),"root",password);jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE novel(id BIGINT PRIMARY KEY,author_id BIGINT,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE chapter(id BIGINT PRIMARY KEY,novel_id BIGINT,chapter_no INT,title VARCHAR(100),status INT,content_version BIGINT DEFAULT 0,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE chapter_content(chapter_id BIGINT PRIMARY KEY,content MEDIUMTEXT)");
        try(var c=ds.getConnection()){ScriptUtils.executeSqlScript(c,new FileSystemResource("../../docs/sql/writing-story-library.sql"));}
    }
    @AfterAll static void drop()throws Exception{if(schema!=null&&schema.matches("writing_test_[a-f0-9]{32}"))try(var c=DriverManager.getConnection(url,"root",password);var s=c.createStatement()){s.execute("DROP DATABASE "+schema);}}
    void login(long uid){var u=new LoginUser();u.setUserId(uid);u.setJti("test");u.setRoles(List.of("AUTHOR"));UserContext.set(u);}
    @AfterEach void clear(){UserContext.clear();}
    @BeforeEach void setup(){
        for(var table:List.of("writing_story_revision","writing_story_entry","writing_private_index_job","chapter_content","chapter","novel"))jdbc.update("DELETE FROM "+table);
        jdbc.update("INSERT INTO novel(id,author_id) VALUES(1,1),(2,2)");
        for(int[] c:List.of(new int[]{1,1,1,0},new int[]{2,1,2,0},new int[]{3,1,3,4},new int[]{10,1,4,0},new int[]{20,1,5,0},new int[]{99,2,1,0})){
            jdbc.update("INSERT INTO chapter(id,novel_id,chapter_no,title,status) VALUES(?,?,?,?,?)",c[0],c[1],c[2],"章节"+c[2],c[3]);jdbc.update("INSERT INTO chapter_content VALUES(?,?)",c[0],"林舟尚不能御剑，玉佩在林舟身上。");
        }
        guard=mock(NovelAccessGuard.class);when(guard.requireOwned(anyLong())).thenAnswer(i->{long id=i.getArgument(0);var owner=jdbc.queryForObject("SELECT author_id FROM novel WHERE id=? AND deleted=0",Long.class,id);if(!owner.equals(UserContext.requireUserId()))throw new BusinessException(403,"拒绝",403);var n=new Novel();n.setId(id);n.setAuthorId(owner);return n;});
        when(guard.requireOwnedChapter(anyLong())).thenAnswer(i->{long id=i.getArgument(0);var row=jdbc.queryForMap("SELECT * FROM chapter WHERE id=? AND deleted=0",id);guard.requireOwned(((Number)row.get("novel_id")).longValue());var c=new Chapter();c.setId(id);c.setNovelId(((Number)row.get("novel_id")).longValue());c.setChapterNo(((Number)row.get("chapter_no")).intValue());c.setTitle(row.get("title").toString());c.setContentVersion(((Number)row.get("content_version")).longValue());c.setStatus(ChapterStatus.fromCode(((Number)row.get("status")).intValue()));return c;});
        when(guard.requireOwnedOutlineNode(anyLong())).thenAnswer(i->{guard.requireOwned(1L);return node(i.getArgument(0));});
        when(guard.requireNodeInNovel(eq(1L),anyLong())).thenAnswer(i->node(i.getArgument(1)));
        library=new WritingLibraryService(jdbc,guard,json);contexts=new WritingContextService(guard,library,jdbc,json);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));login(1);
    }
    OutlineNode node(long id){var n=new OutlineNode();n.setId(id);n.setNovelId(1L);n.setTitle(id==100?"全书":id==101?"第一卷":"本章");n.setNodeType(OutlineNodeType.fromCode(id==100?1:id==101?2:3));n.setParentId(id==100?null:id==101?100L:101L);n.setChapterId(id==102?10L:null);n.setContent(id==100?"第十章揭晓师兄身份，不能提前泄露":id==101?"本卷只埋线索":"小舟找到玉佩，但不揭露师兄身份");return n;}
    ContextRequest request(){return new ContextRequest(102,List.of(),List.of(),"小舟询问玉佩");}
    ObjectNode entry(){var e=json.createObjectNode().put("kind","CHARACTER").put("name","林舟").put("body","林舟尚不能御剑").put("meaning","SETTING").put("state","APPROVED").put("effectiveFrom",1);e.putArray("aliases").add("小舟");return e;}
    Map<String,Object> save(JsonNode e){return tx.execute(t->library.save(1,null,e,false));}
    @Test void layeredNotesAndNormalChaptersAreCompleteAndPlansStaySeparate(){var snap=tx.execute(t->contexts.snapshot(10,request()));assertEquals(Set.of("BOOK","VOLUME","CHAPTER"),snap.notes().stream().map(Note::level).collect(java.util.stream.Collectors.toSet()));assertEquals(3,snap.coverage().size());for(var c:snap.coverage())assertEquals(c.totalChars(),c.readChars());assertTrue(snap.notes().get(2).text().contains("第十章"));assertTrue(snap.sources().stream().noneMatch(s->s.text().contains("第十章")));}
    @Test void futureAndForeignChapterSelectionsAreRejectedBeforeReading(){assertThrows(BusinessException.class,()->contexts.snapshot(10,new ContextRequest(102,List.of(20L),List.of(),"")));assertThrows(BusinessException.class,()->contexts.snapshot(10,new ContextRequest(102,List.of(99L),List.of(),"")));login(2);assertThrows(BusinessException.class,()->contexts.snapshot(10,request()));}
    @Test void longChaptersHaveExplicitPartialCoverageAndExactAnchors(){jdbc.update("UPDATE chapter_content SET content=? WHERE chapter_id=2","玉佩的秘密。".repeat(5000));var s=contexts.snapshot(10,request());assertTrue(s.coverage().stream().anyMatch(c->c.readChars()<c.totalChars()));String raw=jdbc.queryForObject("SELECT content FROM chapter_content WHERE chapter_id=2",String.class);for(var source:s.sources())if(source.chapterId().equals("2"))assertEquals(raw.substring(source.start(),source.end()),source.text());}
    @Test void aliasesCoreRulesAndEffectiveRangesControlActivation(){var person=save(entry());save(entry().put("kind","RULE").put("name","修炼规则").put("body","不得凭空突破境界").put("core",true));save(entry().put("name","未来林舟").put("effectiveFrom",5));var s=contexts.snapshot(10,request());assertTrue(s.settings().stream().anyMatch(e->e.id().equals(person.get("id"))&&e.reason().contains("别名")));assertTrue(s.settings().stream().anyMatch(Setting::core));assertFalse(s.settings().stream().anyMatch(e->e.name().equals("未来林舟")));}
    @Test void namesInLoadedChapterHeadAndCurrentBodyActivateSettings(){
        var earlier=entry().put("name","玄龟");earlier.putArray("aliases");save(earlier);
        var current=entry().put("name","锁魂铃");current.putArray("aliases");save(current);
        jdbc.update("UPDATE chapter_content SET content=? WHERE chapter_id=3","玄龟守在门口。"+"山".repeat(3000));
        jdbc.update("UPDATE chapter_content SET content=? WHERE chapter_id=10","锁魂铃在桌上。"+"云".repeat(3000));
        var snap=contexts.snapshot(10,request());assertTrue(snap.settings().stream().anyMatch(e->e.name().equals("玄龟")));assertTrue(snap.settings().stream().anyMatch(e->e.name().equals("锁魂铃")));
    }
    @Test void revisionsAndOptimisticEditsPreserveAuthorAuthority(){var row=save(entry());var edit=entry().put("body","作者确认修订").put("expectedVersion",1);tx.execute(t->library.save(1,row.get("id").toString(),edit,false));assertEquals(2,library.revisions(1,row.get("id").toString()).size());assertThrows(BusinessException.class,()->tx.execute(t->library.save(1,row.get("id").toString(),edit,false)));login(2);assertThrows(BusinessException.class,()->library.owned(1,row.get("id").toString(),false));}
    @Test void proposalsRemainPendingAndChangedFactsBecomeStale(){var fact=entry().put("meaning","FACT").put("sourceChapterId",2).put("sourceQuote","林舟尚不能御剑").put("effectiveFrom",2);var pending=tx.execute(t->library.save(1,null,fact,true));assertEquals("PENDING",pending.get("state"));assertTrue(contexts.snapshot(10,request()).settings().isEmpty());var approved=save(fact);assertFalse(contexts.snapshot(10,request()).settings().isEmpty());jdbc.update("UPDATE chapter SET content_version=1 WHERE id=2");assertTrue(contexts.snapshot(10,request()).settings().isEmpty());assertTrue(library.list(1).stream().filter(e->e.get("id").equals(approved.get("id"))).anyMatch(e->((Number)e.get("source_stale")).intValue()==1));}
    @Test void forgedOrStaleEsEvidenceNeverLeavesContentService(){var chunks=contexts.chunks(10,102,2);assertFalse(contexts.validate(10,102,chunks).isEmpty());jdbc.update("UPDATE chapter SET content_version=1 WHERE id=2");assertTrue(contexts.validate(10,102,chunks).isEmpty());var wrong=new Source("forged","99",1,"秘密",0,0,2,true,"秘密");assertTrue(contexts.validate(10,102,List.of(wrong)).isEmpty());}
    @Test void coreBudgetAndInvalidFactSourcesAreRejected(){save(entry().put("core",true).put("body","规则".repeat(1000)));assertThrows(BusinessException.class,()->save(entry().put("core",true).put("body","规则".repeat(300))));assertThrows(BusinessException.class,()->save(entry().put("meaning","FACT").put("sourceChapterId",99).put("sourceQuote","秘密")));}
    @Test void duplicateCandidatesAreCoalescedAndDeletedSourcesCanBeDeactivated(){var fact=entry().put("meaning","FACT").put("sourceChapterId",2).put("sourceQuote","林舟尚不能御剑").put("effectiveFrom",2);var first=tx.execute(t->library.save(1,null,fact,true));var second=tx.execute(t->library.save(1,null,fact,true));assertEquals(first.get("id"),second.get("id"));jdbc.update("UPDATE chapter SET deleted=1 WHERE id=2");fact.put("expectedVersion",1).put("state","INACTIVE");assertEquals("INACTIVE",tx.execute(t->library.save(1,first.get("id").toString(),fact,false)).get("state"));}
}
