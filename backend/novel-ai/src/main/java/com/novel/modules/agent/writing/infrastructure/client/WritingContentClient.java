package com.novel.modules.agent.writing.infrastructure.client;

import com.novel.common.internal.WritingContent.*;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

@FeignClient(name="novel-content",contextId="writingContentClient",path="/internal/writing",configuration=WritingContentClient.Errors.class)
public interface WritingContentClient {
    @GetMapping("/access/{id}") Result<Boolean> access(@PathVariable("id") Long id,@RequestParam("nodeId") Long nodeId);
    @GetMapping("/snapshot/{id}") Result<Snapshot> snapshot(@PathVariable("id") Long id,@RequestParam("nodeId") Long nodeId);
    @PostMapping("/context/{id}") Result<Snapshot> context(@PathVariable("id") Long id,@RequestBody ContextRequest request);
    @GetMapping("/metadata/{id}") Result<java.util.Map<String,Object>> metadata(@PathVariable("id") Long id,@RequestParam("nodeId") Long nodeId);
    @GetMapping("/chapters/{id}") Result<java.util.List<java.util.Map<String,Object>>> chapters(@PathVariable("id") Long id,@RequestParam("nodeId") Long nodeId);
    @GetMapping("/chunks/{id}") Result<java.util.List<Source>> chunks(@PathVariable("id") Long id,@RequestParam("nodeId") Long nodeId,@RequestParam("sourceId") Long sourceId);
    @PostMapping("/validate/{id}") Result<java.util.List<Source>> validate(@PathVariable("id") Long id,@RequestParam("nodeId") Long nodeId,@RequestBody java.util.List<Source> hits);
    @PostMapping("/proposal/{novelId}") Result<java.util.Map<String,Object>> propose(@PathVariable("novelId") Long novelId,@RequestBody com.fasterxml.jackson.databind.JsonNode req);
    @PostMapping("/patch/{id}") Result<Applied> patch(@PathVariable("id") Long id,@RequestBody Patch patch);
    @PostMapping("/undo/{id}") Result<Applied> undo(@PathVariable("id") Long id,@RequestBody Undo undo);
    @PostMapping("/merge-preview/{id}") Result<MergeView> mergePreview(@PathVariable("id") Long id,@RequestBody MergePatch merge);
    @PostMapping("/merge/{id}") Result<Applied> merge(@PathVariable("id") Long id,@RequestBody MergePatch merge);
    @GetMapping("/result/{id}") Result<Applied> result(@PathVariable("id") Long id,@RequestParam("taskId") String taskId,@RequestParam("operation") String operation);

    /** Client-local configuration: never installs this decoder for unrelated Feign clients. */
    class Errors {
        @org.springframework.context.annotation.Bean
        public feign.codec.ErrorDecoder writingErrorDecoder() {
            return new WritingRemoteErrors();
        }
    }
}
