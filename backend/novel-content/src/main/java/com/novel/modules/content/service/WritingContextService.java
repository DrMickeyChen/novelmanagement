package com.novel.modules.content.service;

import com.fasterxml.jackson.databind.*;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.internal.WritingText;
import com.novel.common.exception.BusinessException;
import com.novel.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor
public class WritingContextService {
    private final NovelAccessGuard guard;private final WritingLibraryService library;
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public Map<String,Object> metadata(long chapter,long node){var c=bound(chapter,node);return Map.of("novelId",c.getNovelId().toString(),"chapterNo",c.getChapterNo());}
    public List<Map<String,Object>> chapters(long chapter,long node) {
        var c=bound(chapter,node);
        return jdbc.queryForList("SELECT id,chapter_no AS chapterNo,title,content_version AS version FROM chapter WHERE novel_id=? AND deleted=0 AND chapter_no<? AND status IN(0,2,4) ORDER BY chapter_no DESC",c.getNovelId(),c.getChapterNo());
    }
    private com.novel.modules.content.entity.Chapter bound(long chapter,long node) {
        var c=guard.requireOwnedChapter(chapter);var n=guard.requireOwnedOutlineNode(node);
        if(!Objects.equals(n.getNovelId(),c.getNovelId())||!Objects.equals(n.getChapterId(),chapter)) throw error(404,"章节与剧情笔记归属不一致");return c;
    }
    @Transactional(readOnly=true)
    public Snapshot snapshot(long chapter,ContextRequest req) {
        var c=bound(chapter,req.nodeId());var n=guard.requireOwnedOutlineNode(req.nodeId());
        if(n.getContent()==null||n.getContent().isBlank())throw error(422,"请填写当前章节剧情笔记");
        var chosen=req.chapterIds()==null?List.<Long>of():req.chapterIds();var manual=req.settingIds()==null?List.<String>of():req.settingIds();
        if(chosen.size()>8||manual.size()>20||req.goal()!=null&&req.goal().length()>1200)throw error(422,"最多额外选择 8 章和 20 个资料条目");
        var notes=new ArrayList<Note>();var visited=new HashSet<Long>();var current=n;
        while(current!=null) {
            if(!visited.add(current.getId())||visited.size()>20)throw error(422,"剧情笔记层级异常");
            String type=current.getNodeType().getCode()==1?"BOOK":current.getNodeType().getCode()==2?"VOLUME":current.getId().equals(n.getId())?"CHAPTER":"PLAN";
            if(Set.of("BOOK","VOLUME","CHAPTER").contains(type)) {
                String text=Objects.toString(current.getContent(),"");notes.add(new Note("outline:"+current.getId(),type,current.getTitle(),text,WritingText.hash(current.getTitle()+"\n"+text)));
            }
            current=current.getParentId()==null?null:guard.requireNodeInNovel(c.getNovelId(),current.getParentId());
        }
        var catalog=chapters(chapter,req.nodeId());var allowed=new LinkedHashMap<Long,Map<String,Object>>();
        for(var row:catalog)allowed.put(((Number)row.get("id")).longValue(),row);
        var ids=new LinkedHashSet<Long>();
        for(var row:catalog.stream().limit(3).toList())ids.add(((Number)row.get("id")).longValue());
        for(var id:chosen){if(!allowed.containsKey(id))throw error(422,"只能选择当前作品更早且可读取的章节");ids.add(id);}
        String currentBody=body(chapter);
        String trigger=Objects.toString(req.goal(),"")+"\n"+currentBody+"\n"+notes.stream().map(Note::text).reduce("",(a,b)->a+"\n"+b);
        var sources=new ArrayList<Source>();var coverage=new ArrayList<Coverage>();int budget=14000;
        for(long id:ids) {
            var row=allowed.get(id);String raw=body(id);
            int allocation=Math.min(budget,raw.length()<=5000?raw.length():Math.min(4500,budget));
            var parts=select(raw,allocation,Objects.toString(req.goal(),""));int read=0;
            for(var range:parts){int from=range[0],end=range[1];String text=raw.substring(from,end);read+=text.length();sources.add(new Source("chapter:"+id+":"+from,Long.toString(id),((Number)row.get("chapterNo")).intValue(),Objects.toString(row.get("title"),""),((Number)row.get("version")).longValue(),from,end,text.length()!=raw.length(),text));}
            budget-=read;coverage.add(new Coverage(Long.toString(id),((Number)row.get("chapterNo")).intValue(),Objects.toString(row.get("title"),""),((Number)row.get("version")).longValue(),raw.length(),read,read==raw.length()?"全文":read==0?"上下文预算不足，未读取":"按目标选取分段原文，未读取部分不作为事实依据"));
        }
        trigger+="\n"+sources.stream().map(Source::text).reduce("",(a,b)->a+"\n"+b);
        var entries=library.list(c.getNovelId());var settings=new ArrayList<Setting>();int settingBudget=6000;
        for(var id:manual)if(entries.stream().noneMatch(e->e.get("id").equals(id)))throw error(404,"所选资料不属于当前作品");
        entries.sort(Comparator.comparing((Map<String,Object> e)->!truth(e.get("core"))).thenComparing(e->!manual.contains(e.get("id"))));
        for(var e:entries) {
            int from=((Number)e.get("effective_from")).intValue();Integer to=e.get("effective_to")==null?null:((Number)e.get("effective_to")).intValue();
            boolean valid=e.get("state").equals("APPROVED")&&from<=c.getChapterNo()&&(to==null||to>=c.getChapterNo());
            if(e.get("source_chapter_id")!=null) {
                var versions=jdbc.queryForList("SELECT content_version,chapter_no FROM chapter WHERE id=? AND novel_id=? AND deleted=0 AND status IN(0,2,4)",e.get("source_chapter_id"),c.getNovelId());
                valid &= !versions.isEmpty()&&Objects.equals(((Number)versions.getFirst().get("content_version")).longValue(),((Number)e.get("source_version")).longValue())&&((Number)versions.getFirst().get("chapter_no")).intValue()<c.getChapterNo();
            }
            if(manual.contains(e.get("id"))&&!valid)throw error(409,"所选资料尚未确认、已过期或未到生效章节");
            if(!valid)continue;
            boolean core=truth(e.get("core")),selected=manual.contains(e.get("id"));String name=e.get("name").toString();boolean matched=trigger.contains(name);
            try {for(var alias:json.readTree(e.get("aliases").toString()))matched|=trigger.contains(alias.asText());}catch(Exception ex){throw new IllegalStateException(ex);}
            if(!core&&!selected&&!matched)continue;String text=e.get("body").toString();
            if(text.length()>settingBudget){if(core||selected)throw error(422,"固定或手动资料超出 6000 字预算，请精简");continue;}
            settingBudget-=text.length();settings.add(new Setting(e.get("id").toString(),e.get("kind").toString(),name,text,e.get("meaning").toString(),((Number)e.get("version")).longValue(),from,to,core,core?"核心规则，始终参考":selected?"作者手动引用":"名称或别名触发"));
        }
        String outline=n.getTitle()+"\n"+n.getContent();
        return new Snapshot(Long.toString(chapter),c.getNovelId().toString(),c.getChapterNo(),c.getTitle(),c.getContentVersion(),currentBody,Long.toString(req.nodeId()),outline,WritingText.hash(outline),sources,notes,settings,coverage,WritingText.hash(entries.stream().filter(e->e.get("state").equals("APPROVED")).toList().toString()));
    }
    /** Exact source rehydration before external reranking/model access. Never trust an ES body. */
    @Transactional(readOnly=true)
    public List<Source> validate(long chapter,long node,List<Source> hits) {
        var c=bound(chapter,node);if(hits==null||hits.size()>30)throw error(422,"证据数量无效");var result=new ArrayList<Source>();
        for(var hit:hits) {
            if(!hit.id().equals("chapter:"+hit.chapterId()+":"+hit.start()))continue;
            var rows=jdbc.queryForList("SELECT content_version,chapter_no,title FROM chapter WHERE id=? AND novel_id=? AND deleted=0 AND chapter_no<? AND status IN(0,2,4)",hit.chapterId(),c.getNovelId(),c.getChapterNo());
            if(rows.isEmpty()||((Number)rows.getFirst().get("content_version")).longValue()!=hit.version())continue;
            String raw=body(Long.parseLong(hit.chapterId()));if(hit.start()<0||hit.end()>raw.length()||hit.end()<=hit.start()||hit.end()-hit.start()>2000)continue;
            String exact=raw.substring(hit.start(),hit.end());if(!exact.equals(hit.text()))continue;
            result.add(new Source(hit.id(),hit.chapterId(),((Number)rows.getFirst().get("chapter_no")).intValue(),rows.getFirst().get("title").toString(),hit.version(),hit.start(),hit.end(),true,exact));
        }return result;
    }
    @Transactional(readOnly=true)
    public List<Source> chunks(long chapter,long node,long sourceId) {
        if(chapters(chapter,node).stream().noneMatch(r->r.get("id").toString().equals(Long.toString(sourceId))))throw error(404,"无权读取该章节");
        var c=guard.requireOwnedChapter(sourceId);String raw=body(sourceId);if(raw.length()>150000)throw error(422,"单章超过索引上限 15 万字");var result=new ArrayList<Source>();
        for(int start=0;start<raw.length();){int end=Math.min(start+1200,raw.length());if(end<raw.length()&&Character.isHighSurrogate(raw.charAt(end-1)))end--;result.add(new Source("chapter:"+sourceId+":"+start,Long.toString(sourceId),c.getChapterNo(),c.getTitle(),c.getContentVersion(),start,end,true,raw.substring(start,end)));start=end;}
        return result;
    }
    private List<int[]> select(String text,int budget,String query){
        if(budget<=0||text.isEmpty())return List.of();if(text.length()<=budget)return List.of(new int[]{0,text.length()});
        var candidates=new ArrayList<int[]>();for(int from=0;from<text.length();){int end=Math.min(from+1500,text.length());if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;candidates.add(new int[]{from,end});from=end;}
        candidates.sort(Comparator.<int[]>comparingInt(r->{int score=r[0]==0?2:r[1]==text.length()?3:0;String part=text.substring(r[0],r[1]);for(int i=0;i+2<=query.length();i++)if(part.contains(query.substring(i,i+2)))score++;return score;}).reversed());
        var chosen=new ArrayList<int[]>();for(var part:candidates){if(part[1]-part[0]<=budget){chosen.add(part);budget-=part[1]-part[0];}}chosen.sort(Comparator.comparingInt(r->r[0]));return chosen;
    }
    private String body(long id){var rows=jdbc.queryForList("SELECT content FROM chapter_content WHERE chapter_id=?",id);return rows.isEmpty()?"":Objects.toString(rows.getFirst().get("content"),"");}
    private boolean truth(Object value){return value instanceof Boolean b?b:((Number)value).intValue()!=0;}
    private BusinessException error(int code,String message){return new BusinessException(code,message,code);}
}
