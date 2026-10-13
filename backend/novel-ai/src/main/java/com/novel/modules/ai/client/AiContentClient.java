package com.novel.modules.ai.client;
import com.novel.common.internal.*;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@FeignClient(name="novel-content", contextId="aiContentClient", path="/internal/ai-content")
public interface AiContentClient {
 @GetMapping("/owned-novels/{id}") Result<AiNovelDTO> ownedNovel(@PathVariable("id") Long id);
 @GetMapping("/owned-chapters/{id}") Result<AiChapterDTO> ownedChapter(@PathVariable("id") Long id);
 @GetMapping("/owned-outlines/{id}") Result<AiOutlineDTO> ownedOutline(@PathVariable("id") Long id);
 @GetMapping("/chapter-outlines/{id}") Result<List<AiOutlineDTO>> chapterOutlines(@PathVariable("id") Long id);
 @GetMapping("/chapter-range") Result<List<AiChapterDTO>> range(@RequestParam("novelId") Long novelId, @RequestParam("start") int start, @RequestParam("end") int end);
 @PostMapping("/owned-contents") Result<List<AiChapterContentDTO>> ownedContents(@RequestBody Collection<Long> ids);
 @PostMapping("/chapters") Result<List<AiChapterDTO>> chapters(@RequestBody Collection<Long> ids);
 @PostMapping("/novels") Result<List<AiNovelDTO>> novels(@RequestBody Collection<Long> ids);
 @GetMapping("/chapters/{id}") Result<AiChapterDTO> chapter(@PathVariable("id") Long id);
 @GetMapping("/chapter-contents/{id}") Result<AiChapterContentDTO> chapterContent(@PathVariable("id") Long id);
 @PostMapping("/dispositions") Result<List<AiDispositionDTO>> dispositions(@RequestBody List<AiDispositionQuery> rows);
}
