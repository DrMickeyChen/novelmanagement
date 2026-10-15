package com.novel.search.controller;
import com.novel.common.internal.WritingContent.*;
import com.novel.common.result.Result;
import com.novel.search.service.PrivateWritingIndex;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.io.IOException;
@RestController @RequiredArgsConstructor @RequestMapping("/internal/writing-index")
public class InternalPrivateWritingController {
    private final PrivateWritingIndex index;
    @PutMapping public Result<Void> put(@RequestBody List<PrivateChunk> chunks)throws IOException{index.put(chunks);return Result.success();}
    @PostMapping("/search") public Result<PrivateEvidence> search(@RequestBody PrivateRecall req)throws IOException{return Result.success(index.search(req));}
    @DeleteMapping("/{novel}/chapters/{chapter}") public Result<Void> deleteChapter(@PathVariable String novel,@PathVariable String chapter)throws IOException{index.deleteChapter(novel,chapter);return Result.success();}
    @DeleteMapping("/{novel}") public Result<Void> delete(@PathVariable String novel)throws IOException{index.delete(novel);return Result.success();}
}
