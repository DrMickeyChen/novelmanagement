package com.novel.modules.agent.writing.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.novel.common.result.Result;
import com.novel.common.internal.InternalResults;
import com.novel.modules.agent.writing.application.*;
import com.novel.modules.agent.writing.infrastructure.client.WritingSyncClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/ai/writing/extensions/novels/{novel}")
public class WritingSynchronizationController {
    private final WritingSynchronization sync;private final WritingDerivations derivations;private final WritingAssetService assets;private final WritingSyncClient content;
    @GetMapping("/sync") public Result<?> config(@PathVariable long novel){return Result.success(sync.config(novel));}
    @PutMapping("/sync") public Result<?> configure(@PathVariable long novel,@RequestBody JsonNode req){return Result.success(sync.configure(novel,req));}
    @PostMapping("/sync") public Result<?> enqueue(@PathVariable long novel){assets.access(novel);return Result.success(InternalResults.unwrapBusiness(content.enqueue(novel),"writer sync enqueue"));}
    @GetMapping("/events") public Result<?> events(@PathVariable long novel){assets.access(novel);return Result.success(InternalResults.unwrapBusiness(content.events(novel),"writer sync events"));}
    @PostMapping("/events/{id}/retry") public Result<?> retry(@PathVariable long novel,@PathVariable long id){assets.access(novel);return Result.success(InternalResults.unwrapBusiness(content.retry(novel,id),"writer sync retry"));}
    @GetMapping("/recaps") public Result<?> recaps(@PathVariable long novel){return Result.success(sync.recaps(novel));}
    @PostMapping("/samples/{id}/analyze") public Result<?> analyze(@PathVariable long novel,@PathVariable String id,@RequestBody JsonNode req){return Result.success(derivations.submit(novel,id,req.path("acceptCost").asBoolean()));}
    @GetMapping("/jobs/{id}") public Result<?> job(@PathVariable long novel,@PathVariable String id){return Result.success(derivations.job(novel,id));}
    @GetMapping("/jobs") public Result<?> jobs(@PathVariable long novel){return Result.success(derivations.jobs(novel));}
}
