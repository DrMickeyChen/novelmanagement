package com.novel.modules.agent.bookfinder.controller;
import com.novel.common.internal.BookKnowledge.Document;
import com.novel.common.result.Result;
import com.novel.common.security.UserContext;
import com.novel.modules.agent.bookfinder.knowledge.BookKnowledgeService;
import com.novel.modules.agent.bookfinder.skill.BookFinderSkills;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/admin/ai/book-finder")
public class AdminBookKnowledgeController {
    private final BookKnowledgeService knowledge;
    private final BookFinderSkills skills;
    private final com.novel.modules.agent.bookfinder.knowledge.KnowledgeNotes notes;
    private final com.novel.modules.agent.bookfinder.conversation.BookFinderStore store;
    private final com.novel.modules.agent.bookfinder.knowledge.EmbeddingConfigService embeddingConfig;
    private final com.novel.modules.agent.bookfinder.knowledge.RerankConfigService rerankConfig;
    @GetMapping("/rerank-config") public Result<com.novel.modules.agent.bookfinder.knowledge.RerankConfigService.View> rerankConfig() {
        return Result.success(rerankConfig.get());
    }
    @PutMapping("/rerank-config") public Result<com.novel.modules.agent.bookfinder.knowledge.RerankConfigService.View> rerankConfig(
            @RequestBody com.novel.modules.agent.bookfinder.knowledge.RerankConfigService.Save input) {
        return Result.success(rerankConfig.save(input));
    }
    @GetMapping("/embedding-config") public Result<com.novel.modules.agent.bookfinder.knowledge.EmbeddingConfigService.View> embeddingConfig() {
        return Result.success(embeddingConfig.get());
    }
    @PutMapping("/embedding-config") public Result<com.novel.modules.agent.bookfinder.knowledge.EmbeddingConfigService.View> embeddingConfig(
            @RequestBody com.novel.modules.agent.bookfinder.knowledge.EmbeddingConfigService.Save input) {
        return Result.success(embeddingConfig.save(input));
    }
    @GetMapping("/runs") public Result<List<Map<String,Object>>> runs() { return Result.success(store.recentRuns()); }
    @GetMapping("/knowledge/{id}") public Result<Document> preview(@PathVariable String id) { return Result.success(notes.preview(id)); }
    @GetMapping("/notes") public Result<List<com.novel.modules.agent.bookfinder.knowledge.KnowledgeNotes.Note>> notes() { return Result.success(notes.list()); }
    public record Draft(String summary,String chunkId,String quote) {}
    @PutMapping("/notes/{id}") public Result<Void> draft(@PathVariable String id,@RequestBody Draft draft) {
        notes.saveDraft(id,draft.summary(),draft.chunkId(),draft.quote()); return Result.success();
    }
    @PostMapping("/notes/{id}/generate") public Result<BookKnowledgeService.Task> generate(@PathVariable String id) {
        return Result.success(knowledge.submitSummary(id,UserContext.getUserId()));
    }
    public record Review(boolean approved) {}
    @PostMapping("/notes/{id}/review") public Result<Void> review(@PathVariable String id,@RequestBody Review review) {
        notes.review(id,review.approved(),UserContext.getUserId()); return Result.success();
    }
    @GetMapping("/status") public Result<Map<String,Object>> status() { return Result.success(knowledge.status()); }
    @GetMapping("/knowledge") public Result<List<Document>> catalog() { return Result.success(knowledge.catalog()); }
    @GetMapping("/tasks") public Result<List<BookKnowledgeService.Task>> tasks() { return Result.success(knowledge.tasks()); }
    @PostMapping("/build") public Result<List<BookKnowledgeService.Task>> build(@RequestBody BookKnowledgeService.BuildRequest request) {
        return Result.success(knowledge.submit(request,UserContext.getUserId()));
    }
    @PostMapping("/reindex") public Result<List<BookKnowledgeService.Task>> reindex(@RequestBody List<String> ids) {
        return Result.success(knowledge.reindex(ids,UserContext.getUserId()));
    }
    @DeleteMapping("/knowledge/{id}") public Result<Void> remove(@PathVariable String id) { knowledge.remove(id); return Result.success(); }
    @GetMapping("/skills") public Result<List<BookFinderSkills.Skill>> skills() { return Result.success(skills.list()); }
    public record Toggle(boolean enabled) {}
    @PutMapping("/skills/{id}") public Result<Void> toggle(@PathVariable String id,@RequestBody Toggle toggle) { skills.toggle(id,toggle.enabled()); return Result.success(); }
}
