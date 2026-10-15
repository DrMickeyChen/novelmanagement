package com.novel.internal.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.internal.WritingText;
import com.novel.common.internal.WritingMerge;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.Result;
import com.novel.common.security.UserContext;
import com.novel.common.enums.ChapterStatus;
import com.novel.modules.content.dto.ChapterSaveDTO;
import com.novel.modules.content.entity.*;
import com.novel.modules.content.mapper.*;
import com.novel.modules.content.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/internal/writing")
public class InternalWritingController {
    private final NovelAccessGuard guard;
    private final ChapterMapper chapters;
    private final ChapterContentMapper bodies;
    private final ChapterService service;
    private final JdbcTemplate jdbc;

    /** Viewing history requires ownership, not a completed outline. */
    @GetMapping("/access/{id}")
    public Result<Boolean> access(@PathVariable Long id,@RequestParam Long nodeId) {
        Chapter chapter=guard.requireOwnedChapter(id);
        var node=guard.requireOwnedOutlineNode(nodeId);
        if(!Objects.equals(node.getNovelId(),chapter.getNovelId()) || !Objects.equals(node.getChapterId(),id))
            throw error(404,"章节与大纲归属不一致");
        return Result.success(true);
    }

    @GetMapping("/snapshot/{id}") @Transactional(readOnly=true)
    public Result<Snapshot> snapshot(@PathVariable Long id, @RequestParam Long nodeId) {
        Chapter c=guard.requireOwnedChapter(id);
        var node=guard.requireOwnedOutlineNode(nodeId);
        if(!Objects.equals(node.getNovelId(),c.getNovelId()) || !Objects.equals(node.getChapterId(),id))
            throw error(422,"大纲必须绑定当前章节");
        if(node.getContent()==null || node.getContent().isBlank()) throw error(422,"请填写当前章节剧情笔记");
        var previous=chapters.selectList(new LambdaQueryWrapper<Chapter>().eq(Chapter::getNovelId,c.getNovelId())
                .lt(Chapter::getChapterNo,c.getChapterNo()).in(Chapter::getStatus,ChapterStatus.DRAFT,ChapterStatus.REJECTED,ChapterStatus.PUBLISHED)
                .orderByDesc(Chapter::getChapterNo).last("LIMIT 3"));
        var sources=new ArrayList<Source>();
        for(var p:previous) {
            String raw=body(p.getId()); int start=Math.max(0,raw.length()-2500);
            if(start>0 && Character.isLowSurrogate(raw.charAt(start))) start++;
            sources.add(new Source("chapter:"+p.getId(),p.getId().toString(),p.getChapterNo(),p.getTitle(),
                    p.getContentVersion(),start,raw.length(),start>0,raw.substring(start)));
        }
        Collections.reverse(sources);
        String outline=node.getTitle()+"\n"+node.getContent();
        return Result.success(new Snapshot(id.toString(),c.getNovelId().toString(),c.getChapterNo(),c.getTitle(),
                c.getContentVersion(),body(id),nodeId.toString(),outline,WritingText.hash(outline),sources));
    }

    @PostMapping("/patch/{id}") @Transactional
    public Result<Applied> patch(@PathVariable Long id,@RequestBody Patch patch) {
        var c=guard.requireOwnedChapterForUpdate(id);
        validateKey(patch.key());
        String hash=WritingText.hash(patch.toString());
        Applied existing=existing(id,patch.taskId(),"ADOPT",patch.key(),hash);
        if(existing!=null) return Result.success(existing);
        if(c.getStatus()==ChapterStatus.PENDING) throw error(409,"章节正在审核，不能采纳正文");
        if(!Objects.equals(c.getContentVersion(),patch.expectedVersion())) throw error(409,"章节已变化，请保留草稿并重新选择");
        if(patch.replacement()==null || patch.replacement().length()>20000) throw error(422,"草稿长度无效");
        String before=body(id);
        String next=WritingText.replace(before,patch.start(),patch.end(),patch.original(),patch.replacement());
        save(c,next);
        return Result.success(record(c,patch.taskId(),"ADOPT",patch.key(),hash,before,next));
    }

