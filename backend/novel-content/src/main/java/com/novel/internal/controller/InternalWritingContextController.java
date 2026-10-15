package com.novel.internal.controller;
import com.fasterxml.jackson.databind.JsonNode;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.result.Result;
import com.novel.modules.content.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequiredArgsConstructor @RequestMapping("/internal/writing")
public class InternalWritingContextController {
    private final WritingContextService contexts;private final WritingLibraryService library;
    @GetMapping("/metadata/{id}") public Result<Map<String,Object>> metadata(@PathVariable long id,@RequestParam long nodeId){return Result.success(contexts.metadata(id,nodeId));}
    @PostMapping("/context/{id}") public Result<Snapshot> context(@PathVariable long id,@RequestBody ContextRequest req){return Result.success(contexts.snapshot(id,req));}
    @GetMapping("/chapters/{id}") public Result<List<Map<String,Object>>> chapters(@PathVariable long id,@RequestParam long nodeId){return Result.success(contexts.chapters(id,nodeId));}
    @GetMapping("/chunks/{id}") public Result<List<Source>> chunks(@PathVariable long id,@RequestParam long nodeId,@RequestParam long sourceId){return Result.success(contexts.chunks(id,nodeId,sourceId));}
    @PostMapping("/validate/{id}") public Result<List<Source>> validate(@PathVariable long id,@RequestParam long nodeId,@RequestBody List<Source> hits){return Result.success(contexts.validate(id,nodeId,hits));}
    @PostMapping("/proposal/{novelId}") public Result<Map<String,Object>> propose(@PathVariable long novelId,@RequestBody JsonNode input){return Result.success(library.save(novelId,null,input,true));}
}
