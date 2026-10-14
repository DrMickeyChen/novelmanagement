package com.novel.internal.controller;

import com.baomidou.mybatisplus.core.conditions.query.*;
import com.novel.common.enums.*;
import com.novel.common.exception.BusinessException;
import com.novel.common.internal.*;
import com.novel.common.internal.BookKnowledge.*;
import com.novel.common.result.Result;
import com.novel.modules.content.entity.*;
import com.novel.modules.content.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/internal/book-finder/knowledge")
public class InternalBookKnowledgeController {
    private final InternalBookFinderController books;
    private final ChapterMapper chapters;
    private final ChapterContentMapper bodies;

    @GetMapping("/{id}")
    public Result<Snapshot> snapshot(@PathVariable String id, @RequestParam(defaultValue="10") int count) {
        if(count<1 || count>50) throw new BusinessException(400,"每次构建支持前 1～50 个已发布章节");
        var visible=InternalResults.unwrapBusiness(books.details(List.of(id)),"知识库可见性");
        if(visible.isEmpty()) return Result.success(null);
        var predicate=new LambdaQueryWrapper<Chapter>().eq(Chapter::getNovelId,Long.parseLong(id))
                .eq(Chapter::getStatus,ChapterStatus.PUBLISHED);
        int total=chapters.selectCount(predicate).intValue();
        var rows=chapters.selectList(predicate.orderByAsc(Chapter::getChapterNo).last("LIMIT "+count));
        var bodyById=new HashMap<Long,ChapterContent>();
        if(!rows.isEmpty()) bodies.selectList(new QueryWrapper<ChapterContent>()
                .select("chapter_id","SUBSTRING(content,1,20001) AS content","update_time")
                .in("chapter_id",rows.stream().map(Chapter::getId).toList())).forEach(b->bodyById.put(b.getChapterId(),b));
        var result=new ArrayList<ChapterText>();
        int remaining=100000; boolean truncated=false;
        var digest=new StringBuilder(visible.get(0).toString()).append('|').append(count).append('|').append(total);
        for(var chapter:rows) {
            var body=bodyById.get(chapter.getId());
            String raw=body==null || body.getContent()==null?"":body.getContent();
            int limit=Math.min(20000,remaining), size=Math.min(limit,raw.length());
            String text=raw.substring(0,size);
            boolean cut=size<raw.length(); truncated|=cut;
            String version=Objects.toString(chapter.getUpdateTime(),"")+":"+(body==null?"":Objects.toString(body.getUpdateTime(),""));
            digest.append('|').append(chapter.getId()).append('|').append(chapter.getChapterNo()).append('|')
                    .append(chapter.getTitle()).append('|').append(version).append('|').append(text);
            result.add(new ChapterText(chapter.getId().toString(),chapter.getChapterNo(),chapter.getTitle(),text,version,cut));
            remaining-=size;
            if(remaining==0) { truncated|=result.size()<rows.size(); break; }
        }
        // Recheck publication after fetching bodies; concurrent unpublishing must fail closed.
        if(InternalResults.unwrapBusiness(books.details(List.of(id)),"知识库最终可见性").isEmpty()) return Result.success(null);
        return Result.success(new Snapshot(visible.get(0),hash(digest.toString()),count,total,result,truncated));
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
