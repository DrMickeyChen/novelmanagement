package com.novel.modules.agent.bookfinder.client;

import com.novel.common.internal.BookFinderBook;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@FeignClient(name = "novel-content", contextId = "agentBookDetails", url = "${novel.book-finder.content-url:}")
public interface BookFinderContentClient {
    @GetMapping("/internal/book-finder/knowledge/{id}")
    Result<com.novel.common.internal.BookKnowledge.Snapshot> snapshot(@PathVariable("id") String id, @RequestParam("count") int count);
    @GetMapping("/internal/book-finder/categories")
    Result<List<String>> categories();
    @PostMapping("/internal/book-finder/details")
    Result<List<BookFinderBook>> details(@RequestBody List<String> ids);
}
