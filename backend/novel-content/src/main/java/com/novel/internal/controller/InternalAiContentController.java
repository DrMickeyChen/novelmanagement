package com.novel.internal.controller;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.novel.common.internal.*;
import com.novel.common.enums.AuditAction;
import com.novel.common.enums.AuditTargetType;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.*;
import com.novel.modules.content.entity.*;
import com.novel.modules.content.mapper.*;
import com.novel.modules.content.service.NovelAccessGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;
import java.util.*;
/** Internal token required by HeaderAuthenticationFilter. Author reads also enforce ownership. */
@RestController @RequiredArgsConstructor @RequestMapping("/internal/ai-content")
public class InternalAiContentController {
 private final NovelAccessGuard guard;
 private final ChapterMapper chapters;
 private final ChapterContentMapper contents;
 private final NovelMapper novels;
 private final OutlineNodeMapper outlines;
 private final AuditLogMapper audits;
 @GetMapping("/owned-novels/{id}") public Result<AiNovelDTO> ownedNovel(@PathVariable Long id) { return Result.success(novel(guard.requireOwned(id))); }
 @GetMapping("/owned-chapters/{id}") public Result<AiChapterDTO> ownedChapter(@PathVariable Long id) { return Result.success(chapter(guard.requireOwnedChapter(id))); }
 @GetMapping("/owned-outlines/{id}") public Result<AiOutlineDTO> ownedOutline(@PathVariable Long id) { return Result.success(outline(guard.requireOwnedOutlineNode(id))); }
 @GetMapping("/chapter-outlines/{id}") public Result<List<AiOutlineDTO>> chapterOutlines(@PathVariable Long id) {
  guard.requireOwnedChapter(id);
  // Only the first two are needed to detect ambiguity and select the smallest ID.
  return Result.success(outlines.selectList(Wrappers.<OutlineNode>lambdaQuery().eq(OutlineNode::getChapterId,id).orderByAsc(OutlineNode::getId).last("LIMIT 2")).stream().map(this::outline).toList());
 }
 @GetMapping("/chapter-range") public Result<List<AiChapterDTO>> range(@RequestParam Long novelId,@RequestParam int start,@RequestParam int end) {
  guard.requireOwned(novelId);
  if(start<1 || end<start) throw new BusinessException(ResultCode.PARAM_ERROR);
  List<Chapter> rows=chapters.selectList(Wrappers.<Chapter>lambdaQuery().eq(Chapter::getNovelId,novelId).between(Chapter::getChapterNo,start,end).orderByAsc(Chapter::getChapterNo).last("LIMIT 201"));
  if(rows.size()>200) throw new BusinessException(ResultCode.PARAM_ERROR,"单次最多压缩 200 章，请缩小范围");
  return Result.success(rows.stream().map(this::chapter).toList());
 }
 @PostMapping("/owned-contents") public Result<List<AiChapterContentDTO>> ownedContents(@RequestBody Collection<Long> ids) {
  validateIds(ids); for(Long id:ids) guard.requireOwnedChapter(id);
  List<AiChapterContentDTO> result=new ArrayList<>(); int length=0;
  // Bound the HTTP response and reject over-limit input instead of silently truncating prose.
  for(Long id:ids) {
   ChapterContent row=contents.selectById(id);
   if(row!=null) { length+=row.getContent()==null?0:row.getContent().length();
    if(length>8000) throw new BusinessException(ResultCode.PARAM_ERROR,"正文超过 8000 字，请缩小压缩范围");
    result.add(content(row)); }
  }
  return Result.success(result);
 }
 @PostMapping("/chapters") public Result<List<AiChapterDTO>> chapters(@RequestBody Collection<Long> ids) { validateIds(ids);return Result.success(ids.isEmpty()?List.of():chapters.selectByIds(ids).stream().map(this::chapter).toList()); }
 @PostMapping("/novels") public Result<List<AiNovelDTO>> novels(@RequestBody Collection<Long> ids) { validateIds(ids);return Result.success(ids.isEmpty()?List.of():novels.selectByIds(ids).stream().map(this::novel).toList()); }
 @GetMapping("/chapters/{id}") public Result<AiChapterDTO> chapter(@PathVariable Long id) {return Result.success(chapter(chapters.selectById(id)));}
 @GetMapping("/chapter-contents/{id}") public Result<AiChapterContentDTO> chapterContent(@PathVariable Long id) {
  if(chapters.selectById(id)==null) return Result.success(null);
  return Result.success(content(contents.selectById(id)));
 }
 @PostMapping("/dispositions") public Result<List<AiDispositionDTO>> dispositions(@RequestBody List<AiDispositionQuery> rows) {
  if(rows==null || rows.size()>200) throw new BusinessException(ResultCode.PARAM_ERROR);
  List<AiDispositionDTO> result=new ArrayList<>();
  for(AiDispositionQuery row:rows) {
   if(row==null || row.rowId()==null || row.targetType()==null || row.targetId()==null || row.since()==null) throw new BusinessException(ResultCode.PARAM_ERROR);
   AuditTargetType type=AuditTargetType.fromCode(row.targetType());
   // Chapter verdicts belong to a submission. Include a human decision made while
   // the model was still running, but never reuse a decision from an older submission.
   java.time.LocalDateTime since=row.since();
   if(type==AuditTargetType.CHAPTER) {
    List<AuditLog> submitted=audits.selectList(Wrappers.<AuditLog>lambdaQuery().eq(AuditLog::getTargetType,type).eq(AuditLog::getTargetId,row.targetId()).eq(AuditLog::getAction,AuditAction.SUBMIT).le(AuditLog::getCreateTime,row.since()).orderByDesc(AuditLog::getId).last("LIMIT 1"));
    if(!submitted.isEmpty()) since=submitted.get(0).getCreateTime();
   } else if(type==AuditTargetType.COMMENT) since=java.time.LocalDateTime.of(1970,1,1,0,0);
   List<AuditLog> found=audits.selectList(Wrappers.<AuditLog>lambdaQuery().eq(AuditLog::getTargetType,type).eq(AuditLog::getTargetId,row.targetId()).ge(AuditLog::getCreateTime,since).in(AuditLog::getAction,List.of(AuditAction.APPROVE,AuditAction.REJECT)).orderByAsc(AuditLog::getId).last("LIMIT 1"));
   if(!found.isEmpty()) {AuditLog a=found.get(0);result.add(new AiDispositionDTO(row.rowId(),a.getAction()==AuditAction.REJECT?1:2,a.getOperatorId(),a.getCreateTime()));}
  }
  return Result.success(result);
 }
 private void validateIds(Collection<Long> ids) { if(ids==null||ids.size()>200||ids.stream().anyMatch(Objects::isNull))throw new BusinessException(ResultCode.PARAM_ERROR); }
 private AiNovelDTO novel(Novel row) {if(row==null)return null; AiNovelDTO dto=new AiNovelDTO();BeanUtils.copyProperties(row,dto);return dto;}
 private AiChapterDTO chapter(Chapter row) {if(row==null)return null; AiChapterDTO dto=new AiChapterDTO();BeanUtils.copyProperties(row,dto);return dto;}
 private AiOutlineDTO outline(OutlineNode row) {if(row==null)return null; AiOutlineDTO dto=new AiOutlineDTO();BeanUtils.copyProperties(row,dto);return dto;}
 private AiChapterContentDTO content(ChapterContent row) {if(row==null)return null; AiChapterContentDTO dto=new AiChapterContentDTO();BeanUtils.copyProperties(row,dto);return dto;}
}
