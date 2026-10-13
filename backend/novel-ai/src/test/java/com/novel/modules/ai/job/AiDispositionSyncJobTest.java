package com.novel.modules.ai.job;
import com.novel.modules.ai.mapper.AiAuditResultMapper;
import com.novel.modules.ai.integration.AiContentLookup;
import com.novel.modules.ai.entity.AiAuditResult;
import com.novel.common.internal.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiDispositionSyncJobTest {
 private static <T> T mock(Class<T> t){return org.mockito.Mockito.mock(t,withSettings().mockMaker("mock-maker-subclass"));}
 private final AiAuditResultMapper mapper=mock(AiAuditResultMapper.class);
 private final AiContentLookup content=mock(AiContentLookup.class);
 private final AiDispositionSyncJob job=new AiDispositionSyncJob(mapper,content);
 private AiAuditResult pending(){AiAuditResult r=new AiAuditResult();r.setId(99L);r.setStatus(0);r.setTargetType(2);r.setTargetId(88L);r.setCreateTime(LocalDateTime.of(2026,1,1,0,0));return r;}
 @Test void failureLeavesPendingRowsUntouchedAndRetries(){
  when(mapper.selectList(any())).thenReturn(List.of(pending()));
  when(content.dispositions(anyList())).thenThrow(new IllegalStateException("offline"));
  job.sync();job.sync();verify(content,times(2)).dispositions(anyList());verify(mapper,never()).update(isNull(),any());
 }
 @Test void missingDispositionIsNotInvented(){
  when(content.dispositions(anyList())).thenReturn(List.of());job.reconcile(List.of(pending()));verifyNoInteractions(mapper);
 }
 @Test void alreadyDisposedRowsAreNotReconciled(){
  AiAuditResult r=pending();r.setStatus(2);when(content.dispositions(List.of())).thenReturn(List.of());job.reconcile(List.of(r));verify(content).dispositions(List.of());verifyNoInteractions(mapper);
 }
}
