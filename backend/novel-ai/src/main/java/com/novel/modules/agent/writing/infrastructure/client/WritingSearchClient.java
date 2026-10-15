package com.novel.modules.agent.writing.infrastructure.client;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(name="novel-search",contextId="privateWritingSearch",path="/internal/writing-index",configuration=WritingContentClient.Errors.class)
public interface WritingSearchClient {
    @PutMapping Result<Void> put(@RequestBody List<PrivateChunk> chunks);
    @PostMapping("/search") Result<PrivateEvidence> search(@RequestBody PrivateRecall req);
    @DeleteMapping("/{novel}/chapters/{chapter}") Result<Void> deleteChapter(@PathVariable("novel") String novel,@PathVariable("chapter") String chapter);
    @DeleteMapping("/{novel}") Result<Void> delete(@PathVariable("novel") String novel);
}
