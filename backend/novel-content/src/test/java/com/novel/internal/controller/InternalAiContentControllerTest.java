package com.novel.internal.controller;
import com.novel.modules.content.mapper.*;
import com.novel.modules.content.service.NovelAccessGuard;
import com.novel.modules.content.entity.*;
import com.novel.common.exception.BusinessException;
import com.novel.common.result.ResultCode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class InternalAiContentControllerTest {
 private static <T> T mock(Class<T> t){return org.mockito.Mockito.mock(t,withSettings().mockMaker("mock-maker-subclass"));}
 private final NovelAccessGuard guard=mock(NovelAccessGuard.class);
 private final ChapterMapper chapters=mock(ChapterMapper.class);
 private final ChapterContentMapper contents=mock(ChapterContentMapper.class);
 private final NovelMapper novels=mock(NovelMapper.class);
 private final OutlineNodeMapper outlines=mock(OutlineNodeMapper.class);
 private final AuditLogMapper audits=mock(AuditLogMapper.class);
 private final InternalAiContentController controller=new InternalAiContentController(guard,chapters,contents,novels,outlines,audits);
 @Test void ownershipFailurePreventsReadingDraftBody(){
  when(guard.requireOwnedChapter(99L)).thenThrow(new BusinessException(ResultCode.CHAPTER_NOT_YOURS));
  assertThrows(BusinessException.class,()->controller.ownedContents(List.of(99L)));
  verifyNoInteractions(contents);
 }
 @Test void oversizedBatchRejectedBeforeDatabaseAccess(){
  assertThrows(BusinessException.class,()->controller.chapters(Collections.nCopies(201,99L)));
  verifyNoInteractions(chapters);
 }
 @Test void oversizedSummaryBodyFailsInsteadOfTruncating(){
  ChapterContent row=new ChapterContent();row.setChapterId(99L);row.setContent("?".repeat(8001));when(contents.selectById(99L)).thenReturn(row);
  assertThrows(BusinessException.class,()->controller.ownedContents(List.of(99L)));
 }
 @Test void logicallyDeletedChapterDoesNotExposeOrphanedBody(){
  when(chapters.selectById(99L)).thenReturn(null);
  assertNull(controller.chapterContent(99L).getData());verifyNoInteractions(contents);
 }
}
