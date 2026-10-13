package com.novel.modules.content.controller;

import com.novel.common.result.Result;
import com.novel.modules.content.importing.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/novels/imports")
@RequiredArgsConstructor
public class AdminNovelImportController {
    private final NovelImportService service;
    @PostMapping("/preview")
    public Result<NovelImportService.TaskView> preview(@RequestParam MultipartFile file,
            @RequestParam(defaultValue = "AUTO") String encoding, @RequestParam(defaultValue = "AUTO") String mode) {
        return Result.success(service.preview(file, encoding, mode));
    }
    @PostMapping("/{id}/confirm")
    public Result<NovelImportService.TaskView> confirm(@PathVariable String id, @Valid @RequestBody NovelImportOptions options) {
        return Result.success(service.submit(id, options));
    }
    @GetMapping("/{id}")
    public Result<NovelImportService.TaskView> get(@PathVariable String id) { return Result.success(service.get(id)); }
    @DeleteMapping("/{id}")
    public Result<Void> discard(@PathVariable String id) { service.discard(id); return Result.success(null); }
}