    @PostMapping("/merge-preview/{id}") @Transactional(readOnly=true)
    public Result<MergeView> mergePreview(@PathVariable Long id,@RequestBody MergePatch req){
        var c=guard.requireOwnedChapter(id);
        return Result.success(new MergeView(c.getContentVersion(),WritingMerge.preview(req.baseContent(),body(id),req.start(),req.end(),req.original(),req.replacement())));
    }
    @PostMapping("/merge/{id}") @Transactional
    public Result<Applied> merge(@PathVariable Long id,@RequestBody MergePatch req){
        var c=guard.requireOwnedChapterForUpdate(id);validateKey(req.key());String hash=WritingText.hash(req.toString());
        var previous=existing(id,req.taskId(),"ADOPT",req.key(),hash);if(previous!=null)return Result.success(previous);
        if(c.getStatus()==ChapterStatus.PENDING||c.getContentVersion()!=req.expectedVersion())throw error(409,"当前正文或审核状态已变化，请重新预览合并");
        String before=body(id);var preview=WritingMerge.preview(req.baseContent(),before,req.start(),req.end(),req.original(),req.replacement());String next;
        if(req.approveResolved()){
            next=req.resolved();if(next==null||next.length()>100000||next.contains("<<<<<<<")||next.contains(">>>>>>>"))throw error(422,"请提交完整且不含冲突标记的人工解决正文");
        }else {if(!preview.clean())throw error(409,"存在合并冲突，需要作者明确解决");next=preview.merged();}
        save(c,next);return Result.success(record(c,req.taskId(),"ADOPT",req.key(),hash,before,next));
    }

    @PostMapping("/undo/{id}") @Transactional
    public Result<Applied> undo(@PathVariable Long id,@RequestBody Undo req) {
        var c=guard.requireOwnedChapterForUpdate(id); validateKey(req.key());
        String hash=WritingText.hash(req.toString());
        Applied existing=existing(id,req.taskId(),"UNDO",req.key(),hash);
        if(existing!=null) return Result.success(existing);
        var rows=jdbc.queryForList("SELECT * FROM chapter_ai_adoption WHERE id=? AND task_id=? AND chapter_id=? AND owner_user_id=? AND operation='ADOPT' AND created_at>DATE_SUB(UTC_TIMESTAMP(),INTERVAL 90 DAY)",
                req.adoptionId(),req.taskId(),id,UserContext.requireUserId());
        if(rows.isEmpty()) throw error(404,"采纳记录不存在或已过撤销期限");
        var old=rows.getFirst();
        if(c.getStatus()==ChapterStatus.PENDING || c.getStatus()==ChapterStatus.PUBLISHED) throw error(409,"审核或发布后不能直接撤销");
        if(!Objects.equals(c.getContentVersion(),req.expectedVersion()) || c.getContentVersion()!=((Number)old.get("after_version")).longValue()
                || !body(id).equals(old.get("result_content"))) throw error(409,"采纳后已有其他修改，不能自动撤销");
        String before=body(id),next=(String)old.get("before_content"); save(c,next);
        return Result.success(record(c,req.taskId(),"UNDO",req.key(),hash,before,next));
    }

    @GetMapping("/result/{id}")
    public Result<Applied> result(@PathVariable Long id,@RequestParam String taskId,@RequestParam String operation) {
        guard.requireOwnedChapter(id);
        var rows=jdbc.queryForList("SELECT * FROM chapter_ai_adoption WHERE owner_user_id=? AND chapter_id=? AND task_id=? AND operation=?",
                UserContext.requireUserId(),id,taskId,operation);
        return Result.success(rows.isEmpty()?null:applied(rows.getFirst()));
    }

    private Applied existing(Long chapter,String task,String op,String key,String hash) {
        var rows=jdbc.queryForList("SELECT * FROM chapter_ai_adoption WHERE owner_user_id=? AND chapter_id=? AND (task_id=? AND operation=? OR command_key=? AND operation=?)",
                UserContext.requireUserId(),chapter,task,op,key,op);
        if(rows.isEmpty()) return null;
        var row=rows.getFirst();
        if(!hash.equals(row.get("request_hash")) || !key.equals(row.get("command_key"))) throw error(409,"此任务已执行该操作，或幂等键被重复使用");
        return applied(row);
    }
    private Applied applied(Map<String,Object> row) {
        return new Applied((String)row.get("id"),row.get("chapter_id").toString(),((Number)row.get("after_version")).longValue(),
                (String)row.get("result_content"),(String)row.get("title"));
    }
    private void save(Chapter c,String text) {
        var dto=new ChapterSaveDTO();dto.setTitle(c.getTitle());dto.setContent(text);dto.setExpectedVersion(c.getContentVersion());
        service.saveContent(c.getId(),dto);
    }
    private Applied record(Chapter c,String task,String op,String key,String hash,String before,String after) {
        long version=chapters.selectById(c.getId()).getContentVersion(); String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO chapter_ai_adoption(id,owner_user_id,novel_id,chapter_id,task_id,operation,command_key,request_hash,before_version,after_version,before_content,result_content,title,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                id,UserContext.requireUserId(),c.getNovelId(),c.getId(),task,op,key,hash,c.getContentVersion(),version,before,after,c.getTitle());
        return new Applied(id,c.getId().toString(),version,after,c.getTitle());
    }
    private String body(Long id) { var row=bodies.selectById(id);return row==null?"":Objects.toString(row.getContent(),""); }
    private void validateKey(String key) {if(key==null || key.isBlank() || key.length()>100) throw error(422,"操作键无效");}
    private BusinessException error(int code,String message) {return new BusinessException(code,message,code);}
}
