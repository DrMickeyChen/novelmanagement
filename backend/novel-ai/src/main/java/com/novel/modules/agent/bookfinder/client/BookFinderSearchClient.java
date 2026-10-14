package com.novel.modules.agent.bookfinder.client;

import com.novel.common.internal.*;
import com.novel.common.result.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@FeignClient(name = "novel-search", contextId = "agentBookSearch", url = "${novel.book-finder.search-url:}")
public interface BookFinderSearchClient {
    @PostMapping("/internal/book-finder/knowledge/recall")
    Result<BookKnowledge.Recall> recall(@RequestBody BookKnowledge.RecallQuery query);
    @PostMapping("/internal/book-finder/knowledge/metadata")
    Result<List<BookKnowledge.Document>> knowledgeMetadata(@RequestBody List<String> ids);
    @PutMapping("/internal/book-finder/knowledge")
    Result<Void> putKnowledge(@RequestBody BookKnowledge.Document document);
    @PostMapping("/internal/book-finder/knowledge/batch")
    Result<List<BookKnowledge.Document>> knowledge(@RequestBody List<String> ids);
    @GetMapping("/internal/book-finder/knowledge")
    Result<List<BookKnowledge.Document>> knowledgeCatalog();
    @DeleteMapping("/internal/book-finder/knowledge/{id}")
    Result<Void> deleteKnowledge(@PathVariable("id") String id);
    @PostMapping("/internal/book-finder/search")
    Result<List<BookFinderBook>> search(@RequestBody BookFinderQuery query);
}
