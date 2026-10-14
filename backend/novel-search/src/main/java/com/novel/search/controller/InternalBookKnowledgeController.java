package com.novel.search.controller;
import com.novel.common.internal.*;
import com.novel.common.internal.BookKnowledge.Document;
import com.novel.common.result.Result;
import com.novel.search.service.BookKnowledgeIndex;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.io.IOException;

@RestController @RequiredArgsConstructor @RequestMapping("/internal/book-finder/knowledge")
public class InternalBookKnowledgeController {
    private final BookKnowledgeIndex index;
    @PostMapping("/recall") public Result<BookKnowledge.Recall> recall(@RequestBody BookKnowledge.RecallQuery query) throws IOException {
        return Result.success(index.recall(query));
    }
    @PostMapping("/metadata") public Result<List<Document>> metadata(@RequestBody List<String> ids) throws IOException {
        validate(ids); return Result.success(index.metadata(ids));
    }
    @PutMapping public Result<Void> put(@RequestBody Document doc) throws IOException {
        validate(List.of(doc.novelId()));
        if(doc.chunks()==null || doc.chunks().size()>200 || doc.characterCount()>100000)
            throw new com.novel.common.exception.BusinessException(400,"知识文档超过构建上限");
        index.put(doc); return Result.success();
    }
    @PostMapping("/batch") public Result<List<Document>> batch(@RequestBody List<String> ids) throws IOException {
        validate(ids); return Result.success(index.get(ids));
    }
    @GetMapping public Result<List<Document>> catalog() throws IOException { return Result.success(index.catalog()); }
    @DeleteMapping("/{id}") public Result<Void> delete(@PathVariable String id) throws IOException {
        validate(List.of(id)); index.delete(id); return Result.success();
    }
    private void validate(List<String> ids) {
        if(ids==null || ids.size()>20) throw new com.novel.common.exception.BusinessException(400,"最多 20 本书");
        new BookFinderQuery(null,null,null,null,null,null,ids,20);
    }
}
