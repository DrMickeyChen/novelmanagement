package com.novel.modules.agent.writing.api;

import com.novel.modules.agent.writing.context.WritingRetrieval;
import com.novel.modules.agent.writing.infrastructure.persistence.WritingStore;
import com.novel.modules.agent.writing.infrastructure.client.WritingContentClient;
import com.novel.modules.agent.writing.infrastructure.job.WritingIndexJobs;
import com.novel.common.internal.*;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.result.Result;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/ai/writing/resources")
public class WritingResourcesController {
    private final WritingContentClient content;private final WritingRetrieval retrieval;
    private final org.springframework.beans.factory.ObjectProvider<WritingIndexJobs> jobs;
    @GetMapping("/{chapter}") public Result<?> options(@PathVariable long chapter,@RequestParam long nodeId){return Result.success(InternalResults.unwrapBusiness(content.chapters(chapter,nodeId),"author chapter options"));}
    @PostMapping("/{chapter}/preview") public Result<?> preview(@PathVariable long chapter,@RequestBody ContextRequest req){return Result.success(InternalResults.unwrapBusiness(content.context(chapter,req),"writer context preview"));}
    @PostMapping("/{chapter}/index") public Result<?> build(@PathVariable long chapter,@RequestBody JsonNode req)throws Exception {
        var queue=jobs.getIfAvailable();if(queue==null)throw WritingStore.error(503,"请启用写作服务");return Result.success(queue.submit(chapter,req));
    }
    @GetMapping("/index-jobs/{id}") public Result<?> job(@PathVariable String id){var queue=jobs.getIfAvailable();if(queue==null)throw WritingStore.error(503,"请启用写作服务");return Result.success(queue.get(id));}
    @DeleteMapping("/{chapter}/index") public Result<?> delete(@PathVariable long chapter,@RequestParam long nodeId){retrieval.clear(chapter,nodeId);return Result.success();}
}
