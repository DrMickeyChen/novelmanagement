package com.novel.modules.content.service;

import com.fasterxml.jackson.databind.*;
import com.novel.common.internal.WritingText;
import com.novel.common.security.UserContext;
import com.novel.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Queries always bind the authenticated owner and an owned novel. */
@Service @RequiredArgsConstructor
public class WritingLibraryService {
    private final JdbcTemplate jdbc;
    private final NovelAccessGuard guard;
    private final ObjectMapper json;
    public List<Map<String,Object>> list(long novel) {
        guard.requireOwned(novel);
        var rows=jdbc.queryForList("SELECT e.*,CASE WHEN e.source_chapter_id IS NOT NULL AND (c.id IS NULL OR c.deleted<>0 OR c.content_version<>e.source_version) THEN 1 ELSE 0 END AS source_stale FROM writing_story_entry e LEFT JOIN chapter c ON c.id=e.source_chapter_id AND c.novel_id=e.novel_id WHERE e.owner_user_id=? AND e.novel_id=? ORDER BY e.core DESC,e.updated_at DESC,e.id",UserContext.requireUserId(),novel);
        return rows;
    }
    public List<Map<String,Object>> revisions(long novel,String id) {
        owned(novel,id,false);
        return jdbc.queryForList("SELECT version,payload,created_at FROM writing_story_revision WHERE entry_id=? AND owner_user_id=? AND novel_id=? ORDER BY version DESC LIMIT 50",id,UserContext.requireUserId(),novel);
    }
    public String catalogHash(long novel) { return WritingText.hash(list(novel).toString()); }
    @Transactional public void delete(long novel,String id,long expectedVersion){
        var row=owned(novel,id,true);if(oldVersion(row)!=expectedVersion)throw error(409,"资料已变化，请刷新");long uid=UserContext.requireUserId();
        jdbc.update("DELETE FROM writing_story_revision WHERE entry_id=? AND owner_user_id=? AND novel_id=?",id,uid,novel);
        jdbc.update("DELETE FROM writing_story_entry WHERE id=? AND owner_user_id=? AND novel_id=?",id,uid,novel);
        jdbc.update("INSERT INTO writing_source_event(owner_user_id,novel_id,source_id,kind,source_version) VALUES(?,?,?,'SETTING_DELETE',?)",uid,novel,id,expectedVersion);
    }
    public Map<String,Object> owned(long novel,String id,boolean lock) {
        guard.requireOwned(novel);
        var rows=jdbc.queryForList("SELECT * FROM writing_story_entry WHERE id=? AND owner_user_id=? AND novel_id=?"+(lock?" FOR UPDATE":""),id,UserContext.requireUserId(),novel);
        if(rows.isEmpty()) throw error(404,"资料条目不存在");return rows.getFirst();
    }
    @Transactional
    public Map<String,Object> save(long novel,String id,JsonNode req,boolean proposal) {
        guard.requireOwned(novel);long uid=UserContext.requireUserId();
        // Serialize edits and the core-rule budget across the author's book.
        if(jdbc.queryForList("SELECT id FROM novel WHERE id=? AND author_id=? AND deleted=0 FOR UPDATE",novel,uid).isEmpty())throw error(404,"作品不可访问");
        var old=id==null?null:owned(novel,id,true);
        if(old==null && jdbc.queryForObject("SELECT COUNT(*) FROM writing_story_entry WHERE owner_user_id=? AND novel_id=?",Integer.class,uid,novel)>=500)throw error(422,"每本作品最多 500 个资料条目，请整理已有资料");
        if(old!=null && oldVersion(old)!=req.path("expectedVersion").asLong(-1)) throw error(409,"资料已被修改，请刷新后编辑");
        String kind=text(req,"kind",20),name=text(req,"name",100),body=text(req,"body",4000),meaning=text(req,"meaning",20);
        if(!Set.of("CHARACTER","LOCATION","ITEM","WORLD","RULE").contains(kind) || !Set.of("SETTING","FACT","PLAN").contains(meaning)) throw error(422,"资料分类无效");
        String state=proposal?"PENDING":req.path("state").asText("APPROVED");
        if(!Set.of("PENDING","APPROVED","INACTIVE").contains(state)) throw error(422,"资料状态无效");
        var aliases=req.path("aliases");if(!aliases.isArray() || aliases.size()>12) throw error(422,"别名最多 12 个");
        for(var alias:aliases) if(!alias.isTextual() || alias.asText().isBlank() || alias.asText().length()>100) throw error(422,"别名格式无效");
        int from=req.path("effectiveFrom").asInt(1);Integer to=req.path("effectiveTo").isIntegralNumber()?req.get("effectiveTo").asInt():null;
        if(from<1 || to!=null && to<from) throw error(422,"生效章节范围无效");
        boolean core=!proposal && req.path("core").asBoolean();
        if(core && state.equals("APPROVED")) {
            int used=jdbc.queryForObject("SELECT COALESCE(SUM(CHAR_LENGTH(body)),0) FROM writing_story_entry WHERE owner_user_id=? AND novel_id=? AND core=1 AND state='APPROVED' AND id<>?",Integer.class,uid,novel,id==null?"":id);
            if(used+body.length()>2400) throw error(422,"始终参考的核心规则合计最多 2400 字，请精简");
        }
        Long source=req.path("sourceChapterId").isIntegralNumber()?req.get("sourceChapterId").asLong():null;
        Long sourceVersion=null;String quote=req.path("sourceQuote").asText("");
        if(old!=null && state.equals("INACTIVE")) {
            source=old.get("source_chapter_id")==null?null:((Number)old.get("source_chapter_id")).longValue();
            sourceVersion=old.get("source_version")==null?null:((Number)old.get("source_version")).longValue();
            quote=Objects.toString(old.get("source_quote"),"");
        } else if(source!=null) {
            var chapter=guard.requireOwnedChapter(source);
            if(!Objects.equals(chapter.getNovelId(),novel)) throw error(404,"资料来源不属于当前作品");
            sourceVersion=chapter.getContentVersion();
            var rows=jdbc.queryForList("SELECT content FROM chapter_content WHERE chapter_id=?",source);
            if(quote.isBlank() || quote.length()>2000 || rows.isEmpty() || !Objects.toString(rows.getFirst().get("content"),"").contains(quote)) throw error(422,"来源引文必须能在对应章节正文中定位");
            if(meaning.equals("FACT") && from<chapter.getChapterNo()) throw error(422,"正文事实不能在来源章节之前生效");
        } else if(proposal || meaning.equals("FACT")) throw error(422,"正文事实或候选记忆必须指定来源章节及原文");
        if(proposal){var duplicates=jdbc.queryForList("SELECT id FROM writing_story_entry WHERE owner_user_id=? AND novel_id=? AND state='PENDING' AND name=? AND body=? AND source_chapter_id=? AND source_version=?",uid,novel,name,body,source,sourceVersion);if(!duplicates.isEmpty())return owned(novel,duplicates.getFirst().get("id").toString(),false);}
        if(id==null) id=UUID.randomUUID().toString();long version=old==null?1:oldVersion(old)+1;
        if(old==null) jdbc.update("INSERT INTO writing_story_entry(id,owner_user_id,novel_id,kind,name,aliases,body,meaning,state,effective_from,effective_to,core,version,source_chapter_id,source_version,source_quote,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,uid,novel,kind,name,aliases.toString(),body,meaning,state,from,to,core,version,source,sourceVersion,quote);
        else jdbc.update("UPDATE writing_story_entry SET kind=?,name=?,aliases=?,body=?,meaning=?,state=?,effective_from=?,effective_to=?,core=?,version=?,source_chapter_id=?,source_version=?,source_quote=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND owner_user_id=? AND novel_id=?",kind,name,aliases.toString(),body,meaning,state,from,to,core,version,source,sourceVersion,quote,id,uid,novel);
        var result=owned(novel,id,false);
        try { jdbc.update("INSERT INTO writing_story_revision(entry_id,version,owner_user_id,novel_id,payload,created_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(3))",id,version,uid,novel,json.writeValueAsString(result)); }
        catch(Exception e) { throw new IllegalStateException("资料版本保存失败",e); }
        return result;
    }
    private long oldVersion(Map<String,Object> row){return ((Number)row.get("version")).longValue();}
    private String text(JsonNode req,String field,int max){String value=req.path(field).asText("").trim();if(value.isBlank()||value.length()>max)throw error(422,field+" 不能为空且最多 "+max+" 字");return value;}
    private BusinessException error(int code,String message){return new BusinessException(code,message,code);}
}
