package com.novel.modules.agent.bookfinder.controller;
import com.novel.common.result.Result;
import com.novel.modules.agent.bookfinder.eval.BookFinderEvaluation;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/admin/ai/book-finder/evaluations")
public class AdminBookEvaluationController {
    private final BookFinderEvaluation evaluation;
    @GetMapping public Result<List<JsonNode>> list() { return Result.success(evaluation.list()); }
    @GetMapping("/{id}") public Result<JsonNode> get(@PathVariable String id) { return Result.success(evaluation.get(id)); }
    @PostMapping public Result<JsonNode> start(@RequestBody BookFinderEvaluation.Start input) { return Result.success(evaluation.start(input)); }
    @PostMapping("/{id}/cancel") public Result<Void> cancel(@PathVariable String id) { evaluation.cancel(id); return Result.success(); }
    @PutMapping("/{id}/review/{caseId}") public Result<JsonNode> review(@PathVariable String id,@PathVariable String caseId,@RequestBody BookFinderEvaluation.Review input) { return Result.success(evaluation.review(id,caseId,input)); }
    @GetMapping("/{id}/export") public ResponseEntity<JsonNode> export(@PathVariable String id) {
        var report=evaluation.get(id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=book-finder-eval-"+report.path("id").asText()+".json").body(report);
    }
}
