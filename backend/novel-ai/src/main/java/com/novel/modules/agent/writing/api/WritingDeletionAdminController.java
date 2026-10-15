package com.novel.modules.agent.writing.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.novel.common.security.UserContext;
import com.novel.common.result.Result;
import com.novel.common.internal.InternalResults;
import com.novel.modules.agent.writing.infrastructure.persistence.WritingDeletionJournal;
import com.novel.modules.agent.writing.infrastructure.client.WritingSyncClient;
import com.novel.modules.agent.writing.domain.WritingTaskRules;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/admin/ai/writing/deletions")
public class WritingDeletionAdminController {
    private final WritingDeletionJournal journal;private final WritingSyncClient content;
    private void admin(){UserContext.requireUserId();if(!UserContext.hasRole("ADMIN"))throw WritingTaskRules.error(403,"仅管理员可操作删除恢复账本");}
    @PostMapping("/flush") public Result<?> flush()throws Exception{admin();return Result.success(journal.flush());}
    @PostMapping("/replay") public Result<?> replay(@RequestBody JsonNode req)throws Exception{admin();if(!req.path("confirmRestoreCleanup").asBoolean())throw WritingTaskRules.error(422,"需明确确认正在进行备份恢复后的删除清理");int count=0;for(var entry:journal.entries()){InternalResults.unwrapBusiness(content.replayDeletion(entry),"restored deletion replay");count++;}return Result.success(java.util.Map.of("queued",count,"notice","保持写作维护状态，待删除事件完成后重建共享索引并恢复访问"));}
}
