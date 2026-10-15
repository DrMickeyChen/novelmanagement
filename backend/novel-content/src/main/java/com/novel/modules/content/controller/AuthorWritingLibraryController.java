package com.novel.modules.content.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.novel.common.result.Result;
import com.novel.modules.content.service.WritingLibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/author/novels/{novelId}/story-library")
public class AuthorWritingLibraryController {
    private final WritingLibraryService library;
    @GetMapping public Result<List<Map<String,Object>>> list(@PathVariable long novelId){return Result.success(library.list(novelId));}
    @PostMapping public Result<Map<String,Object>> create(@PathVariable long novelId,@RequestBody JsonNode req){return Result.success(library.save(novelId,null,req,false));}
    @PutMapping("/{id}") public Result<Map<String,Object>> update(@PathVariable long novelId,@PathVariable String id,@RequestBody JsonNode req){return Result.success(library.save(novelId,id,req,false));}
    @GetMapping("/{id}/revisions") public Result<List<Map<String,Object>>> revisions(@PathVariable long novelId,@PathVariable String id){return Result.success(library.revisions(novelId,id));}
    @DeleteMapping("/{id}") public Result<?> delete(@PathVariable long novelId,@PathVariable String id,@RequestParam long expectedVersion){library.delete(novelId,id,expectedVersion);return Result.success();}
}
