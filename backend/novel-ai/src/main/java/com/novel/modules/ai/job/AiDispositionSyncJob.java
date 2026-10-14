package com.novel.modules.ai.job;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.novel.modules.ai.entity.AiAuditResult;
import com.novel.modules.ai.mapper.AiAuditResultMapper;
import com.novel.modules.ai.integration.AiContentLookup;
import com.novel.common.internal.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;
/** Retryable reconciliation: human audit remains authoritative and never depends on AI availability. */
@Slf4j @Component @RequiredArgsConstructor
public class AiDispositionSyncJob {
 private final AiAuditResultMapper mapper;
 private final AiContentLookup content;
 private long cursor;
 @Scheduled(fixedDelayString="${novel.ai.disposition-sync-ms:5000}",initialDelayString="${novel.ai.disposition-sync-initial-ms:10000}")
 public synchronized void sync() {
  try {
   List<AiAuditResult> rows=mapper.selectList(Wrappers.<AiAuditResult>lambdaQuery().eq(AiAuditResult::getStatus,0).gt(AiAuditResult::getId,cursor).orderByAsc(AiAuditResult::getId).last("LIMIT 100"));
   if(rows.isEmpty()){cursor=0;return;}
   reconcile(rows);cursor=rows.get(rows.size()-1).getId();
  } catch(Exception e){ log.warn("AI 人工审核状态同步失败，下轮重试",e); }
 }
 public void reconcile(List<AiAuditResult> rows) {
  List<AiDispositionQuery> queries=rows.stream().filter(r->r.getStatus()!=null&&r.getStatus()==0).map(r->new AiDispositionQuery(r.getId(),r.getTargetType(),r.getTargetId(),r.getCreateTime())).toList();
  for(AiDispositionDTO d:content.dispositions(queries)) {
   // Row ID and version timestamp prevent delayed results from disposing a newer verdict.
   AiAuditResult row=rows.stream().filter(r->Objects.equals(r.getId(),d.rowId())).findFirst().orElseThrow();
   mapper.update(null,Wrappers.<AiAuditResult>lambdaUpdate().eq(AiAuditResult::getId,row.getId()).eq(AiAuditResult::getStatus,0).eq(AiAuditResult::getCreateTime,row.getCreateTime()).set(AiAuditResult::getStatus,d.status()).set(AiAuditResult::getAuditUserId,d.operatorId()).set(AiAuditResult::getAuditTime,d.at()));
  }
 }
}
