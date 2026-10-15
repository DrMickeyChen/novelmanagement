package com.novel.modules.agent.writing.infrastructure.client;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(name="novel-content",contextId="writingSyncContent",path="/internal/writing-sync",configuration=WritingContentClient.Errors.class)
public interface WritingSyncClient {
 @GetMapping("/owned/{novel}") Result<Boolean> owned(@PathVariable long novel);
 @PostMapping("/claim") Result<Map<String,Object>> claim();
 @PostMapping("/events/{id}/heartbeat") Result<Boolean> heartbeat(@PathVariable long id,@RequestBody Map<String,String> req);
 @PostMapping("/events/{id}/source") Result<Boolean> stamp(@PathVariable long id,@RequestBody Map<String,Object> req);
 @PostMapping("/events/{id}/finish") Result<Boolean> finish(@PathVariable long id,@RequestBody Map<String,String> req);
 @GetMapping("/events/{novel}") Result<List<Map<String,Object>>> events(@PathVariable long novel);
 @PostMapping("/events/{novel}/{id}/retry") Result<?> retry(@PathVariable long novel,@PathVariable long id);
 @GetMapping("/source/{chapter}") Result<Map<String,Object>> source(@PathVariable long chapter);
 @GetMapping("/catalog/{novel}") Result<List<Map<String,Object>>> catalog(@PathVariable long novel);
 @PostMapping("/enqueue/{novel}") Result<Map<String,Object>> enqueue(@PathVariable long novel);
 @PostMapping("/purge") Result<Map<String,Object>> purge(@RequestBody Map<String,Object> req);
 @PostMapping("/replay-deletion") Result<?> replayDeletion(@RequestBody Map<String,Object> req);
}
