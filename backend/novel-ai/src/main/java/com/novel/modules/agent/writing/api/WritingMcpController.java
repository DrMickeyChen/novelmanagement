package com.novel.modules.agent.writing.api;
import com.fasterxml.jackson.databind.JsonNode;
import com.novel.common.result.Result;
import com.novel.modules.agent.writing.application.WritingMcp;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/ai/writing/mcp/novels/{novel}")
public class WritingMcpController {
    private final WritingMcp mcp;
    @GetMapping public Result<?> list(@PathVariable long novel){return Result.success(mcp.list(novel));}
    @GetMapping("/audit") public Result<?> audit(@PathVariable long novel){return Result.success(mcp.audit(novel));}
    @PostMapping public Result<?> create(@PathVariable long novel,@RequestBody JsonNode req){return Result.success(mcp.save(novel,null,req));}
    @PutMapping("/{id}") public Result<?> update(@PathVariable long novel,@PathVariable String id,@RequestBody JsonNode req){return Result.success(mcp.save(novel,id,req));}
    @DeleteMapping("/{id}") public Result<?> delete(@PathVariable long novel,@PathVariable String id){mcp.delete(novel,id);return Result.success();}
    @PostMapping("/{id}/discover") public Result<?> discover(@PathVariable long novel,@PathVariable String id){return Result.success(mcp.discover(novel,id));}
    @PostMapping("/{id}/grant") public Result<?> grant(@PathVariable long novel,@PathVariable String id,@RequestBody JsonNode req){mcp.grant(novel,id,req);return Result.success(mcp.list(novel));}
}
